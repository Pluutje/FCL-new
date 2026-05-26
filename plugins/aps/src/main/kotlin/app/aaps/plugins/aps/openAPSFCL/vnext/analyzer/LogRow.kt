package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Instant

data class LogRow(
    val timestamp: Instant,
    val bg: Double,
    val target: Double,
    val deltaTarget: Double,
    val iob: Double,
    val iobRatio: Double,
    val slope: Double,
    val accel: Double,
    val recentSlope: Double,
    val recentDelta5m: Double,
    val consistency: Double,
    val predictedPeak: Double?,
    val mealEpisodeId: Long?,
    val minutesSinceMealStart: Int?,
    val riseSinceMealStart: Double?,
    val finalDose: Double,
    val deliveredTotal: Double,
    val externalBolusU: Double = 0.0,   // handmatige bolus of AAPS SMB buiten FCLvNext
    val rescueState: String = "IDLE",       // "IDLE" | "ARMED" | "CONFIRMED"
    val rescueConfidence: Double = 0.0,
    val shouldDeliver: Boolean,

    val mealState: String,
    val earlyConfidence: Double,
    val earlyTargetU: Double,
    val decisionReason: String,

    val watchingFrontloadTriggered: Boolean,
    val watchingFrontloadTargetU: Double,

    val commitAllowed: Boolean,
    val effectiveCommitAllowed: Boolean,
    val commitDoseRaw: Double,
    val commitDoseFinal: Double,

    val hypoActive: Boolean,
    val hypoProjectedBg: Double,

    val topGuardActive: Boolean,
    val topGuardCapFactor: Double,
    val trajectoryHardBlock: Boolean,

    // S/T/V/N
    val sterktePct: Int,
    val timingPct: Int,
    val volhoudendheidPct: Int,
    val nachtFactorPct: Int,
    val doseDistribution: String,
    val nightResponseStyle: String,

    val peakIobBrakeActive: Boolean,
    val peakApproachFactor: Double,
    val suppressForPeak: Boolean,
    val peakState: String,

    val suppressReason: String,
    val lockoutReason: String,
    val commitBlockReason: String,
    val iobMarginToBrake: Double,
    val iobMarginToLockout: Double,
    val predMarginToWatching: Double,
    val predMarginToTarget: Double,
    val slopeMarginToBrake: Double,
    val predictedPeakBallistic: Double,
    val futureDrop60: Double,
    val peakFloorActive: Boolean,
    val peakFloorValue: Double,
    val hEff: Double,
    val iobScaleUsed: Double,
    val vUsed: Double,
    val peakMaxSlope: Double,
    val iobHeadroom: Double,
    val doseSuppressedU: Double,
    val peakApproachActive: Boolean,
    val earlyResetThisCycle: Boolean,
    val downtrendLocked: Boolean,
    val sensorBlipActive: Boolean,
    val earlyBoostActive: Boolean = false,
    val earlyBoostCount: Int = 0,
    val earlyBoostFactor: Double = 1.0,
    val guardMaxSmbLimited: Boolean = false,
    val episodeCommitNr: Int = 0
)