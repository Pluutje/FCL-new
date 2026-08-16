package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.content.Context
import android.os.Environment
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors

/**
 * ============================================================================
 * FCL AI-Advisor — Scheduler / Orchestrator
 * ============================================================================
 *
 * Bewust GEEN WorkManager-scaffolding: dat vereist AndroidManifest- en
 * DI-wiring die buiten de geüploade bestanden valt. In plaats daarvan een
 * simpele "is het >=20u geleden" check die vanuit FCLvNext.getAdvice() wordt
 * aangeroepen (30/06/2026, de gebruiker: "in mijn cycles roept determineBasal
 * fclvnext aan en de rest loopt vanuit daar").
 *
 * KRITIEK: runIfDue() voert zelf nooit de HTTP-call synchroon uit op de
 * aanroepende thread — getAdvice() draait op de APS-doseringsthread en mag
 * NOOIT wachten op een netwerkaanroep. De daadwerkelijke pipeline (collector
 * → prompt → HTTP → parser) wordt op een eigen achtergrond-executor gestart;
 * runIfDue() zelf keert direct terug.
 *
 * Resultaat wordt in-memory gecached (AtomicReference, zelfde patroon als
 * FclOverrideBridge) zodat de UI-Activity het synchroon kan uitlezen zonder
 * zelf een netwerkcall te hoeven doen.
 */
object FclAiAdvisorScheduler {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val LAST_RUN_MARKER = "FCLvNext_AiAdvisorLastRun.txt"
    private const val LAST_SUCCESS_MARKER = "FCLvNext_AiAdvisorLastSuccess.txt"
    private val MIN_INTERVAL = Duration.ofHours(8)  // Productie (08/07/2026): max. 3×/dag — was 12u na de 4 uur testfase
    // Bij een tijdelijke fout (timeout, 503) wordt de run opnieuw geprobeerd
    // elke RETRY_INTERVAL, totdat er een succesvol rapport is of MIN_INTERVAL
    // verstreken is voor de volgende dag-run.
    // 10 → 30 min (28/07/2026): bij aanhoudende 503's ("te veel demand")
    // stapelden retries elke 10 min op — samen met de per-poging 2 keys
    // (zie FclAiAdvisorService.callAdvisor, dat nu bij een 503 overigens niet
    // meer naar de 2e key doorschakelt) kon dat bij een langere periode van
    // Google-overbelasting alleen al tientallen requests kosten. 30 min geeft
    // de overbelasting meer ruimte om te zakken tussen pogingen in, zonder
    // de "eerste succesvolle run vandaag"-ervaring merkbaar te vertragen.
    private val RETRY_INTERVAL = Duration.ofMinutes(30)

