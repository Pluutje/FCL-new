package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ConfigOverrideWriter
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.Episode
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LogRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.FclActiveConfigBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.*
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowEntity
import kotlin.math.roundToInt
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AdvisorScreen(
    recommendation: FclAdvisorRecommendation,
    current: FclAxisState,
    episodeCount: Int = 0,
    episodes: List<Episode> = emptyList(),
    metrics: List<EpisodeMetrics> = emptyList(),
    activeParams: ConfigOverrideWriter.ActiveParams = ConfigOverrideWriter.ActiveParams(),
    onBack: () -> Unit,
    onApplyToAaps: ((Map<String, Int>) -> Boolean)? = null,
    nightWindows: List<NightWindowEntity> = emptyList(),
    onApplyNacht: ((Double) -> Boolean)? = null,
    allRows: List<LogRow> = emptyList(),
    onApplyDFToAaps: ((ConfigOverrideWriter.ParamOverrides, Map<String, Int>) -> Boolean)? = null,
    nfLevel: Double = 5.0,
    onApplyParams: ((ConfigOverrideWriter.ParamOverrides) -> Boolean)? = null,
    // Klikbare kruisverwijzing (24/07/2026, Ecko): laat de "Zie ook: AI Advisor
    // → Nacht"-tekst op het Nacht-tabblad hierheen springen met het Nacht-
    // tabblad al open, i.p.v. altijd op Dag te starten.
    startOnNacht: Boolean = false,
    onJumpToAiAdvisorNacht: (() -> Unit)? = null,
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)

    // Plain remember (geen key) i.p.v. rechtstreeks "if (startOnNacht) 1 else 0"
    // in de InfoTabPager-aanroep verderop (24/07/2026, Ecko): legt de gevraagde
    // starttab ÉÉNMALIG vast bij de eerste compositie. Zonder dit zou een reset
    // van startOnNacht door de aanroeper (Fclanalyzerscreen.kt, vlak na het
    // openen — nodig zodat een volgend, normaal bezoek weer op Dag start) de tab
    // meteen weer terugzetten naar Dag, ook als je net op Nacht was aanbeland.
    val initialTab = remember { if (startOnNacht) 1 else 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(onClick = onBack) { Text("← Terug") }

        Text(s.advisorAnalyse, style = MaterialTheme.typography.headlineMedium)

        val expertMode = androidx.compose.ui.platform.LocalContext.current
            .getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("expert_mode_active", false)

        // "Automaat leert"-schakelaar verplaatst naar Settings → "🤖 Analyser
        // Automaat" (tussen "Dosering & gedrag" en "Dag / nacht context"),
        // zie FCLSettingsScreen.kt. Wordt hier niet meer getoond — neemt
        // weinig ruimte in en wordt zelden aangeraakt.

        val advisorPages = buildList {
            // ── 1. Automaat: D/F zelflerend systeem (startblad) ──────────
            add(InfoTabPage(s.automaat) {
                DFControlTab(
                    episodes    = episodes,
                    metrics     = metrics,
                    nfLevel = nfLevel,
                    onApplyToAaps = onApplyDFToAaps
                )
            })

            // ── 2. Nacht N: nachtfactor instelling ───────────────────────
            add(InfoTabPage(s.nachtNLabel) {
                NachtControlTab(
                    currentNfLevel = DFLearner.getNfLevel(androidx.compose.ui.platform.LocalContext.current),
                    nightWindows = nightWindows,
                    onApplyNacht = onApplyNacht,
                    onJumpToAiAdvisorNacht = onJumpToAiAdvisorNacht
                )
            })

            // ── Expert: Parameters en Analyse tabs ───────────────────────
            if (expertMode) {
                add(InfoTabPage(s.parameters) {
                    HandmatigParametersTab(
                        activeParams  = activeParams,
                        onApplyParams = onApplyParams
                    )
                })
                add(InfoTabPage(s.analyse) {
                    AdvisorOverviewCard(recommendation)
                    AdvisorSummaryCard(recommendation)
                    Spacer(Modifier.height(4.dp))
                    AdvisorActionCard(
                        recommendation = recommendation,
                        current = current,
                        episodeCount = episodeCount,
                        onApplyToAaps = onApplyToAaps
                    )
                    AdvisorCurrentSettingsCard(current)
                    Spacer(Modifier.height(8.dp))
                    AdvisorSelectionCard(recommendation)
                    AdvisorAxisEvidenceCard(recommendation)
                    AdvisorPatternScoresCard(recommendation)
                })
            }
        }

        InfoTabPager(
            modifier = Modifier,
            pages = advisorPages,
            initialTab = initialTab
        )
    }
}

@Composable
private fun AdvisorOverviewCard(recommendation: FclAdvisorRecommendation) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    val stats = recommendation.stats
    val selection = recommendation.selectionInfo

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(s.advisorOverzicht, style = MaterialTheme.typography.titleMedium)

            Text(
                formatPatternLabel(recommendation.dominantPattern),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                FclAdviceText.explainPattern(recommendation.dominantPattern),
                style = MaterialTheme.typography.bodyMedium
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AdvisorMetricBlock(s.confidence, "${(recommendation.confidence * 100).toInt()}%")
                AdvisorMetricBlock(s.gebruikt, "${selection.usedEpisodeCount}")
                AdvisorMetricBlock(s.uitgesloten, "${selection.excludedTotal}")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AdvisorMetricBlock(s.avgPeak, "%.1f".format(stats.avgPeakBg))
                AdvisorMetricBlock(s.avgInsuline, "%.2f U".format(stats.avgInsulinDelivered))
                AdvisorMetricBlock(s.avgDuur, "${stats.avgDurationMinutes} min")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AdvisorMetricBlock(s.hyper, "${stats.hyperPercent}%")
                AdvisorMetricBlock(s.hypo, "${stats.hypoPercent}%")
                AdvisorMetricBlock("Binnen doel", "${stats.meetsGoalPercent}%")
            }
        }
    }
}

