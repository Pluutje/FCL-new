package app.aaps.plugins.calibration.compose

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.calibration.fitLinearCalibration
import app.aaps.plugins.calibration.fitSplineCalibration
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private val WARM_UP_DURATION_MS = T.hours(1).msecs()

@HiltViewModel
@Stable
class SplineCalibrationViewModel @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val profileUtil: ProfileUtil,
    private val uel: UserEntryLogger,
    private val aapsLogger: AAPSLogger,
    val dateUtil: DateUtil,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs by lazy {
        context.getSharedPreferences("fcl_spline_cal", Context.MODE_PRIVATE)
    }

    private val _uiState = MutableStateFlow(SplineCalibrationUiState())
    val uiState: StateFlow<SplineCalibrationUiState> = _uiState.asStateFlow()

    init {
        // Laad opgeslagen offset direct bij initialisatie
        val savedOffset = prefs.getFloat(PREF_MANUAL_OFFSET_MMOL, 0f)
        _uiState.update { it.copy(manualOffsetMmol = savedOffset) }
        observeChanges()
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeChanges() {
        merge(
            persistenceLayer.observeChanges<CAL>().map { },
            persistenceLayer.observeChanges<TE>().map { }
        )
            .onStart { emit(Unit) }
            .debounce(500L)
            .mapLatest { recomputeSuspend() }
            .launchIn(viewModelScope)
    }

    private suspend fun recomputeSuspend() {
        val now          = dateUtil.now()
        val sessionStart = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp
        val entries      = if (sessionStart != null) persistenceLayer.getValidCalibrationEntriesSince(sessionStart) else emptyList()
        val warmUpEndsAt = sessionStart?.plus(WARM_UP_DURATION_MS)
        val isInWarmUp   = warmUpEndsAt != null && now < warmUpEndsAt

        val splineResult = fitSplineCalibration(entries, now)
        val spline = splineResult.fit
        val linear = spline?.linearFallback ?: fitLinearCalibration(entries, now)

        _uiState.update { previous ->
            val stillPresent = previous.selectedEntryId != null &&
                entries.any { it.id == previous.selectedEntryId }
            previous.copy(
                sessionStart    = sessionStart,
                warmUpEndsAt    = warmUpEndsAt,
                isInWarmUp      = isInWarmUp,
                entries         = entries,
                splineFit       = spline,
                splineFailureReason = splineResult.reason,
                linearFit       = linear,
                now             = now,
                selectedEntryId = if (stillPresent) previous.selectedEntryId else entries.lastOrNull()?.id,
                glucoseUnit     = profileUtil.units
                // manualOffsetMmol wordt NIET gereset bij herberekening — blijft zoals ingesteld
            )
        }
    }

    /**
     * Sla een nieuwe handmatige offset op (in mmol/L).
     * Wordt direct in SharedPreferences gepersisteerd zodat de SplineCalibrationPlugin
     * hem ook buiten de UI kan lezen.
     */
    fun setManualOffset(offsetMmol: Float) {
        // Snap naar dichtstbijzijnde stap
        val snapped = (offsetMmol / MANUAL_OFFSET_STEP_MMOL).toInt() * MANUAL_OFFSET_STEP_MMOL
        val clamped = snapped.coerceIn(-MANUAL_OFFSET_MAX_MMOL, MANUAL_OFFSET_MAX_MMOL)
        prefs.edit().putFloat(PREF_MANUAL_OFFSET_MMOL, clamped).apply()
        _uiState.update { it.copy(manualOffsetMmol = clamped) }
        aapsLogger.debug(LTag.GLUCOSE) { "SplineCalibration: manual offset set to $clamped mmol/L" }
    }

    fun resetManualOffset() = setManualOffset(0f)

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            val entry = _uiState.value.entries.firstOrNull { it.id == id }
            try {
                // UserEntry logging gebeurt nu intern in PersistenceLayer.invalidateCalibrationEntry
                persistenceLayer.invalidateCalibrationEntry(
                    id = id,
                    action = Action.CALIBRATION_REMOVED,
                    source = Sources.CalibrationDialog,
                    note = "id=$id",
                    listValues = entry?.let {
                        listOf(
                            ValueWithUnit.fromGlucoseUnit(
                                profileUtil.fromMgdlToUnits(it.fingerstickMgdl),
                                profileUtil.units
                            )
                        )
                    } ?: emptyList()
                )
            } catch (e: Exception) {
                aapsLogger.error(LTag.DATABASE, "Failed to invalidate calibration entry id=$id", e)
            }
        }
    }

    fun selectEntry(id: Long) {
        _uiState.update { it.copy(selectedEntryId = id) }
    }
}