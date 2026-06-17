package app.aaps.plugins.calibration

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.calibration.AddEntryResult
import app.aaps.core.interfaces.calibration.Calibration
import android.content.Context
import app.aaps.core.interfaces.calibration.CalibrationContext
import app.aaps.plugins.calibration.compose.PREF_MANUAL_OFFSET_MMOL
import dagger.hilt.android.qualifiers.ApplicationContext
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventCalibrationChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.icons.IcCalibration
import app.aaps.plugins.calibration.compose.SplineCalibrationComposeContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Spline Calibration Plugin — a drop-in alternative to [LinearCalibrationPlugin].
 *
 * Uses a monotone piecewise-cubic Hermite spline (Fritsch–Carlson) with a
 * single interior knot at 180 mg/dL (≈ 10 mmol/L) to model the S-shaped
 * sensor bias that linear calibration cannot correct.
 *
 * Falls back gracefully to the linear fit when:
 *   - Fewer than [MIN_ENTRIES_FOR_SPLINE] entries are available, or
 *   - The spline fit is rejected (not monotone, correction too large, etc.).
 *
 * Calibration entries now live in the main AAPS DB (model [CAL]) via
 * [PersistenceLayer] — shared with [LinearCalibrationPlugin]. Fingerstick
 * entries entered under either plugin are visible in both, making it safe
 * to switch between plugins without losing calibration history.
 */
