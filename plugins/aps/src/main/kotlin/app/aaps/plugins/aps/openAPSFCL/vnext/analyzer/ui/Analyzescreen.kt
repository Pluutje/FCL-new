package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.Episode
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeClassifier
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.MealAutonomyAnalyzer
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.MealAutonomyClass
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.MealAutonomyMetrics
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LogRow
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.TimeFormat
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.AdviceLifecycleStore
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.CurrentAxisStateResolver
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.FclAxisState
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Divider
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings

@Composable
fun AnalyzeScreen(
    allRows: List<LogRow>,
    episodes: List<Episode>,
    episodeMetrics: List<EpisodeMetrics>,
    classifications: List<EpisodeClassifier.EpisodeClassification>,
    currentAxisState: FclAxisState,
    episodeEntities: List<EpisodeEntity> = emptyList(),
    onRescueUserConfirmed: (startTs: String, confirmed: String) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    var currentPage by remember(episodes.size) {
        mutableStateOf(episodes.lastIndex.coerceAtLeast(0))
    }

    val episode = episodes.getOrNull(currentPage)
    val metrics = episodeMetrics.getOrNull(currentPage)
    val classification = classifications.getOrNull(currentPage)
    val autonomy = remember(episode) { episode?.let { MealAutonomyAnalyzer.analyze(it) } }
    val entity = remember(currentPage, episodeEntities) {
        episode?.let { ep -> episodeEntities.find { it.startTs == ep.start.toString() } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = onBack) { Text("← Terug") }
            Text(s.episodeViewer, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }

        if (episodes.isEmpty()) {
            Text(s.geenEpisodesGevonden)
            return@Column
        }



        EpisodePager(
            allRows = allRows,
            episodes = episodes,
            episodeEntities = episodeEntities,
            onPageChanged = { page -> currentPage = page },
            onRescueUserConfirmed = onRescueUserConfirmed
        )

        if (episode != null && metrics != null && classification != null && autonomy != null) {
            val expertMode = androidx.compose.ui.platform.LocalContext.current
                .getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("expert_mode_active", false)

            val pages = buildList {
                add(InfoTabPage(s.overzicht) {
                    EpisodeOverviewCard(
                        episode = episode,
                        metrics = metrics,
                        classification = classification,
                        currentAxisState = currentAxisState
                    )
                })
                if (expertMode) {
                    add(InfoTabPage(s.piekAnalyse) {
                        PeakAnalyseCard(episode)
                    })
                    add(InfoTabPage(s.marges) {
                        MargesDashboardCard(episode)
                    })
                }
            }
            InfoTabPager(
                modifier = Modifier,
                pages = pages
            )
        }
    }
}