@Composable
private fun AdvisorSelectionCard(recommendation: FclAdvisorRecommendation) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    val selection = recommendation.selectionInfo

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Episode-selectie", style = MaterialTheme.typography.titleMedium)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AdvisorMetricBlock("Totaal", "${selection.totalEpisodesSeen}")
                AdvisorMetricBlock(s.gebruikt, "${selection.usedEpisodeCount}")
                AdvisorMetricBlock(s.uitgesloten, "${selection.excludedTotal}")
            }

            if (selection.excludedTotal > 0) {
                if (selection.excludedOtherSettings > 0)
                    Text("Andere instellingen: ${selection.excludedOtherSettings}")
                if (selection.excludedLowInsulin > 0)
                    Text("Te weinig insuline: ${selection.excludedLowInsulin}")
                if (selection.excludedConsumed > 0)
                    Text("Verbruikt na profielwijziging: ${selection.excludedConsumed}")
                if (selection.excludedIncomplete > 0)
                    Text("Nog live / incompleet: ${selection.excludedIncomplete}")
            } else {
                Text(
                    "Alle zichtbare episodes zijn meegenomen in de analyse.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun AdvisorPatternScoresCard(recommendation: FclAdvisorRecommendation) {
    if (recommendation.patternScores.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Patroonscores", style = MaterialTheme.typography.titleMedium)

            recommendation.patternScores.forEach { score ->
                val isDominant = score.pattern == recommendation.dominantPattern
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatPatternLabel(score.pattern),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isDominant) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = "${(score.score * 100).roundToInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isDominant) FontWeight.Bold else FontWeight.Normal,
                            color = if (isDominant) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(score.score.toFloat().coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (isDominant) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                        )
                    }

                    Text(
                        text = score.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                if (score != recommendation.patternScores.last()) {
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                }
            }
        }
    }
}

@Composable
private fun AdvisorAxisEvidenceCard(recommendation: FclAdvisorRecommendation) {
    if (recommendation.axisEvidence.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("As-signalen", style = MaterialTheme.typography.titleMedium)

            recommendation.axisEvidence.forEach { evidence ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatAxisLabel(evidence.axis),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = formatAxisEvidenceStatus(evidence),
                            style = MaterialTheme.typography.bodySmall,
                            color = evidenceStatusColor(evidence)
                        )
                    }

                    if (evidence.strength >= 0.18) {
                        val barColor = when {
                            evidence.direction > 0 -> MaterialTheme.colorScheme.primary
                            evidence.direction < 0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(evidence.strength.toFloat().coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(barColor)
                            )
                        }
                    }

                    Text(
                        text = evidence.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                if (evidence != recommendation.axisEvidence.last()) {
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                }
            }
        }
    }
}

