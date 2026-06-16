package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpScore
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpScoreCalculator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * CGP Scorekaart met:
 *  1. PGR-totaalscore + label
 *  2. Vijf deelmetrieken met referentie
 *  3. Pentagon (radar) op basis van 14-daags gemiddelde
 *  4. PGR-trendlijn: dagelijkse punten + 3-daags voortschrijdend gemiddelde
 *
 * Methode: Vigersky 2018 (zelfde als AAPS Statistics).
 * Pentagon kleiner = beter; PGR lager = beter.
 */
@Composable
fun CgpScoreKaart(context: Context) {
    val scores14d  = remember { CgpHistory.get14dScores(context) }
    val scores24h  = remember { CgpHistory.get24hScores(context) }
    val rollingAvg = remember { CgpHistory.getRollingAverage14d(context) }

    // Bovenste blok toont het 14-daags voortschrijdend gemiddelde
    // (laatste waarde van de rollingAvg-reeks) — consistent met de lijn
    // in de grafiek en vergelijkbaar met AAPS Statistics.
    // De parametertabel en het pentagon zijn gebaseerd op het gemiddelde
    // van de laatste 14 dagpunten.
    val window14 = scores14d.takeLast(14)
    val pgr14d   = rollingAvg.lastOrNull() ?: scores14d.lastOrNull()?.pgr
    val prev14d  = rollingAvg.dropLast(1).lastOrNull()

    // Synthetische CgpScore op basis van 14-daags gemiddelde parameters
    val display: CgpScore? = if (window14.isNotEmpty() && pgr14d != null) {
        CgpScore(
            tsUtc            = window14.last().tsUtc,
            torPct           = window14.map { it.torPct   }.average(),
            cvPct            = window14.map { it.cvPct    }.average(),
            hypoPct          = window14.map { it.hypoPct  }.average(),
            hyperPct         = window14.map { it.hyperPct }.average(),
            meanMgdl         = window14.map { it.meanMgdl }.average(),
            pgr              = pgr14d,
            weakestDimension = window14.last().weakestDimension
        )
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("📊 CGP Score — 14 dagen",
                         style = MaterialTheme.typography.bodyMedium,
                         fontWeight = FontWeight.SemiBold)
                    Text("Methode: Vigersky 2018",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (display != null) {
                    val arrow = CgpScoreCalculator.trendArrow(display.pgr, prev14d)
                    var toonInfo by remember { mutableStateOf(false) }

                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.clickable { toonInfo = true }
                    ) {
                        Text("PGR ${"%.1f".format(display.pgr)}$arrow",
                             style = MaterialTheme.typography.titleLarge,
                             fontWeight = FontWeight.Bold,
                             color = pgrKleur(display.pgr))
                        Text(display.pgrLabel,
                             style = MaterialTheme.typography.labelSmall,
                             color = pgrKleur(display.pgr))
                        Text("ⓘ tap for info",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                    }

                    if (toonInfo) {
                        PgrInfoDialog(pgr = display.pgr, onDismiss = { toonInfo = false })
                    }
                } else {
                    Text("—", style = MaterialTheme.typography.titleLarge,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (display == null) {
                Text("Score beschikbaar na de eerste middernacht-berekening " +
                     "(minimaal 1 dag data nodig).",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            // ── Metrieken-tabel met bijdragepercentage ────────────────────
            val bijdragen = CgpScoreCalculator.contributionPct(
                torPct   = display.torPct,
                cvPct    = display.cvPct,
                hypoPct  = display.hypoPct,
                hyperPct = display.hyperPct,
                meanMgdl = display.meanMgdl
            )
            val dimensies = listOf("TOR","CV","HYPO","HYPER","MEAN")

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                // Tabelkop
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Parameter",
                         modifier = Modifier.weight(2.2f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold)
                    Text("Ref",
                         modifier = Modifier.weight(1.2f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold,
                         textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Actueel",
                         modifier = Modifier.weight(1.5f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold,
                         textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Bijdrage",
                         modifier = Modifier.weight(1.5f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold,
                         textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                dimensies.forEach { dim ->
                    val pct  = bijdragen[dim] ?: 0
                    val kleur = when (CgpScoreCalculator.contributionColor(pct)) {
                        "GREEN"  -> androidx.compose.ui.graphics.Color(0xFF2E7D32) // donkergroen
                        "YELLOW" -> androidx.compose.ui.graphics.Color(0xFFF9A825) // amber
                        "ORANGE" -> androidx.compose.ui.graphics.Color(0xFFE65100) // donkeroranje
                        else     -> MaterialTheme.colorScheme.error                // rood
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Parameternaam
                        Text(
                            CgpScoreCalculator.dimensionLabel(dim),
                            modifier = Modifier.weight(2.2f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Referentiewaarde
                        Text(
                            CgpScoreCalculator.REFERENCE[dim] ?: "",
                            modifier = Modifier.weight(1.2f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                        // Actuele waarde
                        Text(
                            CgpScoreCalculator.dimensionValue(display, dim),
                            modifier = Modifier.weight(1.5f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                        // Bijdrage % met kleurcode
                        Text(
                            "$pct%",
                            modifier = Modifier.weight(1.5f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = kleur,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            // ── Pentagon op basis van 14-daags gemiddelde ──────────────────
            val gemiddeld14d = gemiddeld14Daags(scores14d)
            if (gemiddeld14d != null) {
                Text("Pentagon (14-daags gemiddelde)",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)

                val refKleur   = MaterialTheme.colorScheme.primary.copy(green = 0.7f)
                val patKleur   = MaterialTheme.colorScheme.primary
                val gridKleur  = MaterialTheme.colorScheme.outlineVariant
                val asKleur    = MaterialTheme.colorScheme.outline
                val labelKleur = MaterialTheme.colorScheme.onSurface
                val textMeasurer = rememberTextMeasurer()

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    tekenPentagon(
                        gemiddeld14d  = gemiddeld14d,
                        latest        = display,
                        refKleur      = refKleur,
                        patKleur      = patKleur,
                        gridKleur     = gridKleur,
                        asKleur       = asKleur,
                        labelKleur    = labelKleur,
                        textMeasurer  = textMeasurer
                    )
                }

                // Legenda
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendaDot(color = refKleur, tekst = "Referentie (gezond)")
                    Spacer(Modifier.width(20.dp))
                    LegendaDot(color = patKleur, tekst = "14-daags gemiddelde")
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            // ── PGR trendlijn: dagpunten + 14-daags voortschrijdend gemiddelde ──
            if (scores14d.size >= 2) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                Text("PGR per dag  |  lijn = 14-daags gemiddelde",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                PgrTrendlijn(
                    scores14d  = scores14d,
                    scores24h  = scores24h,
                    rollingAvg = rollingAvg,
                    modifier   = Modifier.fillMaxWidth().height(80.dp)
                )
            }
        }
    }
}

// ── Pentagon tekenen ─────────────────────────────────────────────────────

private fun DrawScope.tekenPentagon(
    gemiddeld14d: List<Double>,
    latest: CgpScore,
    refKleur: Color, patKleur: Color,
    gridKleur: Color, asKleur: Color,
    labelKleur: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(cx, cy) * 0.72f

    val BASELINE_OFFSET = 0.18
    fun norm(v: Double, max: Double) =
        BASELINE_OFFSET + (1.0 - BASELINE_OFFSET) * (v / max).coerceIn(0.0, 1.0)

    val refValues = listOf(
        norm(0.0,  100.0), norm(17.0,  60.0), norm(0.0,  20.0),
        norm(0.0,   80.0), norm(90.0, 300.0)
    )
    val patValues = gemiddeld14d

    fun axisAngle(i: Int): Double = (-90.0 + i * 72.0) * PI / 180.0

    // Grid-pentagons
    for (level in 1..4) {
        val fr = level / 4f
        val path = Path()
        for (i in 0..4) {
            val a = axisAngle(i)
            val x = cx + maxR * fr * cos(a).toFloat()
            val y = cy + maxR * fr * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, gridKleur, style = Stroke(1f))
    }

    // As-lijnen
    for (i in 0..4) {
        val a = axisAngle(i)
        drawLine(asKleur,
            start = Offset(cx, cy),
            end = Offset(cx + maxR * cos(a).toFloat(), cy + maxR * sin(a).toFloat()),
            strokeWidth = 1f)
    }

    // Referentie-pentagon
    drawDataPentagon(cx, cy, maxR, refValues, refKleur.copy(alpha = 0.20f), refKleur, 2f)

    // Patiënt-pentagon
    drawDataPentagon(cx, cy, maxR, patValues, patKleur.copy(alpha = 0.25f), patKleur, 2.5f)

    // As-labels: naam + actuele waarde in grijs op tweede regel
    val asLabels = listOf("TOR", "%CV", "Hypo%", "Hyper%", "Mean")
    val asWaarden = listOf(
        "${"%.0f".format(latest.torPct)}%",
        "${"%.1f".format(latest.cvPct)}%",
        "${"%.1f".format(latest.hypoPct)}%",
        "${"%.1f".format(latest.hyperPct)}%",
        "${"%.1f".format(latest.meanMmol)} mmol"
    )
    val labelStijl = TextStyle(fontSize = 10.sp, color = labelKleur,
                               textAlign = TextAlign.Center)
    val waardeStijl = TextStyle(fontSize = 9.sp,
                                color = labelKleur.copy(alpha = 0.55f),
                                textAlign = TextAlign.Center)

    for (i in 0..4) {
        val a = axisAngle(i)
        val lr = maxR * 1.20f
        val lx = cx + lr * cos(a).toFloat()
        val ly = cy + lr * sin(a).toFloat()

        val labelTekst = asLabels[i]
        val waardeTekst = asWaarden[i]

        val mLabel  = textMeasurer.measure(labelTekst, labelStijl)
        val mWaarde = textMeasurer.measure(waardeTekst, waardeStijl)
        val totH = mLabel.size.height + mWaarde.size.height + 1

        val topY = ly - totH / 2f
        drawText(textMeasurer, labelTekst,
            topLeft = Offset(lx - mLabel.size.width / 2f, topY),
            style = labelStijl)
        drawText(textMeasurer, waardeTekst,
            topLeft = Offset(lx - mWaarde.size.width / 2f, topY + mLabel.size.height + 1),
            style = waardeStijl)
    }
}

private fun DrawScope.drawDataPentagon(
    cx: Float, cy: Float, maxR: Float,
    values: List<Double>,
    fillColor: Color, strokeColor: Color, strokeWidth: Float
) {
    val path = Path()
    for (i in values.indices) {
        val a = (-90.0 + i * 72.0) * PI / 180.0
        val r = maxR * values[i].toFloat()
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, fillColor, style = Fill)
    drawPath(path, strokeColor, style = Stroke(strokeWidth))
}

// ── PGR Trendlijn: dagelijkse punten + voortschrijdend gemiddelde ─────────

@Composable
private fun PgrTrendlijn(
    scores14d: List<CgpScore>,
    scores24h: List<CgpScore>,
    rollingAvg: List<Double?>,
    modifier: Modifier = Modifier
) {
    val lijnKleur    = MaterialTheme.colorScheme.secondary
    val gridKleur    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val labelKleur   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    // Lijn gebruikt 14d-reeks als x-as referentie voor uitlijning
    // Stippen zijn 24h-reeks (puur die dag), uitgelijnd op dezelfde x-as
    val scores14     = scores14d.map { it.pgr }
    // 24h-stippen: uitlijnen op datum zodat ze op de juiste positie vallen
    // Bouw een map van datum → 24h PGR
    val pgr24hByDate = scores24h.associate { s ->
        try {
            java.time.Instant.parse(s.tsUtc)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate().toString() to s.pgr
        } catch (_: Exception) { "" to s.pgr }
    }
    // Match 24h-stippen op de x-posities van de 14d-reeks
    val stippen = scores14d.map { s ->
        try {
            val date = java.time.Instant.parse(s.tsUtc)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate().toString()
            pgr24hByDate[date]
        } catch (_: Exception) { null }
    }
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val labelW = 32f
        val w = size.width - labelW
        val h = size.height
        val n = scores14.size
        val yMin = 1.0
        // Y-schaal op basis van beide reeksen
        val allPgr = scores14 + stippen.filterNotNull()
        val rawMax = allPgr.maxOrNull() ?: 3.0
        val yMax = kotlin.math.ceil(rawMax).coerceAtLeast(2.0)

        fun xOf(i: Int) = labelW + if (n == 1) w / 2 else (i.toFloat() / (n - 1)) * w
        fun yOf(v: Double) = h - ((v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * h).toFloat()

        // ── Kleurgradiënt achtergrond: groen (laag) → rood (hoog) ────────
        // Verdeel de grafiekhoogte in horizontale banden per kleurzone
        // PGR: ≤2 groen, 2-3 geel, 3-4 oranje, >4 rood
        data class Zone(val pgr: Double, val color: androidx.compose.ui.graphics.Color)
        val zones = listOf(
            Zone(1.0, androidx.compose.ui.graphics.Color(0xFF2E7D32)),  // donkergroen
            Zone(2.0, androidx.compose.ui.graphics.Color(0xFF7CB342)),  // lichtgroen
            Zone(3.0, androidx.compose.ui.graphics.Color(0xFFF9A825)),  // amber
            Zone(4.0, androidx.compose.ui.graphics.Color(0xFFE65100)),  // oranje
            Zone(yMax,androidx.compose.ui.graphics.Color(0xFFB71C1C))   // rood
        )
        zones.forEachIndexed { i, zone ->
            val prevPgr = if (i == 0) yMin else zones[i-1].pgr
            val bandTop = yOf(zone.pgr.coerceAtMost(yMax))
            val bandBot = yOf(prevPgr.coerceAtLeast(yMin))
            if (bandBot > bandTop) {
                drawRect(
                    color = zone.color.copy(alpha = 0.12f),
                    topLeft = Offset(labelW, bandTop),
                    size = androidx.compose.ui.geometry.Size(w, bandBot - bandTop)
                )
            }
        }

        val labelStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 9.sp, color = labelKleur
        )

        // Rasterlijnen en Y-as labels bij elk geheel getal
        val gridLevels = (yMin.toInt()..yMax.toInt()).map { it.toDouble() }
        gridLevels.forEach { level ->
            val y = yOf(level)
            val is3 = level == 3.0
            drawLine(
                if (is3) gridKleur.copy(alpha = 0.35f) else gridKleur,
                Offset(labelW, y), Offset(labelW + w, y),
                if (is3) 1.5f else 0.8f
            )
            val measured = textMeasurer.measure(level.toInt().toString(), labelStyle)
            drawText(textMeasurer, level.toInt().toString(),
                topLeft = Offset(0f, y - measured.size.height / 2f),
                style = labelStyle)
        }

        // 14-daags voortschrijdend gemiddelde als lijn
        val maPath = Path(); var firstMa = true
        rollingAvg.forEachIndexed { i, ma ->
            if (ma != null) {
                val x = xOf(i); val y = yOf(ma)
                if (firstMa) { maPath.moveTo(x, y); firstMa = false }
                else maPath.lineTo(x, y)
            }
        }
        drawPath(maPath, lijnKleur, style = Stroke(width = 2.5f))

        stippen.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = xOf(i); val y = yOf(v)
            val puntZoneKleur = when {
                v <= 2.0 -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                v <= 3.0 -> androidx.compose.ui.graphics.Color(0xFFF9A825)
                v <= 4.0 -> androidx.compose.ui.graphics.Color(0xFFE65100)
                else     -> androidx.compose.ui.graphics.Color(0xFFB71C1C)
            }
            drawCircle(puntZoneKleur, radius = 5f, center = Offset(x, y))
            drawCircle(androidx.compose.ui.graphics.Color.White, radius = 2.5f, center = Offset(x, y))
        }
    }
}


@Composable
private fun LegendaDot(color: Color, tekst: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(tekst, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun pgrKleur(pgr: Double) = when {
    pgr <= 3.0 -> MaterialTheme.colorScheme.primary
    pgr <= 4.0 -> MaterialTheme.colorScheme.tertiary
    else       -> MaterialTheme.colorScheme.error
}

/**
 * Informatieve popup over de CGP/PGR-methode.
 * Bewust in het Engels — methodetoelichting hoeft niet vertaald.
 */
@Composable
private fun PgrInfoDialog(pgr: Double, onDismiss: () -> Unit) {
    val riskLabel = when {
        pgr <= 2.0 -> "very low risk"
        pgr <= 3.0 -> "low risk"
        pgr <= 4.0 -> "moderate risk"
        pgr <= 4.5 -> "high risk"
        else       -> "extremely high risk"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Prognostic Glycemic Risk (PGR)",
                 style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your current PGR is ${"%.1f".format(pgr)} ($riskLabel).",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "What is PGR?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "PGR (Prognostic Glycemic Risk) is a composite score derived from " +
                    "the Comprehensive Glucose Pentagon (CGP), introduced by Vigersky & McMahon (2018). " +
                    "It combines five CGM-derived metrics into a single number that reflects " +
                    "both short-term hypoglycemia risk and long-term complication risk.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "The five metrics",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "• TOR — Time Out of Range (< 3.9 or > 10.0 mmol/L)\n" +
                    "• %CV — Coefficient of Variation (SD ÷ mean glucose)\n" +
                    "• Hypo% — Time below 3.9 mmol/L\n" +
                    "• Hyper% — Time above 10.0 mmol/L\n" +
                    "• Mean — Average glucose level",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "How is it calculated?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Each metric is normalized to a 0.18–1.0 scale. The PGR equals " +
                    "the area of the patient's pentagon divided by the area of a reference " +
                    "pentagon representing a person without diabetes. " +
                    "A smaller pentagon means better control — so a lower PGR is better.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Risk thresholds (Vigersky 2018)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "≤ 2.0  Very low risk\n" +
                    "≤ 3.0  Low risk\n" +
                    "≤ 4.0  Moderate risk\n" +
                    "≤ 4.5  High risk\n" +
                    "> 4.5  Extremely high risk",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    "The score shown here is calculated from the FCLvNext cycle log " +
                    "over the past 14 days and is updated daily at midnight. " +
                    "It uses the same method as AAPS Statistics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
 /* voor de pentagon-grafiek. Retourneert genormaliseerde waarden [0.18-1.0].
 */
private fun gemiddeld14Daags(history: List<CgpScore>): List<Double>? {
    if (history.isEmpty()) return null

    val BASELINE_OFFSET = 0.18
    fun norm(v: Double, max: Double) =
        BASELINE_OFFSET + (1.0 - BASELINE_OFFSET) * (v / max).coerceIn(0.0, 1.0)

    val avgTor   = history.map { it.torPct   }.average()
    val avgCv    = history.map { it.cvPct    }.average()
    val avgHypo  = history.map { it.hypoPct  }.average()
    val avgHyper = history.map { it.hyperPct }.average()
    val avgMean  = history.map { it.meanMgdl }.average()

    return listOf(
        norm(avgTor,    100.0),
        norm(avgCv,      60.0),
        norm(avgHypo,    20.0),
        norm(avgHyper,   80.0),
        norm(avgMean,   300.0)
    )
}