@Composable
private fun EpisodeOverviewCard(
    episode: Episode,
    metrics: EpisodeMetrics,
    classification: EpisodeClassifier.EpisodeClassification,
    currentAxisState: FclAxisState
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    val statusColor = when {
        classification.hyper -> MaterialTheme.colorScheme.error
        classification.hypoEarly || classification.hypoLate -> MaterialTheme.colorScheme.error
        classification.meetsGoal -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }

    val matchesCurrentSettings = CurrentAxisStateResolver.matchesEpisodeSettings(
        episodeSterkte = episode.sterktePct,
        episodeTiming = episode.timingPct,
        episodeVolhoudendheid = episode.volhoudendheidPct,
        current = currentAxisState
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(s.samenvatting, style = MaterialTheme.typography.titleMedium)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricBlock(s.insulineLabel, "%.2f U".format(metrics.totalInsulinDelivered))
                MetricBlock(s.iobAtPiek, "%.2f".format(metrics.iobRatioAtPeak))
                MetricBlock(s.peakPlusMin, metrics.timeToPeakMinutes?.toString() ?: "-")
            }

// Gebruik de predictedPeak op het moment van de werkelijke BG-piek,
            // niet de laatste voorspelling — dat geeft de meest betekenisvolle vergelijking.
            val peakBgTs = episode.rows
                .filter { it.timestamp <= episode.end }
                .maxByOrNull { it.bg }
                ?.timestamp
            val predPeakAtPeak = peakBgTs?.let { peakTs ->
                episode.rows
                    .filter { it.timestamp <= peakTs }
                    .mapNotNull { r -> r.predictedPeak?.takeIf { it > 0.0 } }
                    .lastOrNull()
            }
            if (predPeakAtPeak != null) {
                val diff = classification.peakBg - predPeakAtPeak
                val diffStr = if (diff >= 0) "+%.1f".format(diff) else "%.1f".format(diff)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricBlock(s.werkelijkePiek, "%.1f".format(classification.peakBg))
                    MetricBlock(s.predPiek, "%.1f".format(predPeakAtPeak))
                    MetricBlock(s.deltaVoorspelling, diffStr)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricBlock(s.insulineLabel, "%.2f U".format(metrics.totalInsulinDelivered))
                MetricBlock(s.gewicht, "%.2f".format(metrics.advisorWeight))
                MetricBlock(s.peakPlusMin, metrics.timeToPeakMinutes?.toString() ?: "-")
            }

            Text(
                text = when {
                    metrics.includedInAdvice -> s.meegeteldInAanbeveling
                    metrics.advisorWeight <= 0.0 -> "Niet beschikbaar – te weinig insuline"
                    metrics.adviceStatus == AdviceLifecycleStore.STATE_CONSUMED -> s.verbruiktNaWijziging
                    !matchesCurrentSettings -> "Niet beschikbaar – episode hoort bij andere instellingen"
                    metrics.adviceStatus == AdviceLifecycleStore.STATE_IN_LAST_RECOMMENDATION -> s.behoortBijAanbeveling
                    else -> s.beschikbaarVoorAdvies
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = when {
                    classification.hyper -> "Glucose steeg te hoog."
                    classification.hypoEarly -> "Vroege hypo – vroege insuline mogelijk te sterk."
                    classification.hypoLate -> "Late hypo – totale insuline mogelijk te hoog."
                    classification.meetsGoal -> s.glucoseBleefStabiel
                    else -> "Lichte afwijking zonder duidelijke fout."
                },
                color = statusColor
            )
        }
    }
}

@Composable
private fun AutonomyOverviewCard(autonomy: MealAutonomyMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Autonomie-inschatting", style = MaterialTheme.typography.titleMedium)
            Text(autonomyClassLabel(autonomy.autonomyClass), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(autonomy.autonomyReason, style = MaterialTheme.typography.bodyMedium)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricBlock("Meal intent assist", yesNo(autonomy.mealIntentAssistSeen))
                MetricBlock("Frontload", yesNo(autonomy.frontloadSeen))
                MetricBlock("Guard blok", yesNo(autonomy.guardBlockingSeen))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricBlock("Hypo-protectie", yesNo(autonomy.hypoProtectionSeen))
                MetricBlock("Max conf.", "${(autonomy.maxEarlyConfidence * 100).roundToInt()}%")
                MetricBlock("Max commit", "%.2f U".format(autonomy.maxCommitDoseFirst60m))
            }
        }
    }
}

@Composable
private fun StartTimingCard(episode: Episode, autonomy: MealAutonomyMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Start en detectie", style = MaterialTheme.typography.titleMedium)
            TimingRow("Episode start", TimeFormat.formatLocalAmsterdam(episode.start))
            TimingRow("Eerste detectie", TimeFormat.formatLocalAmsterdam(autonomy.firstDetectionTs))
            TimingRow("Eerste betekenisvolle dosis", TimeFormat.formatLocalAmsterdam(autonomy.firstMeaningfulDoseTs))
            Spacer(modifier = Modifier.height(4.dp))
            TimingRow("Start → detectie", formatMinutes(autonomy.startToDetectionMinutes))
            TimingRow("Detectie → dosis", formatMinutes(autonomy.detectionToDoseMinutes))
            TimingRow("Start → dosis", formatMinutes(autonomy.startToDoseMinutes))
        }
    }
}

