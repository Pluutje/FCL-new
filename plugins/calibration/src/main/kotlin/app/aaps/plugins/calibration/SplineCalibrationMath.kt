package app.aaps.plugins.calibration

import app.aaps.plugins.calibration.db.CalibrationEntry
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/**
 * Minimum number of calibration entries needed to attempt a spline fit.
 * With fewer points the knot at [SPLINE_KNOT_MGDL] cannot be bracketed
 * on both sides, so we fall back to the linear fit.
 */
const val MIN_ENTRIES_FOR_SPLINE = 4

/**
 * Interior knot position (mg/dL).  Placed at 180 mg/dL (≈ 10 mmol/L) where
 * sensor S-curve behaviour typically inflects.  Data density above ~12 mmol/L
 * is too sparse to constrain a second knot reliably.
 */
const val SPLINE_KNOT_MGDL = 180.0

/**
 * Maximum allowed correction (mg/dL) that the spline may apply at the knot.
 * A larger correction almost certainly means a noisy / outlier fit; fall back
 * to linear in that case.
 */
const val SPLINE_MAX_CORRECTION_AT_KNOT = 40.0

/**
 * Maximum delta (mg/dL) between the linear-fit correction and the spline
 * correction at the knot.  Keeps the spline close to the linear baseline
 * when data are sparse.
 */
const val SPLINE_MAX_DELTA_FROM_LINEAR = 25.0

// ---------------------------------------------------------------------------
// Public data types
// ---------------------------------------------------------------------------

/**
 * A piecewise monotone cubic Hermite spline with a single interior knot.
 *
 * The mapping is: **calibrated_mg_dL = spline(sensor_mg_dL)**
 *
 * Two cubic segments, joined at [knotX]:
 *   - Segment 0: x in (-∞, knotX] — anchored at the weighted centroid of the
 *     low-range data and at the knot.
 *   - Segment 1: x in [knotX, +∞) — anchored at the knot and the centroid of
 *     the high-range data.
 *
 * Outside the data range the spline is extrapolated linearly using the
 * endpoint tangent (C¹ continuous, no curvature extrapolation).
 */
data class SplineFit(
    /** x-coordinate of the interior knot (mg/dL). */
    val knotX: Double,
    /** Calibrated value at the knot. */
    val knotY: Double,

    // Segment 0 Hermite coefficients (low segment)
    val low_x0: Double, val low_y0: Double, val low_m0: Double,
    val low_x1: Double, val low_y1: Double, val low_m1: Double,

    // Segment 1 Hermite coefficients (high segment)
    val high_x0: Double, val high_y0: Double, val high_m0: Double,
    val high_x1: Double, val high_y1: Double, val high_m1: Double,

    /** Linear baseline used to sanity-check the spline correction. */
    val linearFallback: CalibrationFit
) {
    /**
     * Apply the spline to a raw sensor value [sensorMgdl], returning the
     * calibrated value in mg/dL.
     */
    fun apply(sensorMgdl: Double): Double = when {
        sensorMgdl <= low_x0  -> low_y0 + low_m0 * (sensorMgdl - low_x0)          // linear extrapolation left
        sensorMgdl >= high_x1 -> high_y1 + high_m1 * (sensorMgdl - high_x1)        // linear extrapolation right
        sensorMgdl <= knotX   -> hermite(sensorMgdl, low_x0, low_y0, low_m0, low_x1, low_y1, low_m1)
        else                  -> hermite(sensorMgdl, high_x0, high_y0, high_m0, high_x1, high_y1, high_m1)
    }

    /**
     * Correction (mg/dL) applied at the knot, analogous to [CalibrationFit.correctionAtCenter].
     */
    val correctionAtKnot: Double get() = knotY - knotX
}

// ---------------------------------------------------------------------------
// Public fit function
// ---------------------------------------------------------------------------

/**
 * Fit a monotone piecewise-cubic (Fritsch–Carlson) calibration curve to
 * [entries], using time-decay weights identical to [fitLinearCalibration].
 *
 * Returns **null** (caller should use [fitLinearCalibration] as fallback) when:
 * - Fewer than [MIN_ENTRIES_FOR_SPLINE] entries are available.
 * - No entries exist on both sides of [SPLINE_KNOT_MGDL]
 *   (knot is not bracketed — slope extrapolation would be unreliable).
 * - The resulting spline is not globally monotone-increasing
 *   (physiologically impossible for a calibration).
 * - The spline correction at the knot is implausibly large.
 * - The linear fallback itself is null (degenerate data).
 */
