package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.io.File
import java.util.Locale

data class FCLvNextCsvLogRow(

    // ── Context ──
    var ts: DateTime = DateTime.now(),
    var isNight: Boolean = false,
    var bg: Double = 0.0,
    var target: Double = 0.0,

    // ── S/T/V/N — vervangt de 5 enum-stijl kolommen ──
    var sterktePct: Int = 100,
    var timingPct: Int = 100,
    var volhoudendheidPct: Int = 100,
    var nachtFactorPct: Int = 85,

    // ── Log-only stijl labels (voor achterwaartse leesbaarbaarheid) ──
    var doseDistributionStyle: String = "",
    var nightResponseStyle: String = "",

    // ── Trends ──
    var slope: Double = 0.0,
    var accel: Double = 0.0,
    var recentSlope: Double = 0.0,
    var recentDelta5m: Double = 0.0,
    var consistency: Double = 0.0,

    // ── IOB ──
    var iob: Double = 0.0,
    var iobRatio: Double = 0.0,
    var bgZone: String = "",
    var doseAccess: String = "",

    // ── Model ──
    var effectiveISF: Double = 0.0,
    var gain: Double = 0.0,
    var energyBase: Double = 0.0,
    var energyTotal: Double = 0.0,

    // ── Meal episode ──
    var mealEpisodeId: Long = -1,
    var minutesSinceMealStart: Int = -1,
    var riseSinceMealStart: Double = 0.0,

    // ── Stagnation ──
    var stagnationActive: Boolean = false,
    var stagnationBoost: Double = 0.0,

    // ── Dose math ──
    var rawDose: Double = 0.0,
    var iobFactor: Double = 0.0,
    var normalDose: Double = 0.0,
    var desiredDosePreGuards: Double = 0.0,

    // ── Guards ──
    var guardIobLimited: Boolean = false,
    var guardPeakLimited: Boolean = false,
    var guardMaxSmbLimited: Boolean = false,
    var guardMinDeliverClipped: Boolean = false,
    var guardZoneLimited: Boolean = false,

    // ── Early ──
    var earlyStage: Int = 0,
    var earlyConfidence: Double = 0.0,
    var earlyTargetU: Double = 0.0,
    var sustainedHighSlopeMinutes: Double = 0.0,
    var earlyBoostActive: Boolean = false,   // was boost actief in deze cyclus?
    var earlyBoostCount: Int = 0,            // aantal boost-commits tot nu toe in episode
    var earlyBoostFactor: Double = 1.0,      // effectieve boost-factor die gebruikt is

    // ── Decision / phase ──
    var mealState: String = "",
    var commitFraction: Double = 0.0,
    var minutesSinceCommit: Int = -1,

    // ── Peak ──
    var peakState: String = "",
    var predictedPeak: Double = 0.0,
    var peakIobBoost: Double = 1.0,
    var effectiveIobRatio: Double = 0.0,
    var peakMaxSlope: Double = 0.0,
    var peakMomentum: Double = 0.0,
    var peakRiseSinceStart: Double = 0.0,
    var peakEpisodeActive: Boolean = false,
    var suppressForPeak: Boolean = false,
    var absorptionActive: Boolean = false,
    var reentrySignal: Boolean = false,
    var decisionReason: String = "",

    // ── Watching frontload ──
    var watchingFrontloadTriggered: Boolean = false,
    var watchingFrontloadTargetU: Double = 0.0,
    var watchingSlopeOk: Boolean = false,
    var watchingDeltaOk: Boolean = false,
    var watchingPeakRiseOk: Boolean = false,
    var watchingIobOk: Boolean = false,

    // ── Rescue ──
    var pred60: Double = 0.0,
    var rescueState: String = "",
    var rescueConfidence: Double = 0.0,
    var rescueReason: String = "",

    // ── Pre-bolus ──

    // ── Execution ──
    var finalDose: Double = 0.0,
    var commandedDose: Double = 0.0,
    var deliveredTotal: Double = 0.0,
    var bolus: Double = 0.0,
    var basalRate: Double = 0.0,
    var shouldDeliver: Boolean = false,

    // ── Reserve ──
    var reserveU: Double = 0.0,
    var reserveAction: String = "NONE",
    var reserveDeltaU: Double = 0.0,
    var reserveAgeMin: Int = -1,

    // ── Trajectory ──
    var trajectoryFactor: Double = 1.0,
    var trajectoryHardBlock: Boolean = false,

    // ── Commit causality ──
    var commitAllowed: Boolean = false,
    var effectiveCommitAllowed: Boolean = false,
    var baseCommitFraction: Double = 0.0,
    var commitZoneFactor: Double = 0.0,
    var commitIobFactor: Double = 0.0,
    var commitPostPeakFactor: Double = 0.0,
    var commitRawPlateauPenalty: Double = 0.0,
    var commitAggressionMul: Double = 0.0,
    var commitDoseRaw: Double = 0.0,
    var commitDoseFinal: Double = 0.0,
    var lateDecayMul: Double = 1.0,
    var episodeCommitNr: Int = 0,

    // ── IOB overshoot ──
    var iobOvershootFactor: Double = 1.0,

    // ── Burst cap ──
    var burstDelivered10m: Double = 0.0,
    var burstCap10m: Double = 0.0,
    var burstRemaining10m: Double = 0.0,

    // ── Hypo ──
    var hypoActive: Boolean = false,
    var hypoProjectedBg: Double = 0.0,
    var hypoDebtU: Double = 0.0,          // opgebouwde schuld door hypo-rem in huidige episode

    // ── Top guard ──
    var topGuardActive: Boolean = false,
    var topGuardCapFactor: Double = 1.0,
    var topPlateauConfirmed: Boolean = false,

    // ── Aggression ──
    var mealAggressionA: Double = 0.0,
    var mealAggressionMul: Double = 0.0,

    // ── Peak benadering ──
    var peakIobBrakeActive: Boolean = false,
    var peakApproachFactor: Double = 1.0,

    // ── Suppress/lockout reden ──
    var suppressReason: String = "NONE",
    var lockoutReason: String = "NONE",
    var commitBlockReason: String = "NONE",

    // ── Marges tot drempels ──
    var iobMarginToBrake: Double = 0.0,
    var iobMarginToLockout: Double = 0.0,
    var predMarginToWatching: Double = 0.0,
    var predMarginToTarget: Double = 0.0,
    var slopeMarginToBrake: Double = 0.0,

    // ── Peak internals ──
    var predictedPeakBallistic: Double = 0.0,
    var futureDrop60: Double = 0.0,
    var peakFloorActive: Boolean = false,
    var peakFloorValue: Double = 0.0,
    var hEff: Double = 0.0,
    var iobScaleUsed: Double = 0.0,
    var vUsed: Double = 0.0,

    // ── Doseerruimte context ──
    var iobHeadroom: Double = 0.0,
    var doseSuppressedU: Double = 0.0,
    var peakApproachActive: Boolean = false,
    var earlyResetThisCycle: Boolean = false,
    var downtrendLocked: Boolean = false,
    var sensorBlipActive: Boolean = false
)

