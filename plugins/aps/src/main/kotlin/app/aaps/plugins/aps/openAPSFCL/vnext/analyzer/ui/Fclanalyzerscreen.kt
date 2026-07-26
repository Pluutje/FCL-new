package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.FclActiveConfigBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.*
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.*
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.night.NightWindowAnalyzer
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings

private enum class Screen { DASHBOARD, ANALYZE, ADVISOR, AI_ADVISOR, RESET, SAFETY }

/**
 * Entrypoint van de geïntegreerde FCL Analyzer.
 * Wordt aangeroepen vanuit MainScreen.kt via onFclAnalyzerClick.
 *
 * Data laad-flow:
 * - Leest rechtstreeks uit FCLAnalyzerDatabase (geen CSV import nodig)
 * - Entiteiten worden via EntityToLogRowMapper omgezet naar LogRow
 * - ConfigOverrideWriter schrijft via FclOverrideBridge (geen context nodig)
 */
@Composable
fun FclAnalyzerScreen(
    onDismiss: () -> Unit,
    startOnAiAdvisor: Boolean = false,
    // 10/07/2026 (Ecko) — zelfde patroon als startOnAiAdvisor, nu voor de
    // Learner's MANUAL-melding (FclLearnerNotificationHelper). Ook dit vlag
    // wordt één keer, hogerop in FCLComposeContent.kt, gelezen en hier
    // doorgegeven — zie de toelichting bij startOnAiAdvisor hieronder.
    startOnLearner: Boolean = false
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Android terugknop sluit de analyzer
    BackHandler { onDismiss() }

    // 05/07/2026 (Ecko): als de gebruiker via de actieknop van de native AAPS-
    // notificatie hierheen kwam, direct op het AI Advisor-tabblad openen i.p.v.
    // het dashboard. HERZIEN (05/07/2026): het one-shot vlag zelf wordt nu
    // ÉÉN keer, hogerop, in FCLComposeContent.kt gelezen (dat bepaalt ook al
    // welke buitenste tab — Status/Analyzer/Statistics/Settings — actief
    // wordt) en hier als parameter doorgegeven. Nogmaals consumeNavigateRequest()
    // aanroepen zou het vlag al verbruikt hebben gevonden (false), omdat het
    // een get-and-reset is — vandaar geen eigen aanroep meer hier.
    //
    // BUGFIX (10/07/2026, Ecko): startOnLearner sprong voorheen DIRECT naar
    // Screen.ADVISOR — maar dat scherm rendert uitsluitend als
    // advisorResultState.value niet-null is (advisorResultState.value?.let{}),
    // en die waarde wordt normaal ALLEEN gezet via de Dashboard-knop
    // "onOpenAdvisor", die eerst refreshData() + runAdvisorFlow() doorloopt.
    // Bij een directe sprong bleef advisorResultState.value voor altijd null
    // → permanent leeg scherm (geen laad-flikkering, gewoon blijvend blanco).
    // Fix: start op DASHBOARD, en laat een LaunchedEffect hieronder exact
    // dezelfde pijplijn als die knop doorlopen zodra de data geladen is —
    // pas ná die pijplijn schakelt currentScreen echt naar Screen.ADVISOR.
    var currentScreen by remember {
        mutableStateOf(
            when {
                startOnAiAdvisor -> Screen.AI_ADVISOR
                else             -> Screen.DASHBOARD
            }
        )
    }
    var allRows by remember { mutableStateOf<List<LogRow>?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var episodeMetrics by remember { mutableStateOf<List<EpisodeMetrics>?>(null) }
    var aiAdvisorResult by remember {
        mutableStateOf(app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorScheduler.latestResult())
    }
    var classifications by remember { mutableStateOf<List<EpisodeClassifier.EpisodeClassification>?>(null) }
    var nightWindows by remember { mutableStateOf<List<NightWindowEntity>?>(null) }
    var currentAxisState by remember { mutableStateOf<FclAxisState?>(null) }
    val advisorResultState = remember { mutableStateOf<FclAdvisorRecommendation?>(null) }
    var episodeEntities by remember { mutableStateOf<List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity>>(emptyList()) }

    // Klikbare kruisverwijzing tussen Automaat en AI Advisor (24/07/2026, Ecko)
    // — zie de "Zie ook: ..."-tekst in Advisorscreen.kt (NachtControlTab) en
    // FclAiAdvisorScreen.kt (Nacht-tabblad). Los van startOnAiAdvisor/
    // startOnLearner hierboven: die zijn one-shot vlaggen van BUITEN de app
    // (tik op een notificatie), deze zijn interne, herbruikbare state — de
    // gebruiker kan zo'n kruisverwijzing meerdere keren per sessie aantikken.
    var jumpToAiAdvisorNacht by remember { mutableStateOf(false) }
    var jumpToAdvisorNacht by remember { mutableStateOf(false) }

    fun loadFromDatabase(entities: List<FCLCycleLogEntity>) {
        val latest = entities.maxByOrNull { it.timestampMs }
        val aggLevel = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner.getAggressiveness(context)
        currentAxisState = latest?.let {
            val base = CurrentAxisStateResolver.fromLogRow(it)
            CurrentAxisStateResolver.withAggLevel(base, aggLevel)
        }
        allRows = entities.map { it.toLogRow() }
    }

    fun refreshData(onDone: (() -> Unit)? = null) {
        scope.launch {
            val db = FCLAnalyzerDatabase.getInstance(context)

            val entities = withContext(Dispatchers.IO) {
                db.cycleLogDao().getAll()
            }

            loadFromDatabase(entities)

            val latest = entities.maxByOrNull { it.timestampMs }
            latest?.let { row ->
                withContext(Dispatchers.IO) {
                    AdviceLifecycleStore.onProfileObserved(
                        context = context,
                        sterkte = row.context.sterktePct,
                        timing = row.context.timingPct,
                        volhoudendheid = row.context.volhoudendheidPct
                    )
                }
            }

            val detected = EpisodeDetector.detect(allRows!!)
            val manualMaxSmb = FclActiveConfigBridge.get()?.manualMaxBolus ?: 1.25

            // Filter nutteloze episodes: toon en sla alleen episodes op waarbij
            // FCLvNext minimaal 1 keer een echte maaltijdbolus heeft gegeven
            // (>= 50% van maxSMB in minstens 1 cyclus) ÉN voldoende totaal
            // geleverd over de hele episode (>= 100% van maxSMB, opgeteld).
            // Episodes met alleen kleine correcties (zoals nachtcorrecties of
            // sensor-artefacten) zijn niet nuttig voor de learner en verwarren
            // de gebruiker in de episode viewer.
            // De meest recente (nog actieve) episode wordt altijd getoond.
            //
            // HERZIEN 07/07/2026 (Ecko): was uitsluitend een piek-eis (>= 80%
            // van maxSMB in ÉÉN cyclus). Naarmate FCLvNext geleidelijker is
            // gaan doseren (curve-fit-confidence-gates, de WatchingFrontload-
            // ease-in-curve — zie FCLvNext.kt), bereikt een cyclus die piek
            // steeds minder vaak, ook bij een daadwerkelijk substantiële
            // maaltijd: de dosis wordt nu vaker over meerdere kleinere commits
            // verspreid in plaats van in één klap gegeven. Een zuiver piek-
            // gebaseerd filter beloont dus onbedoeld "spits" doseren en straft
            // "geleidelijk" doseren — precies het tegenovergestelde van wat we
            // met die wijzigingen beoogden. Vandaar nu een combinatie: nog
            // steeds een (verlaagde) piek-eis, mét een totaal-eis, zodat een
            // episode ook meetelt als de dosis verspreid maar substantieel was.
            val singleDoseThreshold = manualMaxSmb * 0.50
            val totalDoseThreshold = manualMaxSmb * 1.00
            val allCleaned = if (detected.size > 1) detected.drop(1) else emptyList()
            val lastEpisode = allCleaned.lastOrNull()
            val cleanedEpisodes = allCleaned.filter { ep ->
                ep == lastEpisode ||   // meest recente altijd tonen
                    (
                        ep.rows.any { it.deliveredTotal >= singleDoseThreshold } &&
                            ep.rows.sumOf { it.deliveredTotal } >= totalDoseThreshold
                        )
            }
            episodes = cleanedEpisodes

            val builtMetrics = EpisodeMetricsBuilder.build(cleanedEpisodes, manualMaxSmb)
            episodeMetrics = withContext(Dispatchers.IO) {
                enrichMetricsWithAdviceState(context, builtMetrics)
            }

            classifications = EpisodeClassifier.classifyAll(cleanedEpisodes)

            // ── Stap 3: rescue-classificatie override toepassen ───────────
            withContext(Dispatchers.IO) {
                val rescueYes = db.episodeDao().getRescueConfirmedEpisodes()
                    .map { it.startTs }.toSet()
                if (rescueYes.isNotEmpty()) {
                    classifications = EpisodeClassifier.applyRescueOverrides(
                        classifications!!,
                        rescueYes,
                        cleanedEpisodes
                    )
                }
            }

            // ── Sla episodes op in DB (upsert) zodat rescue-vinkjes werken ──
            withContext(Dispatchers.IO) {
                val dao = db.episodeDao()
                val existing = dao.getAllEpisodes().associateBy { it.startTs }
                val toInsert = cleanedEpisodes.mapIndexedNotNull { i, episode ->
                    val prev = existing[episode.start.toString()]

                    // Maaltijdtype detectie via tijdvenster (niet via mealEpisodeId —
                    // die is FCLvNext-intern en komt niet overeen met analyzer episode IDs)
                    // Koppel LogRows via tijdstip: rows binnen episode.start..episode.end
                    val epRows = allRows?.filter { r ->
                        r.timestamp >= episode.start && r.timestamp <= episode.end
                    }?.sortedBy { it.timestamp } ?: emptyList()

                    // mealType niet meer actief gebruikt (maaltijdtype-onderscheid verwijderd)
                    val avgSlope0_15 = epRows.take(3).map { it.slope }
                        .average().takeIf { !it.isNaN() } ?: 0.0
                    val avgSlope15_30 = epRows.drop(3).take(3).map { it.slope }
                        .average().takeIf { !it.isNaN() } ?: 0.0
                    EpisodeEntity(
                        startTs              = episode.start.toString(),
                        endTs                = episode.end.toString(),
                        durationMinutes      = java.time.Duration.between(episode.start, episode.end).toMinutes(),
                        peakBg               = episodeMetrics?.getOrNull(i)?.peakBg ?: 0.0,
                        nadirBg              = episodeMetrics?.getOrNull(i)?.minBgInWindow ?: 0.0,
                        tirPercent           = 0.0,
                        hyper                = (episodeMetrics?.getOrNull(i)?.peakBg ?: 0.0) > 10.0,
                        hypoEarly            = episode.hypoDetected,
                        hypoLate             = (episodeMetrics?.getOrNull(i)?.minBgInWindow ?: 5.0) < 4.0,
                        earlyAxisDir         = 0,
                        lateAxisDir          = 0,
                        earlyConfidence      = 0.0,
                        lateConfidence       = 0.0,
                        meetsGoal            = (episodeMetrics?.getOrNull(i)?.minBgInWindow ?: 0.0) >= 3.9
                            && (episodeMetrics?.getOrNull(i)?.peakBg ?: 99.0) <= 10.0,
                        sterktePct           = episode.sterktePct,
                        timingPct            = episode.timingPct,
                        volhoudendheidPct    = episode.volhoudendheidPct,
                        doseDistribution     = episode.doseDistribution,
                        totalInsulinDelivered = episodeMetrics?.getOrNull(i)?.totalInsulinDelivered ?: 0.0,
                        advisorWeight        = episodeMetrics?.getOrNull(i)?.advisorWeight ?: 0.0,
                        adviceStatus         = prev?.adviceStatus ?: "",
                        // Bewaar rescue-velden van vorige insert
                        rescueAutoState      = prev?.rescueAutoState ?: "NONE",
                        rescueAutoConfidence = prev?.rescueAutoConfidence ?: 0.0,
                        rescueUserConfirmed  = prev?.rescueUserConfirmed ?: "UNSET",
                        rescueArmedIobRatio  = prev?.rescueArmedIobRatio ?: 0.0,
                        rescueArmedSlope     = prev?.rescueArmedSlope ?: 0.0,
                        rescueArmedBg        = prev?.rescueArmedBg ?: 0.0,
                        rescueArmedMinAfterPeak = prev?.rescueArmedMinAfterPeak ?: 0,
                        mealType             = "",
                        mealTypeSlope0_15    = avgSlope0_15,
                        mealTypeSlope15_30   = avgSlope15_30
                    )
                }
                if (toInsert.isNotEmpty()) dao.insertEpisodes(toInsert)

                // ── Stap 1: rescue-status per episode aggregeren ──────────
                // Zoek de hoogste rescue-state die tijdens elke episode bereikt werd
                // en sla ARM-context op voor het leerproces.
                for (episode in cleanedEpisodes) {
                    val epRows = allRows?.filter {
                        it.mealEpisodeId != null &&
                            it.mealEpisodeId == episode.id.toLong()
                    } ?: continue

                    if (epRows.isEmpty()) continue

                    // Hoogste state tijdens episode
                    val hasConfirmed = epRows.any { it.rescueState == "CONFIRMED" }
                    val hasArmed     = epRows.any { it.rescueState == "ARMED" }
                    val autoState    = when {
                        hasConfirmed -> "CONFIRMED"
                        hasArmed     -> "ARMED"
                        else         -> "NONE"
                    }

                    if (autoState == "NONE") continue

                    // Confidence = max over alle cycli
                    val autoConf = epRows.maxOf { it.rescueConfidence }

                    // ARM-context: neem de eerste ARMED rij
                    val armedRow = epRows.firstOrNull { it.rescueState == "ARMED" }

                    // Minuten na pieik: zoek piek-index dan tel afstand tot armedRow
                    val peakIdx = epRows.indexOfFirst { it.bg == epRows.maxOf { r -> r.bg } }
                    val armedIdx = if (armedRow != null) epRows.indexOf(armedRow) else -1
                    val minAfterPeak = if (peakIdx >= 0 && armedIdx > peakIdx)
                        (armedIdx - peakIdx) * 5 else 0

                    dao.updateRescueAuto(
                        startTs          = episode.start.toString(),
                        autoState        = autoState,
                        autoConfidence   = autoConf,
                        armedIobRatio    = armedRow?.iobRatio ?: 0.0,
                        armedSlope       = armedRow?.slope ?: 0.0,
                        armedBg          = armedRow?.bg ?: 0.0,
                        armedMinAfterPeak = minAfterPeak
                    )
                }
            }

            // ── Stap 2: leerproces aansturen op basis van bevestigingen ──
            withContext(Dispatchers.IO) {
                val dao = db.episodeDao()
                val rescueConfirmedEps = dao.getRescueConfirmedEpisodes()
                val rescueFalsePos     = dao.getRescueFalsePositiveEpisodes()
                val rescueMissed       = dao.getRescueMissedEpisodes()
                RescueLearner.learn(context, rescueConfirmedEps, rescueFalsePos, rescueMissed)

                // Stap 4: markeer episodeMetrics voor DFLearner
                val rescueYesTs = rescueConfirmedEps.map { it.startTs }.toSet()
                if (rescueYesTs.isNotEmpty()) {
                    episodeMetrics = episodeMetrics?.mapIndexed { i, m ->
                        val ep = cleanedEpisodes.getOrNull(i)
                        if (ep != null && ep.start.toString() in rescueYesTs)
                            m.copy(rescueConfirmed = true) else m
                    }
                }
            }

            episodeEntities = withContext(Dispatchers.IO) {
                db.episodeDao().getAllEpisodes()
            }

            // ── Stap 5: nachtvensters bouwen en opslaan ───────────────────
            // insertNightWindows() werd hiervoor nooit aangeroepen — de data
            // werd wel gelezen maar nooit berekend. Vanaf hier wordt bij elke
            // refresh opnieuw gebouwd op basis van de laatste 14 nachten.
            withContext(Dispatchers.IO) {
                val cutoffMs = System.currentTimeMillis() - 14 * 24 * 3600_000L
                // entities is List<FCLCycleLogEntity> — het juiste type voor
                // NightWindowAnalyzer.build(). allRows is List<LogRow> (Instant-
                // timestamp), entities heeft timestampMs (Long) voor de filter.
                val recentEntities = entities.filter { it.timestampMs > cutoffMs }
                val recentEpisodes = db.episodeDao().getAllEpisodes()
                val profiles = db.basalProfileHistoryDao().getAll()
                if (recentEntities.isNotEmpty()) {
                    val windows = NightWindowAnalyzer.build(recentEntities, recentEpisodes, profiles)
                    db.nightWindowDao().insertNightWindows(windows)
                }
            }

            nightWindows = withContext(Dispatchers.IO) {
                db.nightWindowDao().getAllNightWindows()
            }

            onDone?.invoke()
        }
    }

    LaunchedEffect(Unit) { refreshData() }

    // BUGFIX (10/07/2026, Ecko) — zie kdoc bij currentScreen hierboven: dit
    // repliceert exact dezelfde pijplijn als de Dashboard-knop "onOpenAdvisor"
    // (regel ~356 verderop), alleen getriggerd door startOnLearner i.p.v. een
    // klik. Draait één keer; als startOnLearner false is, gebeurt er niets.
    LaunchedEffect(startOnLearner) {
        if (!startOnLearner) return@LaunchedEffect
        refreshData {
            if (episodes != null && episodeMetrics != null && classifications != null && currentAxisState != null) {
                scope.launch {
                    runAdvisorFlow(
                        context = context,
                        episodes = episodes!!,
                        episodeMetrics = episodeMetrics!!,
                        classifications = classifications!!,
                        currentAxisState = currentAxisState!!,
                        allRows = allRows ?: emptyList(),
                        onMetricsUpdated = { episodeMetrics = it },
                        onResult = {
                            advisorResultState.value = it
                            currentScreen = Screen.ADVISOR
                        }
                    )
                }
            }
        }
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.AI_ADVISOR) jumpToAiAdvisorNacht = false
        if (currentScreen == Screen.ADVISOR) jumpToAdvisorNacht = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (currentScreen) {

            Screen.DASHBOARD -> DashboardScreen(
                hasData = allRows != null,
                allRows = allRows,
                episodeCount = episodes?.size ?: 0,
                lastSyncTs = allRows?.maxByOrNull { it.timestamp }?.timestamp,
                episodes = episodes,
                metrics = episodeMetrics,
                onBack = onDismiss,
                onRefreshData = { refreshData() },
                onOpenEpisodes = {
                    refreshData {
                        if (allRows != null && episodes != null && episodeMetrics != null && classifications != null) {
                            currentScreen = Screen.ANALYZE
                        }
                    }
                },
                onOpenAdvisor = {
                    refreshData {
                        if (episodes != null && episodeMetrics != null && classifications != null && currentAxisState != null) {
                            scope.launch {
                                runAdvisorFlow(
                                    context = context,
                                    episodes = episodes!!,
                                    episodeMetrics = episodeMetrics!!,
                                    classifications = classifications!!,
                                    currentAxisState = currentAxisState!!,
                                    allRows = allRows ?: emptyList(),
                                    onMetricsUpdated = { episodeMetrics = it },
                                    onResult = {
                                        advisorResultState.value = it
                                        currentScreen = Screen.ADVISOR
                                    }
                                )
                            }
                        }
                    }
                },
                onOpenReset = { currentScreen = Screen.RESET },
                onOpenAiAdvisor = { currentScreen = Screen.AI_ADVISOR },
                onOpenSafetyCheck = { currentScreen = Screen.SAFETY }
            )

            Screen.RESET -> FclResetScreen(onBack = { currentScreen = Screen.DASHBOARD })

            Screen.SAFETY -> FclSafetyCheckScreen(
                episodes = episodes ?: emptyList(),
                onBack = { currentScreen = Screen.DASHBOARD }
            )

            Screen.AI_ADVISOR -> FclAiAdvisorScreen(
                runResult = aiAdvisorResult,
                onBack = { currentScreen = Screen.DASHBOARD },
                onRefreshNow = {
                    FclAiAdvisorScheduler.forceRunNow(
                        context = context,
                        metrics = episodeMetrics ?: emptyList(),
                        onDone = { result ->
                            scope.launch(Dispatchers.Main) { aiAdvisorResult = result }
                        }
                    )
                },
                startOnNacht = jumpToAiAdvisorNacht,
                // Klikbare kruisverwijzing (24/07/2026, Ecko): repliceert exact
                // dezelfde pijplijn als de Dashboard-knop "onOpenAdvisor" hieronder
                // (en LaunchedEffect(startOnLearner) hierboven) — Screen.ADVISOR
                // rendert alleen als advisorResultState.value niet-null is, dus een
                // directe currentScreen-sprong zonder deze pijplijn zou een blijvend
                // leeg scherm geven.
                onJumpToAutomaatNacht = {
                    jumpToAdvisorNacht = true
                    refreshData {
                        if (episodes != null && episodeMetrics != null && classifications != null && currentAxisState != null) {
                            scope.launch {
                                runAdvisorFlow(
                                    context = context,
                                    episodes = episodes!!,
                                    episodeMetrics = episodeMetrics!!,
                                    classifications = classifications!!,
                                    currentAxisState = currentAxisState!!,
                                    allRows = allRows ?: emptyList(),
                                    onMetricsUpdated = { episodeMetrics = it },
                                    onResult = {
                                        advisorResultState.value = it
                                        currentScreen = Screen.ADVISOR
                                    }
                                )
                            }
                        }
                    }
                }
            )

            Screen.ANALYZE -> AnalyzeScreen(
                allRows = allRows!!,
                episodes = episodes!!,
                episodeMetrics = episodeMetrics!!,
                classifications = classifications!!,
                currentAxisState = currentAxisState ?: StvState(100, 100, 100),
                episodeEntities = episodeEntities,
                onRescueUserConfirmed = { startTs, confirmed ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            FCLAnalyzerDatabase.getInstance(context)
                                .episodeDao()
                                .updateRescueUserConfirmed(startTs, confirmed)
                        }
                        episodeEntities = withContext(Dispatchers.IO) {
                            FCLAnalyzerDatabase.getInstance(context)
                                .episodeDao()
                                .getAllEpisodes()
                        }
                    }
                },
                onBack = { currentScreen = Screen.DASHBOARD }
            )

            Screen.ADVISOR -> {
                advisorResultState.value?.let { result ->
                    var activeParams by remember(result) {
                        mutableStateOf(ConfigOverrideWriter.readActiveParams())
                    }

                    LaunchedEffect(Unit) {
                        if (activeParams.earlyBoostFactor <= 1.01) {
                            val d = DFLearner.getD(context)
                            val f = DFLearner.getF(context)
                            ConfigOverrideWriter.writeWithStvAndParams(
                                stvMap       = DFMapping.toStvMap(d, f, DFLearner.getNfLevel(context),
                                                                  aggLevel = DFLearner.getAggressiveness(context)),
                                paramOverrides = DFMapping.toParamOverrides(d, f,
                                                                            aggLevel = DFLearner.getAggressiveness(context)),
                                reason       = "Initiële param-sync: earlyBoost nog op default",
                                context      = context,
                                episodeCount = episodes?.size ?: 0
                            )
                        }
                    }

                    AdvisorScreen(
                        recommendation = result,
                        current = currentAxisState ?: StvState(100, 100, 100),
                        episodeCount = episodes?.size ?: 0,
                        episodes = episodes ?: emptyList(),
                        metrics = episodeMetrics ?: emptyList(),
                        activeParams = activeParams,
                        nightWindows = nightWindows ?: emptyList(),
                        onBack = { currentScreen = Screen.DASHBOARD },
                        onApplyToAaps = { stvMap ->
                            val d = DFLearner.getD(context)
                            val f = DFLearner.getF(context)
                            // BUGFIX (20/06/2026): gaf voorheen alleen d/f/aggLevel door —
                            // refWmd/refWff/refEb/refPeakBias/refLcd vielen daardoor terug
                            // op hun DEFAULT i.p.v. de geleerde waarden, telkens als deze
                            // knop werd ingedrukt. Ontdekt bij het toevoegen van refLcd.
                            ConfigOverrideWriter.writeWithStvAndParams(
                                stvMap = stvMap,
                                paramOverrides = DFMapping.toParamOverrides(d, f,
                                                                            refWmd = DFLearner.getRefWmd(context),
                                                                            refWff = DFLearner.getRefWff(context),
                                                                            refEb = DFLearner.getRefEb(context),
                                                                            refPeakBias = DFLearner.getRefPeakBias(context),
                                                                            refLcd = DFLearner.getRefLcd(context),
                                                                            aggLevel = DFLearner.getAggressiveness(context)),
                                reason = result.summary,
                                context      = context,
                                episodeCount = episodes?.size ?: 0
                            )
                        },
                        onApplyNacht = { newNfLevel ->
                            ConfigOverrideWriter.writeWithNfLevel(
                                currentState = currentAxisState ?: StvState(100, 100, 100),
                                newNfLevel = newNfLevel,
                                reason = "Nacht NF aanpassing via Advisor",
                                episodeCount = episodes?.size ?: 0
                            )
                        },
                        allRows = allRows ?: emptyList(),
                        nfLevel = DFLearner.getNfLevel(context),
                        onApplyDFToAaps = { params, stvMap ->
                            ConfigOverrideWriter.writeWithStvAndParams(
                                stvMap = stvMap,
                                paramOverrides = params,
                                reason = "D/F leer-systeem via analyzer",
                                context      = context,
                                episodeCount = episodes?.size ?: 0
                            )
                        },
                        onApplyParams = null,
                        startOnNacht = jumpToAdvisorNacht,
                        // Klikbare kruisverwijzing (24/07/2026, Ecko): AI_ADVISOR is,
                        // anders dan ADVISOR, een simpele directe toestandswissel — geen
                        // refreshData/runAdvisorFlow-pijplijn nodig (zie ook
                        // onOpenAiAdvisor hierboven, dat hetzelfde al deed).
                        onJumpToAiAdvisorNacht = {
                            jumpToAiAdvisorNacht = true
                            currentScreen = Screen.AI_ADVISOR
                        }
                    )
                } ?: run {
                    currentScreen = Screen.DASHBOARD
                }
            }
        }
    } // end Box
}