fun fitSplineCalibration(entries: List<CalibrationEntry>, now: Long): SplineFit? {
    if (entries.size < MIN_ENTRIES_FOR_SPLINE) return null

    val linear = fitLinearCalibration(entries, now) ?: return null

    // Split entries into low (≤ knot) and high (> knot) groups.
    val low  = entries.filter { it.sensorMgdlAtPairing <= SPLINE_KNOT_MGDL }
    val high = entries.filter { it.sensorMgdlAtPairing >  SPLINE_KNOT_MGDL }

    // Both sides must be represented; otherwise the interior knot is unconstrained.
    if (low.isEmpty() || high.isEmpty()) return null

    // ---------------------------------------------------------------------------
    // Step 1: Compute weighted centroids for each segment.
    // ---------------------------------------------------------------------------
    val (cx0, cy0) = weightedCentroid(low, now)
    val (cx2, cy2) = weightedCentroid(high, now)

    // ---------------------------------------------------------------------------
    // Step 2: The knot value is determined by the linear fit at knotX.
    // This keeps the spline close to the linear baseline in the middle —
    // only the segment slopes are adjusted to accommodate the S-curve.
    // We then allow a small data-driven correction on top.
    // ---------------------------------------------------------------------------
    val knotX = SPLINE_KNOT_MGDL
    val knotYLinear = linear.slope * knotX + linear.offset

    // Data-driven residual at the knot: weighted mean of (fingerstick - sensor)
    // for all entries near the knot (within 30 mg/dL either side).
    val knotResidual = weightedResidualNearKnot(entries, now, knotX, windowMgdl = 30.0)
    val knotY = knotYLinear + knotResidual

    // Sanity-check absolute correction at knot.
    val correctionAtKnot = knotY - knotX
    if (abs(correctionAtKnot) > SPLINE_MAX_CORRECTION_AT_KNOT) return null

    // Sanity-check delta from linear.
    val linearCorrectionAtKnot = knotYLinear - knotX
    if (abs(correctionAtKnot - linearCorrectionAtKnot) > SPLINE_MAX_DELTA_FROM_LINEAR) return null

    // ---------------------------------------------------------------------------
    // Step 3: Estimate tangents at the three control points using finite
    // differences, then apply Fritsch–Carlson monotonicity constraints.
    // ---------------------------------------------------------------------------

    // Chord slopes between consecutive control points.
    val h0 = knotX - cx0           // width of low segment
    val h1 = cx2  - knotX          // width of high segment
    if (h0 <= 0.0 || h1 <= 0.0) return null

    val delta0 = (knotY - cy0) / h0   // chord slope, low segment
    val delta1 = (cy2  - knotY) / h1  // chord slope, high segment

    // Initial tangent estimates (mean of adjacent chords).
    var m0 = delta0                           // left endpoint: one-sided
    var mk = (delta0 + delta1) / 2.0          // knot: average
    var m2 = delta1                           // right endpoint: one-sided

    // Fritsch–Carlson step: enforce monotonicity.
    // If a chord slope is 0, the adjacent tangents must also be 0.
    m0 = fritschCarlson(m0, delta0, Double.NaN)
    mk = fritschCarlson(mk, delta0, delta1)
    m2 = fritschCarlson(m2, Double.NaN, delta1)

    // ---------------------------------------------------------------------------
    // Step 4: Verify the resulting spline is monotone-increasing over [cx0, cx2].
    // ---------------------------------------------------------------------------
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

/** Evaluate a cubic Hermite polynomial at [x] given two endpoints with tangents. */
private fun hermite(
    x: Double,
    x0: Double, y0: Double, m0: Double,
    x1: Double, y1: Double, m1: Double
): Double {
    val h = x1 - x0
    if (h == 0.0) return y0
    val t = (x - x0) / h
    val t2 = t * t
    val t3 = t2 * t
    // Hermite basis functions
    val h00 =  2*t3 - 3*t2 + 1
    val h10 =    t3 - 2*t2 + t
    val h01 = -2*t3 + 3*t2
    val h11 =    t3 -   t2
    return h00*y0 + h10*h*m0 + h01*y1 + h11*h*m1
}

/**
 * Weighted centroid (x̄, ȳ) of [entries], using time-decay weights.
 * x = sensorMgdlAtPairing, y = fingerstickMgdl.
 */
private fun weightedCentroid(entries: List<CalibrationEntry>, now: Long): Pair<Double, Double> {
    var sumW  = 0.0
    var sumWX = 0.0
    var sumWY = 0.0
    for (e in entries) {
        val w = weightFor(e.timestamp, now)
        sumW  += w
        sumWX += w * e.sensorMgdlAtPairing
        sumWY += w * e.fingerstickMgdl
    }
    return if (sumW == 0.0) entries[0].sensorMgdlAtPairing to entries[0].fingerstickMgdl
    else   (sumWX / sumW) to (sumWY / sumW)
}

/**
 * Weighted mean residual (fingerstick - sensor) for entries within
 * [windowMgdl] mg/dL of [knotX], using time-decay weights.
 * Returns 0.0 when no entries fall in the window.
 */
private fun weightedResidualNearKnot(
    entries: List<CalibrationEntry>,
    now: Long,
    knotX: Double,
    windowMgdl: Double
): Double {
    var sumW = 0.0
    var sumWR = 0.0
    for (e in entries) {
        if (abs(e.sensorMgdlAtPairing - knotX) <= windowMgdl) {
            val w = weightFor(e.timestamp, now)
            sumW  += w
            sumWR += w * (e.fingerstickMgdl - e.sensorMgdlAtPairing)
        }
    }
    return if (sumW == 0.0) 0.0 else sumWR / sumW
}

/**
 * Fritsch–Carlson tangent limiter for a single control point.
 *
 * [deltaLeft] and [deltaRight] are the chord slopes of the adjacent segments
 * (pass [Double.NaN] for the missing side at endpoints).
 *
 * Returns a modified tangent that guarantees monotonicity of the segment(s)
 * adjacent to this point.
 */
private fun fritschCarlson(m: Double, deltaLeft: Double, deltaRight: Double): Double {
    // If the chord on either side is 0, tangent must be 0 to keep monotonicity.
    if (!deltaLeft.isNaN()  && deltaLeft  == 0.0) return 0.0
    if (!deltaRight.isNaN() && deltaRight == 0.0) return 0.0

    // If signs of adjacent chords differ, this is a local extremum — tangent must be 0.
    if (!deltaLeft.isNaN() && !deltaRight.isNaN() && sign(deltaLeft) != sign(deltaRight)) return 0.0

    var result = m

    // Clamp using the Fritsch–Carlson 3× rule:
    // |m| ≤ 3 × chord on each side to prevent overshoot.
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

/**
 * Returns true iff the cubic Hermite segment [x0,x1] is monotone-increasing
 * throughout (derivative ≥ 0 everywhere).
 *
 * Uses the sufficient condition from Fritsch & Carlson (1980):
 * the segment is monotone if α² + β² ≤ 9, where
 *   α = m0 / delta,  β = m1 / delta,  delta = (y1−y0)/(x1−x0).
 */
private fun isMonotoneIncreasing(
    x0: Double, y0: Double, m0: Double,
    x1: Double, y1: Double, m1: Double
): Boolean {
    val h = x1 - x0
    if (h <= 0.0) return false
    val delta = (y1 - y0) / h
    if (delta < 0.0) return false          // decreasing chord → not monotone-increasing
    if (delta == 0.0) return m0 == 0.0 && m1 == 0.0
    val alpha = m0 / delta
    val beta  = m1 / delta
    // Fritsch–Carlson sufficient monotonicity condition
    return alpha * alpha + beta * beta <= 9.0 + 1e-9
}

// ---------------------------------------------------------------------------
// Convenience: apply SplineFit to an InMemoryGlucoseValue-like sensor reading.
// Used from SplineCalibrationPlugin.
// ---------------------------------------------------------------------------

/**
 * Returns the best available calibrated value for [sensorMgdl]:
 * the [SplineFit] if non-null, otherwise the [CalibrationFit] linear fallback.
 */
fun applyBestFit(sensorMgdl: Double, spline: SplineFit?, linear: CalibrationFit?): Double? {
    if (spline != null) return spline.apply(sensorMgdl)
    if (linear != null && linear.isApplicable) return linear.slope * sensorMgdl + linear.offset
    return null
}
