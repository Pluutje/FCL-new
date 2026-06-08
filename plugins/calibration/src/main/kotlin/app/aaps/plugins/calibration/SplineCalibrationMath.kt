package app.aaps.plugins.calibration

import app.aaps.plugins.calibration.db.CalibrationEntry
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const val MIN_ENTRIES_FOR_SPLINE = 4

/** Eerste knooppunt: 6 mmol/L = 108 mg/dL */
const val SPLINE_KNOT1_MGDL = 108.0

/** Tweede knooppunt: 11 mmol/L = 198 mg/dL */
const val SPLINE_KNOT2_MGDL = 198.0

const val SPLINE_MAX_CORRECTION_AT_KNOT = 40.0
const val SPLINE_MAX_DELTA_FROM_LINEAR  = 25.0

// ---------------------------------------------------------------------------
// Slope-caps
// ---------------------------------------------------------------------------

/** Minimum en maximum absolute hellingswaarde voor elk segment (sensor fysiologie). */
const val SLOPE_ABS_MIN = 0.70
const val SLOPE_ABS_MAX = 1.80

/**
 * Maximum factor waarmee segment 2 (6-11 mmol) steiler mag zijn dan segment 1.
 * Dekt de S-curve van Libre/Dexcom-type sensoren.
 */
const val SLOPE_CAP_SEG2_VS_SEG1 = 1.50

/**
 * Maximum factor waarmee segment 3 (>11 mmol) steiler mag zijn dan segment 2.
 * Dempt cumulatieve drift bij hoge BG.
 */
const val SLOPE_CAP_SEG3_VS_SEG2 = 1.25

/**
 * Slope-cap voor het hoge segment als het tweede knooppunt NIET actief is
 * (< 2 punten tussen 6-11 of < 1 punt boven 11 mmol).
 * Max 20% steiler dan lage segment, min 85% van lage segment.
 */
const val SLOPE_CAP_HIGH_VS_LOW_MAX = 1.20
const val SLOPE_CAP_HIGH_VS_LOW_MIN = 0.85

/**
 * Minimaal aantal punten in segment 6-11 mmol voor activatie tweede knooppunt.
 */
const val MIN_POINTS_MID_SEGMENT = 2

/**
 * Minimaal aantal punten boven 11 mmol voor activatie tweede knooppunt.
 */
const val MIN_POINTS_HIGH_SEGMENT = 1

// ---------------------------------------------------------------------------
// Public data types
// ---------------------------------------------------------------------------

/**
 * Piecewise monotone cubic Hermite spline met één of twee interieure knooppunten.
 *
 * Twee-knooppunt modus (actief als tweede knooppunt geconditioneerd is):
 *   Segment 0: x in (-∞, knot1X]
 *   Segment 1: x in [knot1X, knot2X]
 *   Segment 2: x in [knot2X, +∞)
 *
 * Één-knooppunt modus (fallback):
 *   Segment 0: x in (-∞, knot1X]
 *   Segment 1: x in [knot1X, +∞)  — slope gecapped t.o.v. segment 0
 */
