package app.aaps.plugins.calibration.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.plugins.calibration.CalibrationFit
import app.aaps.plugins.calibration.SplineFit
import app.aaps.plugins.calibration.db.CalibrationEntry

/** SharedPreferences key for the manual offset. */
const val PREF_MANUAL_OFFSET_MMOL = "spline_manual_offset_mmol"

/** Maximum absolute manual offset (mmol/L). */
const val MANUAL_OFFSET_MAX_MMOL = 1.5f

/** Step size for the manual offset slider (mmol/L). */
const val MANUAL_OFFSET_STEP_MMOL = 0.05f

@Immutable
data class SplineCalibrationUiState(
    val sessionStart: Long?             = null,
    val warmUpEndsAt: Long?             = null,
    val isInWarmUp: Boolean             = false,
    val entries: List<CalibrationEntry> = emptyList(),
    val splineFit: SplineFit?           = null,
    /** Linear fallback — always computed when entries ≥ 2, used for the UI baseline and status text. */
    val linearFit: CalibrationFit?      = null,
    val now: Long                       = 0L,
    val selectedEntryId: Long?          = null,
    val glucoseUnit: GlucoseUnit        = GlucoseUnit.MGDL,
    /**
     * User-controlled additive offset in mmol/L.
     * Applied on top of the spline (or linear fallback) in the plugin's calibrate() call.
     * Persisted in SharedPreferences under [PREF_MANUAL_OFFSET_MMOL].
     */
    val manualOffsetMmol: Float         = 0f
) {
    /** True when the spline is active; false means linear fallback is in use. */
    val isSplineActive: Boolean get() = splineFit != null

    /** Manual offset converted to mg/dL for use in fit calculations. */
    val manualOffsetMgdl: Double get() = manualOffsetMmol * 18.0182

    /**
     * Gecalibreerde waarde voor een ruwe sensorwaarde (mg/dL), inclusief manual offset.
     * Gebruikt spline als actief, anders lineair. Geeft null als geen fit beschikbaar.
     */
    fun calibratedValue(sensorMgdl: Double): Double? {
        val offset = manualOffsetMgdl
        if (splineFit != null) return splineFit.apply(sensorMgdl, offset)
        if (linearFit != null && linearFit.isApplicable)
            return linearFit.slope * sensorMgdl + linearFit.offset + offset
        return null
    }
}