@Composable
private fun AdvisorActionCard(
    recommendation: FclAdvisorRecommendation,
    current: FclAxisState,
    episodeCount: Int = 0,
    onApplyToAaps: ((Map<String, Int>) -> Boolean)? = null
) {
    val rows = FclAdviceFormatter.formatTransitions(recommendation.transitions)
    val monitorUp = recommendation.axisEvidence.filter { it.direction > 0 && it.strength in 0.18..<0.45 }
    val monitorDown = recommendation.axisEvidence.filter { it.direction < 0 && it.strength in 0.18..<0.45 }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var applyResult by remember { mutableStateOf<Boolean?>(null) }
    val selected = remember(rows) { rows.map { mutableStateOf(true) } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Aanbevolen aanpassingen", style = MaterialTheme.typography.titleMedium)

            if (rows.isEmpty()) {
                Text(
                    "Geen directe profielwijziging aanbevolen",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(recommendation.summary, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Voorgestelde profielwijzigingen",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                rows.forEachIndexed { i, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected.getOrNull(i)?.value ?: true,
                            onCheckedChange = { selected.getOrNull(i)?.value = it },
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = formatAxisLabelFromName(row.axis),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${row.fromLabel} → ${row.toLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (selected.getOrNull(i)?.value == true) 1f else 0.4f
                                )
                            )
                        }
                    }
                    if (i < rows.lastIndex) {
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    }
                }
            }

            if (monitorUp.isNotEmpty() || monitorDown.isNotEmpty()) {
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                Text(
                    "Monitor-signalen",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                val allMonitor = (monitorUp + monitorDown)
                allMonitor.forEach { evidence ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val direction = if (monitorUp.contains(evidence)) "lijkt te laag" else "lijkt te hoog"
                        Text(
                            text = "${formatAxisLabel(evidence.axis)} $direction — volg de trend",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = evidence.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    if (evidence != allMonitor.last()) {
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }

            if (onApplyToAaps != null && rows.isNotEmpty()) {
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                when (applyResult) {
                    true -> Text(
                        "✅ Instellingen worden bij de volgende AAPS-cyclus actief.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    false -> Text(
                        "❌ Schrijven mislukt. Controleer of de AAPS-map gekoppeld is.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    null -> {
                        val anySelected = selected.any { it.value }
                        val allSelected = selected.all { it.value }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = { v -> selected.forEach { it.value = v } },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (allSelected) "Alles geselecteerd" else "Selecteer alles",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Button(
                            onClick = { showConfirmDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = anySelected
                        ) {
                            val n = selected.count { it.value }
                            Text(if (n == rows.size) "Toepassen in AAPS" else "Toepassen ($n van ${rows.size})")
                        }
                    }
                }
            } else if (onApplyToAaps == null && rows.isNotEmpty()) {
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                Text(
                    "Koppel eerst de AAPS-map (dashboard) om wijzigingen door te sturen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }

    if (showConfirmDialog && onApplyToAaps != null) {
        val filteredTransitions = recommendation.transitions.filterIndexed { i, _ ->
            selected.getOrNull(i)?.value ?: true
        }
        val filteredAdjustment = filteredTransitions.fold(
            app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvAdjustment()
        ) { acc, t ->
            when (t.axis) {
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvAxis.STERKTE        -> acc.copy(dSterkte = t.step)
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvAxis.TIMING         -> acc.copy(dTiming = t.step)
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvAxis.VOLHOUDENDHEID -> acc.copy(dVolhoudendheid = t.step)
            }
        }
        val settingsMap = ConfigOverrideWriter.buildStvMap(current, filteredAdjustment)
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Instellingen toepassen in AAPS?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "De volgende profielwijzigingen worden naar AAPS gestuurd. " +
                            "AAPS past ze toe bij de volgende cyclus (~5 min).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    rows.filterIndexed { i, _ -> selected.getOrNull(i)?.value ?: true }
                        .forEach { row ->
                            Text(
                                "• ${formatAxisLabelFromName(row.axis)}: ${row.fromLabel} → ${row.toLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Gebaseerd op $episodeCount episodes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    applyResult = onApplyToAaps(settingsMap)
                    showConfirmDialog = false
                }) { Text("Toepassen") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Annuleren") }
            }
        )
    }
}

@Composable
private fun AdvisorSummaryCard(recommendation: FclAdvisorRecommendation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Samenvatting", style = MaterialTheme.typography.titleMedium)
            Text(recommendation.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AdvisorCurrentSettingsCard(current: StvState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val aggLevel = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner.getAggressiveness(context)
    val aggLabel = when {
        aggLevel <= 2 -> "Zeer voorzichtig"
        aggLevel <= 4 -> "Voorzichtig"
        aggLevel == 5 -> "Standaard"
        aggLevel <= 7 -> "Agressief"
        else          -> "Maximaal agressief"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Huidige instellingen", style = MaterialTheme.typography.titleMedium)
                Text("Agressiviteit $aggLevel — $aggLabel",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("💪 Sterkte (S): ${current.sterkte}%")
            Text("⏱️ Timing (T): ${current.timing}%")
            Text("🔁 Volhoudendheid (V): ${current.volhoudendheid}%")
            if (aggLevel != 5) {
                Text(
                    "Agressiviteitsschuif staat op $aggLevel — waarden zijn al gecorrigeerd.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdvisorMetricBlock(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatPatternLabel(pattern: FclPattern): String =
    when (pattern) {
        FclPattern.FLAT_GOOD -> "Stabiel patroon"
        FclPattern.EARLY_SPIKE -> "Vroege piek"
        FclPattern.LATE_PEAK -> "Late piek"
        FclPattern.EARLY_HYPO -> "Vroege hypo"
        FclPattern.LATE_HYPO -> "Late hypo"
        FclPattern.LONG_HIGH_TAIL -> "Lang hoge staart"
        FclPattern.OSCILLATING_RESPONSE -> "Oscillerende respons"
        FclPattern.MIXED_UNCLEAR -> "Gemengd / onduidelijk"
    }

private fun formatAxisLabel(axis: StvAxis): String =
    when (axis) {
        StvAxis.STERKTE        -> "💪 Sterkte (S)"
        StvAxis.TIMING         -> "⏱️ Timing (T)"
        StvAxis.VOLHOUDENDHEID -> "🔁 Volhoudendheid (V)"
    }

private fun formatAxisLabelFromName(axisName: String): String =
    when (axisName) {
        "STERKTE"        -> "💪 Sterkte (S)"
        "TIMING"         -> "⏱️ Timing (T)"
        "VOLHOUDENDHEID" -> "🔁 Volhoudendheid (V)"
        "HEIGHT"         -> "💪 Sterkte (S)"
        "PERSISTENCE"    -> "🔁 Volhoudendheid (V)"
        else             -> axisName
    }

private fun formatAxisEvidenceStatus(evidence: FclAxisEvidence): String =
    when {
        evidence.direction == 0 || evidence.strength < 0.18 -> "Geen sterk signaal"
        evidence.strength < 0.45 && evidence.direction > 0 -> "Lichte aanwijzing omhoog ↑"
        evidence.strength < 0.45 && evidence.direction < 0 -> "Lichte aanwijzing omlaag ↓"
        evidence.direction > 0 -> "Duidelijk omhoog ↑"
        else -> "Duidelijk omlaag ↓"
    }

@Composable
private fun evidenceStatusColor(evidence: FclAxisEvidence) = when {
    evidence.direction == 0 || evidence.strength < 0.18 ->
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    evidence.direction > 0 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

// ════════════════════════════════════════════════════════════════════════════
// ════════════════════════════════════════════════════════════════════════════
// NachtControlTab — structureel identiek aan DFControlTab (dag-aggressiviteit)
// NF 1-9 schaal: 1=zeer voorzichtig, 5=balanced, 9=proactief
// Vervangt: NachtTab (losse N%-schuif) + NachtresponsStyle (5-preset-dropdown)
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun NachtControlTab(
    currentNfLevel: Double,
    nightWindows: List<NightWindowEntity>,
    onApplyNacht: ((Double) -> Boolean)?,
    onJumpToAiAdvisorNacht: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Geleerde NF (door NachtLearner bijgesteld) en de losse handmatige
    // Nacht-Agressiviteit-stap (door de gebruiker, NOOIT door de learner
    // aangeraakt) — zelfde opzet als Dag: D/F worden geleerd, de
    // Agressiviteit-schuif stapelt daar onafhankelijk bovenop. Hier:
    // currentNfLevel = geleerde NF, nachtAgg = handmatige stap.
    var nachtAgg by remember { mutableStateOf(DFLearner.getNachtAggressiviteit(context)) }
    var lastAppliedAgg by remember { mutableStateOf(DFLearner.getLastAppliedNachtAggressiviteit(context)) }
    var lastAppliedNfLevel by remember { mutableStateOf(DFLearner.getLastAppliedNfLevel(context)) }
    var applyResult by remember { mutableStateOf<String?>(null) }
    var applyTs by remember { mutableStateOf(0L) }

    val effectieveNf = (currentNfLevel + (nachtAgg - DFLearner.NACHT_AGGRESSIVITEIT_DEFAULT))
        .coerceIn(1.0, 9.0)
    val labelVoorEffectief: String = DFMapping.nfLabel(effectieveNf)
    val gainPct = DFMapping.nfGainPct(effectieveNf)

    val accentKleur = when {
        effectieveNf <= 2 -> androidx.compose.ui.graphics.Color(0xFF4FC3F7)
        effectieveNf <= 4 -> androidx.compose.ui.graphics.Color(0xFF81C784)
        effectieveNf in 4.1..6.0 -> MaterialTheme.colorScheme.primary
        effectieveNf <= 7 -> androidx.compose.ui.graphics.Color(0xFFFFB74D)
        else -> androidx.compose.ui.graphics.Color(0xFFE57373)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Nacht Agressiviteit (handmatige laag, los van geleerde NF) ───
        // Analoog aan AggressiviteitsKaart bij Dag: deze schuif staat los
        // van wat de NachtLearner leert. Niveau 5 = geen extra effect; de
        // schuif telt als hele stappen op/af bij de geleerde NF.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = accentKleur.copy(alpha = 0.10f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🌙 Nacht Agressiviteit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "$nachtAgg  —  ${DFMapping.nfLabel(nachtAgg.toDouble())}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accentKleur
                    )
                }
                Text(
                    "Eigen stap bovenop de geleerde NF (${currentNfLevel.toInt()}) — " +
                        "5 = geen effect. Effectief: NF ${effectieveNf.toInt()} " +
                        "(~$gainPct% gain, $labelVoorEffectief)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Slider(
                    value = nachtAgg.toFloat(),
                    onValueChange = {
                        nachtAgg = it.toInt().coerceIn(
                            DFLearner.NACHT_AGGRESSIVITEIT_MIN, DFLearner.NACHT_AGGRESSIVITEIT_MAX
                        )
                        DFLearner.setNachtAggressiviteit(context, nachtAgg)
                    },
                    valueRange = 1f..9f,
                    steps = 7,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = accentKleur,
                        activeTrackColor = accentKleur
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Voorzichtiger",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Proactiever",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Toon ook de knop als de geleerde NF zelf is veranderd maar
                // (nog) niet is toegepast — bv. omdat "Automaat leert" uit
                // staat, of de NachtLearner net heeft bijgesteld voordat de
                // gebruiker hier kijkt.
                val nfNogNietToegepast = kotlin.math.abs(currentNfLevel - lastAppliedNfLevel) > 0.01
                if (onApplyNacht != null && (nachtAgg != lastAppliedAgg || nfNogNietToegepast)) {
                    Button(
                        onClick = {
                            val ok = onApplyNacht(effectieveNf)
                            applyResult = if (ok) "✓ NF verzonden naar AAPS" else "✗ Verzenden mislukt"
                            applyTs = System.currentTimeMillis()
                            if (ok) {
                                DFLearner.setLastAppliedNachtAggressiviteit(context, nachtAgg)
                                DFLearner.setLastAppliedNfLevel(context, currentNfLevel)
                                lastAppliedAgg = nachtAgg
                                lastAppliedNfLevel = currentNfLevel
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accentKleur)
                    ) { Text("Toepassen in AAPS") }

                    applyResult?.let { msg ->
                        if (System.currentTimeMillis() - applyTs < 20_000L) {
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // ── NF-kaart: GELEERDE NF (door NachtLearner) — hoofdwaarde +
        // 14-dagen verloop + laatste aanpassing. Onaangeraakt door de
        // handmatige Nacht-Agressiviteit hierboven, zodat dit zuiver toont
        // hoe de automaat zelf leert (net als de Sterkte/Timing-kaarten bij
        // Dag, die ook de geleerde D/F tonen, niet de Agressiviteit-schuif).
        NfKaart(s = FclStrings.get(context), currentNf = currentNfLevel)

        // ── Learner — Nacht: openstaand MANUAL-voorstel (26/07/2026, Ecko) ──
        // Zelfde Accepteren/Afwijzen-patroon als de dag-Learner en de
        // AI-adviseur — "zodat alles consistent blijft". Alleen zichtbaar als
        // er daadwerkelijk een niet-beoordeeld NF-voorstel klaarstaat (dus
        // alleen relevant als Learner — Nacht op Handmatig staat).
        var nachtLearnerPendingRefresh by remember { mutableStateOf(0) }
        val nachtLearnerPending = remember(nachtLearnerPendingRefresh) {
            app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.NachtLearnerPendingProposal.load(context)
        }
        var nachtLearnerActionResult by remember { mutableStateOf<String?>(null) }
        nachtLearnerPending?.let { p ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Learner-voorstel: NF ${"%.1f".format(p.huidigeNf)} → ${"%.1f".format(p.nieuweNf)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val ok = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.NachtLearnerApplier.approve(context)
                                nachtLearnerActionResult = if (ok) "Toegepast." else "Mislukt — probeer het later opnieuw."
                                nachtLearnerPendingRefresh++
                            }
                        ) { Text("Accepteren") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.NachtLearnerApplier.reject(context)
                                nachtLearnerActionResult = "Afgewezen."
                                nachtLearnerPendingRefresh++
                            }
                        ) { Text("Afwijzen") }
                    }
                    nachtLearnerActionResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── Nacht-AI-adviseur verhuisd (24/07/2026, Ecko) ────────────────
        // Staat sindsdien op het AI-adviseur-tabblad → Nacht, naast de dag-
        // AI-adviezen — niet meer hier, om Automaat (regel-gebaseerd/geleerd)
        // en AI-advies duidelijk gescheiden te houden.
        Divider()
        Text(
            "Zie ook: AI Advisor → Nacht voor het AI-advies over de nachtbasaal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = Modifier.clickable(enabled = onJumpToAiAdvisorNacht != null) {
                onJumpToAiAdvisorNacht?.invoke()
            }
        )
    }
}


// gaussWeightForOffset() en computeSpreadAdvice() (regel-gebaseerde spread-
// advies-helpers) verwijderd (24/07/2026, Ecko) samen met de Basaal-adviseur-
// kaart hieronder — zie NachtControlTab hierboven voor de toelichting.

// ── NightAiAdvisorCard ────────────────────────────────────────────────────
// Toont het rapport van de Nacht-AI-adviseur (zie FclNightAiAdvisorScheduler).
// Sinds 24/07/2026 het enige basaal-adviespad — de regel-gebaseerde
// Basaal-adviseur is verwijderd (zie NachtControlTab hierboven).

@Composable
// Niet meer `private` (24/07/2026, Ecko) — wordt sinds de verhuizing van de
// aanroep ook vanuit FclAiAdvisorScreen.kt (ander package) aangeroepen.
fun NightAiAdvisorCard(context: android.content.Context) {
    var result by remember {
        mutableStateOf(
            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightAiAdvisorScheduler
                .latestResult(context)
        )
    }
    var isRequesting by remember { mutableStateOf(false) }

    fun localFmt(utc: String): String = try {
        val instant = java.time.Instant.parse(utc)
        java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(java.time.ZoneId.of("Europe/Amsterdam"))
            .format(instant)
    } catch (_: Exception) { utc }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "\uD83C\uDF19 Nacht-AI-adviseur (basaal)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = {
                        isRequesting = true
                        app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightAiAdvisorScheduler
                            .forceRunNow(context) { r ->
                                result = r
                                isRequesting = false
                            }
                    },
                    enabled = !isRequesting &&
                        !app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightAiAdvisorScheduler.isRunning()
                ) {
                    Text(if (isRequesting) "\u23F3 Bezig\u2026" else "Nu vernieuwen")
                }
            }
            Text(
                "Puur advies over basaal-uren — wordt nooit automatisch toegepast. " +
                    "Draait zelfstandig 1x per ochtend zodra de nacht eindigt, los van de dag-adviseur " +
                    "hiernaast. Voorgestelde percentages zijn bewust bescheiden, " +
                    "eerste-stap-aanpassingen — bij een aanhoudend patroon volgt vanzelf een " +
                    "volgende, vergelijkbare stap op een volgende ochtend.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val r = result
            when {
                r == null -> Text(
                    "Nog geen rapport \u2014 wacht op het einde van de eerstvolgende nacht, " +
                        "of druk op \"Nu vernieuwen\".",
                    style = MaterialTheme.typography.bodyMedium
                )
                r.parseError != null -> Text(
                    "\u26A0\uFE0F ${r.parseError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                else -> {
                    Text(
                        "Rapport van ${localFmt(r.generatedAtUtc)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    r.summaryNl?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (r.suggestions.isEmpty()) {
                        Text(
                            "Geen structurele basaal-aanpassing voorgesteld.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        r.suggestions.forEach { s ->
                            Column(modifier = Modifier.padding(top = 4.dp)) {
                                val pijl = if (s.direction == "LOWER") "\uD83D\uDD3D" else "\uD83D\uDD3C"
                                val teken = if (s.suggestedShiftPct > 0) "+" else ""
                                // Concrete doelwaarde tonen naast het % (23/07/2026, Ecko) —
                                // "+10% huidig 1 -> 1,1 U/h" i.p.v. alleen het percentage,
                                // zodat je niet zelf hoeft om te rekenen wat het advies
                                // concreet betekent voor de basaalstand.
                                val doelUph = (s.currentBasalUph * (1.0 + s.suggestedShiftPct / 100.0))
                                    .coerceAtLeast(0.0)
                                Text(
                                    "$pijl ${s.hourLabel}  ($teken${"%.0f".format(s.suggestedShiftPct)}%, " +
                                        "nu ${"%.2f".format(s.currentBasalUph)} \u2192 ${"%.2f".format(doelUph)} U/h) \u2014 " +
                                        "vertrouwen ${(s.confidence * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(s.reasonNl, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── ProfileAutoAdjustCard (24/07/2026, Ecko; herzien 26/07/2026) ─────────
// Automatisch-profiel-bijstellen: vergelijkingstabel van de laatste run,
// Accepteren/Afwijzen bij een openstaand MANUAL-voorstel, en een handmatige,
// expliciete "basisprofiel opnieuw vastleggen"-actie. Zie kdoc bij
// FclNightBasalAutoAdjuster.kt voor de volledige achtergrond/veiligheidslagen.
//
// 26/07/2026 (Ecko) — dag/nacht-herstructurering: de Uit/Alleen-loggen/
// Automatisch-knoppenrij is verhuisd naar Instellingen → Analyser Automaat /
// AI Advisor → AI-adviseur Nacht (FCLSettingsScreen.kt). Dit kaartje toont
// alleen nog de UITKOMST (info + tabel) en, bij MANUAL met een openstaand
// voorstel, de Accepteren/Afwijzen-knoppen.
//
// Grafiekweergave bewust nog niet meegenomen — eerst de tabel + backend in
// de praktijk laten bewijzen, de grafiek is een voor de hand liggende
// vervolgstap zodra dat gebeurd is.
@Composable
fun ProfileAutoAdjustCard(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val Adjuster = app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightBasalAutoAdjuster
    val Store = app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightBasalAutoAdjustStore
    val AUTO = app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.AUTO
    val MANUAL = app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL
    val mode = Store.getMode(context)
    var capHits by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var baselineSetAt by remember { mutableStateOf(Store.getBaselineSetAt(context)) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    // 27/07/2026 (Ecko) — Accepteren-status voor het gewogen gemiddelde-
    // voorstel (zie FclNightBasalAutoAdjuster.applyPending/computeCurrentProposal).
    // Geen Afwijzen meer — zie kdoc bovenaan FclNightBasalAutoAdjuster.kt.
    var isActing by remember { mutableStateOf(false) }
    var actionResult by remember { mutableStateOf<String?>(null) }
    // 26/07/2026 (Ecko) — extra bevestigingsstap op verzoek: Accepteren opent
    // eerst een popup met de huidig/nieuw-vergelijking en een expliciete
    // "wordt naar de pomp geschreven"-melding, met daarin nog een keer
    // Accepteren. Pas het klikken in de popup voert de actie uit.
    var showAcceptConfirmDialog by remember { mutableStateOf(false) }
    // 27/07/2026 (Ecko) — het (gewogen) gemiddelde-voorstel over de nachten
    // sinds de laatste wijziging; zelfde berekening als applyPending()/de
    // AUTO-toepassing, zie computeCurrentProposal() se kdoc.
    var proposal by remember {
        mutableStateOf<app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightBasalAutoAdjuster.NightlyProposal?>(null)
    }

    LaunchedEffect(refreshTrigger) {
        capHits = Store.getCapHitCounters(context)
        // AUTO: venster is precies AUTO_COOLDOWN_NIGHTS (zo toont de kaart
        // exact de nachten die zullen meetellen bij de volgende toepassing).
        // MANUAL: venster groeit tot MANUAL_MAX_WINDOW_NIGHTS (zie kdoc).
        val windowCap = if (mode == AUTO) Adjuster.AUTO_COOLDOWN_NIGHTS else Adjuster.MANUAL_MAX_WINDOW_NIGHTS
        proposal = Adjuster.computeCurrentProposal(context, windowCap)
    }

    if (mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "🔄 Profiel bijstellen — Uit",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Uitgeschakeld — zet \"AI-adviseur — Nacht\" aan bij Instellingen → " +
                        "Analyser Automaat / AI Advisor om weer nacht-AI-advies te krijgen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 27/07/2026 (Ecko), op verzoek: de titel zelf zegt nu meteen in
            // welke modus dit kaartje draait — "Automatisch profiel
            // bijstellen" als vaste titel botste met de beschrijving eronder
            // bij Handmatig (dat past niets automatisch toe).
            Text(
                if (mode == AUTO) "🔄 Profiel bijstellen — Automatisch" else "🔄 Profiel bijstellen — Handmatig",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (mode == AUTO)
                    "Past het (gewogen) gemiddelde AI-nachtadvies automatisch toe op het echte " +
                        "pompprofiel, in kleine stapjes met een harde grens en een terugvalmogelijkheid."
                else
                    "Berekent elke nacht een (gewogen) gemiddeld voorstel op basis van het AI-" +
                        "nachtadvies hierboven. Na acceptatie wordt dat toegepast op het echte " +
                        "pompprofiel — er wordt nooit automatisch iets doorgevoerd.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── 27/07/2026 (Ecko): duidelijke subkop, om de eerdere klacht
            //    ("de tekst loopt een beetje doorelkaar") te verhelpen — dit
            //    blok gaat puur over het VOORSTEL, de reset-actie hieronder
            //    staat straks onder een eigen, apart gelabelde subkop. ──
            Divider()
            Text(
                "Profielvoorstel",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            val p = proposal
            if (p == null) {
                Text(
                    "Nog geen gegevens sinds de laatste wijziging — verschijnt hier zodra de " +
                        "nacht-AI-adviseur voor het eerst met een basaal-suggestie draait.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val statusText = when {
                    mode == MANUAL && p.nightsUsed < Adjuster.MANUAL_COOLDOWN_NIGHTS ->
                        "Voorlopige inschatting — gebaseerd op nog maar ${p.nightsUsed} nacht(en) " +
                            "sinds de laatste wijziging (t/m ${p.newestLocalDate}). Accepteren komt " +
                            "beschikbaar over ${Adjuster.MANUAL_COOLDOWN_NIGHTS - p.nightsUsed} nacht(en)."
                    mode == MANUAL ->
                        "Gewogen gemiddelde over de laatste ${p.nightsUsed} nacht(en) sinds de " +
                            "laatste wijziging (t/m ${p.newestLocalDate}) — klaar om te accepteren. " +
                            "Dit venster groeit door tot maximaal ${Adjuster.MANUAL_MAX_WINDOW_NIGHTS} nachten."
                    p.nightsUsed < Adjuster.AUTO_COOLDOWN_NIGHTS ->
                        "Verzamelt gegevens: nacht ${p.nightsUsed} van ${Adjuster.AUTO_COOLDOWN_NIGHTS}. " +
                            "Bij nacht ${Adjuster.AUTO_COOLDOWN_NIGHTS} wordt het gewogen gemiddelde " +
                            "automatisch op de pomp toegepast."
                    else ->
                        "Gewogen gemiddelde over de laatste ${Adjuster.AUTO_COOLDOWN_NIGHTS} nachten " +
                            "wordt bij de eerstvolgende cyclus automatisch toegepast."
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ProfileAutoAdjustTable(
                    oldHourly = p.oldHourly,
                    newHourly = p.newHourly,
                    shiftByHour = p.shiftByHour,
                    hoursAtCap = p.hoursAtCap,
                    capHits = capHits
                )

                // 27/07/2026 (Ecko), op verzoek: grafische vergelijking onder
                // de tabel — in één oogopslag zien wat het effect van
                // eventueel accepteren op het profiel zou zijn.
                Spacer(Modifier.height(4.dp))
                BasalProfileChart(oldHourly = p.oldHourly, newHourly = p.newHourly)

                if (mode == MANUAL && p.nightsUsed >= Adjuster.MANUAL_COOLDOWN_NIGHTS) {
                    Button(
                        enabled = !isActing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showAcceptConfirmDialog = true }
                    ) { Text("Accepteren") }
                    actionResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 27/07/2026 (Ecko): tweede, eigen subkop voor de reset-actie —
            //    dit is het ENIGE reset-mechanisme (geen Afwijzen meer); zie
            //    kdoc bovenaan FclNightBasalAutoAdjuster.kt. ──
            Divider()
            Text(
                "Basisprofiel resetten",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            // 26/07/2026 (Ecko) — uitleg vooraf toegevoegd op verzoek ("geen
            // idee wat die doet"). De bevestigingsdialoog legde het al uit,
            // maar pas NA klikken — dit zet het ervoor, zodat je het al weet
            // voordat je de knop indrukt.
            Text(
                "Elk uur mag maximaal ±25% afwijken van een vast referentiepunt " +
                    "(niet van gisteren, anders zou die grens langzaam betekenisloos " +
                    "worden). Deze knop legt het HUIDIGE profiel vast als dat nieuwe " +
                    "referentiepunt — en telt bovendien als het startpunt van een NIEUW " +
                    "voorstel-venster hierboven (nacht 0). Alleen nodig als een uur " +
                    "herhaaldelijk tegen de oude grens aanloopt, of als je bewust opnieuw " +
                    "wilt beginnen met tellen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Basisprofiel opnieuw vastleggen")
            }
            if (baselineSetAt > 0) {
                Text(
                    "Huidig basisprofiel vastgelegd op " +
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                            .withZone(java.time.ZoneId.of("Europe/Amsterdam"))
                            .format(java.time.Instant.ofEpochMilli(baselineSetAt)) + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { if (!isResetting) showResetDialog = false },
            title = { Text("Basisprofiel opnieuw vastleggen?") },
            text = {
                Text(
                    "Het huidige, actieve profiel wordt het nieuwe ankerpunt waar de ±25%-grens " +
                        "voortaan tegen wordt afgemeten, én het startpunt (nacht 0) van een nieuw " +
                        "voorstel-venster. Doe dit alleen bewust, bijvoorbeeld nadat je hebt gezien " +
                        "dat een uur herhaaldelijk tegen de oude grens aanliep, of na een paar " +
                        "nachten waarvan je de gegevens niet wilt laten meetellen."
                )
            },
            confirmButton = {
                Button(
                    enabled = !isResetting,
                    onClick = {
                        isResetting = true
                        scope.launch {
                            val db = app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase.getInstance(context)
                            val latest = db.basalProfileHistoryDao().getLatest()
                            if (latest != null) {
                                val hourly = (0..23).associateWith { latest.basalAtHour(it) }
                                Store.setBaseline(
                                    context, hourly, source = "manual-reset", nowMs = System.currentTimeMillis()
                                )
                                baselineSetAt = Store.getBaselineSetAt(context)
                            }
                            isResetting = false
                            showResetDialog = false
                            refreshTrigger++
                        }
                    }
                ) { Text(if (isResetting) "Bezig…" else "Bevestigen") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }, enabled = !isResetting) { Text("Annuleren") }
            }
        )
    }

    // 26/07/2026 (Ecko) — bevestigingspopup na Accepteren, op verzoek: "als
    // een extra bevestiging uit veiligheidsoogpunt echt nodig is dan zou ik
    // na accepteren eerder een popup verwachten met daarop nog een keer de
    // actuele en nieuwe waarden vermeld en nog een keer de melding dat de
    // wijzigingen naar de pomp zullen worden geschreven." Toont dezelfde
    // ProfileAutoAdjustTable (Huidig/Voorstel/Na caps) als het kaartje zelf,
    // zodat de vergelijking niet twee keer apart onderhouden hoeft te worden.
    // 27/07/2026 (Ecko) — geen Afwijzen-knop meer hier: gewoon annuleren/
    // wegklikken laat het voorstel ongewijzigd staan (het venster blijft
    // gewoon doorgroeien tot de volgende keer dat je hier terugkomt).
    if (showAcceptConfirmDialog) {
        val pendingProposal = proposal
        AlertDialog(
            onDismissRequest = { if (!isActing) showAcceptConfirmDialog = false },
            title = { Text("Voorstel toepassen op de pomp?") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Dit schrijft de nieuwe waarden hieronder direct naar het " +
                            "actieve pompprofiel. Controleer de vergelijking en bevestig " +
                            "pas als je het ermee eens bent."
                    )
                    if (pendingProposal != null) {
                        ProfileAutoAdjustTable(
                            oldHourly = pendingProposal.oldHourly,
                            newHourly = pendingProposal.newHourly,
                            shiftByHour = pendingProposal.shiftByHour,
                            hoursAtCap = pendingProposal.hoursAtCap,
                            capHits = capHits
                        )
                        Spacer(Modifier.height(4.dp))
                        BasalProfileChart(
                            oldHourly = pendingProposal.oldHourly,
                            newHourly = pendingProposal.newHourly,
                            heightDp = 100
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isActing,
                    onClick = {
                        isActing = true
                        actionResult = null
                        scope.launch {
                            val pf = app.aaps.plugins.aps.openAPSFCL.vnext.FclProfileBridge.getProfileFunction()
                            val pr = app.aaps.plugins.aps.openAPSFCL.vnext.FclProfileBridge.getProfileRepository()
                            val ok = if (pf != null && pr != null)
                                Adjuster.applyPending(context, pf, pr)
                            else false
                            actionResult = if (ok) "Toegepast." else "Mislukt, probeer het later opnieuw."
                            isActing = false
                            showAcceptConfirmDialog = false
                            refreshTrigger++
                        }
                    }
                ) { Text(if (isActing) "Bezig..." else "Accepteren") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isActing,
                    onClick = { showAcceptConfirmDialog = false }
                ) { Text("Annuleren") }
            }
        )
    }
}

@Composable
private fun ProfileAutoAdjustTable(
    oldHourly: Map<Int, Double>,
    newHourly: Map<Int, Double>,
    shiftByHour: Map<Int, Double>,
    hoursAtCap: Set<Int>,
    capHits: Map<Int, Int>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Uur", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
            Text("Huidig", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Voorstel", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Na caps", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Status", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Divider()
        shiftByHour.keys.sorted().forEach { hour ->
            val oldVal = oldHourly[hour] ?: 0.0
            val newVal = newHourly[hour] ?: oldVal
            val shiftPct = shiftByHour[hour] ?: 0.0
            val voorstelVal = (oldVal * (1.0 + shiftPct / 100.0)).coerceAtLeast(0.0)
            val atCap = hour in hoursAtCap
            val hitCount = capHits[hour] ?: 0
            val status = if (atCap) "tegen grens" + (if (hitCount > 0) " (${hitCount}d)" else "") else "voorstel"
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("%02d:00".format(hour), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.8f))
                Text("%.2f".format(oldVal), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("%.2f (%+.0f%%)".format(voorstelVal, shiftPct), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("%.2f".format(newVal), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * BasalProfileChart (27/07/2026, Ecko, op verzoek) — trapvormige grafiek
 * (elk uur is een constante waarde, net als een echt basaalprofiel) met
 * Huidig en Voorstel over elkaar heen, zodat het effect van eventueel
 * accepteren in één oogopslag te zien is naast de cijfertabel erboven.
 * newHourly = "na caps" — dus exact wat er zou worden weggeschreven.
 */
@Composable
private fun BasalProfileChart(
    oldHourly: Map<Int, Double>,
    newHourly: Map<Int, Double>,
    heightDp: Int = 140
) {
    val oldColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val newColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(oldColor)
            )
            Text("Huidig", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(newColor)
            )
            Text("Voorstel (na caps)", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
        ) {
            val w = size.width
            val h = size.height
            val maxVal = (oldHourly.values + newHourly.values).filter { it.isFinite() }
                .maxOrNull()?.let { it * 1.15 } ?: 1.0
            val safeMax = if (maxVal <= 0.0) 1.0 else maxVal

            fun x(hour: Int): Float = w * (hour / 24f)
            fun y(v: Double): Float = h - (h * (v / safeMax)).toFloat()

            // Gridlijnen elke 6 uur.
            listOf(0, 6, 12, 18, 24).forEach { hr ->
                val xg = x(hr)
                drawLine(gridColor, Offset(xg, 0f), Offset(xg, h), strokeWidth = 1.5f)
            }
            drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.5f)

            fun drawStairs(hourly: Map<Int, Double>, color: Color, strokeWidthPx: Float) {
                var prevY: Float? = null
                for (hour in 0..23) {
                    val v = hourly[hour] ?: continue
                    val x0 = x(hour)
                    val x1 = x(hour + 1)
                    val yv = y(v)
                    // Verticale connector naar de vorige trede (als die er was).
                    if (prevY != null && prevY != yv) {
                        drawLine(color, Offset(x0, prevY), Offset(x0, yv), strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
                    }
                    // Horizontale trede voor dit uur.
                    drawLine(color, Offset(x0, yv), Offset(x1, yv), strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
                    prevY = yv
                }
            }

            drawStairs(oldHourly, oldColor, 4f)
            drawStairs(newHourly, newColor, 4f)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 24).forEach { hr ->
                Text("%02d".format(hr % 24), style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}

// (Basaal-adviseur-kaart hier verwijderd, zie NachtControlTab hierboven.)

// ── HandmatigParametersTab ────────────────────────────────────────────────
// Toont de actuele FCLvNext-parameters als leesweergave.
// Parameters worden beheerd via DFMapping (Automaat-tab, Kalibratie-sectie).

@Composable
private fun HandmatigParametersTab(
    activeParams: ConfigOverrideWriter.ActiveParams,
    onApplyParams: ((ConfigOverrideWriter.ParamOverrides) -> Boolean)?
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    val D = ConfigOverrideWriter.Defaults
    val mgdl = app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.isMgdl(androidx.compose.ui.platform.LocalContext.current)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            s.actuelParams,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Deze waarden worden automatisch berekend door het systeem op basis van " +
                "S/T/V en de Kalibratie-instellingen in de Automaat-tab. " +
                "Aanpassen kan via die knoppen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ParameterLeesCategorie("⏱ Commit & timing") {
            ParameterLeesRij("Stijgingsdrempel frontload",  app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.formatDelta(activeParams.watchingMinDeltaToTarget, mgdl), D.WATCHING_MIN_DELTA_TARGET)
            ParameterLeesRij("Frontload grootte",           "%.0f%%".format(activeParams.watchingFrontloadFrac * 100),  D.WATCHING_FRONTLOAD_FRAC * 100)
            ParameterLeesRij("Commit pauze",                "${activeParams.commitCooldownMinutes} min",                D.COMMIT_COOLDOWN_MINUTES.toDouble(), activeParams.commitCooldownMinutes.toDouble())
        }

        ParameterLeesCategorie("📈 Piekdrempels") {
            ParameterLeesRij("Piekdrempel",        app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.formatBg(activeParams.peakPredictionThreshold, mgdl),       D.PEAK_PREDICTION_THRESHOLD)
            ParameterLeesRij("Piekhorizon",         "%.1f uur".format(activeParams.peakPredictionHorizonH),        D.PEAK_PREDICTION_HORIZON_H)
            ParameterLeesRij("IOB startfractie",    "%.2f".format(activeParams.iobStart),                          D.IOB_START)
            ParameterLeesRij("IOB-remdrempel piek", "%.2f".format(activeParams.peakIobBrakeSuppressThreshold),     D.PEAK_IOB_BRAKE_SUPPRESS)
        }

        val ctx3 = androidx.compose.ui.platform.LocalContext.current
        val ebBoost   = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner.getEarlyBoostFactor(ctx3)
        val ebWatch   = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner.getWatchingFrac(ctx3)
        val ebStep    = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner.getEbStepSize(ctx3)
        val ebLastSig3 = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner.getEbLastSignal(ctx3)
        val ebLastTs3  = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner.getEbLastSignalTs(ctx3)
        val ebDefault = 1.69
        val ebGeleerd = kotlin.math.abs(ebBoost - ebDefault) > 0.005

        // Leesbare richting gebaseerd op afwijking t.o.v. standaard
        val ebRichting = when {
            !ebGeleerd                -> "Standaard — nog geen aanpassing"
            ebBoost > ebDefault + 0.10 -> "Sterk naar voren verschoven"
            ebBoost > ebDefault + 0.03 -> "Licht naar voren verschoven"
            ebBoost < ebDefault - 0.10 -> "Sterk naar achteren bijgesteld"
            ebBoost < ebDefault - 0.03 -> "Licht naar achteren bijgesteld"
            else                      -> "Dicht bij standaard"
        }
        // Zoekstap: hoe fijn zoekt het systeem momenteel?
        val ebStapUitleg = when {
            ebStep >= 0.10 -> "Grofzoeken (%.2fU stap)".format(ebStep)
            ebStep >= 0.04 -> "Normaal zoeken (%.2fU stap)".format(ebStep)
            else           -> "Fijnzoeken — nabij optimum (%.2fU stap)".format(ebStep)
        }
        val ebTsFormatted3 = if (ebLastTs3 > 0L) {
            try {
                java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(ebLastTs3))
            } catch (_: Exception) { "" }
        } else ""
        val ebSigEmoji3 = when (ebLastSig3) {
            "FORWARD" -> "➡"
            "BACK"    -> "⬅"
            else      -> "⏸"
        }

        ParameterLeesCategorie("🚀 Early Boost") {
            ParameterLeesRij("Boostfactor",          "%.2f ×".format(activeParams.earlyBoostFactor),            D.EARLY_BOOST_FACTOR)
            ParameterLeesRij("Min. betrouwbaarheid", "%.2f".format(activeParams.earlyBoostMinConfidence),       D.EARLY_BOOST_MIN_CONFIDENCE)
            ParameterLeesRij("Max. boost-commits",   "${activeParams.earlyBoostMaxCommits}",                    D.EARLY_BOOST_MAX_COMMITS.toDouble(), activeParams.earlyBoostMaxCommits.toDouble())
            if (ebGeleerd || true) {
                Spacer(Modifier.height(4.dp))
                // Geleerde timing stand
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Geleerde timingverschuiving",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(ebRichting,
                             style = MaterialTheme.typography.bodySmall,
                             fontWeight = FontWeight.Medium,
                             color = when {
                                 ebBoost > ebDefault + 0.03 -> MaterialTheme.colorScheme.primary
                                 ebBoost < ebDefault - 0.03 -> MaterialTheme.colorScheme.error
                                 else -> MaterialTheme.colorScheme.onSurface
                             })
                        Text(ebStapUitleg,
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Laatste evaluatie + signaal
                        if (ebTsFormatted3.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("$ebSigEmoji3 Laatste evaluatie: $ebLastSig3",
                                     style = MaterialTheme.typography.labelSmall,
                                     color = when (ebLastSig3) {
                                         "FORWARD" -> MaterialTheme.colorScheme.primary
                                         "BACK"    -> MaterialTheme.colorScheme.error
                                         else      -> MaterialTheme.colorScheme.onSurfaceVariant
                                     })
                                Text("·  $ebTsFormatted3",
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                    Text("boost %.3f\nwatch %.3f".format(ebBoost, ebWatch),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }

        ParameterLeesCategorie("🔀 Frontload-shift") {
            ParameterLeesRij("Late decay factor",  "%.2f".format(activeParams.lateCommitDecayFactor),    D.LATE_COMMIT_DECAY_FACTOR)
            ParameterLeesRij("Decay drempel",      "%.2f".format(activeParams.lateCommitDecayThreshold), D.LATE_COMMIT_DECAY_THRESHOLD)
            ParameterLeesRij("Vroege piek-bias",   app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.formatDelta(activeParams.earlyPeakBiasMmol, mgdl), D.EARLY_PEAK_BIAS_MMOL)
        }
    }
}

@Composable
private fun ParameterLeesCategorie(
    titel: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(titel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Divider()
            content()
        }
    }
}

@Composable
private fun ParameterLeesRij(
    naam: String,
    waarde: String,
    defaultVal: Double,
    huidigeDouble: Double? = null
) {
    // Vergelijk huidige waarde met default voor kleurcodering
    val huidig = huidigeDouble ?: waarde.filter { it.isDigit() || it == '.' || it == ',' }
        .replace(',', '.').toDoubleOrNull()
    val kleur = when {
        huidig == null                       -> MaterialTheme.colorScheme.onSurface
        huidig > defaultVal + 0.001          -> MaterialTheme.colorScheme.primary
        huidig < defaultVal - 0.001          -> MaterialTheme.colorScheme.tertiary
        else                                 -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(naam, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(waarde, style = MaterialTheme.typography.bodySmall,
             fontWeight = FontWeight.Medium, color = kleur)
    }
}