data class SplineFit(
    val knotX: Double,
    val knotY: Double,

    // Segment 0 Hermite coefficiënten (laag segment)
    val low_x0: Double, val low_y0: Double, val low_m0: Double,
    val low_x1: Double, val low_y1: Double, val low_m1: Double,

    // Segment 1 Hermite coefficiënten (midden of hoog segment)
    val high_x0: Double, val high_y0: Double, val high_m0: Double,
    val high_x1: Double, val high_y1: Double, val high_m1: Double,

    val linearFallback: CalibrationFit,

    // Optioneel tweede knooppunt
    val knot2X: Double? = null,
    val knot2Y: Double? = null,
    val seg2_x0: Double? = null, val seg2_y0: Double? = null, val seg2_m0: Double? = null,
    val seg2_x1: Double? = null, val seg2_y1: Double? = null, val seg2_m1: Double? = null
) {
    fun apply(sensorMgdl: Double, manualOffsetMgdl: Double = 0.0): Double {
        val fitted = when {
            // Twee-knooppunt modus
            knot2X != null && seg2_x0 != null -> when {
                sensorMgdl <= low_x0   -> low_y0  + low_m0  * (sensorMgdl - low_x0)
                sensorMgdl >= seg2_x1!! -> seg2_y1!! + seg2_m1!! * (sensorMgdl - seg2_x1)
                sensorMgdl <= knotX    -> hermite(sensorMgdl, low_x0, low_y0, low_m0, low_x1, low_y1, low_m1)
                sensorMgdl <= knot2X   -> hermite(sensorMgdl, high_x0, high_y0, high_m0, high_x1, high_y1, high_m1)
                else                   -> hermite(sensorMgdl, seg2_x0, seg2_y0!!, seg2_m0!!, seg2_x1, seg2_y1!!, seg2_m1!!)
            }
            // Één-knooppunt modus
            else -> when {
                sensorMgdl <= low_x0   -> low_y0  + low_m0  * (sensorMgdl - low_x0)
                sensorMgdl >= high_x1  -> high_y1 + high_m1 * (sensorMgdl - high_x1)
                sensorMgdl <= knotX    -> hermite(sensorMgdl, low_x0, low_y0, low_m0, low_x1, low_y1, low_m1)
                else                   -> hermite(sensorMgdl, high_x0, high_y0, high_m0, high_x1, high_y1, high_m1)
            }
        }
        return fitted + manualOffsetMgdl
    }

    val correctionAtKnot: Double get() = knotY - knotX
    val hasTwoKnots: Boolean get() = knot2X != null
}

// ---------------------------------------------------------------------------
// Public fit function
// ---------------------------------------------------------------------------

fun fitSplineCalibration(entries: List<CalibrationEntry>, now: Long): SplineFit? {
    if (entries.size < MIN_ENTRIES_FOR_SPLINE) return null

    val linear = fitLinearCalibration(entries, now) ?: return null

    val low  = entries.filter { it.sensorMgdlAtPairing <= SPLINE_KNOT1_MGDL }
    val mid  = entries.filter { it.sensorMgdlAtPairing >  SPLINE_KNOT1_MGDL && it.sensorMgdlAtPairing <= SPLINE_KNOT2_MGDL }
    val high = entries.filter { it.sensorMgdlAtPairing >  SPLINE_KNOT2_MGDL }

    if (low.isEmpty() || (mid.isEmpty() && high.isEmpty())) return null

    // Bepaal of het tweede knooppunt actief is
    val useTwoKnots = mid.size >= MIN_POINTS_MID_SEGMENT && high.size >= MIN_POINTS_HIGH_SEGMENT

    return if (useTwoKnots) {
        // Probeer twee-knooppunt spline; val terug op één knooppunt als die faalt.
        // Dit voorkomt dat de spline volledig wegvalt als het HIGH segment te smal is
        // (bijv. als alle HIGH punten net boven KNOT2 zitten).
        fitTwoKnotSpline(entries, low, mid, high, linear, now)
            ?: fitOneKnotSpline(entries, low, mid + high, linear, now)
    } else {
        fitOneKnotSpline(entries, low, mid + high, linear, now)
    }
}

// ---------------------------------------------------------------------------
// Twee-knooppunt spline
// ---------------------------------------------------------------------------

