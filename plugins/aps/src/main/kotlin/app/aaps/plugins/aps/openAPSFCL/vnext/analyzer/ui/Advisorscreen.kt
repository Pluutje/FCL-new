package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.FclActiveConfigBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.*
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowEntity
import kotlin.math.roundToInt
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings

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
    onApplyNacht: ((Int) -> Boolean)? = null,
    allRows: List<LogRow> = emptyList(),
    onApplyDFToAaps: ((ConfigOverrideWriter.ParamOverrides, Map<String, Int>) -> Boolean)? = null,
    nachtFactor: Int = 85,
    onApplyParams: ((ConfigOverrideWriter.ParamOverrides) -> Boolean)? = null,
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)

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

        val advisorPages = buildList {
            // ── 1. Automaat: D/F zelflerend systeem (startblad) ──────────
            add(InfoTabPage(s.automaat) {
                DFControlTab(
                    episodes    = episodes,
                    metrics     = metrics,
                    nachtFactor = nachtFactor,
                    onApplyToAaps = onApplyDFToAaps
                )
            })

            // ── 2. Nacht N: nachtfactor instelling ───────────────────────
            add(InfoTabPage(s.nachtNLabel) {
                NachtTab(
                    currentNachtFactor = activeParams.nachtFactor,
                    nightWindows = nightWindows,
                    onApplyNacht = onApplyNacht
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
            pages = advisorPages
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
// Nacht-N Tab — geïntegreerde nacht-gain analyse in de Advisor
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun NachtTab(
    currentNachtFactor: Int,
    nightWindows: List<NightWindowEntity>,
    onApplyNacht: ((Int) -> Boolean)?
) {
    var factor by remember(currentNachtFactor) { mutableStateOf(currentNachtFactor) }
    var applyResult by remember { mutableStateOf<String?>(null) }
    var applyTs by remember { mutableStateOf(0L) }

    val stap = 5
    val eps = 1

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Uitleg
        Text(
            "De nacht-factor schalt de insulinedosering tijdens nachtelijke uren. " +
                "N=85 betekent 15% minder dan overdag. Pas aan op basis van nachtelijke BG-trends.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // N-factor kaart
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("🌙 Nacht-factor (N)",
                             style = MaterialTheme.typography.titleSmall,
                             fontWeight = FontWeight.SemiBold)
                        Text("Insulinesterkte 's nachts t.o.v. overdag",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "$factor%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            factor < currentNachtFactor -> MaterialTheme.colorScheme.tertiary
                            factor > currentNachtFactor -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (factor - stap >= 60) factor -= stap },
                        enabled = factor > 60 + eps,
                        modifier = Modifier.weight(1f)
                    ) { Text("−  ${factor - stap}%") }
                    OutlinedButton(
                        onClick = { if (factor + stap <= 100) factor += stap },
                        enabled = factor < 100 - eps,
                        modifier = Modifier.weight(1f)
                    ) { Text("+  ${factor + stap}%") }
                }
                if (onApplyNacht != null) {
                    Button(
                        onClick = {
                            val ok = onApplyNacht(factor)
                            applyResult = if (ok) "✓ Nacht-factor verzonden naar AAPS" else "✗ Verzenden mislukt"
                            applyTs = System.currentTimeMillis()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Toepassen in AAPS") }
                    applyResult?.let { msg ->
                        if (System.currentTimeMillis() - applyTs < 20_000L) {
                            Text(msg,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // Nachtvensters tonen als aanwezig
        NachtVenstersCompact(nightWindows = nightWindows)
    }
}



// ── NachtVenstersCompact ──────────────────────────────────────────────────
// Compacte weergave van nachtvensters, opgenomen in de Nacht-tab zodat de
// aparte dashboard-knop "Nacht" niet meer nodig is.

@Composable
private fun NachtVenstersCompact(nightWindows: List<NightWindowEntity>) {
    if (nightWindows.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Nachtvensters", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Divider()
            val recent = nightWindows.sortedByDescending { it.localDate }.take(7)
            recent.forEach { w ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(w.localDate, style = MaterialTheme.typography.bodySmall)
                    Text(
                        w.nightMechanism ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (w.nightMechanism) {
                            "CLEAN_BASAL" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        w.driftSignal ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (w.driftSignal) {
                            "BASAL_UP"   -> MaterialTheme.colorScheme.tertiary
                            "BASAL_DOWN" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            if (nightWindows.size > 7) {
                Text(
                    "… nog ${nightWindows.size - 7} eerdere nachten",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

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