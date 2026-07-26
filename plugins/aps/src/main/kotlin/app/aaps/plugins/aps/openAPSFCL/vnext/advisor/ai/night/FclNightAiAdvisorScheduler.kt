package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night

import android.content.Context
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorService
import app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorSettingsStore
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.night.NightWindowAnalyzer
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 * FCL Nacht-AI-Adviseur — Scheduler
 * ============================================================================
 *
 * 23/07/2026 (Ecko): "volledig onafhankelijk van de andere ai adviezen 1 keer
 * draait bv als 'is nacht' weer false wordt".
 *
 * Onafhankelijk van FclAiAdvisorScheduler (de dag-adviseur) op elk vlak dat
 * ertoe doet:
 *  - Trigger: randdetectie isNight true->false (via onCycle()), niet een
 *    episode-callback of een vaste tijdsinterval.
 *  - Gating: GEEN check tegen FclAiAdvisorSettingsStore.isEnabled() — dat is
 *    de aan/uit-schakelaar van de dag-adviseur en mag deze feature niet
 *    raken (en omgekeerd).
 *  - Opslag: eigen FclNightAiAdvisorStore, losstaand van
 *    FclAiAdvisorHistoryRepository/FclAiParamStore.
 *  - Toepassing: GEEN — dit is pure tekst-adviesweergave, er is geen
 *    Applier-equivalent en dat is expliciet zo bedoeld (voorlopig alleen
 *    advies, niets automatisch aangepast).
 *
 * Wél hergebruikt, bewust: de generieke HTTP-laag (FclAiAdvisorService) en
 * de bestaande model/API-sleutel-instellingen (FclAiAdvisorSettingsStore)
 * — hetzelfde account, geen reden voor een tweede sleutelbeheer-scherm.
 *
 * Net als de dag-adviseur draait de daadwerkelijke pipeline nooit synchroon
 * op de aanroepende thread — onCycle() wordt aangeroepen vanuit
 * DetermineBasalFCL.determine_basal(), en dat pad mag nooit op een
 * netwerkaanroep wachten.
 *
 * MODUS (26/07/2026, Ecko — dag/nacht-herstructurering AI-adviseur/Learner):
 * FclNightBasalAutoAdjustStore's FclSystemMode is niet langer alleen de
 * schakelaar voor het automatisch profiel-bijstellen, maar de modus van de
 * VOLLEDIGE Nacht-AI-adviseur ("AI-adviseur — Nacht" in Instellingen).
 * OFF: geen enkele nacht-AI-aanroep meer, geen rapport, geen advies — zowel
 * de dagelijkse automatische route als "Nu vernieuwen" slaan de pipeline
 * dan over (zie de gates in runIfNotAlreadyToday()/forceRunNow() hieronder).
 * AUTO/MANUAL: rapport wordt altijd gegenereerd; alleen het WEL/NIET
 * automatisch toepassen van de profiel-suggestie verschilt (zie
 * FclNightBasalAutoAdjuster.maybeApply()/applyPending()/rejectPending()).
 */