/*
object FCLvNextCsvLogger {

    @Volatile
    private var schemaVerified = false
    private const val SCHEMA_VERSION = "6"
    private const val FILE_NAME = "FCLvNext_Log_v6.csv"

    private const val MAX_DAYS  = 30
    private const val MAX_LINES = MAX_DAYS * 288
    private const val SEP = ";"

    @Volatile
    private var lastTrimDayKeyUtc: String? = null

//    private fun getFile(context: android.content.Context): File {
//        val dir = File(context.getExternalFilesDir(null), "ANALYSE")
//        if (!dir.exists()) dir.mkdirs()
//        return File(dir, FILE_NAME)
//    }

    private var outputDir: File? = null

    fun init(context: android.content.Context) {
        outputDir = File(
            android.os.Environment.getExternalStorageDirectory(),
            "Documents/AAPS/ANALYSE"
        )
    }

    private fun getFile(): File {
        val dir = outputDir ?: File("/data/local/tmp/ANALYSE") // fallback
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun utcIso(ts: DateTime): String =
        ts.withZone(DateTimeZone.UTC).toString("yyyy-MM-dd'T'HH:mm:ss'Z'")

    private fun utcDayKey(ts: DateTime): String =
        ts.withZone(DateTimeZone.UTC).toString("yyyy-MM-dd")

    // ── Header ────────────────────────────────────────────────────────────
    // Schema v6: 5 enum-stijl kolommen vervangen door sterkte/timing/volhoudendheid/nacht_factor
    private val header = listOf(
        // META
        "schema_version",
        "ts_utc",

        // CONTEXT — S/T/V/N vervangt de oude enum-kolommen
        "is_night",
        "sterkte_pct",
        "timing_pct",
        "volhoudendheid_pct",
        "nacht_factor_pct",
        "doseDistributionStyle",
        "nightResponseStyle",

        // GLUCOSE/IOB
        "bg_mmol",
        "target_mmol",
        "delta_target",
        "iob",
        "iob_ratio",
        "bg_zone",
        "dose_access",

        // DELIVERY/EXECUTION
        "final_dose",
        "commanded_dose",
        "delivered_total",
        "bolus",
        "basal_u_h",
        "should_deliver",

        // TRENDS
        "slope",
        "accel",
        "recent_slope",
        "recent_delta5m",
        "consistency",

        // MODEL
        "effective_isf",
        "gain",
        "energy_base",
        "energy_total",
        "raw_dose",
        "iob_factor",
        "normal_dose",
        "desired_dose_pre_guards",

        // STAGNATION
        "stagnation_active",
        "stagnation_boost",

        // GUARDS
        "guard_iob_limited",
        "guard_peak_limited",
        "guard_maxsmb_limited",
        "guard_mindeliver_clipped",
        "guard_zone_limited",

        // MEAL EPISODE
        "meal_episode_id",
        "minutes_since_meal_start",
        "rise_since_meal_start",
        "early_stage",
        "early_confidence",
        "early_target_u",
        "sustained_high_slope_min",
        "early_boost_active",
        "early_boost_count",
        "early_boost_factor",
        "meal_state",
        "commit_fraction",
        "minutes_since_commit",

        // PEAK/PREDICTION
        "peak_state",
        "predicted_peak",
        "peak_iob_boost",
        "effective_iob_ratio",
        "peak_max_slope",
        "peak_momentum",
        "peak_rise_since_start",
        "peak_episode_active",
        "suppress_for_peak",
        "absorption_active",
        "reentry_signal",
        "decision_reason",

        // WATCHING
        "watching_frontload_triggered",
        "watching_frontload_target_u",
        "watching_slope_ok",
        "watching_delta_ok",
        "watching_peak_rise_ok",
        "watching_iob_ok",

        // RESCUE
        "pred60",
        "rescue_state",
        "rescue_confidence",
        "rescue_reason",

        // PREBOLUS

        // RESERVE
        "reserve_u",
        "reserve_action",
        "reserve_delta_u",
        "reserve_age_min",

        // FORENSIC
        "trajectory_factor",
        "trajectory_hard_block",
        "commit_allowed",
        "effective_commit_allowed",
        "base_commit_fraction",
        "commit_zone_factor",
        "commit_iob_factor",
        "commit_postpeak_factor",
        "commit_raw_plateau_penalty",
        "commit_aggression_mul",
        "commit_dose_raw",
        "commit_dose_final", "late_decay_mul", "episode_commit_nr",
        "iob_overshoot_factor",
        "burst_delivered_10m",
        "burst_cap_10m",
        "burst_remaining_10m",
        "hypo_active",
        "hypo_projected_bg",
        "hypo_debt_u",
        "topguard_active",
        "topguard_cap_factor",
        "top_plateau_confirmed",
        "meal_aggression_a",
        "meal_aggression_mul",
        "peak_iob_brake_active",
        "peak_approach_factor",

        // SUPPRESS/LOCKOUT
        "suppress_reason",
        "lockout_reason",
        "commit_block_reason",

        // MARGES
        "iob_margin_to_brake",
        "iob_margin_to_lockout",
        "pred_margin_to_watching",
        "pred_margin_to_target",
        "slope_margin_to_brake",

        // PEAK INTERNALS
        "predicted_peak_ballistic",
        "future_drop_60",
        "peak_floor_active",
        "peak_floor_value",
        "h_eff",
        "iob_scale_used",
        "v_used",

        // DOSEERRUIMTE
        "iob_headroom",
        "dose_suppressed_u",
        "peak_approach_active",
        "early_reset_this_cycle",
        "downtrend_locked",
        "sensor_blip_active"
    ).joinToString(SEP)

    fun log(row: FCLvNextCsvLogRow) {
        android.util.Log.d("FCLvNextCsvLogger", "log() called, outputDir=${outputDir?.absolutePath}")
        try {
            val file = getFile()
            if (!schemaVerified) {
                verifySchemaIntegrity()
                schemaVerified = true
            }
            if (!file.exists() || file.length() == 0L) {
                file.writeText(header + "\n")
            } else {
                val firstLine = file.useLines { it.firstOrNull() }
                if (firstLine != header) {
                    val backupName = file.nameWithoutExtension +
                        "_backup_" + DateTime.now().toString("yyyyMMdd_HHmmss") + ".csv"
                    file.renameTo(File(file.parentFile, backupName))
                    file.writeText(header + "\n")
                }
            }
            file.appendText(buildLine(row) + "\n")
            val todayKey = utcDayKey(row.ts)
            if (lastTrimDayKeyUtc != todayKey) {
                lastTrimDayKeyUtc = todayKey
                trimIfNeeded(file)
            }
        } catch (e: Exception) {
            android.util.Log.e("FCLvNextCsvLogger", "Write failed: ${e.message}", e)
        }
    }

    private fun trimIfNeeded(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val lines = file.readLines()
            if (lines.size <= MAX_LINES + 1) return
            val headerLine = lines.first()
            val body = lines.drop(1).takeLast(MAX_LINES)
            file.writeText(headerLine + "\n")
            file.appendText(body.joinToString("\n") + "\n")
        } catch (e: Exception) {
            android.util.Log.e("FCLvNextCsvLogger", "Write failed: ${e.message}", e)
        }
    }

    private fun sanitize(text: String): String =
        text.replace(SEP, ",").replace("\n", " ").replace("\r", " ").trim()

    private fun buildLine(row: FCLvNextCsvLogRow): String {
        val tsUtc = utcIso(row.ts)
        val deltaTarget = row.bg - row.target

        return listOf(
            SCHEMA_VERSION, tsUtc,

            // Context — S/T/V/N
            row.isNight,
            row.sterktePct,
            row.timingPct,
            row.volhoudendheidPct,
            row.nachtFactorPct,
            sanitize(row.doseDistributionStyle),
            sanitize(row.nightResponseStyle),

            bg1(row.bg), bg1(row.target), d2(deltaTarget),
            u2(row.iob), u2(row.iobRatio),
            sanitize(row.bgZone), sanitize(row.doseAccess),

            u2(row.finalDose), u2(row.commandedDose), u2(row.deliveredTotal),
            u2(row.bolus), u2(row.basalRate), row.shouldDeliver,

            t2(row.slope), a2(row.accel), t2(row.recentSlope),
            t2(row.recentDelta5m), t2(row.consistency),

            bg2(row.effectiveISF), u2(row.gain),
            e2(row.energyBase), e2(row.energyTotal),
            u2(row.rawDose), u2(row.iobFactor), u2(row.normalDose),
            u2(row.desiredDosePreGuards),

            row.stagnationActive, e2(row.stagnationBoost),

            row.guardIobLimited, row.guardPeakLimited, row.guardMaxSmbLimited,
            row.guardMinDeliverClipped, row.guardZoneLimited,

            row.mealEpisodeId, row.minutesSinceMealStart,
            bg1(row.riseSinceMealStart),
            row.earlyStage, t2(row.earlyConfidence), u2(row.earlyTargetU), t2(row.sustainedHighSlopeMinutes),
            row.earlyBoostActive, row.earlyBoostCount, t2(row.earlyBoostFactor),
            sanitize(row.mealState), t2(row.commitFraction), row.minutesSinceCommit,

            sanitize(row.peakState), bg1(row.predictedPeak),
            u2(row.peakIobBoost), u2(row.effectiveIobRatio),
            t2(row.peakMaxSlope), t2(row.peakMomentum),
            bg1(row.peakRiseSinceStart), row.peakEpisodeActive,
            row.suppressForPeak, row.absorptionActive, row.reentrySignal,
            sanitize(row.decisionReason),

            row.watchingFrontloadTriggered, u2(row.watchingFrontloadTargetU),
            row.watchingSlopeOk, row.watchingDeltaOk,
            row.watchingPeakRiseOk, row.watchingIobOk,

            bg1(row.pred60), sanitize(row.rescueState),
            t2(row.rescueConfidence), sanitize(row.rescueReason),


            u2(row.reserveU), sanitize(row.reserveAction),
            u2(row.reserveDeltaU), row.reserveAgeMin,

            u2(row.trajectoryFactor), row.trajectoryHardBlock,
            row.commitAllowed, row.effectiveCommitAllowed,
            t2(row.baseCommitFraction), t2(row.commitZoneFactor),
            t2(row.commitIobFactor), t2(row.commitPostPeakFactor),
            t2(row.commitRawPlateauPenalty), t2(row.commitAggressionMul),
            u2(row.commitDoseRaw), u2(row.commitDoseFinal), t2(row.lateDecayMul), row.episodeCommitNr,

            t2(row.iobOvershootFactor),
            u2(row.burstDelivered10m), u2(row.burstCap10m), u2(row.burstRemaining10m),
            row.hypoActive, bg1(row.hypoProjectedBg), u2(row.hypoDebtU),
            row.topGuardActive, t2(row.topGuardCapFactor), row.topPlateauConfirmed,
            t2(row.mealAggressionA), t2(row.mealAggressionMul),
            row.peakIobBrakeActive, t2(row.peakApproachFactor),

            sanitize(row.suppressReason), sanitize(row.lockoutReason),
            sanitize(row.commitBlockReason),

            d2(row.iobMarginToBrake), d2(row.iobMarginToLockout),
            d2(row.predMarginToWatching), d2(row.predMarginToTarget),
            d2(row.slopeMarginToBrake),

            bg1(row.predictedPeakBallistic), bg1(row.futureDrop60),
            row.peakFloorActive, bg1(row.peakFloorValue),
            t2(row.hEff), t2(row.iobScaleUsed), t2(row.vUsed),

            u2(row.iobHeadroom), u2(row.doseSuppressedU),
            row.peakApproachActive, row.earlyResetThisCycle,
            row.downtrendLocked, row.sensorBlipActive

        ).joinToString(SEP)
    }

    private fun verifySchemaIntegrity() {
        val headerCount = header.split(SEP).size
        val dataCount   = buildLine(FCLvNextCsvLogRow()).split(SEP).size
        if (headerCount != dataCount)
            throw IllegalStateException(
                "FCLvNext CSV SCHEMA MISMATCH: header=$headerCount columns, data=$dataCount columns"
            )
    }

    private fun bg1(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun bg2(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun d2(x: Double)  = String.format(Locale.US, "%.2f", x)
    private fun u2(x: Double)  = String.format(Locale.US, "%.2f", x)
    private fun a2(x: Double)  = String.format(Locale.US, "%.2f", x)
    private fun e2(x: Double)  = String.format(Locale.US, "%.2f", x)
    private fun t2(x: Double)  = String.format(Locale.US, "%.2f", x)

}   */