@Singleton
class SplineCalibrationPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val notificationManager: NotificationManager,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val rxBus: RxBus,
    @ApplicationContext private val appContext: Context
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.CALIBRATION)
        .icon(IcCalibration)
        .pluginName(R.string.spline_calibration_name)
        .shortName(R.string.spline_calibration_shortname)
        .description(R.string.description_spline_calibration)
        .composeContent { SplineCalibrationComposeContent() },
    aapsLogger, rh
), Calibration {

    private var scope: CoroutineScope? = null

    override suspend fun onStart() {
        super.onStart()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Calibration entries live in the main DB and arrive both from local entry (master)
        // and NS sync (follower). Re-emit EventCalibrationChanged on any change so the BG graph
        // recomputes the fit — identiek aan LinearCalibrationPlugin.
        scope?.launch {
            persistenceLayer.observeChanges<CAL>().collect {
                rxBus.send(EventCalibrationChanged())
            }
        }
        // App-wide "Reset databases" wipes the table via Room clearAllTables, which bypasses the
        // change-tracking flow above — observe the dedicated cleared signal so the graph recomputes.
        scope?.launch {
            persistenceLayer.databaseClearedFlow.collect {
                rxBus.send(EventCalibrationChanged())
            }
        }
    }

    override suspend fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    override suspend fun calibrate(
        data: MutableList<InMemoryGlucoseValue>,
        context: CalibrationContext
    ): MutableList<InMemoryGlucoseValue> {
        if (data.isEmpty()) return data

        val now = dateUtil.now()
        val sessionStart = context.sensorSessionStart
            ?: persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp


        detectAndNotifyGap(data, sessionStart)

        if (sessionStart == null) {
            aapsLogger.debug(LTag.GLUCOSE) { "SplineCalibration: no sensor session start, identity" }
            return data
        }

        val entries = persistenceLayer.getValidCalibrationEntriesSince(sessionStart)

        // Try spline first; fall back to linear.
        val spline = fitSplineCalibration(entries, now)
        val linear = spline?.linearFallback ?: fitLinearCalibration(entries, now)

        // Manual offset (mmol/L → mg/dL), persisted by SplineCalibrationViewModel
        val manualOffsetMgdl = appContext
            .getSharedPreferences("fcl_spline_cal", Context.MODE_PRIVATE)
            .getFloat(PREF_MANUAL_OFFSET_MMOL, 0f)
            .toDouble() * 18.0182

        if (spline != null) {
            // Spline fit succeeded — apply curve + manual offset.
            for (entry in data) {
                if (entry.timestamp >= sessionStart) {
                    entry.calibrated = spline.apply(entry.value, manualOffsetMgdl)
                }
            }
            aapsLogger.debug(LTag.GLUCOSE) {
                "SplineCalibration[SPLINE]: knotX=${spline.knotX} knotY=%.2f corrAtKnot=%.2f entries=${entries.size} applied=${data.count { it.calibrated != null }}/${data.size}"
                    .format(spline.knotY, spline.correctionAtKnot)
            }
        } else if (linear != null) {
            // Spline fallback: use linear.
            if (!linear.slopeInRange) {
                aapsLogger.warn(LTag.GLUCOSE, "SplineCalibration[LINEAR fallback]: slope ${linear.slope} outside range, identity")
                return data
            }
            if (!linear.correctionInRange) {
                aapsLogger.warn(LTag.GLUCOSE, "SplineCalibration[LINEAR fallback]: correction ${linear.correctionAtCenter} outside range, identity")
                return data
            }
            for (entry in data) {
                if (entry.timestamp >= sessionStart) {
                    entry.calibrated = linear.slope * entry.value + linear.offset + manualOffsetMgdl
                }
            }
            aapsLogger.debug(LTag.GLUCOSE) {
                "SplineCalibration[LINEAR fallback]: slope=${linear.slope} offset=${linear.offset} entries=${entries.size} applied=${data.count { it.calibrated != null }}/${data.size}"
            }
        } else {
            aapsLogger.debug(LTag.GLUCOSE) { "SplineCalibration: ${entries.size} entries (<$MIN_ENTRIES_FOR_FIT), identity" }
        }

        return data
    }

    override suspend fun checkPreconditions(): AddEntryResult = checkPreconditionsAt(dateUtil.now())

    private suspend fun checkPreconditionsAt(timestamp: Long): AddEntryResult {
        val sessionStart = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp
            ?: return AddEntryResult.Rejected.NoSession
        val delta = glucoseStatusProvider.glucoseStatusData?.shortAvgDelta
        if (delta != null) {
            val activeFit = fitLinearCalibration(persistenceLayer.getValidCalibrationEntriesSince(sessionStart), timestamp)
            val effectiveThreshold = if (activeFit != null && activeFit.isApplicable) {
                DELTA_GATE_MGDL_PER_5MIN * activeFit.slope
            } else {
                DELTA_GATE_MGDL_PER_5MIN
            }
            if (abs(delta) > effectiveThreshold) return AddEntryResult.Rejected.DeltaTooHigh(delta, effectiveThreshold)
        }
        persistenceLayer.getBgReadingsDataFromTimeToTime(
            start = timestamp - PAIR_LOOKBACK_MS,
            end = timestamp,
            ascending = false
        ).firstOrNull() ?: return AddEntryResult.Rejected.NoSensorPair
        return AddEntryResult.Accepted
    }

    override suspend fun addEntry(bgMgdl: Double, timestamp: Long): AddEntryResult {
        val pre = checkPreconditionsAt(timestamp)
        if (pre is AddEntryResult.Rejected) {
            aapsLogger.warn(LTag.GLUCOSE, "SplineCalibration.addEntry rejected: $pre")
            return pre
        }
        val pair = persistenceLayer.getBgReadingsDataFromTimeToTime(
            start = timestamp - PAIR_LOOKBACK_MS,
            end = timestamp,
            ascending = false
        ).first()
        persistenceLayer.insertOrUpdateCalibrationEntry(
            CAL(timestamp = timestamp, fingerstickMgdl = bgMgdl, sensorMgdlAtPairing = pair.value)
        )
        aapsLogger.debug(LTag.GLUCOSE) {
            "SplineCalibration.addEntry: fingerstick=$bgMgdl sensorAtPairing=${pair.value}"
        }
        return AddEntryResult.Accepted
    }

    private suspend fun detectAndNotifyGap(data: List<InMemoryGlucoseValue>, sessionStart: Long?) {
        val gapThresholdMs = T.mins(GAP_THRESHOLD_MIN).msecs()
        var gapTime: Long? = null
        for (i in 0 until data.size - 1) {
            val newer = data[i].timestamp
            val older = data[i + 1].timestamp
            if (sessionStart != null && newer <= sessionStart) break
            if (newer - older > gapThresholdMs) {
                gapTime = older + (newer - older) / 2
                break
            }
        }
        val detectedAt = gapTime ?: return

        val nearby = persistenceLayer.getTherapyEventDataFromToTime(
            from = detectedAt - SENSOR_CHANGE_PROXIMITY_MS,
            to   = detectedAt + SENSOR_CHANGE_PROXIMITY_MS
        ).any { it.type == TE.Type.SENSOR_CHANGE }
        if (nearby) return

        notificationManager.post(
            id = NotificationId.SENSOR_CHANGE_DETECTED,
            text = rh.gs(R.string.sensor_change_detected_text, dateUtil.timeString(detectedAt)),
            actions = listOf(
                NotificationAction(R.string.sensor_change_detected_action) {
                    runBlocking { insertSensorChange(detectedAt) }
                }
            )
        )
    }

    private suspend fun insertSensorChange(timestamp: Long) {
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp   = timestamp,
                type        = TE.Type.SENSOR_CHANGE,
                glucoseUnit = GlucoseUnit.MGDL
            ),
            action     = Action.CAREPORTAL,
            source     = Sources.SensorInsert,
            note       = null,
            listValues = listOf(
                ValueWithUnit.Timestamp(timestamp),
                ValueWithUnit.TEType(TE.Type.SENSOR_CHANGE)
            )
        )
    }

    // clearAllTables() verwijderd: calibratiedata leeft nu in de centrale
    // AAPS-database (model CAL via PersistenceLayer), niet meer in een eigen
    // CalibrationDatabase. De app-wide "Reset databases" wist de CAL-tabel
    // automatisch; databaseClearedFlow (zie onStart) zorgt voor de UI-refresh.

    private companion object {
        const val GAP_THRESHOLD_MIN         = 30L
        const val DELTA_GATE_MGDL_PER_5MIN  = 5.0
        const val SENSOR_CHANGE_PROXIMITY_MS = 60L * 60L * 1000L
        const val PAIR_LOOKBACK_MS           = 10L * 60L * 1000L
    }
}