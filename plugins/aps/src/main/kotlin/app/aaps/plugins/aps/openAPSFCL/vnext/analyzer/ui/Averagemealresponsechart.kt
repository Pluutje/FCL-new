package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.Episode
import kotlin.math.max
import kotlin.math.min

@Composable
fun AverageMealResponseChart(
    episodes: List<Episode>
) {
    Column {

        Text(
            "Average Meal Response",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {

            if (episodes.isEmpty()) return@Canvas

            val width = size.width
            val height = size.height

            // ----------------------------
            // Plot layout (marges)
            // ----------------------------
            val leftPad = 54.dp.toPx()
            val rightPad = 14.dp.toPx()
            val topPad = 10.dp.toPx()
            val bottomPad = 42.dp.toPx()   // ruimte voor x-ticks
            val xTitlePad = 22.dp.toPx()   // extra ruimte voor x-titel onder ticks

            val plotLeft = leftPad
            val plotTop = topPad
            val plotRight = width - rightPad
            val plotBottom = height - bottomPad

            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            // ----------------------------
            // Data domain
            // ----------------------------
            val maxTime = 180f
            val minDelta = -3f
            val maxDelta = 6f

            fun x(t: Float) = plotLeft + (t / maxTime) * plotW
            fun y(v: Float) = plotTop + (1f - ((v - minDelta) / (maxDelta - minDelta))) * plotH

            // ----------------------------
            // Paints (maak ze 1x)
            // ----------------------------
            val axisPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(190, 170, 170, 170)
                strokeWidth = 2f
                isAntiAlias = true
            }
            val tickPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(180, 190, 190, 190)
                textSize = 26f
                isAntiAlias = true
            }
            val xTickPaint = android.graphics.Paint(tickPaint).apply {
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val yTickPaint = android.graphics.Paint(tickPaint).apply {
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            val titlePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(210, 210, 210, 210)
                textSize = 28f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val yTitlePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(210, 210, 210, 210)
                textSize = 28f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val legendPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(160, 200, 200, 200)
                textSize = 24f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.LEFT
            }

            // ----------------------------
            // Grid / axes
            // ----------------------------
            val gridColor = Color(0x22FFFFFF) // iets zichtbaarder op dark
            val axisColor = Color(0x66FFFFFF)

            // grid horizontaal
            for (v in -3..6 step 1) {
                val yt = y(v.toFloat())
                drawLine(
                    color = gridColor,
                    start = Offset(plotLeft, yt),
                    end = Offset(plotRight, yt),
                    strokeWidth = 1f
                )
            }

            // grid verticaal
            for (t in 0..180 step 30) {
                val xt = x(t.toFloat())
                drawLine(
                    color = gridColor,
                    start = Offset(xt, plotTop),
                    end = Offset(xt, plotBottom),
                    strokeWidth = 1f
                )
            }

            // assen (x en y)
            drawLine(
                color = axisColor,
                start = Offset(plotLeft, plotBottom),
                end = Offset(plotRight, plotBottom),
                strokeWidth = 2f
            )
            drawLine(
                color = axisColor,
                start = Offset(plotLeft, plotTop),
                end = Offset(plotLeft, plotBottom),
                strokeWidth = 2f
            )

            // ----------------------------
            // Ticks + labels
            // ----------------------------
            // X ticks (0..180)
            for (t in 0..180 step 30) {
                val xt = x(t.toFloat())
                drawLine(
                    color = axisColor,
                    start = Offset(xt, plotBottom),
                    end = Offset(xt, plotBottom + 10f),
                    strokeWidth = 2f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "$t",
                    xt,
                    plotBottom + 30f,  // <-- dit zit nu netjes onder de as, niet “weggevallen”
                    xTickPaint
                )
            }

            // Y ticks (-3..6)
            for (v in -3..6 step 1) {
                val yt = y(v.toFloat())
                drawLine(
                    color = axisColor,
                    start = Offset(plotLeft - 10f, yt),
                    end = Offset(plotLeft, yt),
                    strokeWidth = 2f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "$v",
                    plotLeft - 14f,
                    yt + 9f,
                    yTickPaint
                )
            }

            // X-axis title (onder ticks)
            drawContext.canvas.nativeCanvas.drawText(
                "Time since meal (minutes)",
                (plotLeft + plotRight) / 2f,
                plotBottom + 30f + xTitlePad,
                titlePaint
            )

            // Y-axis title (links, verticaal)
            drawContext.canvas.nativeCanvas.save()
            val yTitleX = 16.dp.toPx()
            val yTitleY = (plotTop + plotBottom) / 2f
            drawContext.canvas.nativeCanvas.rotate(-90f, yTitleX, yTitleY)
            drawContext.canvas.nativeCanvas.drawText(
                "ΔBG (mmol/L)",
                yTitleX,
                yTitleY,
                yTitlePaint
            )
            drawContext.canvas.nativeCanvas.restore()

            // ----------------------------
            // Helper: quantiles
            // ----------------------------
            fun quantile(sorted: List<Float>, q: Float): Float {
                if (sorted.isEmpty()) return Float.NaN
                val n = sorted.size
                if (n == 1) return sorted[0]
                val pos = q * (n - 1)
                val i0 = pos.toInt()
                val i1 = (i0 + 1).coerceAtMost(n - 1)
                val frac = pos - i0
                return sorted[i0] * (1f - frac) + sorted[i1] * frac
            }

            // ----------------------------
            // Collect binned deltas
            // ----------------------------
            val timeBins = (0..180 step 5).toList()

            val medians = Array<Float?>(timeBins.size) { null }
            val q1s = Array<Float?>(timeBins.size) { null }
            val q3s = Array<Float?>(timeBins.size) { null }

            for (i in timeBins.indices) {
                val t = timeBins[i]
                val values = episodes.mapNotNull { ep ->
                    val row = ep.rows.find { it.minutesSinceMealStart == t }
                    row?.let { (it.bg - ep.startBg).toFloat() }
                }.sorted()

                if (values.isNotEmpty()) {
                    q1s[i] = quantile(values, 0.25f)
                    medians[i] = quantile(values, 0.50f)
                    q3s[i] = quantile(values, 0.75f)
                }
            }


// ---------- episode overlay (sorted by time) ----------

            episodes.forEach { ep ->

                val pts = ep.rows
                    .asSequence()
                    .mapNotNull { r ->
                        val t = r.minutesSinceMealStart ?: return@mapNotNull null
                        if (t < 0 || t > 180) return@mapNotNull null
                        val d = (r.bg - ep.startBg).toFloat()
                        t to d
                    }
                    .sortedBy { it.first } // ✅ key fix: time-order
                    .toList()

                // als er dubbele timestamps zijn, ga je anders "stilstaande" lijntjes krijgen.
                // (optioneel) maak duplicates uniek door alleen de laatste per t te nemen:
                val compact = pts
                    .groupBy { it.first }
                    .map { (t, list) -> t to list.last().second }
                    .sortedBy { it.first }

                compact
                    .zipWithNext()
                    .forEach { (a, b) ->
                        val t0 = a.first.toFloat()
                        val t1 = b.first.toFloat()
                        val d0 = a.second
                        val d1 = b.second

                        drawLine(
                            color = Color(0x5522AAFF), // iets zichtbaarder op dark
                            start = Offset(x(t0), y(d0)),
                            end = Offset(x(t1), y(d1)),
                            strokeWidth = 2f
                        )
                    }
            }

            // ----------------------------
            // IQR band (Q1..Q3) - duidelijker fill
            // ----------------------------
            val bandPath = androidx.compose.ui.graphics.Path()
            var started = false

            for (i in timeBins.indices) {
                val mQ3 = q3s[i]
                if (mQ3 == null) continue
                val xt = x(timeBins[i].toFloat())
                val yt = y(mQ3)
                if (!started) {
                    bandPath.moveTo(xt, yt)
                    started = true
                } else {
                    bandPath.lineTo(xt, yt)
                }
            }

            for (i in timeBins.indices.reversed()) {
                val mQ1 = q1s[i]
                if (mQ1 == null) continue
                val xt = x(timeBins[i].toFloat())
                val yt = y(mQ1)
                bandPath.lineTo(xt, yt)
            }

            if (started) {
                bandPath.close()
                drawPath(
                    path = bandPath,
                    color = Color(0x6633CCFF) // <-- veel zichtbaarder dan 0x33...
                )
            }

            // ----------------------------
            // Median curve
            // ----------------------------
            val medianColor = Color(0xFF00E5FF) // helderder cyan
            var prevX: Float? = null
            var prevY: Float? = null

            for (i in timeBins.indices) {

                val m = medians[i]

                if (m == null) {
                    prevX = null
                    prevY = null
                    continue
                }

                val xt = x(timeBins[i].toFloat())
                val yt = y(m)

                if (prevX != null && prevY != null) {
                    drawLine(
                        color = medianColor,
                        start = Offset(prevX!!, prevY!!),
                        end = Offset(xt, yt),
                        strokeWidth = 4.5f
                    )
                }

                prevX = xt
                prevY = yt
            }

            // Kleine legenda rechtsboven in plot
            drawContext.canvas.nativeCanvas.drawText(
                "Median (line) + IQR (band) + episodes",
                plotLeft + 12.dp.toPx(),
                plotTop + 18.dp.toPx(),
                legendPaint
            )
        }
    }
}

// Small helper because we used Color.toArgb() without importing android.graphics.Color
private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt()
)