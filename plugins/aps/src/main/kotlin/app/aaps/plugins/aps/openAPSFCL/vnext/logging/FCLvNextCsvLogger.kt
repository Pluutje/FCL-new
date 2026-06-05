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
    var externalBolusU: Double = 0.0,

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
    var afterloadFutureDrop60Scale: Double = 1.0,
    var afterloadHighIobLateScale: Double = 1.0,

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