private fun fitTwoKnotSpline(
    all: List<CalibrationEntry>,
    low: List<CalibrationEntry>,
    mid: List<CalibrationEntry>,
    high: List<CalibrationEntry>,
    linear: CalibrationFit,
    now: Long
): SplineFit? {
    val (cx0, cy0) = weightedCentroid(low, now)
    val (cx2, cy2) = weightedCentroid(mid, now)
    val (cx4, cy4) = weightedCentroid(high, now)

    val knotX  = SPLINE_KNOT1_MGDL
    val knot2X = SPLINE_KNOT2_MGDL

    // Knooppuntwaarden via lineaire fit + lokale residual
    val knotYLinear  = linear.slope * knotX  + linear.offset
    val knot2YLinear = linear.slope * knot2X + linear.offset
    val knotResidual  = weightedResidualNearKnot(all, now, knotX,  windowMgdl = 15.0)
    val knot2Residual = weightedResidualNearKnot(all, now, knot2X, windowMgdl = 20.0)
    val knotY  = knotYLinear  + knotResidual
    val knot2Y = knot2YLinear + knot2Residual

    // Sanity checks
    if (abs(knotY  - knotX)  > SPLINE_MAX_CORRECTION_AT_KNOT) return null
    if (abs(knot2Y - knot2X) > SPLINE_MAX_CORRECTION_AT_KNOT) return null
    if (abs((knotY - knotX) - (knotYLinear - knotX)) > SPLINE_MAX_DELTA_FROM_LINEAR) return null

    // Segment breedtes
    val h0 = knotX  - cx0
    val h1 = knot2X - knotX
    val h2 = cx4    - knot2X
    if (h0 < 18.0 || h1 < 18.0 || h2 < 18.0) return null

    // Chord slopes
    val delta0 = (knotY  - cy0)  / h0
    val delta1 = (knot2Y - knotY) / h1
    val delta2 = (cy4    - knot2Y) / h2

    // Absolute slope caps
    if (delta0 < SLOPE_ABS_MIN || delta0 > SLOPE_ABS_MAX) return null
    if (delta1 < SLOPE_ABS_MIN || delta1 > SLOPE_ABS_MAX) return null
    if (delta2 < SLOPE_ABS_MIN || delta2 > SLOPE_ABS_MAX) return null

    // Relatieve slope caps: segment 2 max 1.5× segment 1, segment 3 max 1.25× segment 2
    if (delta1 > delta0 * SLOPE_CAP_SEG2_VS_SEG1) return null
    if (delta2 > delta1 * SLOPE_CAP_SEG3_VS_SEG2) return null

    // Ratio check per segmentpaar
    for ((dA, dB) in listOf(delta0 to delta1, delta1 to delta2)) {
        if (dA > 0 && dB > 0) {
            val ratio = if (dA > dB) dA / dB else dB / dA
            if (ratio > 3.0) return null
        }
    }

    // Tangenten via Fritsch-Carlson
    var m0  = fritschCarlson(delta0,              Double.NaN, delta0)
    var mk1 = fritschCarlson((delta0 + delta1) / 2, delta0,   delta1)
    var mk2 = fritschCarlson((delta1 + delta2) / 2, delta1,   delta2)
    var m4  = fritschCarlson(delta2,              delta2,    Double.NaN)

    // Monotoniteitscontrole
    if (!isMonotoneIncreasing(cx0, cy0, m0, knotX, knotY, mk1))       return null
    if (!isMonotoneIncreasing(knotX, knotY, mk1, knot2X, knot2Y, mk2)) return null
    if (!isMonotoneIncreasing(knot2X, knot2Y, mk2, cx4, cy4, m4))      return null

    return SplineFit(
        knotX = knotX, knotY = knotY,
        low_x0  = cx0,   low_y0  = cy0,   low_m0  = m0,
        low_x1  = knotX, low_y1  = knotY, low_m1  = mk1,
        high_x0 = knotX, high_y0 = knotY, high_m0 = mk1,
        high_x1 = knot2X, high_y1 = knot2Y, high_m1 = mk2,
        linearFallback = linear,
        knot2X = knot2X, knot2Y = knot2Y,
        seg2_x0 = knot2X, seg2_y0 = knot2Y, seg2_m0 = mk2,
        seg2_x1 = cx4,    seg2_y1 = cy4,    seg2_m1 = m4
    )
}

// ---------------------------------------------------------------------------
// Één-knooppunt spline (met slope-cap voor hoge segment)
// ---------------------------------------------------------------------------

