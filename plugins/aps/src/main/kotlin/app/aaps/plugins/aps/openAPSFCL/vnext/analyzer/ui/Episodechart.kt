package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

private val AapsGreen    = Color(0xFF00C853)
private val AapsYellow   = Color(0xFFFFD600)
private val AapsRed      = Color(0xFFD50000)
private val AapsBlue     = Color(0xFF00B0FF)   // IOB curve: lichtblauw
private val AapsDoseBlue = Color(0xFF1565C0)   // dosis staven: donkerblauw voor contrast

private val RangeBg   = Color(0x2200AA00)
private val HypoBg    = Color(0x22FF0000)
private val EpisodeBg = Color(0x2200FF00)

// Chart gebruikt altijd een donkere achtergrond — de gekleurde zones
// en lijnen zijn ontworpen voor dark-mode en zien er goed uit op donker
private val ChartBg = Color(0xFF101010)

@Composable
fun EpisodeChart(
    rows: List<LogRow>,
    episodeStart: Instant,
    episodeEnd: Instant,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(300.dp)
) {
    if (rows.isEmpty()) return

    val gridColor = Color(0x33FFFFFF)
    val labelColor = Color(0xCCFFFFFF)

    val sorted = rows.sortedBy { it.timestamp }
    val minTime = sorted.first().timestamp
    val maxTime = sorted.last().timestamp

    val rawMaxBg = sorted.maxOf { it.bg }
    val roundedBgMax = kotlin.math.ceil(rawMaxBg / 2.0) * 2.0
    val maxBg = roundedBgMax.coerceAtLeast(10.0)   // minimum 8 voor leesbaarheid, schaalt mee omhoog
    val minBg = 0.0

    val hasPredPeak = sorted.any { it.predictedPeak != null && it.predictedPeak > 0.0 }

    val rawMaxIob = sorted.maxOf { it.iob }
    val maxIob = max(1, kotlin.math.ceil(rawMaxIob).toInt() + 1).toDouble()
    val maxDose = max(0.1, sorted.maxOf { if (it.shouldDeliver) it.finalDose else 0.0 })

    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    val dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy").withZone(ZoneId.systemDefault())

    Canvas(modifier = modifier.padding(12.dp)) {

        val width = size.width
        val height = size.height

        val leftPad = 52f
        val rightPad = 48f
        val topPad = 20f
        val bottomPad = 36f

        val plotW = width - leftPad - rightPad
        val plotH = height - topPad - bottomPad

        val totalDurationMs = maxTime.toEpochMilli() - minTime.toEpochMilli()
        if (totalDurationMs <= 0) return@Canvas

        fun xOf(ts: Instant): Float {
            val frac = (ts.toEpochMilli() - minTime.toEpochMilli()).toFloat() / totalDurationMs
            return leftPad + frac * plotW
        }
        fun yBg(bg: Double) = topPad + plotH * (1.0 - (bg - minBg) / (maxBg - minBg)).toFloat()
        fun yIob(iob: Double) = topPad + plotH * (1.0 - (iob / maxIob)).toFloat()

        // Achtergrond
        drawRect(color = ChartBg, topLeft = Offset(0f, 0f), size = size)

        // TIR zone (3.9–10.0)
        val yTirTop = yBg(10.0)
        val yTirBot = yBg(3.9)
        drawRect(color = RangeBg, topLeft = Offset(leftPad, yTirTop), size = androidx.compose.ui.geometry.Size(plotW, yTirBot - yTirTop))

        // Hypo zone (<3.9)
        val yHypoTop = yBg(3.9)
        val yHypoBot = topPad + plotH
        drawRect(color = HypoBg, topLeft = Offset(leftPad, yHypoTop), size = androidx.compose.ui.geometry.Size(plotW, yHypoBot - yHypoTop))

        // Episode venster
        val xEpStart = xOf(episodeStart).coerceIn(leftPad, leftPad + plotW)
        val xEpEnd = xOf(episodeEnd).coerceIn(leftPad, leftPad + plotW)
        if (xEpEnd > xEpStart) {
            drawRect(color = EpisodeBg, topLeft = Offset(xEpStart, topPad), size = androidx.compose.ui.geometry.Size(xEpEnd - xEpStart, plotH))
        }

        // Grid-lijnen (BG) — dynamisch op basis van maxBg
        val bgGridStep = if (maxBg <= 10.0) 1.0 else 2.0
        val bgGridSteps = generateSequence(bgGridStep) { it + bgGridStep }
            .takeWhile { it <= maxBg }
            .toList()
        bgGridSteps.forEach { bgVal ->
            val y = yBg(bgVal)
            val isTarget10 = bgVal == 10.0   // doelgrens altijd markeren
            drawLine(
                color = if (isTarget10) Color(0x88FFD600) else gridColor,
                start = Offset(leftPad, y),
                end = Offset(leftPad + plotW, y),
                strokeWidth = if (isTarget10) 1.0f else 0.5f
            )
        }

        // IOB lijn
        val iobPath = Path()
        var iobStarted = false
        sorted.forEach { row ->
            val x = xOf(row.timestamp)
            val y = yIob(row.iob)
            if (!iobStarted) { iobPath.moveTo(x, y); iobStarted = true } else iobPath.lineTo(x, y)
        }
        drawPath(iobPath, color = AapsBlue.copy(alpha = 0.80f), style = Stroke(width = 1.5f))

        // Predicted peak stippellijn (oranje)
        if (hasPredPeak) {
            val predPaint = android.graphics.Paint().apply {
                color = Color(0xFFFF8C00).toArgb()
                strokeWidth = 2f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
            }
            val predPath = android.graphics.Path()
            var predStarted = false
            sorted.forEach { row ->
                val pp = row.predictedPeak ?: return@forEach
                if (pp <= 0.0) return@forEach
                val x = xOf(row.timestamp)
                val y = yBg(pp)
                if (!predStarted) { predPath.moveTo(x, y); predStarted = true }
                else predPath.lineTo(x, y)
            }
            drawContext.canvas.nativeCanvas.drawPath(predPath, predPaint)
        }

        // Dosis staven
        sorted.filter { it.shouldDeliver && it.finalDose > 0.0 }.forEach { row ->
            val x = xOf(row.timestamp)
            val barH = (row.finalDose.toFloat() / maxDose.toFloat()) * plotH * 0.35f
            val yTop = topPad + plotH - barH
            drawRect(
                color = AapsDoseBlue.copy(alpha = 0.90f),
                topLeft = Offset(x - 2f, yTop),
                size = androidx.compose.ui.geometry.Size(4f, barH)
            )
        }

        // BG lijn
        val bgPath = Path()
        var bgStarted = false
        sorted.forEach { row ->
            val x = xOf(row.timestamp)
            val y = yBg(row.bg)
            if (!bgStarted) { bgPath.moveTo(x, y); bgStarted = true } else bgPath.lineTo(x, y)
        }
        drawPath(bgPath, color = AapsGreen, style = Stroke(width = 2f))

        // BG punten gekleurd
        sorted.forEach { row ->
            val x = xOf(row.timestamp)
            val y = yBg(row.bg)
            val dotColor = when {
                row.bg < 3.9  -> AapsRed
                row.bg > 10.0 -> AapsYellow
                else          -> AapsGreen
            }
            drawCircle(color = dotColor, radius = 3f, center = Offset(x, y))
        }

        // Y-as labels (BG)
        val paint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 28f
            isAntiAlias = true
        }
        bgGridSteps.forEach { bgVal ->
            val y = yBg(bgVal)
            drawContext.canvas.nativeCanvas.drawText(
                "%.0f".format(bgVal), 4f, y + 9f, paint
            )
        }
        // Markeer de piekwaarde als die boven 10 uitkomt
        if (rawMaxBg > 10.1) {
            val peakPaint = android.graphics.Paint().apply {
                color = Color(0xCCFFD600).toArgb()
                textSize = 26f
                isAntiAlias = true
            }
            val y = yBg(rawMaxBg)
            drawContext.canvas.nativeCanvas.drawText(
                "▲%.1f".format(rawMaxBg), 4f, y + 9f, peakPaint
            )
        }

        // IOB Y-as (rechts)
        val iobPaint = android.graphics.Paint().apply {
            color = AapsBlue.copy(alpha = 0.8f).toArgb()
            textSize = 26f
            isAntiAlias = true
        }
        listOf(0.0, maxIob / 2, maxIob).forEach { iobVal ->
            val y = yIob(iobVal)
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f".format(iobVal), leftPad + plotW + 4f, y + 8f, iobPaint
            )
        }

        // X-as tijdlabels
        val timePaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 26f
            isAntiAlias = true
        }
        val numLabels = 5
        for (i in 0..numLabels) {
            val frac = i.toFloat() / numLabels
            val ts = Instant.ofEpochMilli((minTime.toEpochMilli() + frac * totalDurationMs).toLong())
            val x = leftPad + frac * plotW
            val label = timeFormatter.format(ts)
            drawContext.canvas.nativeCanvas.drawText(label, x - 20f, topPad + plotH + 28f, timePaint)
        }
    }
}