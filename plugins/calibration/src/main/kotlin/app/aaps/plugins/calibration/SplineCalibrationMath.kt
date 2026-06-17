package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import kotlin.math.abs
import kotlin.math.sign

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const val MIN_ENTRIES_FOR_SPLINE = 4

/**
 * Splitspunt laag/hoog segment: 6 mmol/L = 108 mg/dL.
 * Punten met sensor <= SPLIT vallen in het lage segment,
 * punten met sensor > SPLIT in het hoge segment.
 */
const val SPLINE_SPLIT_MGDL = 108.0

/**
 * Minimaal aantal punten per segment voor een betrouwbare regressie.
 * Met minder punten is de segment-slope onbetrouwbaar.
 */
const val MIN_POINTS_PER_SEGMENT = 2

/**
 * Minimale spreiding van sensorwaarden per segment (mg/dL).
 * Onder dit niveau is de slope niet te onderscheiden van ruis
 * en wordt een vaste slope van 1.0 gebruikt voor dat segment.
 */
const val MIN_SEGMENT_RANGE_MGDL = 18.0  // 1 mmol

// ---------------------------------------------------------------------------
// Public data types
// ---------------------------------------------------------------------------

/**
 * Enkelvoudige monotone cubic Hermite spline tussen het lage en hoge segment.
 *
 * Architectuur:
 *   - Laag segment  (sensor <= cx_low):  lineaire extrapolatie met slope_low
 *   - Overgangszone (cx_low..cx_high):   Hermite-curve met tangenten slope_low en slope_high
 *   - Hoog segment  (sensor >= cx_high): lineaire extrapolatie met slope_high
 *
 * slope_low en slope_high komen elk uit een onafhankelijke gewogen lineaire
 * regressie op de punten in het respectieve segment. Hierdoor weerspiegelen
 * de tangenten direct de sensor-karakteristiek in dat bereik, onafhankelijk
 * van de globale fit of een willekeurig knooppuntniveau.
 *
 * knotX / knotY zijn het punt op de curve bij SPLINE_SPLIT_MGDL —
 * uitsluitend voor informatieve weergave in de UI (knot correction).
 */
data class SplineFit(
    val knotX: Double,
    val knotY: Double,

    val cx_low:  Double, val cy_low:  Double, val slope_low:  Double,
    val cx_high: Double, val cy_high: Double, val slope_high: Double,

    val linearFallback: CalibrationFit
) {
    fun apply(sensorMgdl: Double, manualOffsetMgdl: Double = 0.0): Double {
        // Bij extrapolatie boven cx_high: slope minimaal 1.0 zodat de correctie
        // niet daalt bij verder stijgende BG. slope_high < 1.0 reflecteert sensor-
        // compressie in de data maar mag de gecalibreerde waarde niet laten afbuigen.
        val extrapolateHighSlope = slope_high.coerceAtLeast(1.0)
        val fitted = when {
            sensorMgdl <= cx_low  -> cy_low  + slope_low            * (sensorMgdl - cx_low)
            sensorMgdl >= cx_high -> cy_high + extrapolateHighSlope  * (sensorMgdl - cx_high)
            else -> hermite(sensorMgdl, cx_low, cy_low, slope_low,
                            cx_high, cy_high, slope_high)
        }
        return fitted + manualOffsetMgdl
    }

    val correctionAtKnot: Double get() = knotY - knotX
    val hasTwoKnots: Boolean get() = false
}

// ---------------------------------------------------------------------------
// Public fit function
// ---------------------------------------------------------------------------

fun fitSplineCalibration(entries: List<CAL>, now: Long): SplineFit? {
    if (entries.size < MIN_ENTRIES_FOR_SPLINE) return null

    val linear = fitLinearCalibration(entries, now) ?: return null

    val low  = entries.filter { it.sensorMgdlAtPairing <= SPLINE_SPLIT_MGDL }
    val high = entries.filter { it.sensorMgdlAtPairing >  SPLINE_SPLIT_MGDL }

    // Beide segmenten moeten voldoende punten hebben voor een betrouwbare fit.
    if (low.size  < MIN_POINTS_PER_SEGMENT) return null
    if (high.size < MIN_POINTS_PER_SEGMENT) return null

    return fitSegmentSpline(low, high, linear, now)
}

// ---------------------------------------------------------------------------
// Segment-spline fit
// ---------------------------------------------------------------------------