private fun fitOneKnotSpline(
    all: List<CalibrationEntry>,
    low: List<CalibrationEntry>,
    highAll: List<CalibrationEntry>,  // mid + high gecombineerd
    linear: CalibrationFit,
    now: Long
): SplineFit? {
    if (highAll.isEmpty()) return null

    val (cx0, cy0) = weightedCentroid(low, now)
    val (cx2, cy2) = weightedCentroid(highAll, now)

    val knotX       = SPLINE_KNOT1_MGDL
    val knotYLinear = linear.slope * knotX + linear.offset
    val knotResidual = weightedResidualNearKnot(all, now, knotX, windowMgdl = 15.0)
    val knotY = knotYLinear + knotResidual

    if (abs(knotY - knotX) > SPLINE_MAX_CORRECTION_AT_KNOT) return null
    if (abs((knotY - knotX) - (knotYLinear - knotX)) > SPLINE_MAX_DELTA_FROM_LINEAR) return null

    val h0 = knotX - cx0
    val h1 = cx2   - knotX
    if (h0 < 18.0 || h1 < 18.0) return null

    val delta0 = (knotY - cy0) / h0
    var delta1 = (cy2  - knotY) / h1

    // Absolute slope cap
    if (delta0 < SLOPE_ABS_MIN || delta0 > SLOPE_ABS_MAX) return null

    // Slope-cap hoog segment t.o.v. laag segment (geen tweede knooppunt actief)
    val delta1Max = delta0 * SLOPE_CAP_HIGH_VS_LOW_MAX
    val delta1Min = delta0 * SLOPE_CAP_HIGH_VS_LOW_MIN
    delta1 = delta1.coerceIn(delta1Min, delta1Max)

    // Na capping ook absolute cap toepassen
    delta1 = delta1.coerceIn(SLOPE_ABS_MIN, SLOPE_ABS_MAX)

    // Ratio check
    if (delta0 > 0 && delta1 > 0) {
        val ratio = if (delta0 > delta1) delta0 / delta1 else delta1 / delta0
        if (ratio > 3.0) return null
    }

    var m0 = fritschCarlson(delta0, Double.NaN, delta0)
    var mk = fritschCarlson((delta0 + delta1) / 2, delta0, delta1)
    var m2 = fritschCarlson(delta1, delta1, Double.NaN)

    if (!isMonotoneIncreasing(cx0, cy0, m0, knotX, knotY, mk)) return null
    if (!isMonotoneIncreasing(knotX, knotY, mk, cx2, cy2, m2)) return null

    return SplineFit(
        knotX = knotX, knotY = knotY,
        low_x0  = cx0,   low_y0  = cy0,   low_m0  = m0,
        low_x1  = knotX, low_y1  = knotY, low_m1  = mk,
        high_x0 = knotX, high_y0 = knotY, high_m0 = mk,
        high_x1 = cx2,   high_y1 = cy2,   high_m1 = m2,
        linearFallback = linear
    )
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

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

private fun weightedCentroid(entries: List<CalibrationEntry>, now: Long): Pair<Double, Double> {
    var sumW = 0.0; var sumWX = 0.0; var sumWY = 0.0
    for (e in entries) {
        val w = weightFor(e.timestamp, now)
        sumW += w; sumWX += w * e.sensorMgdlAtPairing; sumWY += w * e.fingerstickMgdl
    }
    return if (sumW == 0.0) entries[0].sensorMgdlAtPairing to entries[0].fingerstickMgdl
    else (sumWX / sumW) to (sumWY / sumW)
}

private fun weightedResidualNearKnot(
    entries: List<CalibrationEntry>, now: Long, knotX: Double, windowMgdl: Double
): Double {
    var sumW = 0.0; var sumWR = 0.0
    for (e in entries) {
        if (abs(e.sensorMgdlAtPairing - knotX) <= windowMgdl) {
            val w = weightFor(e.timestamp, now)
            sumW += w; sumWR += w * (e.fingerstickMgdl - e.sensorMgdlAtPairing)
        }
    }
    return if (sumW == 0.0) 0.0 else sumWR / sumW
}

private fun fritschCarlson(m: Double, deltaLeft: Double, deltaRight: Double): Double {
    if (!deltaLeft.isNaN()  && deltaLeft  == 0.0) return 0.0
    if (!deltaRight.isNaN() && deltaRight == 0.0) return 0.0
    if (!deltaLeft.isNaN() && !deltaRight.isNaN() && sign(deltaLeft) != sign(deltaRight)) return 0.0
    var result = m
    if (!deltaLeft.isNaN()) {
        val limit = 3.0 * abs(deltaLeft)
        if (abs(result) > limit) result = sign(result) * limit
    }
    if (!deltaRight.isNaN()) {
        val limit = 3.0 * abs(deltaRight)
        if (abs(result) > limit) result = sign(result) * limit
    }
    return result
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
