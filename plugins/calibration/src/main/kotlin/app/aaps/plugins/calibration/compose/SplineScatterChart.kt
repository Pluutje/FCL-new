package app.aaps.plugins.calibration.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.plugins.calibration.CalibrationFit
import app.aaps.plugins.calibration.SplineFit
import app.aaps.plugins.calibration.db.CalibrationEntry
import app.aaps.plugins.calibration.weightFor
import kotlin.math.max
import kotlin.math.min

private const val CHART_MIN_BG = 40f
private const val CHART_MAX_BG = 400f
private const val AXIS_PAD     = 20f
private const val MIN_SPAN     = 80f
private const val CURVE_STEPS  = 120   // number of segments used to draw the spline curve

private const val LONGEST_AXIS_LABEL_SAMPLE = "22.2"

/**
 * Scatter chart that renders:
 *  - Identity line (dashed)
 *  - Linear fallback (dashed, secondary colour) when spline is active
 *  - Spline curve (solid, primary colour) — OR linear line when spline is null
 *  - Calibration data points (time-decay opacity)
 *  - Interior knot marker (small diamond) when spline is active
 */
@Composable
internal fun SplineScatterChart(
    entries: List<CalibrationEntry>,
    splineFit: SplineFit?,
    linearFit: CalibrationFit?,
    selectedEntryId: Long?,
    now: Long,
    glucoseUnit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    val axisColor         = MaterialTheme.colorScheme.onSurfaceVariant
    val identityColor     = MaterialTheme.colorScheme.outline
    val regressionColor   = MaterialTheme.colorScheme.primary
    val linearGhostColor  = MaterialTheme.colorScheme.secondary
    val dotColor          = MaterialTheme.colorScheme.onSurface
    val selectedColor     = MaterialTheme.colorScheme.primary
    val selectedHaloColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val knotColor         = MaterialTheme.colorScheme.tertiary
    val labelArgb         = axisColor.toArgb()
    val density           = LocalDensity.current

    val labelPaint = remember(labelArgb, density) {
        android.graphics.Paint().apply {
            color    = labelArgb
            textSize = with(density) { 11.sp.toPx() }
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

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val topPad   = 8.dp.toPx()
        val rightPad = 8.dp.toPx()

        val plotOrigin = Offset(leftAxisWidthPx, topPad)
        val plotSize   = Size(
            width  = size.width - leftAxisWidthPx - rightPad,
            height = size.height - topPad - bottomAxisHeightPx
        )
        if (plotSize.width <= 0f || plotSize.height <= 0f) return@Canvas

        val (axisMin, axisMax) = computeAxisRange(entries)
        val span = axisMax - axisMin

        fun xToPx(v: Float) = plotOrigin.x + ((v - axisMin) / span) * plotSize.width
        fun yToPx(v: Float) = plotOrigin.y + plotSize.height - ((v - axisMin) / span) * plotSize.height

        drawAxes(plotOrigin, plotSize, axisColor)
        drawAxisLabels(plotOrigin, plotSize, axisMin, axisMax, glucoseUnit, labelPaint, density)
        drawIdentityLine(::xToPx, ::yToPx, axisMin, axisMax, identityColor)

        if (splineFit != null) {
            // Show linear as a ghost so the user can see the difference.
            linearFit?.takeIf { it.isApplicable }?.let {
                drawLinearLine(::xToPx, ::yToPx, axisMin, axisMax, it, linearGhostColor, dashed = true)
            }
            drawSplineCurve(::xToPx, ::yToPx, axisMin, axisMax, splineFit, regressionColor)
            drawKnotMarker(::xToPx, ::yToPx, splineFit, knotColor, density)
        } else {
            // Spline not yet available — show linear fit directly.
            linearFit?.takeIf { it.isApplicable }?.let {
                drawLinearLine(::xToPx, ::yToPx, axisMin, axisMax, it, regressionColor, dashed = false)
            }
        }

        drawEntries(
            entries, selectedEntryId, now, ::xToPx, ::yToPx,
            dotColor, selectedColor, selectedHaloColor, density
        )
    }
}

// ---------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------

private fun computeAxisRange(entries: List<CalibrationEntry>): Pair<Float, Float> {
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
        val mid  = (axisMax + axisMin) / 2f
        axisMin  = (mid - MIN_SPAN / 2f).coerceAtLeast(CHART_MIN_BG)
        axisMax  = (mid + MIN_SPAN / 2f).coerceAtMost(CHART_MAX_BG)
    }
    return axisMin to axisMax
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

    canvas.drawText(maxL, origin.x - paint.measureText(maxL) - gap, origin.y + cOff, paint)
    canvas.drawText(minL, origin.x - paint.measureText(minL) - gap, origin.y + size.height + cOff, paint)

    val botBase = origin.y + size.height + gap - fm.ascent
    canvas.drawText(minL, origin.x - paint.measureText(minL) / 2f, botBase, paint)
    canvas.drawText(maxL, origin.x + size.width - paint.measureText(maxL) / 2f, botBase, paint)
}