// FIX 7: Leesbare Nederlandse labels in de doseringslogica-kaart
@Composable
private fun DosingLogicCard(episode: Episode) {
    val rows = episode.rows
        .filter { it.timestamp >= episode.start && it.timestamp <= episode.end }
        .sortedBy { it.timestamp }
    val first60mRows = rows.filter {
        java.time.Duration.between(episode.start, it.timestamp).toMinutes() in 0..60
    }

    val lastDecisionReason = first60mRows
        .map { it.decisionReason.trim() }
        .lastOrNull { it.isNotEmpty() } ?: "-"

    val lastMealState = first60mRows
        .map { it.mealState.trim() }
        .lastOrNull { it.isNotEmpty() } ?: "-"

    val commitBlockedCount = first60mRows.count { !it.effectiveCommitAllowed }
    val topGuardSeen = first60mRows.any { it.topGuardActive }
    val hypoSeen = first60mRows.any { it.hypoActive }
    val assistSeen = false // prebolus niet meer aanwezig in FCLvNext v6
    val maxTargetU = first60mRows.maxOfOrNull { it.earlyTargetU } ?: 0.0
    val maxCommit = first60mRows.maxOfOrNull { kotlin.math.max(it.commitDoseFinal, it.finalDose) } ?: 0.0

    // Hypo guard: hoeveel keer geblokkeerd vs doorgelaten
    val hypoBlockCount = first60mRows.count { it.hypoActive }
    val hypoBlockedBeforeFirstDose = run {
        val firstDoseTs = first60mRows.firstOrNull {
            kotlin.math.max(kotlin.math.max(it.finalDose, it.commitDoseFinal), it.deliveredTotal) >= 0.05
        }?.timestamp
        if (firstDoseTs == null) hypoBlockCount
        else first60mRows.count { it.hypoActive && it.timestamp < firstDoseTs }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Doseerlogica eerste uur", style = MaterialTheme.typography.titleMedium)

            TimingRow("Maaltijdstatus (laatste)", lastMealState)
            TimingRow("Blokkeerreden (laatste)", lastDecisionReason)
            TimingRow("Watching/frontload actief", yesNo(first60mRows.any { it.watchingFrontloadTriggered }))
            TimingRow("Meal intent / prebolus assist", yesNo(assistSeen))
            TimingRow("Commit geblokkeerd (aantal)", commitBlockedCount.toString())
            TimingRow("Top guard actief", yesNo(topGuardSeen))
            TimingRow("Hypo-bescherming actief (totaal)", hypoBlockCount.toString())
            TimingRow("Hypo-bescherming vóór eerste dosis", hypoBlockedBeforeFirstDose.toString())
            TimingRow("Max vroege dosistarget", "%.2f U".format(maxTargetU))
            TimingRow("Max commit/finale dosis", "%.2f U".format(maxCommit))
            Spacer(modifier = Modifier.height(4.dp))
            val brakeCount = first60mRows.count { it.peakIobBrakeActive }
            val suppressCount = first60mRows.count { it.suppressForPeak }
            val taperMin = first60mRows.filter { it.peakApproachFactor < 0.99 }
                .minOfOrNull { it.peakApproachFactor }
            TimingRow("PeakIobBrake actief (cycli)", brakeCount.toString())
            TimingRow("Suppress actief (cycli)", suppressCount.toString())
            TimingRow("Min. approach-factor", taperMin?.let { "%.2f".format(it) } ?: "—")
            TimingRow("Piekstatus (einde episode)",
                      first60mRows.lastOrNull()?.peakState ?: "—")
        }
    }
}

@Composable
private fun EpisodeSettingsCard(
    episode: Episode,
    metrics: EpisodeMetrics,
    currentAxisState: FclAxisState
) {
    val matchesCurrentSettings = CurrentAxisStateResolver.matchesEpisodeSettings(
        episodeSterkte = episode.sterktePct,
        episodeTiming = episode.timingPct,
        episodeVolhoudendheid = episode.volhoudendheidPct,
        current = currentAxisState
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Instellingen en lifecycle", style = MaterialTheme.typography.titleMedium)
            TimingRow("💪 Sterkte (S)", "${episode.sterktePct}%")
            TimingRow("⏱️ Timing (T)", "${episode.timingPct}%")
            TimingRow("🔁 Volhoudendheid (V)", "${episode.volhoudendheidPct}%")
            TimingRow("Dose distributie", episode.doseDistribution)
            Spacer(modifier = Modifier.height(6.dp))
            TimingRow("Matcht huidige instellingen", yesNo(matchesCurrentSettings))
            TimingRow("Advice status", metrics.adviceStatus)
            TimingRow("Meegeteld in advies", yesNo(metrics.includedInAdvice))
            TimingRow("Advisor gewicht", "%.2f".format(metrics.advisorWeight))
        }
    }
}

@Composable
private fun TimingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}


