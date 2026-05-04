package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

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
    // MaxSmbLearner: (newMaxSmbDay, newIobBrake) -> succes
    onApplyMaxSmb: ((Double, Double) -> Boolean)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(onClick = onBack) { Text("← Terug") }

        Text("Advisor Analyse", style = MaterialTheme.typography.headlineMedium)

        InfoTabPager(
            modifier = Modifier,
            pages = listOf(

                // ── 1. Automaat: D/F zelflerend systeem (startblad) ───────
                InfoTabPage("Automaat") {
                    DFControlTab(
                        episodes    = episodes,
                        metrics     = metrics,
                        nachtFactor = nachtFactor,
                        onApplyToAaps = onApplyDFToAaps
                    )
                },

                // ── 2. MaxSMB: veiligheidsmarges leren ────────────────────
                InfoTabPage("MaxSMB") {
                    MaxSmbTab(
                        metrics      = metrics,
                        manualMaxSmb = FclActiveConfigBridge.get()?.manualMaxSmbDay ?: 1.25,
                        onApplyToAaps = onApplyMaxSmb
                    )
                },

                // ── 3. Parameters: handmatige fijnafstelling ──────────────
                InfoTabPage("Parameters") {
                    HandmatigParametersTab(
                        activeParams  = activeParams,
                        onApplyParams = onApplyParams
                    )
                },

                // ── 3. Nacht N: nachtfactor instelling ────────────────────
                InfoTabPage("Nacht N") {
                    NachtTab(
                        currentNachtFactor = activeParams.nachtFactor,
                        nightWindows = nightWindows,
                        onApplyNacht = onApplyNacht
                    )
                },

                // ── 4. Analyse: patroon + voorstel (achtergrond info) ──────
                InfoTabPage("Analyse") {
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
                }
            )
        )
    }
}

@Composable
private fun AdvisorOverviewCard(recommendation: FclAdvisorRecommendation) {
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
            Text("Analyse-overzicht", style = MaterialTheme.typography.titleMedium)

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
                AdvisorMetricBlock("Confidence", "${(recommendation.confidence * 100).toInt()}%")
                AdvisorMetricBlock("Gebruikt", "${selection.usedEpisodeCount}")
                AdvisorMetricBlock("Uitgesloten", "${selection.excludedTotal}")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AdvisorMetricBlock("Avg peak", "%.1f".format(stats.avgPeakBg))
                AdvisorMetricBlock("Avg insuline", "%.2f U".format(stats.avgInsulinDelivered))
                AdvisorMetricBlock("Avg duur", "${stats.avgDurationMinutes} min")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AdvisorMetricBlock("Hyper", "${stats.hyperPercent}%")
                AdvisorMetricBlock("Hypo", "${stats.hypoPercent}%")
                AdvisorMetricBlock("Binnen doel", "${stats.meetsGoalPercent}%")
            }
        }
    }
}