private fun formatAxisLabel(mgdl: Float, glucoseUnit: GlucoseUnit): String = when (glucoseUnit) {
    GlucoseUnit.MGDL -> mgdl.toInt().toString()
    GlucoseUnit.MMOL -> "%.1f".format(mgdl * GlucoseUnit.MGDL_TO_MMOLL)
}

private fun DrawScope.drawIdentityLine(
    xToPx: (Float) -> Float, yToPx: (Float) -> Float,
    axisMin: Float, axisMax: Float, color: Color
) {
    val dash = 8.dp.toPx()
    drawLine(
        color        = color.copy(alpha = 0.5f),
        start        = Offset(xToPx(axisMin), yToPx(axisMin)),
        end          = Offset(xToPx(axisMax), yToPx(axisMax)),
        strokeWidth  = 1.5.dp.toPx(),
        pathEffect   = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
    )
}

private fun DrawScope.drawLinearLine(
    xToPx: (Float) -> Float, yToPx: (Float) -> Float,
    axisMin: Float, axisMax: Float,
    fit: CalibrationFit,
    color: Color,
    dashed: Boolean
) {
    fun model(x: Float): Float = (fit.slope * x + fit.offset).toFloat()
    var x1 = axisMin; var y1 = model(x1)
    var x2 = axisMax; var y2 = model(x2)
    if (fit.slope != 0.0) {
        val inv = 1.0 / fit.slope
        fun xAtY(y: Double) = ((y - fit.offset) * inv).toFloat()
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

/*
 * Draw the spline as a polyline of [CURVE_STEPS] segments over [axisMin..axisMax].
 * Using a Path + drawPath gives smoother anti-aliasing than many individual drawLine calls.
 */
private fun DrawScope.drawSplineCurve(
    xToPx: (Float) -> Float, yToPx: (Float) -> Float,
    axisMin: Float, axisMax: Float,
    fit: SplineFit,
    color: Color
) {
    val path = Path()
    var first = true
    for (i in 0..CURVE_STEPS) {
        val sensorMgdl = axisMin + (axisMax - axisMin) * (i.toFloat() / CURVE_STEPS)
        val calibrated = fit.apply(sensorMgdl.toDouble()).toFloat()
        // Clip to axis range so the curve never draws outside the plot area.
        val clampedY = calibrated.coerceIn(axisMin, axisMax)
        val px = xToPx(sensorMgdl)
        val py = yToPx(clampedY)
        if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
    }
    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
}

/**
 * Draw a small diamond at the interior knot to make it visible to the user.
 */
private fun DrawScope.drawKnotMarker(
    xToPx: (Float) -> Float, yToPx: (Float) -> Float,
    fit: SplineFit,
    color: Color,
    density: Density
) {
    val knotPx = xToPx(fit.knotX.toFloat())
    val knotPy = yToPx(fit.knotY.toFloat().coerceIn(CHART_MIN_BG, CHART_MAX_BG))
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
    entries: List<CalibrationEntry>,
    selectedEntryId: Long?,
    now: Long,
    xToPx: (Float) -> Float,
    yToPx: (Float) -> Float,
    dotColor: Color,
    selectedColor: Color,
    haloColor: Color,
    density: Density
) {
    val normalRadius   = with(density) { 3.5.dp.toPx() }
    val selectedRadius = with(density) { 6.dp.toPx() }
    val haloRadius     = with(density) { 11.dp.toPx() }
    val selectedStroke = with(density) { 1.5.dp.toPx() }

    for (e in entries) {
        if (e.id == selectedEntryId) continue
        val w     = weightFor(e.timestamp, now).toFloat()
        val alpha = (0.2f + 0.8f * w).coerceIn(0.2f, 1f)
        drawCircle(
            color  = dotColor.copy(alpha = alpha),
            radius = normalRadius,
            center = Offset(xToPx(e.sensorMgdlAtPairing.toFloat()), yToPx(e.fingerstickMgdl.toFloat()))
        )
    }
    entries.firstOrNull { it.id == selectedEntryId }?.let { e ->
        val center = Offset(xToPx(e.sensorMgdlAtPairing.toFloat()), yToPx(e.fingerstickMgdl.toFloat()))
        drawCircle(color = haloColor, radius = haloRadius, center = center)
        drawCircle(color = selectedColor, radius = selectedRadius, center = center)
        drawCircle(color = dotColor, radius = selectedRadius, center = center, style = Stroke(width = selectedStroke))
    }
}
