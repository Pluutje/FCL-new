package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.Episode
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LogRow
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Kleurpalet consistent met EpisodeChart ──────────────────────────────────
private val MargePositief = Color(0xFF00C853)   // groen: ruim onder drempel (veilig)
private val MargeKrap     = Color(0xFFFFD600)   // geel: dicht bij drempel
private val MargeActief   = Color(0xFFFF5252)   // rood: drempel geraakt
private val ColorErrorNeg2 = Color(0x551A73E8)   // blauw transparant — pred te laag
private val ChartBg2      = Color(0xFF101010)
private val GridC         = Color(0x33FFFFFF)

/**
 * Scherm 2: Marges-dashboard
 * Toont per episode:
 * 1. Suppress/lockout reden-overzicht (tekstkaart)
 * 2. Marges-over-tijd grafiek voor de 3 meest relevante drempels
 * 3. Samenvatting van vroege vs late voorspellingsfout
 */
@Composable
fun MargesDashboardCard(episode: Episode) {
    val mgdl = app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.isMgdl(androidx.compose.ui.platform.LocalContext.current)
    val rows = episode.rows
        .filter { (it.minutesSinceMealStart ?: -1) >= 0 }
        .sortedBy { it.minutesSinceMealStart }

    if (rows.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Text(
                "Geen episode-data beschikbaar",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val actualPeak = episode.rows.maxOf { it.bg }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── 1. Suppress reden samenvatting ───────────────────────────────
        SuppressRedenKaart(rows)

        // ── 2. Marges over tijd ──────────────────────────────────────────
        MargesOverTijdKaart(rows)

        // ── 3. Voorspellingsfout samenvatting ────────────────────────────
        VoorspellingsKwaliteitKaart(rows, actualPeak)
    }
}

@Composable
private fun SuppressRedenKaart(rows: List<LogRow>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Rem-activaties", style = MaterialTheme.typography.titleMedium)

            // Suppress redenen
            val suppressCounts = rows
                .filter { it.suppressReason != "NONE" }
                .groupBy { it.suppressReason }
                .mapValues { it.value.size }

            val lockoutCounts = rows
                .filter { it.lockoutReason != "NONE" }
                .groupBy { it.lockoutReason }
                .mapValues { it.value.size }

            val commitBlockCounts = rows
                .filter { it.commitBlockReason != "NONE" }
                .groupBy { it.commitBlockReason }
                .mapValues { it.value.size }

            if (suppressCounts.isEmpty() && lockoutCounts.isEmpty() && commitBlockCounts.isEmpty()) {
                Text(
                    "Geen rem-activaties in deze episode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                if (suppressCounts.isNotEmpty()) {
                    Text(
                        "Suppress",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    suppressCounts.forEach { (reden, count) ->
                        RedenRij(reden, count, MargeActief)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (lockoutCounts.isNotEmpty()) {
                    Text(
                        "Lockout",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    lockoutCounts.forEach { (reden, count) ->
                        RedenRij(reden, count, Color(0xFFFF8C00))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (commitBlockCounts.isNotEmpty()) {
                    Text(
                        "Commit geblokkeerd",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    commitBlockCounts.forEach { (reden, count) ->
                        RedenRij(reden, count, Color(0xFFFFD600))
                    }
                }
            }

            // Extra context
            val earlyResets = rows.count { it.earlyResetThisCycle }
            val downtrendRows = rows.count { it.downtrendLocked }
            val sensorBlips = rows.count { it.sensorBlipActive }

            if (earlyResets > 0 || downtrendRows > 0 || sensorBlips > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Overige events",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (earlyResets > 0)
                    RedenRij("Early reset", earlyResets, Color(0xFF00B0FF))
                if (downtrendRows > 0)
                    RedenRij("Downtrend locked", downtrendRows, Color(0xFF78909C))
                if (sensorBlips > 0)
                    RedenRij("Sensor blip", sensorBlips, Color(0xFFFF8C00))
            }
        }
    }
}

@Composable
private fun RedenRij(reden: String, count: Int, kleur: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = kleur, radius = size.minDimension / 2)
            }
            Text(
                redenLabel(reden),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            "${count}×",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = kleur
        )
    }
}

private fun redenLabel(reden: String): String = when (reden) {
    "ABSORPTION"         -> "Absorptievenster"
    "PRE_COMMIT_TOP"     -> "Pre-commit plateau"
    "TAIL"               -> "Aflopende stijging (tail)"
    "SENSOR_BLIP"        -> "Sensor-artefact"
    "PEAK_IOB_BRAKE"     -> "Peak IOB-rem"
    "PEAK_IOB_BRAKE_HIGH"-> "Peak IOB-rem (hoog)"
    "DECEL_HIGH_IOB"     -> "Afremmen + hoge IOB"
    "Early reset"        -> "Early reset (momentum weg)"
    "Downtrend locked"   -> "Dalende trend geblokkeerd"
    "Sensor blip"        -> "Sensor-artefact"
    else                 -> reden
}

@Composable
private fun MargesOverTijdKaart(rows: List<LogRow>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Marges tot drempels over tijd", style = MaterialTheme.typography.titleMedium)
            Text(
                "Negatief = onder drempel (rem inactief) · Nul = drempel geraakt · Positief = rem actief",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            // Drie subgrafieken gestapeld
            MargeGrafiek(
                rows = rows,
                label = "IOB-marge tot rem (0.62)",
                values = rows.map { it.minutesSinceMealStart to it.iobMarginToBrake },
                yRange = -0.7 to 0.4
            )

            MargeGrafiek(
                rows = rows,
                label = "Pred-marge tot WATCHING (9.0)",
                values = rows.map { it.minutesSinceMealStart to it.predMarginToWatching },
                yRange = -6.0 to 2.0
            )

            MargeGrafiek(
                rows = rows,
                label = "Pred-marge tot doel (10.0)",
                values = rows.map { it.minutesSinceMealStart to it.predMarginToTarget },
                yRange = -7.0 to 1.0
            )
        }
    }
}

@Composable
private fun MargeGrafiek(
    rows: List<LogRow>,
    label: String,
    values: List<Pair<Int?, Double>>,
    yRange: Pair<Double, Double>
) {
    val valid = values.filter { (min, v) -> min != null && min >= 0 }
    if (valid.isEmpty()) return

    val xMax = valid.maxOf { (min, _) -> min!! }.toDouble().coerceAtLeast(60.0)
    val (yMin, yMax) = yRange

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            val w = size.width
            val h = size.height
            val lPad = 8f
            val rPad = 8f
            val tPad = 4f
            val bPad = 4f
            val plotW = w - lPad - rPad
            val plotH = h - tPad - bPad

            fun xOf(min: Int) = lPad + (min / xMax).toFloat() * plotW
            fun yOf(v: Double): Float {
                val frac = ((v - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
                return tPad + plotH * (1f - frac.toFloat())
            }

            // Achtergrond
            drawRect(color = ChartBg2, topLeft = Offset(0f, 0f), size = size)

            // Nul-lijn (de drempel zelf)
            val yZero = yOf(0.0)
            drawLine(
                color = Color(0x88FFFFFF),
                start = Offset(lPad, yZero),
                end = Offset(lPad + plotW, yZero),
                strokeWidth = 1.0f
            )

            // Vlakken: positief = rem actief (rood), negatief = rem inactief (blauw)
            for (i in 0 until valid.size - 1) {
                val (min0, v0) = valid[i]
                val (min1, v1) = valid[i + 1]
                val x0 = xOf(min0!!)
                val x1 = xOf(min1!!)
                val yV0 = yOf(v0)
                val yV1 = yOf(v1)

                val fillColor = if (v0 >= 0 && v1 >= 0) MargeActief.copy(alpha = 0.35f)
                else ColorErrorNeg2.copy(alpha = 0.25f)

                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(x0, yZero)
                path.lineTo(x0, yV0)
                path.lineTo(x1, yV1)
                path.lineTo(x1, yZero)
                path.close()
                drawPath(path, color = fillColor)
            }

            // Lijn
            val linePath = androidx.compose.ui.graphics.Path()
            var started = false
            valid.forEach { (min, v) ->
                val x = xOf(min!!)
                val y = yOf(v)
                if (!started) { linePath.moveTo(x, y); started = true }
                else linePath.lineTo(x, y)
            }
            drawPath(
                linePath,
                color = Color(0xCCFFFFFF),
                style = Stroke(width = 1.5f)
            )

            // Min/max labels
            val paint = android.graphics.Paint().apply {
                color = Color(0x88FFFFFF).toArgb(); textSize = 20f; isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f".format(yMax), lPad, tPad + 14f, paint
            )
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f".format(yMin), lPad, tPad + plotH, paint
            )
        }
    }
}

@Composable
private fun VoorspellingsKwaliteitKaart(rows: List<LogRow>, actualPeak: Double) {
    val mgdl = app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.isMgdl(androidx.compose.ui.platform.LocalContext.current)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Voorspellingskwaliteit", style = MaterialTheme.typography.titleMedium)

            val activeRows = rows.filter {
                (it.minutesSinceMealStart ?: -1) >= 0 &&
                    (it.predictedPeak ?: 0.0) > 0.0
            }

            if (activeRows.isEmpty()) {
                Text("Geen voorspellingsdata", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            // Vroeg (0-20 min) vs midden (20-40 min) vs laat (>40 min)
            val vroeg  = activeRows.filter { (it.minutesSinceMealStart ?: 0) in 0..20 }
            val midden = activeRows.filter { (it.minutesSinceMealStart ?: 0) in 21..40 }
            val laat   = activeRows.filter { (it.minutesSinceMealStart ?: 0) > 40 }

            fun fout(rijen: List<LogRow>): Double? {
                if (rijen.isEmpty()) return null
                return rijen.mapNotNull { it.predictedPeak }.average() - actualPeak
            }

            fun foutLabel(f: Double?): String {
                if (f == null) return "—"
                return if (f >= 0) "+${if (mgdl) "%.0f".format(f*18.0) else "%.2f".format(f)} ${app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.unitShort(mgdl)}" else "${if (mgdl) "%.0f".format(f*18.0) else "%.2f".format(f)} ${app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.unitShort(mgdl)}"
            }

            fun foutKleur(f: Double?): Color = when {
                f == null -> Color(0xCCFFFFFF)
                abs(f) <= 0.5 -> MargePositief
                abs(f) <= 1.2 -> MargeKrap
                else -> MargeActief
            }

            // Ballistic vs gecorrigeerd verschil
            val ballisticGem = activeRows.filter { it.predictedPeakBallistic > 0 }
                .map { it.predictedPeakBallistic }.average()
            val correctedGem = activeRows.mapNotNull { it.predictedPeak }.average()
            val iobCorrectie = ballisticGem - correctedGem

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricBlock("Werkelijke piek", app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.formatBg(actualPeak, mgdl))
                MetricBlock("Gem. voorspelling", app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.formatBg(correctedGem, mgdl))
                MetricBlock("IOB-correctie", "-${if (mgdl) "%.0f".format(iobCorrectie*18.0) else "%.2f".format(iobCorrectie)} ${app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.unitShort(mgdl)}")
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "Gemiddelde fout per tijdvenster",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val fVroeg  = fout(vroeg)
                val fMidden = fout(midden)
                val fLaat   = fout(laat)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("0–20 min", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(foutLabel(fVroeg), style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold, color = foutKleur(fVroeg))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("20–40 min", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(foutLabel(fMidden), style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold, color = foutKleur(fMidden))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(">40 min", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(foutLabel(fLaat), style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold, color = foutKleur(fLaat))
                }
            }

            // Floor impact
            val floorActief = rows.count { it.peakFloorActive && it.peakFloorValue > 0.01 }
            if (floorActief > 0) {
                val gemFloor = rows.filter { it.peakFloorActive && it.peakFloorValue > 0.01 }
                    .map { it.peakFloorValue }.average()
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Floor-correctie actief in $floorActief cycli " +
                        "(gem. +${if (mgdl) "%.0f".format(gemFloor*18.0) else "%.2f".format(gemFloor)} ${app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.unitShort(mgdl)}) — compenseert vroege onderschatting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            // h_eff en iobScale info
            val gemHeff = rows.filter { it.hEff > 0 }.map { it.hEff }.average().takeIf { !it.isNaN() }
            val gemIobScale = rows.filter { it.iobScaleUsed > 0 }.map { it.iobScaleUsed }.average().takeIf { !it.isNaN() }

            if (gemHeff != null) {
                Text(
                    "Gem. kijkhorizon: %.0f min  ·  Gem. IOB-schaalfactor: %.2f".format(
                        (gemHeff * 60), gemIobScale ?: 0.0
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    }
}