@Composable
private fun AdvisorSelectionCard(recommendation: FclAdvisorRecommendation) {
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
                AdvisorMetricBlock("Gebruikt", "${selection.usedEpisodeCount}")
                AdvisorMetricBlock("Uitgesloten", "${selection.excludedTotal}")
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Huidige instellingen", style = MaterialTheme.typography.titleMedium)
            Text("💪 Sterkte (S): ${current.sterkte}%")
            Text("⏱️ Timing (T): ${current.timing}%")
            Text("🔁 Volhoudendheid (V): ${current.volhoudendheid}%")
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
// Alle instelbare FCLvNext-parameters met ± knoppen per categorie.
// Toont default-waarden en een indicator (↑ / = / ↓) met kleur.
// State blijft stabiel na write — reset alleen bij nieuwe activeParams.

@Composable
private fun HandmatigParametersTab(
    activeParams: ConfigOverrideWriter.ActiveParams,
    onApplyParams: ((ConfigOverrideWriter.ParamOverrides) -> Boolean)?
) {
    // Lokale edit-state: startwaarden = actuele AAPS-waarden
    var peakThreshold   by remember(activeParams) { mutableStateOf(activeParams.peakPredictionThreshold) }
    var frontloadFrac   by remember(activeParams) { mutableStateOf(activeParams.watchingFrontloadFrac) }
    var minDelta        by remember(activeParams) { mutableStateOf(activeParams.watchingMinDeltaToTarget) }
    var cooldown        by remember(activeParams) { mutableStateOf(activeParams.commitCooldownMinutes.toDouble()) }
    var horizonH        by remember(activeParams) { mutableStateOf(activeParams.peakPredictionHorizonH) }
    var iobStart        by remember(activeParams) { mutableStateOf(activeParams.iobStart) }
    var iobBrake        by remember(activeParams) { mutableStateOf(activeParams.peakIobBrakeSuppressThreshold) }
    var boostFactor     by remember(activeParams) { mutableStateOf(activeParams.earlyBoostFactor) }
    var boostMinConf    by remember(activeParams) { mutableStateOf(activeParams.earlyBoostMinConfidence) }
    var boostMaxCommits by remember(activeParams) { mutableStateOf(activeParams.earlyBoostMaxCommits.toDouble()) }
    var riseFracMin     by remember(activeParams) { mutableStateOf(activeParams.earlyRiseFracMin) }
    var maxSlopeWeight  by remember(activeParams) { mutableStateOf(activeParams.peakMaxSlopeWeight) }
    var decayFactor     by remember(activeParams) { mutableStateOf(activeParams.lateCommitDecayFactor) }
    var decayThreshold  by remember(activeParams) { mutableStateOf(activeParams.lateCommitDecayThreshold) }

    // showResult: stable eigen state, niet gereset door activeParams-wijziging
    var showResult by remember { mutableStateOf<String?>(null) }
    var showResultTs by remember { mutableStateOf(0L) }

    fun buildOverrides() = ConfigOverrideWriter.ParamOverrides(
        peakPredictionThreshold       = peakThreshold,
        watchingFrontloadFrac         = frontloadFrac,
        watchingMinDeltaToTarget      = minDelta,
        commitCooldownMinutes         = cooldown.toInt(),
        peakPredictionHorizonH        = horizonH,
        iobStart                      = iobStart,
        peakIobBrakeSuppressThreshold = iobBrake,
        earlyBoostFactor              = boostFactor,
        earlyBoostMinConfidence       = boostMinConf,
        earlyBoostMaxCommits          = boostMaxCommits.toInt(),
        earlyRiseFracMin              = riseFracMin,
        peakMaxSlopeWeight            = maxSlopeWeight,
        lateCommitDecayFactor         = decayFactor,
        lateCommitDecayThreshold      = decayThreshold
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Handmatige parameters",
             style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold)
        Text(
            "Pas FCLvNext-parameters aan via ± stappen. " +
                "Groen = hoger dan default, oranje = lager, wit = gelijk aan default.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val D = ConfigOverrideWriter.Defaults  // shorthand

        // ── Categorie 1: Commit & timing ──────────────────────────────────
        ParamCategorie(
            titel = "⏱ Commit & timing",
            uitleg = "Wanneer en hoe snel actie bij stijgende BG. Kortere pauze en lagere drempel = eerder en actiever, maar ook hoger risico op teveel insuline als stijging stokt."
        ) {
            ParamRij("Commit pauze",         "commitCooldownMinutes",   cooldown,       D.COMMIT_COOLDOWN_MINUTES.toDouble(), "min",  1.0,  5.0, 30.0, isInt = true,
                     hogerBetekent = "meer pauze tussen commits — minder agressief, minder kans op ophoping",
                     lagerBetekent = "sneller opeenvolgende commits — eerder en meer insuline vroeg in episode") { cooldown = it }
            ParamRij("Min. delta naar doel", "watchingMinDeltaToTarget", minDelta,      D.WATCHING_MIN_DELTA_TARGET,          "mmol", 0.25, 0.5, 5.0,
                     hogerBetekent = "hogere drempel — systeem wacht op grotere stijging boven target",
                     lagerBetekent = "lagere drempel — systeem reageert al bij kleine stijgingen") { minDelta = it }
            ParamRij("Frontload fractie",    "watchingFrontloadFrac",   frontloadFrac,  D.WATCHING_FRONTLOAD_FRAC,            "",    0.05, 0.20, 1.0,
                     hogerBetekent = "grotere frontload dosis bij stijgingsdetectie",
                     lagerBetekent = "voorzichtiger bij eerste stijgingssignaal") { frontloadFrac = it }
        }

        // ── Categorie 2: Piekdrempels ─────────────────────────────────────
        ParamCategorie(
            titel = "📈 Piekdrempels",
            uitleg = "Hoe hoog het systeem de BG-piek inschat en waar het remt. Lagere piekdrempel = eerder remmen. Lagere IOB-drempel = eerder stoppen met doseren."
        ) {
            ParamRij("Piekdrempel",          "peakPredictionThreshold",  peakThreshold, D.PEAK_PREDICTION_THRESHOLD,          "mmol", 0.5,  8.0, 16.0,
                     hogerBetekent = "systeem verwacht hogere piek — gaat vroeger en meer remmen",
                     lagerBetekent = "systeem verwacht lagere piek — minder vroeg remmen, kans op hogere BG") { peakThreshold = it }
            ParamRij("Piekhoriz.",           "peakPredictionHorizonH",   horizonH,      D.PEAK_PREDICTION_HORIZON_H,          "uur",  0.1,  0.5,  3.0,
                     hogerBetekent = "verder vooruit kijken bij piekschatting — conservatiever, eerder remmen",
                     lagerBetekent = "korter vooruit kijken — agressiever doorcommitteren tot later in episode") { horizonH = it }
            ParamRij("IOB startfractie",     "iobStart",                 iobStart,      D.IOB_START,                          "",    0.02, 0.10, 0.60,
                     hogerBetekent = "hogere IOB-fractie nodig voor WATCHING-activering — systeem start later",
                     lagerBetekent = "lagere drempel — WATCHING activeert al bij minder actief insuline") { iobStart = it }
            ParamRij("IOB-remdrempel piek",  "peakIobBrakeSuppressThreshold", iobBrake, D.PEAK_IOB_BRAKE_SUPPRESS,            "",    0.02, 0.20, 0.80,
                     hogerBetekent = "rem activeert pas bij hogere IOB — meer commits toegestaan voor rem ingrijpt",
                     lagerBetekent = "rem activeert eerder — minder commits na vroeg hoge IOB-opbouw") { iobBrake = it }
        }

        // ── Categorie 3: Early Boost ──────────────────────────────────────
        ParamCategorie(
            titel = "🚀 Early Boost",
            uitleg = "Versterkt de eerste 1-2 commits bij een duidelijke stijging. Factor 1.0 = uit. Hoger = meer vroege insuline en lagere piek, maar hogere kans op post-maaltijd hypo als de stijging tegenvalt."
        ) {
            ParamRij("Boostfactor",          "earlyBoostFactor",         boostFactor,   D.EARLY_BOOST_FACTOR,                 "×",   0.05, 1.0,  2.0,
                     hogerBetekent = "sterkere vroege boost — meer insuline in eerste commits, lagere piek, hogere hypo-kans na piek",
                     lagerBetekent = "zwakkere boost — minder verschil met normale commit, veiliger maar hogere piek") { boostFactor = it }
            ParamRij("Min. betrouwbaarheid", "earlyBoostMinConfidence",  boostMinConf,  D.EARLY_BOOST_MIN_CONFIDENCE,         "",    0.05, 0.30, 0.90,
                     hogerBetekent = "hogere zekerheidsdrempel — boost alleen bij sterkere maaltijdsignalen",
                     lagerBetekent = "lagere drempel — boost ook bij zwakkere stijgingssignalen") { boostMinConf = it }
            ParamRij("Max. boost-commits",   "earlyBoostMaxCommits",     boostMaxCommits, D.EARLY_BOOST_MAX_COMMITS.toDouble(), "", 1.0,  1.0,  5.0, isInt = true,
                     hogerBetekent = "boost actief voor meer commits — langere periode van versterkte dosering",
                     lagerBetekent = "boost stopt eerder — minder totale insuline versterking vroeg in episode") { boostMaxCommits = it }
        }

        // ── Categorie 4: Piekkalibr. ──────────────────────────────────────
        ParamCategorie(
            titel = "🔬 Piekkalibr.",
            uitleg = "Fijnregeling piekvoorspelling. Alleen aanpassen als de piek structureel eerder of later wordt voorspeld dan hij werkelijk optreedt."
        ) {
            ParamRij("Vroeg stijgfractie",   "earlyRiseFracMin",         riseFracMin,   D.EARLY_RISE_FRAC_MIN,                "",    0.05, 0.20, 1.0,
                     hogerBetekent = "hogere stijgfractie — piek wordt steiler ingeschat, eerder hoge piekschatting",
                     lagerBetekent = "lagere fractie — piek wordt vlakker ingeschat") { riseFracMin = it }
            ParamRij("Max helling gewicht",  "peakMaxSlopeWeight",       maxSlopeWeight, D.PEAK_MAX_SLOPE_WEIGHT,             "",    0.05, 0.0,  0.80,
                     hogerBetekent = "steilere stijging weegt zwaarder in piekschatting — hogere pieken voorspeld",
                     lagerBetekent = "minder gewicht op stijlheid — vlakkere piekschatting") { maxSlopeWeight = it }
        }

        // ── Categorie 5: Frontload-shift ─────────────────────────────────
        ParamCategorie(
            titel = "🔀 Frontload-shift",
            uitleg = "Koppelt de vroege boost aan een late rem. Als earlyBoost actief was (budget verbruikt) en IOBratio een drempel overschrijdt, worden volgende commits gereduceerd. Doel: insuline meer naar voren halen, lagere IOB na de piek, minder hypo-risico. Decay factor 0.0 = uit (veilige default)."
        ) {
            ParamRij("Late decay factor",    "lateCommitDecayFactor",   decayFactor,    D.LATE_COMMIT_DECAY_FACTOR,    "",    0.05, 0.0, 1.0,
                     hogerBetekent = "sterkere afremming van late commits na vroege boost",
                     lagerBetekent = "zwakkere rem — meer late commits toegestaan ook na boost") { decayFactor = it }
            ParamRij("Decay drempel",        "lateCommitDecayThreshold", decayThreshold, D.LATE_COMMIT_DECAY_THRESHOLD, "",    0.05, 0.30, 0.70,
                     hogerBetekent = "rem start pas bij hogere IOB — meer ruimte voor commits",
                     lagerBetekent = "rem start eerder — minder commits al bij lagere IOB") { decayThreshold = it }
        }

        // ── Toepassen / Reset ─────────────────────────────────────────────
        if (onApplyParams != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val ok = onApplyParams(buildOverrides())
                        showResult = if (ok) "✓ Verzonden naar AAPS" else "✗ Verzenden mislukt"
                        showResultTs = System.currentTimeMillis()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Toepassen in AAPS") }
                OutlinedButton(
                    onClick = {
                        peakThreshold   = D.PEAK_PREDICTION_THRESHOLD
                        frontloadFrac   = D.WATCHING_FRONTLOAD_FRAC
                        minDelta        = D.WATCHING_MIN_DELTA_TARGET
                        cooldown        = D.COMMIT_COOLDOWN_MINUTES.toDouble()
                        horizonH        = D.PEAK_PREDICTION_HORIZON_H
                        iobStart        = D.IOB_START
                        iobBrake        = D.PEAK_IOB_BRAKE_SUPPRESS
                        boostFactor     = D.EARLY_BOOST_FACTOR
                        boostMinConf    = D.EARLY_BOOST_MIN_CONFIDENCE
                        boostMaxCommits = D.EARLY_BOOST_MAX_COMMITS.toDouble()
                        riseFracMin     = D.EARLY_RISE_FRAC_MIN
                        maxSlopeWeight  = D.PEAK_MAX_SLOPE_WEIGHT
                        decayFactor     = D.LATE_COMMIT_DECAY_FACTOR
                        decayThreshold  = D.LATE_COMMIT_DECAY_THRESHOLD
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset defaults") }
            }
        }

        // Bevestiging — toont de timestamp zodat recomposities het niet wissen
        showResult?.let { msg ->
            val age = System.currentTimeMillis() - showResultTs
            if (age < 30_000L) {  // verdwijnt na 30 seconden
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.startsWith("✓"))
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (msg.startsWith("✓"))
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

// ── ParamCategorie: uitklapbare categorie met uitleg ─────────────────────

@Composable
private fun ParamCategorie(
    titel: String,
    uitleg: String,                       // categorietoelichting
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: klikbaar om uit/in te klappen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    titel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (open) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (open) {
                Divider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                // Categorietoelichting
                Text(
                    uitleg,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Divider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                )
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                }
            }
        }
    }
}

// ── ParamRij: parameter met ±knoppen, default, indicator en uitleg ────────

@Composable
private fun ParamRij(
    naam: String,
    technisch: String,
    waarde: Double,
    default: Double,
    eenheid: String,
    stap: Double,
    min: Double,
    max: Double,
    isInt: Boolean = false,
    hogerBetekent: String = "",           // "meer effect / eerder actief / ..."
    lagerBetekent: String = "",           // "minder effect / later actief / ..."
    onChange: (Double) -> Unit
) {
    val verschil = waarde - default
    val eps = stap * 0.49
    val indicator = when {
        verschil > eps  -> "↑"
        verschil < -eps -> "↓"
        else            -> "="
    }
    val indicatorColor = when {
        verschil > eps  -> MaterialTheme.colorScheme.primary
        verschil < -eps -> MaterialTheme.colorScheme.tertiary
        else            -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val waardeColor = when {
        verschil > eps  -> MaterialTheme.colorScheme.primary
        verschil < -eps -> MaterialTheme.colorScheme.tertiary
        else            -> MaterialTheme.colorScheme.onSurface
    }

    val defaultTekst = if (isInt) "${default.toInt()}$eenheid"
    else "${String.format("%.2f", default)}$eenheid"

    var showInfo by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Links: naam, technisch, default — klikbaar voor uitleg
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        enabled = hogerBetekent.isNotBlank() || lagerBetekent.isNotBlank()
                    ) { showInfo = !showInfo }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(naam,
                         style = MaterialTheme.typography.bodySmall,
                         fontWeight = FontWeight.Medium)
                    if (hogerBetekent.isNotBlank() || lagerBetekent.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text("ℹ",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    }
                }
                Text(technisch,
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("default: $defaultTekst",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }

            // Rechts: − [indicator waarde] +
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { if (waarde - stap >= min - eps) onChange(
                        if (isInt) kotlin.math.round(waarde - stap).toDouble()
                        else kotlin.math.round((waarde - stap) / stap) * stap
                    )},
                    modifier = Modifier.size(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    enabled = waarde > min + eps
                ) { Text("−", style = MaterialTheme.typography.bodyMedium) }

                Row(
                    modifier = Modifier.width(80.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(indicator,
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.Bold,
                         color = indicatorColor)
                    Spacer(Modifier.width(2.dp))
                    Text(
                        if (isInt) "${waarde.toInt()}$eenheid"
                        else "${String.format("%.2f", waarde)}$eenheid",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = waardeColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                    )
                }

                OutlinedButton(
                    onClick = { if (waarde + stap <= max + eps) onChange(
                        if (isInt) kotlin.math.round(waarde + stap).toDouble()
                        else kotlin.math.round((waarde + stap) / stap) * stap
                    )},
                    modifier = Modifier.size(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    enabled = waarde < max - eps
                ) { Text("+", style = MaterialTheme.typography.bodyMedium) }
            }
        }

        // Uitleg popup (inline, verschijnt onder de rij bij klik op ℹ)
        if (showInfo && (hogerBetekent.isNotBlank() || lagerBetekent.isNotBlank())) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp),
                       verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (hogerBetekent.isNotBlank())
                        Text("↑ hoger: $hogerBetekent",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (lagerBetekent.isNotBlank())
                        Text("↓ lager: $lagerBetekent",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}