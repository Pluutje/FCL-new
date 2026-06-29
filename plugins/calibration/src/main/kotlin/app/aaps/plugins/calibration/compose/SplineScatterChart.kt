package app.aaps.plugins.calibration.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.plugins.calibration.CalibrationFit
import app.aaps.plugins.calibration.SplineFit
import app.aaps.core.data.model.CAL
import app.aaps.plugins.calibration.weightFor
import kotlin.math.max
import kotlin.math.min

private const val CHART_MIN_BG = 40f
private const val CHART_MAX_BG = 400f
private const val AXIS_PAD     = 20f
private const val MIN_SPAN     = 80f
private const val CURVE_STEPS  = 200

// Aantal kruisdraden per as (exclusief de assen zelf)
private const val GRID_LINES   = 3

private const val LONGEST_AXIS_LABEL_SAMPLE = "22.2"

/**
 * Zoomstatus voor de spline grafiek.
 * FULL = volledig overzicht
 * LOW  = laag segment (onder knot1 = 6 mmol)
 * MID  = midden segment (6-11 mmol, alleen actief bij twee knooppunten)
 * HIGH = hoog segment (boven knot1/knot2)
 */
internal enum class ZoomSegment { FULL, LOW, MID, HIGH }

/**
 * Vierkante scatter-grafiek (aspect ratio 1:1) met:
 *  - Identiteitslijn (gestippeld)
 *  - Kruisdraden / gridlijnen
 *  - Lineaire ghost (gestippeld, secundaire kleur) als spline actief is
 *  - Spline-curve (vol, primaire kleur) verschoven met [manualOffsetMmol]
 *  - Calibratiepunten (time-decay opacity)
 *  - Knooppunt-diamantje
 */
