package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.Episode
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeSafetyResult
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.SafetyInvariantChecker
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.SafetyViolation
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.TimeFormat
import java.time.Instant

/**
 * FclSafetyCheckScreen (12/07/2026, Ecko)
 *
 * Nieuwe tab naast Episodes/Advisor/AI Advisor: laat per episode zien of de
 * SafetyInvariantChecker een schending heeft gevonden ("late, te grote
 * commit vlak op de piek" — het §3-patroon uit het overdrachtsdocument),
 * en zo ja, welke bestaande rem (indien enige) op dat moment actief was.
 *
 * Puur informatief/read-only — geen enkele knop hier past iets aan het
 * doseeralgoritme zelf aan. Data komt rechtstreeks uit de al-geladen
 * episodes (dezelfde data als de Episode Viewer), geen extra databronnen.
 */
@Composable
fun FclSafetyCheckScreen(
    episodes: List<Episode>,
    onBack: () -> Unit
) {
    var windowMinutes by remember { mutableStateOf(SafetyInvariantChecker.DEFAULT_WINDOW_MINUTES) }
    var maxFractionPct by remember { mutableStateOf((SafetyInvariantChecker.DEFAULT_MAX_FRACTION * 100).toInt()) }

    val results = remember(episodes, windowMinutes, maxFractionPct) {
        SafetyInvariantChecker.checkAll(
            episodes = episodes,
            windowMinutes = windowMinutes,
            maxFraction = maxFractionPct / 100.0
        ).sortedByDescending { it.episodeStart }
    }
    val violationCount = results.count { it.hasViolation }

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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) { Text("← Terug") }
            Text(
                "Veiligheidscontrole",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (violationCount > 0)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (violationCount > 0)
                        "$violationCount van ${results.size} episodes met een late, te grote commit"
                    else
                        "Geen schendingen gevonden in ${results.size} episodes",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Regel: geen commit binnen $windowMinutes min van de BG-piek mag groter zijn " +
                        "dan $maxFractionPct% van de grootste commit die episode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Instelbare gevoeligheid (puur voor deze weergave, wijzigt niets
        // aan het doseeralgoritme) ──────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gevoeligheid van deze weergave", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Venster: $windowMinutes min", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { windowMinutes = (windowMinutes - 5).coerceAtLeast(5) }) { Text("−5") }
                    OutlinedButton(onClick = { windowMinutes = (windowMinutes + 5).coerceAtMost(30) }) { Text("+5") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Drempel: $maxFractionPct%", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { maxFractionPct = (maxFractionPct - 10).coerceAtLeast(10) }) { Text("−10") }
                    OutlinedButton(onClick = { maxFractionPct = (maxFractionPct + 10).coerceAtMost(100) }) { Text("+10") }
                }
            }
        }

        if (results.isEmpty()) {
            Text(
                "Nog geen episodes om te controleren.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        results.forEach { result ->
            EpisodeSafetyCard(result)
        }
    }
}

@Composable
private fun EpisodeSafetyCard(result: EpisodeSafetyResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.hasViolation)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Episode ${result.episodeId} — ${TimeFormat.formatLocalAmsterdam(result.episodeStart)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                StatusBadge(ok = !result.hasViolation)
            }
            Text(
                "Piek ${"%.1f".format(result.peakBg)} mmol/L" +
                    (result.peakTimestamp?.let { " om ${TimeFormat.formatLocalAmsterdam(it)}" } ?: "") +
                    " • grootste commit ${"%.2f".format(result.episodePeakCommitU)}U",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            result.violations.forEach { v -> ViolationRow(v) }
        }
    }
}

@Composable
private fun ViolationRow(v: SafetyViolation) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "⚠ ${TimeFormat.formatLocalAmsterdam(v.timestamp)}: ${"%.2f".format(v.deliveredU)}U " +
                "(${(v.fractionOfPeak * 100).toInt()}% van piek-commit, " +
                "${if (v.minutesFromPeak >= 0) "+" else ""}${v.minutesFromPeak} min t.o.v. BG-piek)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Rem op dat moment: ${v.dominantReason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusBadge(ok: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    ) {
        Text(
            if (ok) "OK" else "SCHENDING",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError
        )
    }
}