@Composable
private fun PeakAnalyseCard(episode: Episode) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    val episodeRows = episode.rows
        .filter { (it.minutesSinceMealStart ?: -1) >= 0 }
        .sortedBy { it.minutesSinceMealStart }

    val actualPeak = episode.rows.maxOf { it.bg }
    val hasPredData = episodeRows.any { (it.predictedPeak ?: 0.0) > 0.0 }
    val mgdl = app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.isMgdl(androidx.compose.ui.platform.LocalContext.current)
    val bgUnit = app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.unitShort(mgdl)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(s.piekVoorspellingVsWerkelijkheid, style = MaterialTheme.typography.titleMedium)
            Text(
                "X-as: minuten na episodestart  \u00b7  Y-as: BG ${app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.unitLabel(app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.isMgdl(androidx.compose.ui.platform.LocalContext.current))}  \u00b7  Gele lijn = werkelijke piek",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            if (!hasPredData) {
                Text(
                    "Geen voorspellingsdata in deze episode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                PeakPredictionChart(episodeRows = episodeRows)
                PeakPredictionLegend()

                Spacer(modifier = Modifier.height(4.dp))

                val activeRows = episodeRows.filter { (it.predictedPeak ?: 0.0) > 0.0 }
                val vroegFout = activeRows
                    .filter { (it.minutesSinceMealStart ?: 0) in 0..20 }
                    .mapNotNull { it.predictedPeak }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.let { it - actualPeak }
                val piekFout = activeRows
                    .minByOrNull { kotlin.math.abs(it.bg - actualPeak) }
                    ?.predictedPeak
                    ?.let { it - actualPeak }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Vroege fout (0\u201320 min)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            vroegFout?.let {
                                if (it >= 0) "+${if (mgdl) "%.0f".format(it * 18.0) else "%.2f".format(it)} $bgUnit"
                                else "${if (mgdl) "%.0f".format(it * 18.0) else "%.2f".format(it)} $bgUnit"
                            } ?: "\u2014",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                vroegFout == null -> MaterialTheme.colorScheme.onSurface
                                kotlin.math.abs(vroegFout) <= 0.5 -> Color(0xFF00C853)
                                kotlin.math.abs(vroegFout) <= 1.2 -> Color(0xFFFFD600)
                                else -> Color(0xFFFF5252)
                            }
                        )
                    }
                    Column {
                        Text(
                            s.foutBijPiek,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            piekFout?.let {
                                if (it >= 0) "+${if (mgdl) "%.0f".format(it * 18.0) else "%.2f".format(it)} $bgUnit"
                                else "${if (mgdl) "%.0f".format(it * 18.0) else "%.2f".format(it)} $bgUnit"
                            } ?: "\u2014",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                piekFout == null -> MaterialTheme.colorScheme.onSurface
                                kotlin.math.abs(piekFout) <= 0.5 -> Color(0xFF00C853)
                                kotlin.math.abs(piekFout) <= 1.2 -> Color(0xFFFFD600)
                                else -> Color(0xFFFF5252)
                            }
                        )
                    }
                    Column {
                        Text(
                            s.werkelijkePiek,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.formatBg(actualPeak, mgdl),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private fun formatMinutes(value: Long?): String = value?.let { "$it min" } ?: "-"
private fun yesNo(value: Boolean): String = if (value) "Ja" else "Nee"
private fun autonomyClassLabel(value: MealAutonomyClass): String = when (value) {
    MealAutonomyClass.EARLY_AUTONOMOUS_RESPONSE -> "Vroege autonome respons"
    MealAutonomyClass.BORDERLINE_AUTONOMOUS_RESPONSE -> "Bijna autonoom"
    MealAutonomyClass.ASSIST_USED_OR_NEEDED -> "Assist gebruikt of nodig"
    MealAutonomyClass.SAFETY_LIMITED -> "Veiligheid remde"
    MealAutonomyClass.UNCLEAR -> "Nog onduidelijk"
}
// ── MealTypeCard ──────────────────────────────────────────────────────────────
// Toont slope-informatie per episode (maaltijdtype-onderscheid verwijderd)

@Composable
private fun MealTypeCard(entity: EpisodeEntity) {
    val mgdlM = app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.isMgdl(
        androidx.compose.ui.platform.LocalContext.current)

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "📈 Stijgingspatroon",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Divider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DetectieRij("Slope 0-15 min",
                    app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.formatSlope(
                        entity.mealTypeSlope0_15, mgdlM))
                DetectieRij("Slope 15-30 min",
                    app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.formatSlope(
                        entity.mealTypeSlope15_30, mgdlM))
            }
        }
    }
}


@Composable
private fun DetectieRij(label: String, waarde: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Text(waarde, style = MaterialTheme.typography.labelMedium,
             fontWeight = FontWeight.SemiBold)
    }
}