object FclNightAiAdvisorScheduler {

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FclNightAiAdvisorScheduler").apply { isDaemon = true }
    }

    private val AMSTERDAM = ZoneId.of("Europe/Amsterdam")

    fun latestResult(context: Context): NightAiAdvisorRunResult? =
        FclNightAiAdvisorStore.loadResult(context)

    fun isRunning(): Boolean = running.get()

    /**
     * Aan te roepen elke cyclus vanuit DetermineBasalFCL, met de actuele
     * isNight-waarde van dayNightHelper.isNightNow(). Detecteert zelf de
     * randovergang true->false en start dan (indien nog niet vandaag
     * gedaan) een run.
     */
    // profileFunction/profileRepository: alleen nodig voor het optionele
    // automatisch-profiel-bijstellen (zie FclNightBasalAutoAdjuster.kt) — de
    // rest van deze scheduler gebruikt ze niet. Doorgegeven vanuit
    // DetermineBasalFCL, dat ze al via Dagger injecteert; deze scheduler is
    // zelf een plain object, geen @Inject-klasse.
    fun onCycle(
        context: Context,
        isNightNow: Boolean,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository
    ) {
        val wasNight = FclNightAiAdvisorStore.wasNightLastCycle(context)
        FclNightAiAdvisorStore.setWasNightLastCycle(context, isNightNow)
        if (wasNight && !isNightNow) {
            runIfNotAlreadyToday(context, profileFunction, profileRepository)
        }
    }

    private fun runIfNotAlreadyToday(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository
    ) {
        // 26/07/2026 (Ecko) — OFF: helemaal geen nacht-AI-advies meer, zie kdoc
        // bovenaan dit object.
        if (FclNightBasalAutoAdjustStore.getMode(context) == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF) return
        val today = LocalDate.now(AMSTERDAM).toString()
        if (FclNightAiAdvisorStore.getLastProcessedLocalDate(context) == today) return
        if (!running.compareAndSet(false, true)) return
        FclNightAiAdvisorStore.setLastProcessedLocalDate(context, today)
        launchPipeline(context, profileFunction = profileFunction, profileRepository = profileRepository)
    }

    /** Voor een handmatige "Nu vernieuwen"-knop op het Nacht-tabblad, negeert de dagelijkse dedup.
     *
     *  26/07/2026 (Ecko, herzien): gebruikte eerder GEEN profileFunction/profileRepository, met als
     *  reden "een handmatige 'Nu vernieuwen' ververst alleen het advies, past nooit iets automatisch
     *  toe". Ecko's terugkoppeling: bij Handmatig verscheen na "Nu vernieuwen" geen Accepteren/
     *  Afwijzen — logisch, want er werd door dit ontbreken helemaal geen voorstel berekend/gelogd.
     *  Nu WEL via FclProfileBridge (dezelfde brug die de Accepteren-knop zelf ook gebruikt), zodat
     *  Handmatig na een handmatige verversing ook een vers voorstel krijgt. De oorspronkelijke
     *  veiligheidsgedachte — "Nu vernieuwen" mag nooit ONGEVRAAGD echt op de pomp schrijven — blijft
     *  intact: zie de `isManualTrigger`-kortsluiting in FclNightBasalAutoAdjuster.applyInternal(),
     *  die bij AUTO-modus een handmatige trigger nog steeds volledig overslaat (exact het oude
     *  gedrag). Alleen bij MANUAL levert een handmatige trigger nu ook daadwerkelijk iets op.
     *
     *  Ook hier de OFF-gate: bij uit doet zelfs "Nu vernieuwen" niets meer (de UI verbergt de knop
     *  dan ook, dit is de defensieve tweede laag). */
    fun forceRunNow(context: Context, onDone: (NightAiAdvisorRunResult) -> Unit = {}) {
        if (FclNightBasalAutoAdjustStore.getMode(context) == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF) return
        if (!running.compareAndSet(false, true)) return
        val today = LocalDate.now(AMSTERDAM).toString()
        FclNightAiAdvisorStore.setLastProcessedLocalDate(context, today)
        val pf = app.aaps.plugins.aps.openAPSFCL.vnext.FclProfileBridge.getProfileFunction()
        val pr = app.aaps.plugins.aps.openAPSFCL.vnext.FclProfileBridge.getProfileRepository()
        launchPipeline(context, onDone = onDone, profileFunction = pf, profileRepository = pr, isManualTrigger = true)
    }

    private fun launchPipeline(
        context: Context,
        onDone: (NightAiAdvisorRunResult) -> Unit = {},
        profileFunction: ProfileFunction? = null,
        profileRepository: ProfileRepository? = null,
        // 26/07/2026 (Ecko) — true voor "Nu vernieuwen", false voor de dagelijkse
        // nacht-rand-route. Zie kdoc bij forceRunNow() hierboven en bij
        // FclNightBasalAutoAdjuster.applyInternal()'s AUTO+isManualTrigger-kortsluiting.
        isManualTrigger: Boolean = false
    ) {
        val apiKeys  = FclAiAdvisorSettingsStore.getActiveKeys(context)
        val model    = FclAiAdvisorSettingsStore.getActiveModel(context)
        val provider = FclAiAdvisorSettingsStore.getProvider(context)
        executor.submit {
            try {
                val result = executePipeline(context, apiKeys, model, provider)
                FclNightAiAdvisorStore.saveResult(context, result)
                // Automatisch profiel-bijstellen (24/07/2026, Ecko; herzien 26/07/2026).
                // Best-effort: een fout hier mag het AI-rapport zelf nooit blokkeren of
                // ongedaan maken — vandaar los van de try/finally hierboven, met zijn
                // eigen try/catch binnen de adjuster zelf.
                if (profileFunction != null && profileRepository != null) {
                    FclNightBasalAutoAdjuster.maybeApply(
                        context = context,
                        profileFunction = profileFunction,
                        profileRepository = profileRepository,
                        payload = lastCollectedPayload,
                        result = result,
                        isManualTrigger = isManualTrigger
                    )
                }
                onDone(result)
            } finally {
                running.set(false)
            }
        }
    }

    // Bewaart de payload van de LAATSTE executePipeline()-aanroep in dit proces
    // (best-effort, geen persistente opslag nodig) zodat launchPipeline() hierboven
    // nightsAnalyzed kan doorgeven aan de auto-adjuster zonder de hele pipeline-
    // signatuur te moeten omgooien. Alleen gelezen binnen dezelfde executor-thread
    // die hem net gezet heeft, dus geen synchronisatie nodig.
    @Volatile
    private var lastCollectedPayload: FclNightReportPayload? = null

    private fun executePipeline(
        context: Context,
        apiKeys: List<String>,
        model: String,
        provider: FclAiAdvisorSettingsStore.Provider
    ): NightAiAdvisorRunResult {
        // Nachtvensters herbouwen vóór het verzamelen (24/07/2026, Ecko) — zie
        // kdoc bij rebuildNightWindows() hieronder: zonder deze stap bleef een
        // net gewijzigd basaalprofiel onnodig lang "onzichtbaar" voor dit
        // rapport, zowel bij de dagelijkse automatische run als bij "Nu
        // vernieuwen" — beide riepen tot nu toe alleen collect() aan, dat puur
        // leest wat er al in de database staat.
        rebuildNightWindows(context)
        val payload = FclNightAiAdvisorDataCollector.collect(context)
        lastCollectedPayload = payload
        val prompt = FclNightAiAdvisorPromptBuilder.buildPrompt(payload)
        return when (val r = FclAiAdvisorService.callAdvisor(provider, apiKeys, prompt, model)) {
            is FclAiAdvisorService.Result.Success ->
                FclNightAiAdvisorResponseParser.parse(r.rawText, payload)
            is FclAiAdvisorService.Result.Failure -> NightAiAdvisorRunResult(
                generatedAtUtc = payload.generatedAtUtc,
                rawModelResponse = "",
                suggestions = emptyList(),
                parseError = r.reasonNl
            )
        }
    }

    // Herbouwt de nachtvensters (NightWindowEntity) — zelfde stap die
    // Fclanalyzerscreen.kt tot nu toe alleen deed bij het openen van het
    // Analyzer-scherm (LaunchedEffect(Unit) { refreshData() }). Deze
    // adviseur draait ook zonder dat scherm ooit geopend te zijn (dagelijks
    // automatisch, of via "Nu vernieuwen"), dus zonder deze stap kon een
    // net gewijzigd basaalprofiel onnodig lang hetzelfde advies opleveren:
    // de profielwijziging zelf staat al snel (~1 cyclus, via
    // FclBasalProfileNightLogger) in basal_profile_history, maar de
    // nachtvensters die daarnaar verwijzen (activeProfileBasalUph, zie
    // resolveActiveProfile() in Nightwindowanalyzer.kt) werden zonder dit
    // pas bijgewerkt bij de eerstvolgende schermopening.
    //
    // Best-effort: een fout hier mag het rapport niet blokkeren. Bij falen
    // gaat het rapport gewoon verder met de laatst bekende vensters —
    // hetzelfde gedrag als vóór deze wijziging.
    private fun rebuildNightWindows(context: Context) {
        try {
            runBlocking {
                val db = FCLAnalyzerDatabase.getInstance(context)
                val cutoffMs = System.currentTimeMillis() - 14 * 24 * 3600_000L
                val entities = db.cycleLogDao().getAll().filter { it.timestampMs > cutoffMs }
                if (entities.isEmpty()) return@runBlocking
                val episodes = db.episodeDao().getAllEpisodes()
                val profiles = db.basalProfileHistoryDao().getAll()
                val windows = NightWindowAnalyzer.build(entities, episodes, profiles)
                db.nightWindowDao().insertNightWindows(windows)
            }
        } catch (_: Exception) {
            // best-effort — zie kdoc hierboven
        }
    }
}