@Composable
internal fun SplineScatterChart(
    entries: List<CAL>,
    splineFit: SplineFit?,
    linearFit: CalibrationFit?,
    selectedEntryId: Long?,
    now: Long,
    glucoseUnit: GlucoseUnit,
    manualOffsetMmol: Float = 0f,
    zoomSegment: ZoomSegment = ZoomSegment.FULL,
    onZoomChange: (ZoomSegment) -> Unit = {},
    modifier: Modifier = Modifier
) {

    val axisColor         = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor         = MaterialTheme.colorScheme.outlineVariant
    val identityColor     = MaterialTheme.colorScheme.outline
    val regressionColor   = MaterialTheme.colorScheme.primary
    val linearGhostColor  = MaterialTheme.colorScheme.secondary
    val dotColor          = MaterialTheme.colorScheme.onSurface
    val selectedColor     = MaterialTheme.colorScheme.primary
    val selectedHaloColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val knotColor         = MaterialTheme.colorScheme.tertiary
    val residualAbove     = MaterialTheme.colorScheme.error.copy(alpha = 0.45f)   // sensor te hoog
    val residualBelow     = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f) // sensor te laag
    val labelArgb         = axisColor.toArgb()
    val density           = LocalDensity.current

    // Manual offset in mg/dL voor gebruik in de curve-rendering
    val manualOffsetMgdl = (manualOffsetMmol * 18.0182f)

    val labelPaint = remember(labelArgb, density) {
        android.graphics.Paint().apply {
            color       = labelArgb
            textSize    = with(density) { 11.sp.toPx() }
            isAntiAlias = true
        }
    }
    val leftAxisWidthPx = remember(labelPaint, density) {
        val gap = with(density) { 4.dp.toPx() }
        labelPaint.measureText(LONGEST_AXIS_LABEL_SAMPLE) + gap * 2f
    }
    val bottomAxisHeightPx = remember(labelPaint, density) {
        val gap = with(density) { 4.dp.toPx() }
        val fm  = labelPaint.fontMetrics
        gap + (fm.descent - fm.ascent) + gap
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(splineFit, zoomSegment) {
                    detectTapGestures {
                        val next = when (zoomSegment) {
                            ZoomSegment.FULL -> ZoomSegment.LOW
                            ZoomSegment.LOW  -> ZoomSegment.HIGH
                            ZoomSegment.MID  -> ZoomSegment.HIGH
                            ZoomSegment.HIGH -> ZoomSegment.FULL
                        }
                        onZoomChange(next)
                    }
                }
        ) {
            val topPad   = 8.dp.toPx()
            val rightPad = leftAxisWidthPx   // zelfde marge rechts als links zodat het plot zelf vierkant is

            val plotSize = Size(
                width  = size.width  - leftAxisWidthPx - rightPad,
                height = size.height - topPad - bottomAxisHeightPx
            )
            val plotOrigin = Offset(leftAxisWidthPx, topPad)

            if (plotSize.width <= 0f || plotSize.height <= 0f) return@Canvas

            // Bepaal as-bereik op basis van zoomstatus
            val (fullMin, fullMax) = computeAxisRange(entries)
            val (axisMin, axisMax) = computeZoomedRange(
                fullMin, fullMax, zoomSegment, splineFit
            )
            val span = axisMax - axisMin

            // Toon zoom-indicator als ingezoomd
            val isZoomed = zoomSegment != ZoomSegment.FULL

            fun xToPx(v: Float) = plotOrigin.x + ((v - axisMin) / span) * plotSize.width
            fun yToPx(v: Float) = plotOrigin.y + plotSize.height - ((v - axisMin) / span) * plotSize.height

            // Achtergrond gridlijnen (kruisdraden)
            drawGrid(plotOrigin, plotSize, axisMin, axisMax, gridColor)

            drawAxes(plotOrigin, plotSize, axisColor)
            drawAxisLabels(plotOrigin, plotSize, axisMin, axisMax, glucoseUnit, labelPaint, density)
            drawIdentityLine(::xToPx, ::yToPx, axisMin, axisMax, identityColor)

            if (splineFit != null) {
                linearFit?.takeIf { it.isApplicable }?.let {
                    drawLinearLine(::xToPx, ::yToPx, axisMin, axisMax, it, 0f, linearGhostColor, dashed = true)
                }
                drawSplineCurve(::xToPx, ::yToPx, axisMin, axisMax, splineFit, manualOffsetMgdl, regressionColor)
                drawKnotMarker(::xToPx, ::yToPx, splineFit, manualOffsetMgdl, knotColor, density)
                // Enkelvoudige Hermite — geen tweede knooppunt marker
            } else {
                linearFit?.takeIf { it.isApplicable }?.let {
                    drawLinearLine(::xToPx, ::yToPx, axisMin, axisMax, it, manualOffsetMgdl, regressionColor, dashed = false)
                }
            }

            drawEntries(
                entries, selectedEntryId, now, ::xToPx, ::yToPx,
                dotColor, selectedColor, selectedHaloColor, density,
                splineFit, linearFit, manualOffsetMgdl, residualAbove, residualBelow
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing helpers

// ---------------------------------------------------------------------------

/**
 * Berekent het ingezoomde as-bereik voor een specifiek segment.
 * Zoomfactor: ±40% van het knooppunt als centrum — groot genoeg voor detail,
 * klein genoeg om context te bewaren (naburige punten nog zichtbaar).
 */
private fun computeZoomedRange(
    fullMin: Float, fullMax: Float,
    zoom: ZoomSegment,
    spline: SplineFit?
): Pair<Float, Float> {
    if (zoom == ZoomSegment.FULL || spline == null) return fullMin to fullMax

    val split = spline.knotX.toFloat()  // splitspunt = SPLINE_SPLIT_MGDL
    val pad = 18f  // ± 1 mmol marge buiten het segment
    return when (zoom) {
        ZoomSegment.LOW -> {
            val zMin = (fullMin - pad).coerceAtLeast(CHART_MIN_BG)
            val zMax = (split + pad).coerceAtMost(CHART_MAX_BG)
            if (zMax - zMin < MIN_SPAN) fullMin to fullMax else zMin to zMax
        }
        ZoomSegment.MID  -> fullMin to fullMax  // niet gebruikt, val terug op FULL
        ZoomSegment.HIGH -> {
            val zMin = (split - pad).coerceAtLeast(CHART_MIN_BG)
            val zMax = (fullMax + pad).coerceAtMost(CHART_MAX_BG)
            if (zMax - zMin < MIN_SPAN) fullMin to fullMax else zMin to zMax
        }
        ZoomSegment.FULL -> fullMin to fullMax
    }
}

private fun computeAxisRange(entries: List<CAL>): Pair<Float, Float> {
    if (entries.isEmpty()) return 40f to 200f
    var lo = Float.POSITIVE_INFINITY
    var hi = Float.NEGATIVE_INFINITY
    for (e in entries) {
        lo = min(lo, min(e.sensorMgdlAtPairing, e.fingerstickMgdl).toFloat())
        hi = max(hi, max(e.sensorMgdlAtPairing, e.fingerstickMgdl).toFloat())
    }
    var axisMin = (lo - AXIS_PAD).coerceAtLeast(CHART_MIN_BG)
    var axisMax = (hi + AXIS_PAD).coerceAtMost(CHART_MAX_BG)
    if (axisMax - axisMin < MIN_SPAN) {
        val mid = (axisMax + axisMin) / 2f
        axisMin = (mid - MIN_SPAN / 2f).coerceAtLeast(CHART_MIN_BG)
        axisMax = (mid + MIN_SPAN / 2f).coerceAtMost(CHART_MAX_BG)
    }
    return axisMin to axisMax
}

/**
 * Lichte kruisdraden op regelmatige intervallen binnen het plotgebied.
 */
private fun DrawScope.drawGrid(
    origin: Offset, plotSize: Size,
    axisMin: Float, axisMax: Float,
    color: Color
) {
    val sw    = 0.5.dp.toPx()
    val alpha = 0.35f
    val n     = GRID_LINES + 1  // aantal tussenruimten
    for (i in 1..GRID_LINES) {
        val frac = i.toFloat() / n
        val v    = axisMin + frac * (axisMax - axisMin)

        // Verticale kruisdraad (x = v)
        val px = origin.x + frac * plotSize.width
        drawLine(
            color       = color.copy(alpha = alpha),
            start       = Offset(px, origin.y),
            end         = Offset(px, origin.y + plotSize.height),
            strokeWidth = sw
        )
        // Horizontale kruisdraad (y = v)
        val py = origin.y + plotSize.height - frac * plotSize.height
        drawLine(
            color       = color.copy(alpha = alpha),
            start       = Offset(origin.x, py),
            end         = Offset(origin.x + plotSize.width, py),
            strokeWidth = sw
        )
    }
}

private fun DrawScope.drawAxes(origin: Offset, size: Size, color: Color) {
    val sw = 1.dp.toPx()
    drawLine(color, origin, Offset(origin.x, origin.y + size.height), sw)
    drawLine(color, Offset(origin.x, origin.y + size.height), Offset(origin.x + size.width, origin.y + size.height), sw)
}

private fun DrawScope.drawAxisLabels(
    origin: Offset, size: Size,
    axisMin: Float, axisMax: Float,
    glucoseUnit: GlucoseUnit,
    paint: android.graphics.Paint,
    density: Density
) {
    val gap    = with(density) { 4.dp.toPx() }
    val canvas = drawContext.canvas.nativeCanvas
    val fm     = paint.fontMetrics
    val cOff   = -(fm.ascent + fm.descent) / 2f
    val minL   = formatAxisLabel(axisMin, glucoseUnit)
    val maxL   = formatAxisLabel(axisMax, glucoseUnit)
    val n      = GRID_LINES + 1

    // Y-as labels: min, tussenliggende, max
    canvas.drawText(maxL, origin.x - paint.measureText(maxL) - gap, origin.y + cOff, paint)
    canvas.drawText(minL, origin.x - paint.measureText(minL) - gap, origin.y + size.height + cOff, paint)
    for (i in 1..GRID_LINES) {
        val frac = i.toFloat() / n
        val v    = axisMin + frac * (axisMax - axisMin)
        val lbl  = formatAxisLabel(v, glucoseUnit)
        val py   = origin.y + size.height - frac * size.height
        canvas.drawText(lbl, origin.x - paint.measureText(lbl) - gap, py + cOff, paint)
    }

    // X-as labels: min, tussenliggende, max
    val botBase = origin.y + size.height + gap - fm.ascent
    canvas.drawText(minL, origin.x - paint.measureText(minL) / 2f, botBase, paint)
    canvas.drawText(maxL, origin.x + size.width - paint.measureText(maxL) / 2f, botBase, paint)
    for (i in 1..GRID_LINES) {
        val frac = i.toFloat() / n
        val v    = axisMin + frac * (axisMax - axisMin)
        val lbl  = formatAxisLabel(v, glucoseUnit)
        val px   = origin.x + frac * size.width
        canvas.drawText(lbl, px - paint.measureText(lbl) / 2f, botBase, paint)
    }
}

private fun formatAxisLabel(mgdl: Float, glucoseUnit: GlucoseUnit): String = when (glucoseUnit) {
    GlucoseUnit.MGDL -> mgdl.toInt().toString()
    GlucoseUnit.MMOL -> "%.1f".format(mgdl * Constants.MGDL_TO_MMOLL)
}

private fun DrawScope.drawIdentityLine(
    xToPx: (Float) -> Float, yToPx: (Float) -> Float,
    axisMin: Float, axisMax: Float, color: Color
) {
    val dash = 8.dp.toPx()
    drawLine(
        color       = color.copy(alpha = 0.5f),
        start       = Offset(xToPx(axisMin), yToPx(axisMin)),
        end         = Offset(xToPx(axisMax), yToPx(axisMax)),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect  = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
    )
}

private fun DrawScope.drawLinearLine(
    xToPx: (Float) -> Float, yToPx: (Float) -> Float,
    axisMin: Float, axisMax: Float,
    fit: CalibrationFit,
    offsetMgdl: Float,
    color: Color,
    dashed: Boolean
) {
    fun model(x: Float): Float = (fit.slope * x + fit.offset + offsetMgdl).toFloat()
    var x1 = axisMin; var y1 = model(x1)
    var x2 = axisMax; var y2 = model(x2)
    if (fit.slope != 0.0) {
        val inv = 1.0 / fit.slope
        fun xAtY(y: Double) = ((y - fit.offset - offsetMgdl) * inv).toFloat()
        if (y1 < axisMin) { y1 = axisMin; x1 = xAtY(axisMin.toDouble()) }
        if (y1 > axisMax) { y1 = axisMax; x1 = xAtY(axisMax.toDouble()) }
        if (y2 < axisMin) { y2 = axisMin; x2 = xAtY(axisMin.toDouble()) }
        if (y2 > axisMax) { y2 = axisMax; x2 = xAtY(axisMax.toDouble()) }
    }
    if (x1 == x2 && y1 == y2) return
    if (x1 !in axisMin..axisMax || x2 !in axisMin..axisMax) return
    drawLine(
        color       = if (dashed) color.copy(alpha = 0.45f) else color,
        start       = Offset(xToPx(x1), yToPx(y1)),
        end         = Offset(xToPx(x2), yToPx(y2)),
        strokeWidth = if (dashed) 1.5.dp.toPx() else 2.5.dp.toPx(),
        pathEffect  = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())) else null
    )
}

