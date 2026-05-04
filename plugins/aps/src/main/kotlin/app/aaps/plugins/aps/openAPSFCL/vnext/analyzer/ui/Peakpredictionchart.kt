package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LogRow
import kotlin.math.abs

// Kleuren consistent met EpisodeChart
private val ColorActualPeak  = Color(0xFF00C853)   // groen — werkelijke BG
private val ColorPredPeak    = Color(0xFFFF8C00)   // oranje — gecorrigeerde voorspelling
private val ColorBallistic   = Color(0xFF00B0FF)   // blauw — ballistisch (vóór IOB)
private val ColorPeakLine    = Color(0xFFFFD600)   // geel — werkelijke piek referentie
private val ColorErrorPos    = Color(0x55FF4444)   // rood transparant — overschatting
private val ColorErrorNeg    = Color(0x551A73E8)   // blauw transparant — onderschatting
private val ChartBg          = Color(0xFF101010)
private val GridColor        = Color(0x33FFFFFF)
private val LabelColor       = Color(0xCCFFFFFF)

/**
 * Toont per episode:
 * - werkelijke BG-lijn (groen)
 * - predictedPeak over de tijd (oranje stippellijn)
 * - predictedPeakBallistic over de tijd (blauw stippellijn)
 * - gele horizontale lijn op werkelijke piektop
 * - foutzone (rood/blauw vlak tussen pred en werkelijk)
 */
