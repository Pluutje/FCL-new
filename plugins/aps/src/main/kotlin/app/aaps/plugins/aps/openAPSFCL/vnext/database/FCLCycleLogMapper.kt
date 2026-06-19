package app.aaps.plugins.aps.openAPSFCL.vnext.database

import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCsvLogRow

/**
 * Top-level extensiefunctie — buiten elk object zodat deze importeerbaar is
 * vanuit FCLvNext.kt via:
 *   import app.aaps.plugins.aps.openAPSFCL.vnext.database.toEntity
 */
fun FCLvNextCsvLogRow.toEntity(): FCLCycleLogEntity = FCLCycleLogEntity(
    // META
    schemaVersion          = "6",
    timestampMs            = ts.millis,

    // CONTEXT
    isNight                = isNight,
    sterktePct             = sterktePct,
    timingPct              = timingPct,
    volhoudendheidPct      = volhoudendheidPct,
    nachtFactorPct         = nachtFactorPct,
    doseDistributionStyle  = doseDistributionStyle,
    nightResponseStyle     = nightResponseStyle,

    // GLUCOSE / IOB
    bg                     = bg,
    target                 = target,
    iob                    = iob,
    iobRatio               = iobRatio,
    bgZone                 = bgZone,
    doseAccess             = doseAccess,

    // DELIVERY / EXECUTION
    finalDose              = finalDose,
    commandedDose          = commandedDose,
    deliveredTotal         = deliveredTotal,
    bolus                  = bolus,
    basalRate              = basalRate,
    realDeliveredBasalU    = realDeliveredBasalU,
    realDeliveredBolusU    = realDeliveredBolusU,
    profileBasalUH         = profileBasalUH,
    activityActive         = activityActive,
    activityInsulinPct     = activityInsulinPct,
    activityTargetAdjust   = activityTargetAdjust,
    aapsMultiplier         = aapsMultiplier,
    nfLevelGeleerd                = nfLevelGeleerd,
    nfLevelEffectief              = nfLevelEffectief,
    nachtAggressiviteit           = nachtAggressiviteit,
    nightStagnationDeltaMin       = nightStagnationDeltaMin,
    nightStagnationEnergyBoost    = nightStagnationEnergyBoost,
    nightPersistentAggressionMul  = nightPersistentAggressionMul,
    nightCooldownMinutes          = nightCooldownMinutes,
    nightCorrectionHoldDeltaMax   = nightCorrectionHoldDeltaMax,
    nightAbsorptionDoseFactor     = nightAbsorptionDoseFactor,
    shouldDeliver          = shouldDeliver,
    externalBolusU         = externalBolusU,

    // TRENDS
    slope                  = slope,
    accel                  = accel,
    recentSlope            = recentSlope,
    recentDelta5m          = recentDelta5m,
    consistency            = consistency,

    // MODEL
    effectiveISF           = effectiveISF,
    gain                   = gain,
    energyBase             = energyBase,
    energyTotal            = energyTotal,
    rawDose                = rawDose,
    iobFactor              = iobFactor,
    normalDose             = normalDose,
    desiredDosePreGuards   = desiredDosePreGuards,

    // STAGNATION
    stagnationActive       = stagnationActive,
    stagnationBoost        = stagnationBoost,

    // GUARDS
    guardIobLimited        = guardIobLimited,
    guardPeakLimited       = guardPeakLimited,
    guardMaxSmbLimited     = guardMaxSmbLimited,
    guardMinDeliverClipped = guardMinDeliverClipped,
    guardZoneLimited       = guardZoneLimited,

    // MEAL EPISODE
    mealEpisodeId          = mealEpisodeId,
    minutesSinceMealStart  = minutesSinceMealStart,
    riseSinceMealStart     = riseSinceMealStart,
    earlyStage             = earlyStage,
    earlyConfidence        = earlyConfidence,
    earlyTargetU           = earlyTargetU,
    sustainedHighSlopeMinutes = sustainedHighSlopeMinutes,
    earlyBoostActive       = earlyBoostActive,
    earlyBoostCount        = earlyBoostCount,
    earlyBoostFactor       = earlyBoostFactor,
    mealState              = mealState,
    commitFraction         = commitFraction,
    minutesSinceCommit     = minutesSinceCommit,

    // PEAK / PREDICTION
    peakState              = peakState,
    predictedPeak          = predictedPeak,
    peakIobBoost           = peakIobBoost,
    effectiveIobRatio      = effectiveIobRatio,
    peakMaxSlope           = peakMaxSlope,
    peakMomentum           = peakMomentum,
    peakRiseSinceStart     = peakRiseSinceStart,
    peakEpisodeActive      = peakEpisodeActive,
    suppressForPeak        = suppressForPeak,
    absorptionActive       = absorptionActive,
    reentrySignal          = reentrySignal,
    decisionReason         = decisionReason,

    // WATCHING FRONTLOAD
    watchingFrontloadTriggered = watchingFrontloadTriggered,
    watchingFrontloadTargetU   = watchingFrontloadTargetU,
    watchingSlopeOk            = watchingSlopeOk,
    watchingDeltaOk            = watchingDeltaOk,
    watchingPeakRiseOk         = watchingPeakRiseOk,
    watchingIobOk              = watchingIobOk,

    // RESCUE
    pred60                 = pred60,
    rescueState            = rescueState,
    rescueConfidence       = rescueConfidence,
    rescueReason           = rescueReason,

    // RESERVE
    reserveU               = reserveU,
    reserveAction          = reserveAction,
    reserveDeltaU          = reserveDeltaU,
    reserveAgeMin          = reserveAgeMin,

    // FORENSIC / TRAJECTORY
    trajectoryFactor       = trajectoryFactor,
    trajectoryHardBlock    = trajectoryHardBlock,
    commitAllowed          = commitAllowed,
    effectiveCommitAllowed = effectiveCommitAllowed,
    baseCommitFraction     = baseCommitFraction,
    commitZoneFactor       = commitZoneFactor,
    commitIobFactor        = commitIobFactor,
    commitPostPeakFactor   = commitPostPeakFactor,
    commitRawPlateauPenalty = commitRawPlateauPenalty,
    commitAggressionMul    = commitAggressionMul,
    commitDoseRaw          = commitDoseRaw,
    commitDoseFinal        = commitDoseFinal,
    lateDecayMul           = lateDecayMul,
    episodeCommitNr        = episodeCommitNr,
    iobOvershootFactor     = iobOvershootFactor,

    // BURST CAP
    burstDelivered10m      = burstDelivered10m,
    burstCap10m            = burstCap10m,
    burstRemaining10m      = burstRemaining10m,

    // HYPO
    hypoActive             = hypoActive,
    hypoProjectedBg        = hypoProjectedBg,
    hypoDebtU              = hypoDebtU,

    // TOP GUARD
    topGuardActive         = topGuardActive,
    topGuardCapFactor      = topGuardCapFactor,
    topPlateauConfirmed    = topPlateauConfirmed,

    // AGGRESSION
    mealAggressionA        = mealAggressionA,
    mealAggressionMul      = mealAggressionMul,

    // PEAK BENADERING
    peakIobBrakeActive     = peakIobBrakeActive,
    peakApproachFactor          = peakApproachFactor,
    afterloadFutureDrop60Scale  = afterloadFutureDrop60Scale,
    afterloadHighIobLateScale   = afterloadHighIobLateScale,

    // SUPPRESS / LOCKOUT
    suppressReason         = suppressReason,
    lockoutReason          = lockoutReason,
    commitBlockReason      = commitBlockReason,

    // MARGES TOT DREMPELS
    iobMarginToBrake       = iobMarginToBrake,
    iobMarginToLockout     = iobMarginToLockout,
    predMarginToWatching   = predMarginToWatching,
    predMarginToTarget     = predMarginToTarget,
    slopeMarginToBrake     = slopeMarginToBrake,

    // PEAK INTERNALS
    predictedPeakBallistic = predictedPeakBallistic,
    futureDrop60           = futureDrop60,
    peakFloorActive        = peakFloorActive,
    peakFloorValue         = peakFloorValue,
    hEff                   = hEff,
    iobScaleUsed           = iobScaleUsed,
    vUsed                  = vUsed,

    // DOSEERRUIMTE CONTEXT
    iobHeadroom            = iobHeadroom,
    doseSuppressedU        = doseSuppressedU,
    peakApproachActive     = peakApproachActive,
    earlyResetThisCycle    = earlyResetThisCycle,
    downtrendLocked        = downtrendLocked,
    sensorBlipActive       = sensorBlipActive
)