/**
 * Teken de spline-curve inclusief de handmatige offset.
 * De curve stopt zodra hij buiten het plotgebied komt (geen clamp langs de rand).
 */
private fun DrawScope.drawSplineCurve(
    xToPx: (Float) -> Float, yToPx: (Float) -> Float,
    axisMin: Float, axisMax: Float,
    fit: SplineFit,
    offsetMgdl: Float,
    color: Color
) {
    val path = Path()
    var first = true
    for (i in 0..CURVE_STEPS) {
        val sensorMgdl = axisMin + (axisMax - axisMin) * (i.toFloat() / CURVE_STEPS)
        val calibrated = fit.apply(sensorMgdl.toDouble()).toFloat() + offsetMgdl
        if (calibrated < axisMin || calibrated > axisMax) {
            first = true
            continue
        }
        val px = xToPx(sensorMgdl)
        val py = yToPx(calibrated)
        if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
    }
    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
}

/**
 * Knooppunt-diamantje, verschoven met de offset.
 */
private fun DrawScope.drawKnotMarker(
    xToPx: (Float) -> Float, yToPx: (Float) -> Float,
    fit: SplineFit,
    offsetMgdl: Float,
    color: Color,
    density: Density
) {
    val knotPx = xToPx(fit.knotX.toFloat())
    val knotY  = (fit.knotY.toFloat() + offsetMgdl).coerceIn(CHART_MIN_BG, CHART_MAX_BG)
    val knotPy = yToPx(knotY)
    val r      = with(density) { 4.dp.toPx() }
    val diamond = Path().apply {
        moveTo(knotPx, knotPy - r)
        lineTo(knotPx + r, knotPy)
        lineTo(knotPx, knotPy + r)
        lineTo(knotPx - r, knotPy)
        close()
    }
    drawPath(diamond, color.copy(alpha = 0.85f))
    drawPath(diamond, color, style = Stroke(width = 1.dp.toPx()))
}