    private val cachedResult = AtomicReference<AiAdvisorRunResult?>(null)
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FclAiAdvisorScheduler").apply { isDaemon = true }
    }

    fun latestResult(): AiAdvisorRunResult? = cachedResult.get()
    fun isRunning(): Boolean = running.get()

    private fun markerFile(): File {
        val dir = File(Environment.getExternalStorageDirectory(), RELATIVE_PATH)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LAST_RUN_MARKER)
    }

    private fun successMarkerFile(): File {
        val dir = File(Environment.getExternalStorageDirectory(), RELATIVE_PATH)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LAST_SUCCESS_MARKER)
    }

    private fun lastRunAt(): Instant? {
        val f = markerFile()
        if (!f.exists()) return null
        return try { Instant.parse(f.readText().trim()) } catch (_: Exception) { null }
    }

    private fun lastSuccessAt(): Instant? {
        val f = successMarkerFile()
        if (!f.exists()) return null
        return try { Instant.parse(f.readText().trim()) } catch (_: Exception) { null }
    }

    private fun markRunNow() {
        try { markerFile().writeText(Instant.now().toString()) } catch (_: Exception) { }
    }

    private fun markSuccessNow() {
        try { successMarkerFile().writeText(Instant.now().toString()) } catch (_: Exception) { }
    }

    /**
     * Automatische trigger vanuit de episode-callback (FclLearnerLogger).
     *
     * Logica:
     *  - Als er vandaag al een SUCCESVOL rapport is (lastSuccessAt >= MIN_INTERVAL):
     *    niets doen.
     *  - Als er nog nooit een succes was, of de laatste success > 20u geleden:
     *    starten als er nog geen run loopt.
     *  - Als de laatste poging mislukte (parseError != null) en de laatste RUN-marker
     *    >= RETRY_INTERVAL geleden: opnieuw proberen (elke 15 min bij timeout/503).
     */
    fun runIfDue(context: Context, metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics> = emptyList()) {
        // 10/07/2026 — AI-adviseur volledig uit: geen nieuwe runs, geen
        // sticky-herinnering meer voor nog-openstaande voorstellen (die
        // beslissing doet er niet meer toe zolang AI niet meer meebeslist —
        // zie de kdoc bij FclAiAdvisorSettingsStore.isEnabled voor wat "uit"
        // wél en niet doet met al goedgekeurde waarden).
        if (!app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorSettingsStore.isEnabled(context)) return

        val now = Instant.now()

        // ── Sticky pending-melding (05/07/2026) ───────────────────────
        // Onafhankelijk van of er nu een NIEUWE AI-run aan de beurt is: zolang
        // er uit het LAATSTE rapport nog onbeoordeelde voorstellen openstaan,
        // wordt de melding elke cyclus (~5 min, dus elke keer dat getAdvice()
        // dit aanroept) opnieuw geplaatst. De gebruiker kan 'm zo negeren
        // zonder dat hij stilletjes verdwijnt — pas als elk voorstel is goed-
        // of afgekeurd (stillPendingCount == 0) stopt dit vanzelf, want
        // showPendingAdvice(0) dismisst 'm dan juist (zie FclAiNotificationHelper).
        // 14/07/2026 — bugfix: automatisch door de validatie afgewezen
        // voorstellen (suggestion.rejected == true, zie "Automatisch verworpen"
        // in FclAiAdvisorScreen.kt) worden NOOIT naar FclAiAdvisorHistoryRepository
        // geschreven — er is geen Goedkeuren/Afwijzen-knop voor, de gebruiker kan
        // ze niet "afhandelen". isStillPending() vond dus nooit een history-entry
        // voor zo'n voorstel en bleef elke cyclus (~5 min) TRUE teruggeven, ook al
        // was er niets te doen — de melding "1 voorstel klaar" bleef daardoor
        // urenlang terugkomen totdat een volgende run cachedResult verving.
        // Fix: !it.rejected uitsluiten, exact dezelfde filter die forceRunNow()
        // (de "Nu vernieuwen"-knop) hieronder al wél toepast.
        val stillPendingCount = cachedResult.get()?.suggestions
            ?.count { !it.rejected && app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorHistoryRepository.isStillPending(it) }
            ?: 0
        FclAiNotificationHelper.showPendingAdvice(context, stillPendingCount)

        // Al een succesvol rapport vandaag? Dan niets doen.
        val lastSuccess = lastSuccessAt()
        if (lastSuccess != null && Duration.between(lastSuccess, now) < MIN_INTERVAL) return

        // Geen succes nodig als de vorige run recent was én succesvol afgerond
        val lastRun = lastRunAt()
        val recentFailed = cachedResult.get()?.parseError != null
        val tooSoonForRetry = lastRun != null && Duration.between(lastRun, now) < RETRY_INTERVAL
        if (lastRun != null && !recentFailed && Duration.between(lastRun, now) < MIN_INTERVAL) return
        if (tooSoonForRetry) return

        if (!running.compareAndSet(false, true)) return
        markRunNow()
        val apiKeys  = FclAiAdvisorSettingsStore.getActiveKeys(context)
        val model    = FclAiAdvisorSettingsStore.getActiveModel(context)
        val provider = FclAiAdvisorSettingsStore.getProvider(context)
        executor.submit {
            try {
                val result = executePipeline(context, apiKeys, model, metrics, provider)
                cachedResult.set(result)
                if (result.parseError == null) {
                    markSuccessNow()

                    // 10/07/2026 — AUTO-modus: elk niet-door-veiligheids-
                    // checks afgewezen voorstel wordt meteen toegepast, geen
                    // goedkeuring nodig. "Wat en wanneer" blijft zichtbaar via
                    // de bestaande geschiedenis (FclAiAdvisorApplier.approve()
                    // schrijft daar sowieso al naartoe) — geen aparte melding
                    // nodig, er is niets te beslissen.
                    if (FclAiAdvisorSettingsStore.getMode(context) ==
                        app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.AUTO
                    ) {
                        result.suggestions.filter { !it.rejected }.forEach { suggestion ->
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorApplier
                                .approve(suggestion, context)
                        }
                    } else {
                        // MANUAL-modus (bestaand gedrag): sticky check bovenaan
                        // runIfDue() herbevestigt de melding daarna elke volgende
                        // cyclus zolang er nog iets onbeoordeeld is (05/07/2026).
                        // Zelfde bugfix als de sticky-check bovenaan: automatisch
                        // afgewezen voorstellen (rejected == true) uitsluiten — die zijn
                        // nooit "af te handelen" en horen dus nooit mee te tellen.
                        val pendingCount = result.suggestions.count {
                            !it.rejected && app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorHistoryRepository.isStillPending(it)
                        }
                        FclAiNotificationHelper.showPendingAdvice(context, pendingCount)
                    }
                }
            } finally {
                running.set(false)
            }
        }
    }

    /** Voor handmatig opnieuw aanroepen vanuit de UI ("Nu vernieuwen"-knop), negeert MIN_INTERVAL. */
    fun forceRunNow(
        context: Context,
        metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics> = emptyList(),
        onDone: (AiAdvisorRunResult) -> Unit = {}
    ) {
        if (!running.compareAndSet(false, true)) return
        markRunNow()
        val apiKeys  = FclAiAdvisorSettingsStore.getActiveKeys(context)
        val model    = FclAiAdvisorSettingsStore.getActiveModel(context)
        val provider = FclAiAdvisorSettingsStore.getProvider(context)
        executor.submit {
            try {
                val result = executePipeline(context, apiKeys, model, metrics, provider)
                cachedResult.set(result)
                // Notificatie ook bij handmatige run (04/07/2026)
                val pendingCount = result.suggestions.count { !it.rejected }
                FclAiNotificationHelper.showPendingAdvice(context, pendingCount)
                onDone(result)
            } finally {
                running.set(false)
            }
        }
    }

    private fun executePipeline(
        context: android.content.Context,
        apiKeys: List<String>,
        model: String?,
        metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics>,
        provider: FclAiAdvisorSettingsStore.Provider
    ): AiAdvisorRunResult {
        val payload = FclAiAdvisorDataCollector.collect(context = context, metrics = metrics)
        val prompt = FclAiAdvisorPromptBuilder.buildPrompt(payload)

        return when (val r = FclAiAdvisorService.callAdvisor(provider, apiKeys, prompt, model ?: FclAiAdvisorSettingsStore.DEFAULT_MODEL_ID)) {
            is FclAiAdvisorService.Result.Success -> {
                val parsed = FclAiAdvisorResponseParser.parse(r.rawText, payload)
                val accepted = parsed.suggestions.filter { !it.rejected }
                val afterCooldown = FclAiAdvisorHistoryRepository.filterCooldown(accepted)
                parsed.copy(suggestions = afterCooldown + parsed.suggestions.filter { it.rejected })
            }
            is FclAiAdvisorService.Result.Failure -> AiAdvisorRunResult(
                generatedAtUtc = payload.dateUtc,
                rawModelResponse = "",
                suggestions = emptyList(),
                parseError = r.reasonNl
            )
        }
    }
}