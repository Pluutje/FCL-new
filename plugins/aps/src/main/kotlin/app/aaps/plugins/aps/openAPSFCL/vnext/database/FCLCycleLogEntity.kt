package app.aaps.plugins.aps.openAPSFCL.vnext.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity die exact overeenkomt met FCLvNext_Log_v6.csv (130 kolommen).
 * Bewaartermijn: 90 dagen. Bewuste keuze om alle velden te bewaren zodat
 * de geïntegreerde FCL Analyzer volledig kan werken zonder informatieverlies.
 *
 * Kolommen zijn gegroepeerd conform de CSV header van FCLvNextCsvLogger.
 */
@Entity(
    tableName = "fcl_cycle_log",
    indices = [Index("timestampMs")]
)
data class FCLCycleLogEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ── META ──────────────────────────────────────────────────────────────
    val schemaVersion: String = "7",
    val timestampMs: Long,              // ts_utc als epochMillis

    // ── CONTEXT ───────────────────────────────────────────────────────────
    val isNight: Boolean,
    val sterktePct: Int,
    val timingPct: Int,
    val volhoudendheidPct: Int,
    val nachtFactorPct: Int,
    val doseDistributionStyle: String,
    val nightResponseStyle: String,

    // ── GLUCOSE / IOB ─────────────────────────────────────────────────────
    val bg: Double,
    val target: Double,
    // delta_target is afgeleid (bg - target) — wordt berekend bij CSV export
    val iob: Double,
    val iobRatio: Double,
    val bgZone: String,
    val doseAccess: String,

    // ── DELIVERY / EXECUTION ──────────────────────────────────────────────
    val finalDose: Double,
    val commandedDose: Double,
    val deliveredTotal: Double,
    val bolus: Double,
    val basalRate: Double,
    val realDeliveredBasalU: Double = 0.0,
    val realDeliveredBolusU: Double = 0.0,
    val profileBasalUH: Double = 0.0,
    val activityActive: Boolean = false,
    val activityInsulinPct: Double = 100.0,
    val activityTargetAdjust: Double = 0.0,
    val aapsMultiplier: Double = 1.0,
    val nfLevelGeleerd: Double = 5.0,
    val nfLevelEffectief: Double = 5.0,
    val nachtAggressiviteit: Int = 5,
    val nightStagnationDeltaMin: Double = 0.0,
    val nightStagnationEnergyBoost: Double = 0.0,
    val nightPersistentAggressionMul: Double = 0.0,
    val nightCooldownMinutes: Int = 0,
    val nightCorrectionHoldDeltaMax: Double = 0.0,
    val nightAbsorptionDoseFactor: Double = 0.0,
    val shouldDeliver: Boolean,
    // Externe insuline (handmatige bolus of AAPS SMB) gedetecteerd via IOB-delta.
    // Berekend als max(0, currentIOB - prevIOB + expectedDecay - fclOwnDose).
    // 0.0 als geen externe bolus gedetecteerd. Gebruikt door analyzer voor
    // correcte totalInsulinDelivered en hasManualCorrection markering.
    val externalBolusU: Double = 0.0,

    // ── TRENDS ────────────────────────────────────────────────────────────
    val slope: Double,
    val accel: Double,
    val recentSlope: Double,
    val recentDelta5m: Double,
    val consistency: Double,

    // ── MODEL ─────────────────────────────────────────────────────────────
    val effectiveISF: Double,
    val gain: Double,
    val energyBase: Double,
    val energyTotal: Double,
    val rawDose: Double,
    val iobFactor: Double,
    val normalDose: Double,
    val desiredDosePreGuards: Double,

    // ── STAGNATION ────────────────────────────────────────────────────────
    val stagnationActive: Boolean,
    val stagnationBoost: Double,

    // ── GUARDS ────────────────────────────────────────────────────────────
    val guardIobLimited: Boolean,
    val guardPeakLimited: Boolean,
    val guardMaxSmbLimited: Boolean,
    val guardMinDeliverClipped: Boolean,
    val guardZoneLimited: Boolean,

    // ── MEAL EPISODE ──────────────────────────────────────────────────────
    val mealEpisodeId: Long,
    val minutesSinceMealStart: Int,
    val riseSinceMealStart: Double,
    val earlyStage: Int,
    val earlyConfidence: Double,
    val earlyTargetU: Double,
    val sustainedHighSlopeMinutes: Double,
    val earlyBoostActive: Boolean,
    val earlyBoostCount: Int,
    val earlyBoostFactor: Double,
    val mealState: String,
    val commitFraction: Double,
    val minutesSinceCommit: Int,

    // ── PEAK / PREDICTION ─────────────────────────────────────────────────
    val peakState: String,
    val predictedPeak: Double,
    val peakIobBoost: Double,
    val effectiveIobRatio: Double,
    val peakMaxSlope: Double,
    val peakMomentum: Double,
    val peakRiseSinceStart: Double,
    val peakEpisodeActive: Boolean,
    val suppressForPeak: Boolean,
    val absorptionActive: Boolean,
    val reentrySignal: Boolean,
    val decisionReason: String,

    // ── WATCHING FRONTLOAD ────────────────────────────────────────────────
    val watchingFrontloadTriggered: Boolean,
    val watchingFrontloadTargetU: Double,
    val watchingSlopeOk: Boolean,
    val watchingDeltaOk: Boolean,
    val watchingPeakRiseOk: Boolean,
    val watchingIobOk: Boolean,

    // ── RESCUE ────────────────────────────────────────────────────────────
    val pred60: Double,
    val rescueState: String,
    val rescueConfidence: Double,
    val rescueReason: String,

    // ── RESERVE ───────────────────────────────────────────────────────────
    val reserveU: Double,
    val reserveAction: String,
    val reserveDeltaU: Double,
    val reserveAgeMin: Int,

    // ── FORENSIC / TRAJECTORY ─────────────────────────────────────────────
    val trajectoryFactor: Double,
    val trajectoryHardBlock: Boolean,
    val commitAllowed: Boolean,
    val effectiveCommitAllowed: Boolean,
    val baseCommitFraction: Double,
    val commitZoneFactor: Double,
    val commitIobFactor: Double,
    val commitPostPeakFactor: Double,
    val commitRawPlateauPenalty: Double,
    val commitAggressionMul: Double,
    val commitDoseRaw: Double,
    val commitDoseFinal: Double,
    val lateDecayMul: Double,
    val episodeCommitNr: Int,
    val iobOvershootFactor: Double,

    // ── BURST CAP ─────────────────────────────────────────────────────────
    val burstDelivered10m: Double,
    val burstCap10m: Double,
    val burstRemaining10m: Double,

    // ── HYPO ──────────────────────────────────────────────────────────────
    val hypoActive: Boolean,
    val hypoProjectedBg: Double,
    val hypoDebtU: Double,

    // ── TOP GUARD ─────────────────────────────────────────────────────────
    val topGuardActive: Boolean,
    val topGuardCapFactor: Double,
    val topPlateauConfirmed: Boolean,

    // ── AGGRESSION ────────────────────────────────────────────────────────
    val mealAggressionA: Double,
    val mealAggressionMul: Double,

    // ── PEAK BENADERING ───────────────────────────────────────────────────
    val peakIobBrakeActive: Boolean,
    val peakApproachFactor: Double,
    val afterloadFutureDrop60Scale: Double,
    val afterloadHighIobLateScale: Double,

    // ── SUPPRESS / LOCKOUT ────────────────────────────────────────────────
    val suppressReason: String,
    val lockoutReason: String,
    val commitBlockReason: String,

    // ── MARGES TOT DREMPELS ───────────────────────────────────────────────
    val iobMarginToBrake: Double,
    val iobMarginToLockout: Double,
    val predMarginToWatching: Double,
    val predMarginToTarget: Double,
    val slopeMarginToBrake: Double,

    // ── PEAK INTERNALS ────────────────────────────────────────────────────
    val predictedPeakBallistic: Double,
    val futureDrop60: Double,
    val peakFloorActive: Boolean,
    val peakFloorValue: Double,
    val hEff: Double,
    val iobScaleUsed: Double,
    val vUsed: Double,

    // ── DOSEERRUIMTE CONTEXT ──────────────────────────────────────────────
    val iobHeadroom: Double,
    val doseSuppressedU: Double,
    val peakApproachActive: Boolean,
    val earlyResetThisCycle: Boolean,
    val downtrendLocked: Boolean,
    val sensorBlipActive: Boolean
)
