package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogEntity
import java.time.Instant

/**
 * Zet FCLCycleLogEntity (Room database) om naar LogRow (analyzer domeinmodel).
 * prebolus velden bestaan niet meer in FCLvNext en zijn verwijderd.
 *
 * 05/07/2026 (Ecko): FCLCycleLogEntity is herstructureerd in @Embedded-groepen
 * (zie doc-comment bij FCLCycleLogEntity.kt) om een VerifyError-crash op de
 * platte ~150-parameter constructor op te lossen. Deze mapper voedt de
 * Analyzer/statistics-schermen — was hierdoor niet meer compileerbaar met de
 * oude platte veldnamen en is hier bijgewerkt naar de nieuwe group-paden
 * (bijv. `bg` is nu `glucoseIob.bg`). De LogRow-veldnamen zelf (linkerkant)
 * zijn ongewijzigd.
 */
fun FCLCycleLogEntity.toLogRow(): LogRow = LogRow(
    timestamp              = Instant.ofEpochMilli(timestampMs),
    isNight                = context.isNight,
    bg                     = glucoseIob.bg,
    target                 = glucoseIob.target,
    deltaTarget            = glucoseIob.bg - glucoseIob.target,
    iob                    = glucoseIob.iob,
    iobRatio               = glucoseIob.iobRatio,
    slope                  = trends.slope,
    accel                  = trends.accel,
    recentSlope            = trends.recentSlope,
    recentDelta5m          = trends.recentDelta5m,
    consistency            = trends.consistency,
    predictedPeak          = peak.predictedPeak.takeIf { it > 0.0 },
    mealEpisodeId          = mealEpisode.mealEpisodeId.takeIf { it >= 0 },
    rescueState            = rescue.rescueState,
    rescueConfidence       = rescue.rescueConfidence,
    minutesSinceMealStart  = mealEpisode.minutesSinceMealStart.takeIf { it >= 0 },
    riseSinceMealStart     = mealEpisode.riseSinceMealStart.takeIf { it > 0.0 },
    finalDose              = delivery.finalDose,
    deliveredTotal         = delivery.deliveredTotal,
    externalBolusU         = delivery.externalBolusU,
    shouldDeliver          = delivery.shouldDeliver,
    mealState              = mealEpisode.mealState,
    earlyConfidence        = mealEpisode.earlyConfidence,
    earlyTargetU           = mealEpisode.earlyTargetU,
    decisionReason         = peak.decisionReason,
    watchingFrontloadTriggered = watching.watchingFrontloadTriggered,
    watchingFrontloadTargetU   = watching.watchingFrontloadTargetU,
    commitAllowed          = forensic.commitAllowed,
    effectiveCommitAllowed = forensic.effectiveCommitAllowed,
    commitDoseRaw          = forensic.commitDoseRaw,
    commitDoseFinal        = forensic.commitDoseFinal,
    hypoActive             = hypo.hypoActive,
    hypoProjectedBg        = hypo.hypoProjectedBg,
    topGuardActive         = topGuard.topGuardActive,
    topGuardCapFactor      = topGuard.topGuardCapFactor,
    trajectoryHardBlock    = forensic.trajectoryHardBlock,
    sterktePct             = context.sterktePct,
    timingPct              = context.timingPct,
    volhoudendheidPct      = context.volhoudendheidPct,
    nachtFactorPct         = context.nachtFactorPct,
    doseDistribution       = context.doseDistributionStyle,
    nightResponseStyle     = context.nightResponseStyle,
    peakIobBrakeActive     = peakBenadering.peakIobBrakeActive,
    peakApproachFactor     = peakBenadering.peakApproachFactor,
    suppressForPeak        = peak.suppressForPeak,
    peakState              = peak.peakState,
    suppressReason         = suppress.suppressReason,
    lockoutReason          = suppress.lockoutReason,
    commitBlockReason      = suppress.commitBlockReason,
    iobMarginToBrake       = marges.iobMarginToBrake,
    iobMarginToLockout     = marges.iobMarginToLockout,
    predMarginToWatching   = marges.predMarginToWatching,
    predMarginToTarget     = marges.predMarginToTarget,
    slopeMarginToBrake     = marges.slopeMarginToBrake,
    predictedPeakBallistic = peakInternals.predictedPeakBallistic,
    futureDrop60           = peakInternals.futureDrop60,
    peakFloorActive        = peakInternals.peakFloorActive,
    peakFloorValue         = peakInternals.peakFloorValue,
    hEff                   = peakInternals.hEff,
    iobScaleUsed           = peakInternals.iobScaleUsed,
    vUsed                  = peakInternals.vUsed,
    peakMaxSlope           = peak.peakMaxSlope,
    iobHeadroom            = doseerruimte.iobHeadroom,
    doseSuppressedU        = doseerruimte.doseSuppressedU,
    peakApproachActive     = doseerruimte.peakApproachActive,
    earlyResetThisCycle    = doseerruimte.earlyResetThisCycle,
    downtrendLocked        = doseerruimte.downtrendLocked,
    sensorBlipActive       = doseerruimte.sensorBlipActive,
    earlyBoostActive       = mealEpisode.earlyBoostActive,
    earlyBoostCount        = mealEpisode.earlyBoostCount,
    earlyBoostFactor       = mealEpisode.earlyBoostFactor,
    guardMaxSmbLimited     = guards.guardMaxSmbLimited,
    episodeCommitNr        = forensic.episodeCommitNr,
    bgStijgtNogFors         = forensic.bgStijgtNogFors,
    commitNrUsed            = forensic.commitNrUsed
)