// ── Dashboard screen ──────────────────────────────────────────────────────

@Composable
private fun DashboardScreen(
    hasData: Boolean,
    allRows: List<LogRow>?,
    episodeCount: Int,
    lastSyncTs: Instant?,
    episodes: List<Episode>?,
    metrics: List<EpisodeMetrics>?,
    onBack: () -> Unit,
    onRefreshData: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenAdvisor: () -> Unit,
    onOpenAiAdvisor: () -> Unit,
    onOpenReset: () -> Unit,
    onOpenSafetyCheck: () -> Unit = {}
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    val metricCount = metrics?.size ?: 0
    val advisorEnabled = episodes?.isNotEmpty() == true && metrics?.isNotEmpty() == true
    val hasData2 = hasData && (allRows?.isNotEmpty() == true)

    // ✅ Openstaande AI-voorstellen tellen voor badge/notificatie (02/07/2026, Ecko)
    // 05/07/2026, Ecko — bugfix: readPendingFromLastRun() gaf altijd 0 (zie
    // toelichting in FclAiAdvisorHistoryRepository.kt). Nu via de daadwerkelijke
    // laatste run + isStillPending() per suggestie.
    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingAiCount = remember {
        app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorScheduler.latestResult()
            ?.suggestions
            ?.count { app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorHistoryRepository.isStillPending(it) }
            ?: 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Titelrij ──────────────────────────────────────────────────────
        // ── Titelbalk met accentkleur ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                s.analyzerTabLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Inhoudelijke knoppen ──────────────────────────────────────
            NavActieKaart(
                emoji   = "🧠",
                label   = s.advisor,
                badge   = if (metricCount > 0) "$metricCount" else null,
                enabled = advisorEnabled,
                toelichting = "Bekijk hoe de vier leerassen (Sterkte, Timing, " +
                    "Vasthoudendheid en Frontload-timing) zijn bijgesteld op basis " +
                    "van de laatste maaltijdepisodes. Hier stel je ook de agressiviteit in.",
                knoopTekst = s.advisor,
                onClick = onOpenAdvisor
            )
            NavActieKaart(
                emoji   = "📊",
                label   = s.episodes,
                badge   = if (episodeCount > 0) "$episodeCount" else null,
                enabled = hasData2,
                toelichting = "Bekijk en analyseer individuele maaltijdepisodes. " +
                    "Per episode zie je de BG-curve, insulineverdeling en de diagnose " +
                    "die de automaat heeft gesteld.",
                knoopTekst = s.episodes,
                onClick = onOpenEpisodes
            )

            NavActieKaart(
                emoji   = if (pendingAiCount > 0) "🤖" else "🤖",
                label   = if (pendingAiCount > 0) "AI Advisor  ·  $pendingAiCount wacht" else "AI Advisor",
                badge   = if (pendingAiCount > 0) "$pendingAiCount" else null,
                enabled = hasData2,
                toelichting = if (pendingAiCount > 0)
                    "⚠️ Er ${if (pendingAiCount == 1) "staat 1 voorstel" else "staan $pendingAiCount voorstellen"} klaar om te beoordelen. " +
                        "Tik om ze te bekijken — elk voorstel toont de reden en het bewijs. " +
                        "Pas actief na jouw handmatige goedkeuring per parameter."
                else
                    "Vraag 2× per dag (of nu handmatig, om te testen) een externe AI " +
                        "om je geleerde FCLvNext-parameters te controleren. Elk voorstel verschijnt " +
                        "als losse kaart met reden en bewijs — wordt pas actief na jouw handmatige " +
                        "goedkeuring per parameter.",
                knoopTekst = if (pendingAiCount > 0) "Bekijk voorstellen →" else "AI Advisor",
                onClick = onOpenAiAdvisor
            )

            // ✅ Veiligheidscontrole: aantal episodes met een late, te grote
            // commit vlak op de piek (SafetyInvariantChecker, 12/07/2026, Ecko —
            // zie kdoc daar voor de exacte regel). Puur informatief, geen
            // effect op het doseeralgoritme zelf.
            val safetyViolationCount = remember(episodes) {
                episodes?.let { app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.SafetyInvariantChecker.checkAll(it) }
                    ?.count { r -> r.hasViolation } ?: 0
            }
            NavActieKaart(
                emoji   = if (safetyViolationCount > 0) "⚠️" else "🛡️",
                label   = "Veiligheidscontrole",
                badge   = if (safetyViolationCount > 0) "$safetyViolationCount" else null,
                enabled = hasData2,
                toelichting = if (safetyViolationCount > 0)
                    "$safetyViolationCount episode(s) met een commit die vlak op de BG-piek " +
                        "groter was dan verwacht op basis van de afbouw die episode. Bekijk welke " +
                        "rem (indien enige) op dat moment actief was."
                else
                    "Controleert elke episode automatisch op het \"late, te grote commit\"-patroon " +
                        "(zie overdrachtsdocument §3) — zodat je dit niet handmatig in de CSV hoeft " +
                        "op te sporen.",
                knoopTekst = "Check",
                onClick = onOpenSafetyCheck
            )

            // Ruimte gereserveerd voor eventuele vierde inhoudelijke knop
            Spacer(modifier = Modifier.height(4.dp))

            // ── Scheidingslijn: inhoud / beheer ───────────────────────────
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp
            )

            // ── Beheerfuncties: Ververs + Reset ───────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Ververs
                OutlinedButton(
                    onClick = onRefreshData,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(s.vernieuwen)
                }
                // Reset
                OutlinedButton(
                    onClick = onOpenReset,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Reset...")
                }
            }

            // Data-info onderaan
            Text(
                text = if (allRows != null)
                    "${allRows.size} cycli • data direct uit AAPS database"
                else "Laden…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun NavActieKaart(
    emoji: String,
    label: String,
    badge: String?,
    enabled: Boolean,
    toelichting: String,
    knoopTekst: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Links: grote knop met emoji + tekst
            Button(
                onClick = { if (enabled) onClick() },
                enabled = enabled,
                modifier = Modifier.width(96.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor   = MaterialTheme.colorScheme.onSecondaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                    Text(knoopTekst,
                         style = MaterialTheme.typography.labelSmall,
                         maxLines = 1)
                    if (badge != null) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (enabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        ) {
                            Text(
                                badge,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
            // Rechts: titel + toelichting
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Text(
                    toelichting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


// ── NavTile ───────────────────────────────────────────────────────────────

@Composable
private fun NavTile(
    emoji: String,
    label: String,
    badge: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (enabled)
        MaterialTheme.colorScheme.secondaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    val contentColor = if (enabled)
        MaterialTheme.colorScheme.onSecondaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = contentColor)
            if (badge != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.3f)
                ) {
                    Text(
                        text = badge,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Advisor flow helper ───────────────────────────────────────────────────

private suspend fun runAdvisorFlow(
    context: android.content.Context,
    episodes: List<Episode>,
    episodeMetrics: List<EpisodeMetrics>,
    classifications: List<EpisodeClassifier.EpisodeClassification>,
    currentAxisState: FclAxisState,
    allRows: List<LogRow> = emptyList(),
    onMetricsUpdated: (List<EpisodeMetrics>) -> Unit,
    onResult: (FclAdvisorRecommendation) -> Unit
) {
    val db = FCLAnalyzerDatabase.getInstance(context)

    val storedEpisodesByStart = withContext(Dispatchers.IO) {
        db.episodeDao().getAllEpisodes().associateBy { it.startTs }
    }

    val selections = episodeMetrics.indices.map { index ->
        val episode = episodes[index]
        val metric = episodeMetrics[index]
        val classification = classifications[index]
        val stored = storedEpisodesByStart[episode.start.toString()]

        val matchesCurrentSettings = CurrentAxisStateResolver.matchesEpisodeSettings(
            episodeSterkte = episode.sterktePct,
            episodeTiming = episode.timingPct,
            episodeVolhoudendheid = episode.volhoudendheidPct,
            current = currentAxisState
        )

        val exclusionReason = when {
            !episode.isComplete -> AdvisorExclusionReason.INCOMPLETE
            !matchesCurrentSettings -> AdvisorExclusionReason.OTHER_SETTINGS
            stored?.adviceStatus == AdviceLifecycleStore.STATE_CONSUMED -> AdvisorExclusionReason.CONSUMED
            metric.advisorWeight <= 0.0 -> AdvisorExclusionReason.LOW_INSULIN
            else -> null
        }

        AdvisorEpisodeSelection(
            startTs = episode.start.toString(),
            metric = metric.copy(
                includedInAdvice = exclusionReason == null,
                adviceStatus = stored?.adviceStatus ?: metric.adviceStatus
            ),
            classification = classification,
            isEligible = exclusionReason == null,
            exclusionReason = exclusionReason
        )
    }

    val selectionInfo = FclAdvisorSelectionInfo(
        totalEpisodesSeen = selections.size,
        usedEpisodeCount = selections.count { it.isEligible },
        excludedOtherSettings = selections.count { it.exclusionReason == AdvisorExclusionReason.OTHER_SETTINGS },
        excludedLowInsulin = selections.count { it.exclusionReason == AdvisorExclusionReason.LOW_INSULIN },
        excludedConsumed = selections.count { it.exclusionReason == AdvisorExclusionReason.CONSUMED },
        excludedIncomplete = selections.count { it.exclusionReason == AdvisorExclusionReason.INCOMPLETE }
    )

    onMetricsUpdated(selections.map { it.metric })

    val filteredMetrics = selections.filter { it.isEligible }.map { it.metric }
    val filteredClasses = selections.filter { it.isEligible }.map { it.classification }

    if (filteredMetrics.isEmpty()) {
        onResult(FclAdvisorRecommendation(
            dominantPattern = FclPattern.MIXED_UNCLEAR,
            confidence = 0.0,
            patternScores = emptyList(),
            adjustment = StvAdjustment(),
            vector = FclAdjustmentVector(),
            transitions = emptyList(),
            stats = FclAdvisorStats(
                usedEpisodeCount = 0, avgTirPercent = 0,
                avgPeakBg = 0.0, avgRiseMagnitude = 0.0,
                avgDurationMinutes = 0, avgInsulinDelivered = 0.0,
                hyperPercent = 0, hypoPercent = 0, meetsGoalPercent = 0
            ),
            summary = "Geen episodes beschikbaar voor analyse onder de huidige selectie.",
            selectionInfo = selectionInfo
        ))
        return
    }

    val aggregate = EpisodeAggregateBuilder.build(filteredMetrics, filteredClasses)
    val recommendation = FclPatternAdvisor.analyzeAggregate(
        aggregate = aggregate,
        metrics = filteredMetrics,
        classes = filteredClasses,
        current = currentAxisState
    )

    // D/F leer-stap
    // BELANGRIJK: gebruik ALLE afgesloten episodes voor het leerproces,
    // NIET alleen de advisor-eligible episodes (filteredMetrics).
    // CONSUMED betekent "niet meer bruikbaar voor advisor aanbeveling"
    // maar de leerdata is nog steeds geldig voor D/F optimalisatie.
    // Als we alleen filteredMetrics gebruiken stopt het leren zodra de automaat
    // een aanpassing heeft gedaan (want dan worden alle vorige episodes CONSUMED).
    // 26/07/2026 (Ecko) — dag/nacht-splitsing: latestMetrics moet EERST bekend
    // zijn voordat de modus-gate kan bepalen welke as (dag/nacht) geldt —
    // was voorheen een vaste DFLearner.isAutoEnabled(context) zonder as.
    val allCompletedMetrics = episodeMetrics.filterIndexed { i, _ ->
        episodes.getOrNull(i)?.isComplete == true
    }
    val latestMetricsForLearn = allCompletedMetrics.lastOrNull()

    if (latestMetricsForLearn != null && DFLearner.isAutoEnabled(context, latestMetricsForLearn.isNight)) {
        val latestMetrics = latestMetricsForLearn

        // Gebruik de laatste AFGESLOTEN episode voor type-detectie
        // Een lopende episode heeft mogelijk een afwijkend slopepatroon
        val latestCompletedEpisode = episodes.lastOrNull { it.isComplete }
            ?: episodes.lastOrNull()

        if (latestMetrics != null) {
            // Type-specifieke leer-stap
            // manualMaxSmb hier opnieuw opgehaald (niet in scope vanuit de
            // LaunchedEffect hierboven) — zie controlevraag Ecko 20/06/2026.
            val manualMaxSmbForEval = FclActiveConfigBridge.get()?.manualMaxBolus ?: 1.25
            val step = DFLearner.evaluate(context, latestMetrics, manualMaxSmb = manualMaxSmbForEval)
            // MaxSmbLearner.evaluate verwijderd — maxSMB volgt S%

            // Frontload timing leren — alle bruikbare episodes meegeven
            val frontloadResult = FrontloadLearner.evaluate(context, episodeMetrics)
            val dfChanged = step != null && step.hasChange

            if (dfChanged) {
                // Gebruik type-specifieke D/F als er een type-aanpassing was,
                // anders de algemene D/F.
                val newD = DFLearner.getD(context)
                val newF = DFLearner.getF(context)
                val nfLevel = DFLearner.getNfLevel(context)
                ConfigOverrideWriter.writeWithStvAndParams(
                    stvMap         = DFMapping.toStvMap(newD, newF, nfLevel,
                                                        aggLevel = DFLearner.getAggressiveness(context)),
                    paramOverrides = DFMapping.toParamOverrides(
                        d = newD, f = newF,
                        refWmd = DFLearner.getRefWmd(context),
                        refWff = DFLearner.getRefWff(context),
                        refEb = DFLearner.getRefEb(context),
                        refPeakBias = DFLearner.getRefPeakBias(context),
                        refLcd = DFLearner.getRefLcd(context),
                        aggLevel = DFLearner.getAggressiveness(context)
                    ),
                    reason         = "D/F: ${step!!.reason}",
                    context        = context,
                    episodeCount   = filteredMetrics.size
                )
            }
        }
    }

    // Lifecycle: markeer episodes in huidig advies
    val hasActionableAdvice = recommendation.transitions.isNotEmpty()
    val includedStartTs = selections.filter { it.isEligible }.map { it.startTs }
    withContext(Dispatchers.IO) {
        val episodeDao = db.episodeDao()
        episodeDao.replaceAdviceState(
            oldState = AdviceLifecycleStore.STATE_IN_LAST_RECOMMENDATION,
            newState = AdviceLifecycleStore.STATE_NEW
        )
        if (hasActionableAdvice && includedStartTs.isNotEmpty()) {
            episodeDao.updateAdviceStateForStarts(
                startTsList = includedStartTs,
                state = AdviceLifecycleStore.STATE_IN_LAST_RECOMMENDATION
            )
        }
    }

    onResult(recommendation.copy(selectionInfo = selectionInfo))
}

// ── Helpers ───────────────────────────────────────────────────────────────

private enum class AdvisorExclusionReason {
    OTHER_SETTINGS, LOW_INSULIN, CONSUMED, INCOMPLETE
}

private data class AdvisorEpisodeSelection(
    val startTs: String,
    val metric: EpisodeMetrics,
    val classification: EpisodeClassifier.EpisodeClassification,
    val isEligible: Boolean,
    val exclusionReason: AdvisorExclusionReason?
)

private suspend fun enrichMetricsWithAdviceState(
    context: android.content.Context,
    metrics: List<EpisodeMetrics>
): List<EpisodeMetrics> {
    val dao = FCLAnalyzerDatabase.getInstance(context).episodeDao()
    return metrics.map { metric ->
        val entity = dao.getEpisodeByStartTs(metric.start.toString())
        metric.copy(adviceStatus = entity?.adviceStatus ?: metric.adviceStatus)
    }
}