@Composable
fun PeakPredictionChart(
    episodeRows: List<LogRow>,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(280.dp)
) {
    if (episodeRows.isEmpty()) return

    // Alleen actieve rijen met zinvolle pieksschatting
    val active = episodeRows
        .filter { (it.minutesSinceMealStart ?: -1) >= 0 }
        .sortedBy { it.minutesSinceMealStart }

    if (active.isEmpty()) return

    val actualPeak = episodeRows.maxOf { it.bg }

    // Y-domein: van net onder min BG tot boven max van ballistic
    val allPredicted = active.mapNotNull { it.predictedPeak }.filter { it > 0 }
    val allBallistic = active.map { it.predictedPeakBallistic }.filter { it > 0 }
    val allBg = active.map { it.bg }

    val yMax = (listOf(allPredicted, allBallistic, allBg).flatten().maxOrNull() ?: 12.0)
        .let { kotlin.math.ceil(it / 2.0) * 2.0 }
        .coerceAtLeast(10.0)
    val yMin = 0.0

    // X-domein: minuten sinds maaltijdstart
    val xMax = (active.maxOf { it.minutesSinceMealStart ?: 0 }).toDouble().coerceAtLeast(60.0)
    val xMin = 0.0

    Canvas(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        val w = size.width
        val h = size.height

        val lPad = 44f
        val rPad = 12f
        val tPad = 16f
        val bPad = 32f
        val plotW = w - lPad - rPad
        val plotH = h - tPad - bPad

        fun xOf(min: Int) = lPad + ((min - xMin) / (xMax - xMin)).toFloat() * plotW
        fun yOf(v: Double) = tPad + plotH * (1f - ((v - yMin) / (yMax - yMin)).toFloat())

        // Achtergrond
        drawRect(color = ChartBg, topLeft = Offset(0f, 0f), size = size)

        // Grid
        val gridSteps = generateSequence(2.0) { it + 2.0 }.takeWhile { it <= yMax }.toList()
        gridSteps.forEach { v ->
            val y = yOf(v)
            val is10 = v == 10.0
            drawLine(
                color = if (is10) Color(0x88FFD600) else GridColor,
                start = Offset(lPad, y),
                end = Offset(lPad + plotW, y),
                strokeWidth = if (is10) 1.2f else 0.5f
            )
        }

        // Werkelijke piek — horizontale referentielijn
        val yPeakLine = yOf(actualPeak)
        drawLine(
            color = ColorPeakLine.copy(alpha = 0.7f),
            start = Offset(lPad, yPeakLine),
            end = Offset(lPad + plotW, yPeakLine),
            strokeWidth = 1.5f
        )

        // Foutzone: vlak tussen predictedPeak en werkelijke piek
        // Rood waar pred > actual (overschatting), blauw waar pred < actual (onderschatting)
        for (i in 0 until active.size - 1) {
            val r0 = active[i]
            val r1 = active[i + 1]
            val p0 = r0.predictedPeak ?: continue
            val p1 = r1.predictedPeak ?: continue
            if (p0 <= 0 || p1 <= 0) continue

            val x0 = xOf(r0.minutesSinceMealStart ?: continue)
            val x1 = xOf(r1.minutesSinceMealStart ?: continue)
            val yP0 = yOf(p0)
            val yP1 = yOf(p1)
            val yA = yPeakLine

            val isOver0 = p0 > actualPeak
            val isOver1 = p1 > actualPeak
            val zoneColor = if (isOver0 && isOver1) ColorErrorPos else ColorErrorNeg

            val path = Path()
            path.moveTo(x0, yA)
            path.lineTo(x0, yP0)
            path.lineTo(x1, yP1)
            path.lineTo(x1, yA)
            path.close()
            drawPath(path, color = zoneColor)
        }

        // Ballistic stippellijn (blauw)
        val ballisticPaint = android.graphics.Paint().apply {
            color = ColorBallistic.copy(alpha = 0.65f).toArgb()
            strokeWidth = 2f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 5f), 0f)
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
        }
        val ballisticPath = android.graphics.Path()
        var balStarted = false
        active.forEach { r ->
            val b = r.predictedPeakBallistic
            if (b <= 0) return@forEach
            val x = xOf(r.minutesSinceMealStart ?: return@forEach)
            val y = yOf(b)
            if (!balStarted) { ballisticPath.moveTo(x, y); balStarted = true }
            else ballisticPath.lineTo(x, y)
        }
        drawContext.canvas.nativeCanvas.drawPath(ballisticPath, ballisticPaint)

        // Gecorrigeerde predicted peak (oranje stippellijn)
        val predPaint = android.graphics.Paint().apply {
            color = ColorPredPeak.toArgb()
            strokeWidth = 2.5f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 5f), 0f)
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
        }
        val predPath = android.graphics.Path()
        var predStarted = false
        active.forEach { r ->
            val p = r.predictedPeak ?: return@forEach
            if (p <= 0) return@forEach
            val x = xOf(r.minutesSinceMealStart ?: return@forEach)
            val y = yOf(p)
            if (!predStarted) { predPath.moveTo(x, y); predStarted = true }
            else predPath.lineTo(x, y)
        }
        drawContext.canvas.nativeCanvas.drawPath(predPath, predPaint)

        // Werkelijke BG-lijn (groen)
        val bgPath = Path()
        var bgStarted = false
        active.forEach { r ->
            val x = xOf(r.minutesSinceMealStart ?: return@forEach)
            val y = yOf(r.bg)
            if (!bgStarted) { bgPath.moveTo(x, y); bgStarted = true }
            else bgPath.lineTo(x, y)
        }
        drawPath(bgPath, color = ColorActualPeak, style = Stroke(width = 2f))

        // BG punten
        active.forEach { r ->
            val x = xOf(r.minutesSinceMealStart ?: return@forEach)
            val y = yOf(r.bg)
            drawCircle(
                color = if (r.bg > 10.0) ColorPeakLine else ColorActualPeak,
                radius = 2.5f,
                center = Offset(x, y)
            )
        }

        // Y-as labels
        val labelPaint = android.graphics.Paint().apply {
            color = LabelColor.toArgb(); textSize = 26f; isAntiAlias = true
        }
        gridSteps.forEach { v ->
            drawContext.canvas.nativeCanvas.drawText(
                "%.0f".format(v), 4f, yOf(v) + 9f, labelPaint
            )
        }

        // Werkelijke pieklabel
        val peakLabelPaint = android.graphics.Paint().apply {
            color = ColorPeakLine.toArgb(); textSize = 24f; isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(
            "▶ %.1f".format(actualPeak), lPad + plotW + 2f, yPeakLine + 8f, peakLabelPaint
        )

        // X-as labels (minuten)
        val xLabelPaint = android.graphics.Paint().apply {
            color = LabelColor.toArgb(); textSize = 24f; isAntiAlias = true
        }
        val xSteps = listOf(0, 15, 30, 45, 60, 90, 120).filter { it <= xMax }
        xSteps.forEach { min ->
            val x = xOf(min)
            if (x >= lPad && x <= lPad + plotW) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${min}'", x - 10f, h - 6f, xLabelPaint
                )
                drawLine(
                    color = GridColor,
                    start = Offset(x, tPad),
                    end = Offset(x, tPad + plotH),
                    strokeWidth = 0.4f
                )
            }
        }
    }
}

/**
 * Legenda voor de PeakPredictionChart
 */
@Composable
fun PeakPredictionLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LegendDot("Werkelijk BG", ColorActualPeak)
        LegendDot("Pred (IOB-correct.)", ColorPredPeak)
        LegendDot("Ballistisch", ColorBallistic)
        LegendDot("Werkelijke piek", ColorPeakLine)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .padding(0.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = color, radius = size.minDimension / 2)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}