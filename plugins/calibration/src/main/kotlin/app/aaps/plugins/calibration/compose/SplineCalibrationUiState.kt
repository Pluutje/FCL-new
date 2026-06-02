package app.aaps.plugins.calibration.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.plugins.calibration.CalibrationFit
import app.aaps.plugins.calibration.SplineFit
import app.aaps.plugins.calibration.db.CalibrationEntry

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
    val glucoseUnit: GlucoseUnit        = GlucoseUnit.MGDL
) {
    /** True when the spline is active; false means linear fallback is in use. */
    val isSplineActive: Boolean get() = splineFit != null
}
