package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * MaxSmbTab — interface voor de MaxSmbLearner.
 *
 * Toont:
 *   - Huidige geleerde maxSMB (dag) en iobBrake-drempel
 *   - Advies op basis van meest recente episode
 *   - Automaat aan/uit schakelaar
 *   - Geschiedenis van aanpassingen
 *   - "Toepassen in AAPS" knop (handmatige modus)
 *
 * Leerregels (zichtbaar voor gebruiker):
 *   maxSMB ↑ +0.05U als: piek > 12 mmol + rem ≥ 3× actief + earlyBoost actief + geen hypo
 *   maxSMB ↓ -0.10U als: hypo na episode
 *   iobBrake ↑ +0.02 als: maxSMB al op max + piek > 12 + rem actief
 *   iobBrake ↓ -0.02 als: hypo + iobBrake boven default
 */
@Composable
fun MaxSmbTab(
    metrics: List<EpisodeMetrics> = emptyList(),
    manualMaxSmb: Double = MaxSmbLearner.MAX_SMB_DEFAULT,
    onApplyToAaps: ((Double, Double) -> Boolean)? = null
) {
    val context = LocalContext.current

    var maxSmbDay    by remember { mutableStateOf(MaxSmbLearner.getMaxSmbDay(context)) }
    var iobBrake     by remember { mutableStateOf(MaxSmbLearner.getIobBrake(context)) }
    var autoEnabled  by remember { mutableStateOf(MaxSmbLearner.isAutoEnabled(context)) }
    var history      by remember { mutableStateOf(MaxSmbLearner.getHistory(context)) }
    var applyResult  by remember { mutableStateOf<String?>(null) }
    var applyTs      by remember { mutableStateOf(0L) }

    // Dynamische grenzen gebaseerd op handmatige instelling
    val smbMin = MaxSmbLearner.dynamicMin(manualMaxSmb)
    val smbMax = MaxSmbLearner.dynamicMax(manualMaxSmb)

    // Bereken advies op basis van meest recente bruikbare episode
    val latestMetrics = metrics.lastOrNull()
    val advies = latestMetrics?.let {
        MaxSmbLearner.evaluate(context, it, manualMaxSmb = manualMaxSmb, forceApply = false)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Uitleg ────────────────────────────────────────────────────────
        Text(
            "Past de maximale bolus per commit (maxSMB) en de IOB-remdrempel aan " +
                "op basis van maaltijduitkomsten. Werkt onafhankelijk van S/T/V.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Huidige waarden ───────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                Text(
                    "Huidige instellingen",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Divider()

                WaardeRij(
                    label = "💉  Max bolus/commit (dag)",
                    waarde = "${"%.2f".format(maxSmbDay)} U",
                    bereik = "${"%.2f".format(smbMin)}–${"%.2f".format(smbMax)} U  (ref: ${"%.2f".format(manualMaxSmb)} U)",
                    afwijking = maxSmbDay - manualMaxSmb
                )
                WaardeRij(
                    label = "🛑  IOB-remdrempel",
                    waarde = "${"%.2f".format(iobBrake)}",
                    bereik = "${MaxSmbLearner.IOB_BRAKE_MIN}–${MaxSmbLearner.IOB_BRAKE_MAX}",
                    afwijking = iobBrake - MaxSmbLearner.IOB_BRAKE_DEFAULT,
                    lagerIsBeter = true
                )
            }
        }

        // ── Advies op basis van laatste episode ───────────────────────────
        if (advies != null && advies.hasChange) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Text(
                        "💡  Advies op basis van episode ${latestMetrics?.id}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Divider()

                    val smbDelta = advies.newMaxSmb - advies.oldMaxSmb
                    val brakeDelta = advies.newIobBrake - advies.oldIobBrake

                    if (kotlin.math.abs(smbDelta) > 0.001) {
                        val teken = if (smbDelta > 0) "+" else ""
                        Text(
                            "maxSMB: ${"%.2f".format(advies.oldMaxSmb)} → ${"%.2f".format(advies.newMaxSmb)} U  ($teken${"%.2f".format(smbDelta)}U)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (smbDelta > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (kotlin.math.abs(brakeDelta) > 0.001) {
                        val teken = if (brakeDelta > 0) "+" else ""
                        Text(
                            "iobBrake: ${"%.3f".format(advies.oldIobBrake)} → ${"%.3f".format(advies.newIobBrake)}  ($teken${"%.3f".format(brakeDelta)})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (brakeDelta > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        advies.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Toepassen knop (alleen handmatige modus)
                    if (!autoEnabled && onApplyToAaps != null) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                // Sla op en stuur naar AAPS
                                MaxSmbLearner.evaluate(context, latestMetrics!!, forceApply = true)
                                maxSmbDay   = MaxSmbLearner.getMaxSmbDay(context)
                                iobBrake    = MaxSmbLearner.getIobBrake(context)
                                history     = MaxSmbLearner.getHistory(context)
                                val ok = onApplyToAaps(maxSmbDay, iobBrake)
                                applyResult = if (ok) "✓ Verzonden naar AAPS" else "✗ Verzenden mislukt"
                                applyTs = System.currentTimeMillis()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Advies toepassen in AAPS")
                        }
                    }
                }
            }
        } else if (latestMetrics != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "= Geen aanpassing nodig op basis van laatste episode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

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

        // ── Automaat aan/uit ──────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "🤖  Automaat leert",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (autoEnabled)
                            "Past maxSMB en iobBrake automatisch aan na elke episode"
                        else
                            "Handmatig — berekent advies maar past niet automatisch aan",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoEnabled,
                    onCheckedChange = {
                        autoEnabled = it
                        MaxSmbLearner.setAutoEnabled(context, it)
                    }
                )
            }
        }

        // ── Leerregels (uitleg) ───────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Leerregels",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Divider()
                Text("↑ maxSMB +0.05U  als piek > 12 mmol + rem ≥ 3× actief + earlyBoost actief + geen hypo",
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("↑ iobBrake +0.02  als maxSMB al op max en piek > 12 + rem actief",
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("↓ maxSMB -0.10U  als hypo (< 4.0 mmol) na episode",
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Text("↓ iobBrake -0.02  als hypo + iobBrake boven standaard",
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Text("Grenzen: maxSMB 0.75–2.00 U  •  iobBrake 0.35–0.55  •  48u wachttijd",
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── Geschiedenis ──────────────────────────────────────────────────
        if (history.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Laatste aanpassingen",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Divider()
                    history.reversed().take(5).forEach { stap ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val smbDelta = stap.newMaxSmb - stap.oldMaxSmb
                                val brakeDelta = stap.newIobBrake - stap.oldIobBrake
                                val parts = buildList {
                                    if (kotlin.math.abs(smbDelta) > 0.001)
                                        add("maxSMB ${if (smbDelta > 0) "+" else ""}${"%.2f".format(smbDelta)}U → ${"%.2f".format(stap.newMaxSmb)}U")
                                    if (kotlin.math.abs(brakeDelta) > 0.001)
                                        add("brake ${if (brakeDelta > 0) "+" else ""}${"%.3f".format(brakeDelta)} → ${"%.3f".format(stap.newIobBrake)}")
                                }
                                Text(
                                    parts.joinToString("  ").ifBlank { "Geen wijziging" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    stap.diagnose,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (stap.diagnose.contains("HYPO"))
                                        MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                fmtTs(stap.tsUtc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (stap != history.reversed().take(5).last())
                            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    }
                }
            }
        }
    }
}

// ── Helper composables ────────────────────────────────────────────────────

@Composable
private fun WaardeRij(
    label: String,
    waarde: String,
    bereik: String,
    afwijking: Double,
    lagerIsBeter: Boolean = false
) {
    val positief = if (lagerIsBeter) afwijking < -0.001 else afwijking > 0.001
    val kleur = when {
        afwijking > 0.001  -> if (lagerIsBeter) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        afwijking < -0.001 -> if (lagerIsBeter) MaterialTheme.colorScheme.primary   else MaterialTheme.colorScheme.tertiary
        else               -> MaterialTheme.colorScheme.onSurface
    }
    val indicator = when {
        afwijking > 0.001  -> "↑"
        afwijking < -0.001 -> "↓"
        else               -> "="
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text("bereik: $bereik", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(indicator, color = kleur, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(waarde, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = kleur)
        }
    }
}

private fun fmtTs(tsUtc: String): String = try {
    val instant = Instant.parse(tsUtc)
    DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(instant)
} catch (_: Exception) { tsUtc.take(10) }