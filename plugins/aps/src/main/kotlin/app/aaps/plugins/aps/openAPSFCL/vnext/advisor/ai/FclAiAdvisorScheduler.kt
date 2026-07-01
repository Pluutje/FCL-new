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
 * aangeroepen (30/06/2026, Ecko: "in mijn cycles roept determineBasal
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
    private val MIN_INTERVAL = Duration.ofHours(20) // iets onder 24u, voorkomt drift door cycle-jitter

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

    private fun lastRunAt(): Instant? {
        val f = markerFile()
        if (!f.exists()) return null
        return try { Instant.parse(f.readText().trim()) } catch (_: Exception) { null }
    }

    private fun markRunNow() {
        try { markerFile().writeText(Instant.now().toString()) } catch (_: Exception) { /* niet kritiek */ }
    }

    /**
     * Aanroepen vanuit FCLvNext.getAdvice() (via DetermineBasalFCL), elke cyclus.
     * Doet vrijwel niets (één bestands-timestamp-check) als de laatste run nog
     * geen MIN_INTERVAL geleden was — dus goedkoop genoeg om elke 5 minuten
     * aan te roepen. Start de echte pipeline NOOIT op de aanroepende thread.
     *
     * @param metrics episode-metrics van vandaag (voor tijd-tot-piek/voorspel-
     *                fout-evidence) — optioneel, lege lijst als niet beschikbaar
     *                in deze cyclus context; de collector werkt dan zonder die
     *                evidence (CSV-gebaseerde TIR/hypo-stats blijven wel werken).
     */
    fun runIfDue(context: Context, metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics> = emptyList()) {
        val last = lastRunAt()
        if (last != null && Duration.between(last, Instant.now()) < MIN_INTERVAL) return
        if (!running.compareAndSet(false, true)) return // vorige run nog bezig, niet dubbel starten
        markRunNow() // eerst markeren, zodat een falende run niet elke cyclus opnieuw probeert
        val apiKey   = FclAiAdvisorSettingsStore.getActiveApiKey(context)
        val model    = FclAiAdvisorSettingsStore.getActiveModel(context)
        val provider = FclAiAdvisorSettingsStore.getProvider(context)
        executor.submit {
            try {
                cachedResult.set(executePipeline(apiKey, model, metrics, provider))
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
        val apiKey   = FclAiAdvisorSettingsStore.getActiveApiKey(context)
        val model    = FclAiAdvisorSettingsStore.getActiveModel(context)
        val provider = FclAiAdvisorSettingsStore.getProvider(context)
        executor.submit {
            try {
                val result = executePipeline(apiKey, model, metrics, provider)
                cachedResult.set(result)
                onDone(result)
            } finally {
                running.set(false)
            }
        }
    }

    private fun executePipeline(
        apiKey: String,
        model: String?,
        metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics>,
        provider: FclAiAdvisorSettingsStore.Provider
    ): AiAdvisorRunResult {
        val payload = FclAiAdvisorDataCollector.collect(metrics = metrics)
        val prompt = FclAiAdvisorPromptBuilder.buildPrompt(payload)

        return when (val r = FclAiAdvisorService.callAdvisor(provider, apiKey, prompt, model ?: FclAiAdvisorSettingsStore.DEFAULT_GEMINI_MODEL)) {
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