private fun fitSegmentSpline(
    low:    List<CAL>,
    high:   List<CAL>,
    linear: CalibrationFit,
    now:    Long
): SplineFit? {
    // Gewogen centroiden per segment
    val (cx_low,  cy_low)  = weightedCentroid(low,  now)
    val (cx_high, cy_high) = weightedCentroid(high, now)

    // Segment-specifieke gewogen lineaire fits voor de tangenten.
    // Als de sensorwaarden in een segment te dicht bij elkaar liggen,
    // val terug op de globale slope voor dat segment.
    val slope_low  = segmentSlope(low,  now) ?: linear.slope
    val slope_high = segmentSlope(high, now) ?: linear.slope

    // Beide slopes moeten fysiek plausibel zijn.
    if (slope_low  < SLOPE_MIN || slope_low  > SLOPE_MAX) return null
    if (slope_high < SLOPE_MIN || slope_high > SLOPE_MAX) return null

    // De overgangszone moet breed genoeg zijn voor numerieke stabiliteit.
    val span = cx_high - cx_low
    if (span < MIN_SEGMENT_RANGE_MGDL) return null

    // Monotonie: de Hermite-curve mag niet dalen tussen cx_low en cx_high.
    if (!isMonotoneIncreasing(cx_low, cy_low, slope_low, cx_high, cy_high, slope_high)) return null

    // Knooppuntwaarde voor display (evalueer Hermite bij SPLINE_SPLIT_MGDL).
    val knotX = SPLINE_SPLIT_MGDL
    val knotY = when {
        knotX <= cx_low  -> cy_low  + slope_low  * (knotX - cx_low)
        knotX >= cx_high -> cy_high + slope_high * (knotX - cx_high)
        else -> hermite(knotX, cx_low, cy_low, slope_low, cx_high, cy_high, slope_high)
    }

    return SplineFit(
        knotX      = knotX,
        knotY      = knotY,
        cx_low     = cx_low,  cy_low  = cy_low,  slope_low  = slope_low,
        cx_high    = cx_high, cy_high = cy_high, slope_high = slope_high,
        linearFallback = linear
    )
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Gewogen lineaire regressie voor één segment → slope.
 * Geeft null als de sensorwaarden te dicht bij elkaar liggen (< MIN_SEGMENT_RANGE_MGDL).
 */
private fun segmentSlope(entries: List<CAL>, now: Long): Double? {
    val sensorRange = entries.maxOf { it.sensorMgdlAtPairing } -
        entries.minOf { it.sensorMgdlAtPairing }
    if (sensorRange < MIN_SEGMENT_RANGE_MGDL) return null

    var sumW = 0.0; var sumWX = 0.0; var sumWY = 0.0
    var sumWXX = 0.0; var sumWXY = 0.0
    for (e in entries) {
        val w = weightFor(e.timestamp, now)
        val x = e.sensorMgdlAtPairing
        val y = e.fingerstickMgdl
        sumW += w; sumWX += w*x; sumWY += w*y; sumWXX += w*x*x; sumWXY += w*x*y
    }
    val denom = sumW * sumWXX - sumWX * sumWX
    if (abs(denom) < 1e-9) return null
    return (sumW * sumWXY - sumWX * sumWY) / denom
}

private fun hermite(
    x: Double,
    x0: Double, y0: Double, m0: Double,
    x1: Double, y1: Double, m1: Double
): Double {
    val h = x1 - x0
    if (h == 0.0) return y0
    val t  = (x - x0) / h
    val t2 = t * t
    val t3 = t2 * t
    val h00 =  2*t3 - 3*t2 + 1
    val h10 =    t3 - 2*t2 + t
    val h01 = -2*t3 + 3*t2
    val h11 =    t3 -   t2
    return h00*y0 + h10*h*m0 + h01*y1 + h11*h*m1
}

private fun weightedCentroid(entries: List<CAL>, now: Long): Pair<Double, Double> {
    var sumW = 0.0; var sumWX = 0.0; var sumWY = 0.0
    for (e in entries) {
        val w = weightFor(e.timestamp, now)
        sumW += w; sumWX += w * e.sensorMgdlAtPairing; sumWY += w * e.fingerstickMgdl
    }
    return if (sumW == 0.0) entries[0].sensorMgdlAtPairing to entries[0].fingerstickMgdl
    else (sumWX / sumW) to (sumWY / sumW)
}

private fun isMonotoneIncreasing(
    x0: Double, y0: Double, m0: Double,
    x1: Double, y1: Double, m1: Double
): Boolean {
    val h = x1 - x0
    if (h <= 0.0) return false
    val delta = (y1 - y0) / h
    if (delta < 0.0) return false
    if (delta == 0.0) return m0 == 0.0 && m1 == 0.0
    val alpha = m0 / delta
    val beta  = m1 / delta
    return alpha * alpha + beta * beta <= 9.0 + 1e-9
}

fun applyBestFit(sensorMgdl: Double, spline: SplineFit?, linear: CalibrationFit?, manualOffsetMgdl: Double = 0.0): Double? {
    if (spline != null) return spline.apply(sensorMgdl, manualOffsetMgdl)
    if (linear != null && linear.isApplicable) return linear.slope * sensorMgdl + linear.offset + manualOffsetMgdl
    return null
}