package app.aaps.plugins.aps.openAPSFCL.vnext.database

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FCLCycleLogRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val db by lazy { FCLAnalyzerDatabase.getInstance(context) }
    private val dao by lazy { db.cycleLogDao() }
    private val scope = CoroutineScope(Dispatchers.IO)

    private var lastCsvExportHour: Int = -1

    fun insert(entity: FCLCycleLogEntity) {
        scope.launch {
            dao.insert(entity)
            pruneOldData()
            maybeExportCsv()
        }
    }

    private suspend fun pruneOldData() {
        dao.deleteOlderThan(FCLAnalyzerDatabase.cutoffMs())
    }

    private suspend fun maybeExportCsv() {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (currentHour == lastCsvExportHour) return
        lastCsvExportHour = currentHour
        exportCsvLast7Days()
    }

    private suspend fun exportCsvLast7Days() {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000L
        val rows = dao.getSince(sevenDaysAgo)
        if (rows.isEmpty()) return

        val dir = File(
            Environment.getExternalStorageDirectory(),
            "Documents/AAPS/ANALYSE"
        )
        dir.mkdirs()
        val file = File(dir, "FCLvNext_Log_v7.csv")

        val sep = ";"
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        file.bufferedWriter().use { writer ->
            writer.write(csvHeader(sep))
            writer.newLine()
            rows.forEach { row ->
                writer.write(row.toCsvLine(sep, fmt))
                writer.newLine()
            }
        }
    }

    suspend fun getRecent(limit: Int) = dao.getRecent(limit)
    suspend fun getSince(fromMs: Long) = dao.getSince(fromMs)
    suspend fun count() = dao.count()
    suspend fun getAll() = dao.getAll()
}

// ── CSV header — exact gelijk aan FCLvNextCsvLogger ──────────────────────

private fun csvHeader(sep: String): String = listOf(
    "schema_version", "ts_utc",
    "is_night", "sterkte_pct", "timing_pct", "volhoudendheid_pct", "nacht_factor_pct",
    "doseDistributionStyle", "nightResponseStyle",
    "bg_mmol", "target_mmol", "delta_target",
    "iob", "iob_ratio", "bg_zone", "dose_access",
    "final_dose", "commanded_dose", "delivered_total", "bolus", "basal_u_h", "should_deliver",
    "external_bolus_u",
    "slope", "accel", "recent_slope", "recent_delta5m", "consistency",
    "effective_isf", "gain", "energy_base", "energy_total",
    "raw_dose", "iob_factor", "normal_dose", "desired_dose_pre_guards",
    "stagnation_active", "stagnation_boost",
    "guard_iob_limited", "guard_peak_limited", "guard_maxsmb_limited",
    "guard_mindeliver_clipped", "guard_zone_limited",
    "meal_episode_id", "minutes_since_meal_start", "rise_since_meal_start",
    "early_stage", "early_confidence", "early_target_u", "sustained_high_slope_min",
    "early_boost_active", "early_boost_count", "early_boost_factor",
    "meal_state", "commit_fraction", "minutes_since_commit",
    "peak_state", "predicted_peak", "peak_iob_boost", "effective_iob_ratio",
    "peak_max_slope", "peak_momentum", "peak_rise_since_start",
    "peak_episode_active", "suppress_for_peak", "absorption_active", "reentry_signal",
    "decision_reason",
    "watching_frontload_triggered", "watching_frontload_target_u",
    "watching_slope_ok", "watching_delta_ok", "watching_peak_rise_ok", "watching_iob_ok",
    "pred60", "rescue_state", "rescue_confidence", "rescue_reason",
    "reserve_u", "reserve_action", "reserve_delta_u", "reserve_age_min",
    "trajectory_factor", "trajectory_hard_block",
    "commit_allowed", "effective_commit_allowed", "base_commit_fraction",
    "commit_zone_factor", "commit_iob_factor", "commit_postpeak_factor",
    "commit_raw_plateau_penalty", "commit_aggression_mul",
    "commit_dose_raw", "commit_dose_final", "late_decay_mul", "episode_commit_nr",
    "iob_overshoot_factor",
    "burst_delivered_10m", "burst_cap_10m", "burst_remaining_10m",
    "hypo_active", "hypo_projected_bg", "hypo_debt_u",
    "topguard_active", "topguard_cap_factor", "top_plateau_confirmed",
    "meal_aggression_a", "meal_aggression_mul",
    "peak_iob_brake_active", "peak_approach_factor",
    "afterload_fd60_scale", "afterload_high_iob_scale",
    "suppress_reason", "lockout_reason", "commit_block_reason",
    "iob_margin_to_brake", "iob_margin_to_lockout",
    "pred_margin_to_watching", "pred_margin_to_target", "slope_margin_to_brake",
    "predicted_peak_ballistic", "future_drop_60",
    "peak_floor_active", "peak_floor_value", "h_eff",
    "iob_scale_used", "v_used",
    "iob_headroom", "dose_suppressed_u",
    "peak_approach_active", "early_reset_this_cycle", "downtrend_locked", "sensor_blip_active"
).joinToString(sep)