private fun DrawScope.drawEntries(
    entries: List<CAL>,
    selectedEntryId: Long?,
    now: Long,
    xToPx: (Float) -> Float,
    yToPx: (Float) -> Float,
    dotColor: Color,
    selectedColor: Color,
    haloColor: Color,
    density: Density,
    splineFit: SplineFit? = null,
    linearFit: CalibrationFit? = null,
    manualOffsetMgdl: Float = 0f,
    residualAbove: Color = Color.Transparent,
    residualBelow: Color = Color.Transparent
) {
    val normalRadius   = with(density) { 3.5.dp.toPx() }
    val selectedRadius = with(density) { 6.dp.toPx() }
    val haloRadius     = with(density) { 11.dp.toPx() }
    val selectedStroke = with(density) { 1.5.dp.toPx() }

    // Bepaal de actieve fit voor residual-lijnen
    val activeFit: ((Double) -> Double)? = when {
        splineFit != null -> { s -> splineFit.apply(s) + manualOffsetMgdl }
        linearFit != null && linearFit.isApplicable -> { s -> linearFit.slope * s + linearFit.offset + manualOffsetMgdl }
        else -> null
    }

    for (e in entries) {
        if (e.id == selectedEntryId) continue
        val w     = weightFor(e.timestamp, now).toFloat()
        val alpha = (0.2f + 0.8f * w).coerceIn(0.2f, 1f)
        val sx = xToPx(e.sensorMgdlAtPairing.toFloat())
        val fy = yToPx(e.fingerstickMgdl.toFloat())
        // Residual-lijn: van het punt naar de spline/lineaire fit
        if (activeFit != null) {
            val fittedY = yToPx(activeFit(e.sensorMgdlAtPairing).toFloat())
            val residualColor = if (e.fingerstickMgdl > activeFit(e.sensorMgdlAtPairing))
                residualBelow.copy(alpha = residualBelow.alpha * alpha)  // sensor te laag: prik hoger dan fit
            else residualAbove.copy(alpha = residualAbove.alpha * alpha)
            drawLine(
                color       = residualColor,
                start       = Offset(sx, fy),
                end         = Offset(sx, fittedY),
                strokeWidth = with(density) { 1.5.dp.toPx() }
            )
        }
        // Puntgrootte schaalt ook mee met tijdgewicht: oudere punten kleiner
        val scaledRadius = normalRadius * (0.5f + 0.5f * w)
        drawCircle(
            color  = dotColor.copy(alpha = alpha),
            radius = scaledRadius,
            center = Offset(sx, fy)
        )
    }
    entries.firstOrNull { it.id == selectedEntryId }?.let { e ->
        val center = Offset(xToPx(e.sensorMgdlAtPairing.toFloat()), yToPx(e.fingerstickMgdl.toFloat()))
        drawCircle(color = haloColor, radius = haloRadius, center = center)
        drawCircle(color = selectedColor, radius = selectedRadius, center = center)
        drawCircle(color = dotColor, radius = selectedRadius, center = center, style = Stroke(width = selectedStroke))
    }
}