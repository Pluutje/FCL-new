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
import app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.*
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.*
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private enum class Screen { DASHBOARD, ANALYZE, ADVISOR }

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
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Android terugknop sluit de analyzer
    BackHandler { onDismiss() }

    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    var allRows by remember { mutableStateOf<List<LogRow>?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var episodeMetrics by remember { mutableStateOf<List<EpisodeMetrics>?>(null) }
    var classifications by remember { mutableStateOf<List<EpisodeClassifier.EpisodeClassification>?>(null) }
    var nightWindows by remember { mutableStateOf<List<NightWindowEntity>?>(null) }
    var currentAxisState by remember { mutableStateOf<FclAxisState?>(null) }
    val advisorResultState = remember { mutableStateOf<FclAdvisorRecommendation?>(null) }
    var episodeEntities by remember { mutableStateOf<List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity>>(emptyList()) }

    fun loadFromDatabase(entities: List<FCLCycleLogEntity>) {
        val latest = entities.maxByOrNull { it.timestampMs }
        currentAxisState = latest?.let {
            CurrentAxisStateResolver.fromLogRow(it)
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
                        sterkte = row.sterktePct,
                        timing = row.timingPct,
                        volhoudendheid = row.volhoudendheidPct
                    )
                }
            }

            val detected = EpisodeDetector.detect(allRows!!)
            val cleanedEpisodes = if (detected.size > 1) detected.drop(1) else emptyList()
            episodes = cleanedEpisodes

            val manualMaxSmb = FclActiveConfigBridge.get()?.manualMaxSmbDay ?: 1.25
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

                    // Verbeterde type-detectie: zoek het EERSTE venster van echte stijging
                    // (2+ opeenvolgende cycli met slope > 0.2 mmol/5min)
                    // Reden: episode kan starten terwijl BG nog daalt van vorige dosis.
                    // De eerste stijgingsfase bepaalt het karakter van de maaltijd.
                    val stijgingsVensterIdx = run {
                        val slopes = epRows.map { it.slope }
                        var idx = -1
                        for (j in 0 until slopes.size - 1) {
                            if (slopes[j] > 0.20 && slopes[j + 1] > 0.20) {
                                idx = j
                                break
                            }
                        }
                        idx
                    }

                    val avgSlopeVenster: Double
                    val avgSlope0_15: Double
                    val avgSlope15_30: Double

                    if (stijgingsVensterIdx >= 0) {
                        // Gebruik het stijgingsvenster voor type-detectie
                        val vensterRows = epRows.drop(stijgingsVensterIdx).take(4)
                        avgSlopeVenster = vensterRows.map { it.slope }
                            .average().takeIf { !it.isNaN() } ?: 0.0
                        // Bewaar ook originele 0-15 / 15-30 voor weergave
                        avgSlope0_15 = epRows.take(3).map { it.slope }
                            .average().takeIf { !it.isNaN() } ?: 0.0
                        avgSlope15_30 = epRows.drop(3).take(3).map { it.slope }
                            .average().takeIf { !it.isNaN() } ?: 0.0
                    } else {
                        // Geen stijging gevonden → TRAAG
                        avgSlopeVenster = 0.0
                        avgSlope0_15 = epRows.take(3).map { it.slope }
                            .average().takeIf { !it.isNaN() } ?: 0.0
                        avgSlope15_30 = epRows.drop(3).take(3).map { it.slope }
                            .average().takeIf { !it.isNaN() } ?: 0.0
                    }

                    // Vereenvoudigd: alleen SNEL of TRAAG, geen GEMENGD meer.
                    // SNEL: stijgingsvenster gemiddeld ≥ 0.35 mmol/5min
                    // TRAAG: al het overige (langzame stijging of geen stijging)
                    val detectedType = when {
                        stijgingsVensterIdx < 0          -> "TRAAG"   // geen stijging
                        avgSlopeVenster >= 0.35          -> "SNEL"    // snelle stijging
                        else                             -> "TRAAG"   // trage stijging
                    }
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
                        mealType             = detectedType,
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

            nightWindows = withContext(Dispatchers.IO) {
                db.nightWindowDao().getAllNightWindows()
            }

            onDone?.invoke()
        }
    }

    LaunchedEffect(Unit) { refreshData() }

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
                        // Herlaad entities zodat de UI direct de nieuwe staat toont
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
                                stvMap       = DFMapping.toStvMap(d, f, activeParams.nachtFactor),
                                paramOverrides = DFMapping.toParamOverrides(d, f),
                                reason       = "Initiële param-sync: earlyBoost nog op default",
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
                            ConfigOverrideWriter.writeWithStvAndParams(
                                stvMap = stvMap,
                                paramOverrides = DFMapping.toParamOverrides(d, f),
                                reason = result.summary,
                                episodeCount = episodes?.size ?: 0
                            )
                        },
                        onApplyNacht = { newNachtFactor ->
                            ConfigOverrideWriter.writeWithNacht(
                                currentState = currentAxisState ?: StvState(100, 100, 100),
                                newNachtFactor = newNachtFactor,
                                reason = "Nacht-N aanpassing via Analyzer",
                                episodeCount = episodes?.size ?: 0
                            )
                        },
                        allRows = allRows ?: emptyList(),
                        nachtFactor = activeParams.nachtFactor,
                        onApplyDFToAaps = { params, stvMap ->
                            ConfigOverrideWriter.writeWithStvAndParams(
                                stvMap = stvMap,
                                paramOverrides = params,
                                reason = "D/F leer-systeem via analyzer",
                                episodeCount = episodes?.size ?: 0
                            )
                        },
                        onApplyParams = null,
                        onApplyMaxSmb = { newMaxSmb, newIobBrake ->
                            val d = DFLearner.getD(context)
                            val f = DFLearner.getF(context)
                            val nachtFactor = ConfigOverrideWriter.readActiveParams().nachtFactor
                            ConfigOverrideWriter.writeWithStvAndParams(
                                stvMap           = DFMapping.toStvMap(d, f, nachtFactor),
                                paramOverrides   = DFMapping.toParamOverrides(d, f),
                                reason           = "Handmatig MaxSMB: maxSMB=${"%.2f".format(newMaxSmb)}U brake=${"%.3f".format(newIobBrake)}",
                                episodeCount     = episodes?.size ?: 0,
                                maxSmbDayLearned = newMaxSmb,
                                iobBrakeLearned  = newIobBrake
                            )
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
    onOpenAdvisor: () -> Unit
) {
    val metricCount = metrics?.size ?: 0
    val advisorEnabled = episodes?.isNotEmpty() == true && metrics?.isNotEmpty() == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Terug"
                )
            }
            Text("FCL Analyzer", style = MaterialTheme.typography.titleLarge)
            // Spacer zodat titel gecentreerd lijkt
            androidx.compose.foundation.layout.Spacer(Modifier.size(48.dp))
        }

        TimeInRangeCard(
            rows = allRows ?: emptyList(),
            lastSyncTs = lastSyncTs
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NavTile(
                emoji = "📊",
                label = "Episodes",
                badge = if (episodeCount > 0) "$episodeCount" else null,
                enabled = hasData,
                onClick = onOpenEpisodes,
                modifier = Modifier.weight(1f)
            )
            NavTile(
                emoji = "🧠",
                label = "Advisor",
                badge = if (metricCount > 0) "$metricCount" else null,
                enabled = advisorEnabled,
                onClick = onOpenAdvisor,
                modifier = Modifier.weight(1f)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Gegevens", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (allRows != null)
                            "${allRows.size} cycli • data direct uit AAPS database"
                        else
                            "Laden…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onRefreshData) {
                    Text("Vernieuwen")
                }
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
    if (DFLearner.isAutoEnabled(context)) {
        val allCompletedMetrics = episodeMetrics.filterIndexed { i, _ ->
            episodes.getOrNull(i)?.isComplete == true
        }
        val latestMetrics = allCompletedMetrics.lastOrNull()

        // Gebruik de laatste AFGESLOTEN episode voor type-detectie
        // Een lopende episode heeft mogelijk een afwijkend slopepatroon
        val latestCompletedEpisode = episodes.lastOrNull { it.isComplete }
            ?: episodes.lastOrNull()

        if (latestMetrics != null) {
            // Bepaal maaltijdtype: eerst uit opgeslagen entity, dan herberekenen
            val latestMealType: MealTypeBridge.MealType = run {
                val entity = latestCompletedEpisode?.let { ep ->
                    storedEpisodesByStart[ep.start.toString()]
                }
                when {
                    entity?.mealType == "SNEL"   -> MealTypeBridge.MealType.SNEL
                    entity?.mealType == "TRAAG"  -> MealTypeBridge.MealType.TRAAG
                    entity?.mealType == "GEMENGD" -> MealTypeBridge.MealType.GEMENGD
                    latestCompletedEpisode != null -> {
                        // Koppel via tijdstip, niet via mealEpisodeId
                        val epRows = allRows.filter { r ->
                            r.timestamp >= latestCompletedEpisode.start &&
                                r.timestamp <= latestCompletedEpisode.end
                        }.sortedBy { it.timestamp }
                        // Zelfde stijgingsvenster-logica als bij episode-insert
                        val vensterIdx = run {
                            val slopes = epRows.map { it.slope }
                            var idx = -1
                            for (j in 0 until slopes.size - 1) {
                                if (slopes[j] > 0.20 && slopes[j + 1] > 0.20) { idx = j; break }
                            }
                            idx
                        }
                        val avgVenster = if (vensterIdx >= 0)
                            epRows.drop(vensterIdx).take(4).map { it.slope }.average().takeIf { !it.isNaN() } ?: 0.0
                        else 0.0
                        when {
                            vensterIdx < 0       -> MealTypeBridge.MealType.TRAAG
                            avgVenster >= 0.35   -> MealTypeBridge.MealType.SNEL
                            else                 -> MealTypeBridge.MealType.TRAAG
                        }
                    }
                    else -> MealTypeBridge.MealType.TRAAG   // veilige fallback
                }
            }

            // Update MealTypeBridge active overrides voor komende FCLvNext cycli
            if (latestMealType != MealTypeBridge.MealType.GEMENGD &&
                latestMealType != MealTypeBridge.MealType.ONBEKEND) {
                MealTypeBridge.activeTypeDOverride = DFLearner.getDForType(context, latestMealType)
                MealTypeBridge.activeTypeFOverride = DFLearner.getFForType(context, latestMealType)
            }

            // Type-specifieke leer-stap
            val step = DFLearner.evaluateForType(context, latestMetrics, latestMealType)
            val smbResult = MaxSmbLearner.evaluate(
                context,
                latestMetrics,
                manualMaxSmb = FclActiveConfigBridge.get()?.manualMaxSmbDay
                    ?: MaxSmbLearner.MAX_SMB_DEFAULT
            )

            // Frontload timing leren — alle bruikbare episodes meegeven
            val frontloadResult = FrontloadLearner.evaluate(context, episodeMetrics)
            val dfChanged = step != null && step.hasChange
            val smbChanged = smbResult != null && smbResult.hasChange

            if (dfChanged || smbChanged) {
                // Gebruik type-specifieke D/F als er een type-aanpassing was,
                // anders de algemene D/F.
                // Reden: evaluateForType past KEY_D_SNEL/KEY_F_SNEL aan maar
                // getD/getF levert de ALGEMENE waarden → T zou nooit bewegen voor Snel.
                val newD = if (dfChanged && latestMealType != MealTypeBridge.MealType.ONBEKEND)
                    DFLearner.getDForType(context, latestMealType)
                else
                    DFLearner.getD(context)

                val newF = if (dfChanged && latestMealType != MealTypeBridge.MealType.ONBEKEND)
                    DFLearner.getFForType(context, latestMealType)
                else
                    DFLearner.getF(context)
                val nachtFactor = ConfigOverrideWriter.readActiveParams().nachtFactor
                ConfigOverrideWriter.writeWithStvAndParams(
                    stvMap           = DFMapping.toStvMap(newD, newF, nachtFactor),
                    paramOverrides   = DFMapping.toParamOverrides(newD, newF),
                    reason           = buildString {
                        if (dfChanged)  append("D/F: ${step!!.reason} ")
                        if (smbChanged) append("MaxSMB: ${smbResult!!.reason}")
                    }.trim(),
                    episodeCount     = filteredMetrics.size,
                    maxSmbDayLearned = if (smbChanged) smbResult!!.newMaxSmb else null,
                    iobBrakeLearned  = if (smbChanged) smbResult!!.newIobBrake else null
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