// ── CSV regel — delta_target afgeleid als bg - target ────────────────────

private fun FCLCycleLogEntity.toCsvLine(
    sep: String,
    fmt: DateTimeFormatter
): String {
    val ts = fmt.format(Instant.ofEpochMilli(timestampMs))
    val deltaTarget = bg - target

    fun d2(v: Double) = "%.2f".format(v)
    fun d3(v: Double) = "%.3f".format(v)
    fun bg1(v: Double) = "%.1f".format(v)
    fun bool(v: Boolean) = v.toString()

    return listOf(
        schemaVersion, ts,
        bool(isNight), sterktePct, timingPct, volhoudendheidPct, nachtFactorPct,
        doseDistributionStyle, nightResponseStyle,
        bg1(bg), bg1(target), d2(deltaTarget),
        d2(iob), d2(iobRatio), bgZone, doseAccess,
        d2(finalDose), d2(commandedDose), d2(deliveredTotal), d2(bolus), d2(basalRate),
        bool(shouldDeliver),
        d2(externalBolusU),
        d2(slope), d2(accel), d2(recentSlope), d2(recentDelta5m), d2(consistency),
        d2(effectiveISF), d2(gain), d2(energyBase), d2(energyTotal),
        d2(rawDose), d2(iobFactor), d2(normalDose), d2(desiredDosePreGuards),
        bool(stagnationActive), d2(stagnationBoost),
        bool(guardIobLimited), bool(guardPeakLimited), bool(guardMaxSmbLimited),
        bool(guardMinDeliverClipped), bool(guardZoneLimited),
        "fcl_intern", minutesSinceMealStart, d2(riseSinceMealStart),
        earlyStage, d2(earlyConfidence), d2(earlyTargetU), d2(sustainedHighSlopeMinutes),
        bool(earlyBoostActive), earlyBoostCount, d2(earlyBoostFactor),
        mealState, d2(commitFraction), minutesSinceCommit,
        peakState, bg1(predictedPeak), d2(peakIobBoost), d2(effectiveIobRatio),
        d2(peakMaxSlope), d2(peakMomentum), d2(peakRiseSinceStart),
        bool(peakEpisodeActive), bool(suppressForPeak), bool(absorptionActive),
        bool(reentrySignal), decisionReason,
        bool(watchingFrontloadTriggered), d2(watchingFrontloadTargetU),
        bool(watchingSlopeOk), bool(watchingDeltaOk),
        bool(watchingPeakRiseOk), bool(watchingIobOk),
        d2(pred60), rescueState, d2(rescueConfidence), rescueReason,
        d2(reserveU), reserveAction, d2(reserveDeltaU), reserveAgeMin,
        d2(trajectoryFactor), bool(trajectoryHardBlock),
        bool(commitAllowed), bool(effectiveCommitAllowed), d2(baseCommitFraction),
        d2(commitZoneFactor), d2(commitIobFactor), d2(commitPostPeakFactor),
        d2(commitRawPlateauPenalty), d2(commitAggressionMul),
        d2(commitDoseRaw), d2(commitDoseFinal), d2(lateDecayMul), episodeCommitNr,
        d2(iobOvershootFactor),
        d2(burstDelivered10m), d2(burstCap10m), d2(burstRemaining10m),
        bool(hypoActive), bg1(hypoProjectedBg), d2(hypoDebtU),
        bool(topGuardActive), d2(topGuardCapFactor), bool(topPlateauConfirmed),
        d2(mealAggressionA), d2(mealAggressionMul),
        bool(peakIobBrakeActive), d2(peakApproachFactor),
        d2(afterloadFutureDrop60Scale), d2(afterloadHighIobLateScale),
        suppressReason, lockoutReason, commitBlockReason,
        d2(iobMarginToBrake), d2(iobMarginToLockout),
        d2(predMarginToWatching), d2(predMarginToTarget), d2(slopeMarginToBrake),
        bg1(predictedPeakBallistic), bg1(futureDrop60),
        bool(peakFloorActive), bg1(peakFloorValue), d2(hEff),
        d2(iobScaleUsed), d2(vUsed),
        d2(iobHeadroom), d2(doseSuppressedU),
        bool(peakApproachActive), bool(earlyResetThisCycle),
        bool(downtrendLocked), bool(sensorBlipActive)
    ).joinToString(sep)
}