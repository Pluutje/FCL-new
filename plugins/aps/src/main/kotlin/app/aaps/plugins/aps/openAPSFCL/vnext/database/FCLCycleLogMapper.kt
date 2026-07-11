package app.aaps.plugins.aps.openAPSFCL.vnext.database

import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCsvLogRow

/**
 * Top-level extensiefunctie — buiten elk object zodat deze importeerbaar is
 * vanuit FCLvNext.kt via:
 *   import app.aaps.plugins.aps.openAPSFCL.vnext.database.toEntity
 *
 * 05/07/2026 (Ecko): herschreven om per @Embedded-groep een klein sub-object
 * te bouwen i.p.v. één platte aanroep met alle ~150 velden. Zie de doc-
 * comment bij FCLCycleLogEntity voor waarom (registerlimiet invoke-direct/
 * range, veroorzaakte een VerifyError-crash met de oude platte structuur).
 */
fun FCLvNextCsvLogRow.toEntity(): FCLCycleLogEntity = FCLCycleLogEntity(
    // META
    schemaVersion          = "8",
    timestampMs            = ts.millis,

    context = ContextFields(
        isNight                = isNight,
        sterktePct             = sterktePct,
        timingPct              = timingPct,
        volhoudendheidPct      = volhoudendheidPct,
        nachtFactorPct         = nachtFactorPct,
        doseDistributionStyle  = doseDistributionStyle,
        nightResponseStyle     = nightResponseStyle
    ),

    glucoseIob = GlucoseIobFields(
        bg       = bg,
        target   = target,
        iob      = iob,
        iobRatio = iobRatio,
        bgZone   = bgZone,
        doseAccess = doseAccess
    ),

    delivery = DeliveryFields(
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
        accelDeclineSinceUncertain    = accelDeclineSinceUncertain,
        shouldDeliver          = shouldDeliver,
        externalBolusU         = externalBolusU
    ),

    trends = TrendsFields(
        slope             = slope,
        accel             = accel,
        recentSlope       = recentSlope,
        recentDelta5m     = recentDelta5m,
        consistency       = consistency,
        curveFitR2        = curveFitR2,
        curveAcceleration = curveAcceleration,
        toppingOutBoost   = toppingOutBoost
    ),

    model = ModelFields(
        effectiveISF           = effectiveISF,
        gain                   = gain,
        energyBase             = energyBase,
        energyTotal            = energyTotal,
        rawDose                = rawDose,
        iobFactor              = iobFactor,
        normalDose             = normalDose,
        desiredDosePreGuards   = desiredDosePreGuards
    ),

    stagnation = StagnationFields(
        stagnationActive = stagnationActive,
        stagnationBoost  = stagnationBoost
    ),

    guards = GuardsFields(
        guardIobLimited        = guardIobLimited,
        guardPeakLimited       = guardPeakLimited,
        guardMaxSmbLimited     = guardMaxSmbLimited,
        guardMinDeliverClipped = guardMinDeliverClipped,
        guardZoneLimited       = guardZoneLimited
    ),

    mealEpisode = MealEpisodeFields(
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
        minutesSinceCommit     = minutesSinceCommit
    ),

    peak = PeakFields(
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
        decisionReason         = decisionReason
    ),

    watching = WatchingFields(
        watchingFrontloadTriggered = watchingFrontloadTriggered,
        watchingFrontloadTargetU   = watchingFrontloadTargetU,
        watchingSlopeOk            = watchingSlopeOk,
        watchingDeltaOk            = watchingDeltaOk,
        watchingPeakRiseOk         = watchingPeakRiseOk,
        watchingIobOk              = watchingIobOk
    ),

    rescue = RescueFields(
        pred60                 = pred60,
        rescueState            = rescueState,
        rescueConfidence       = rescueConfidence,
        rescueReason           = rescueReason
    ),

    reserve = ReserveFields(
        reserveU               = reserveU,
        reserveAction          = reserveAction,
        reserveDeltaU          = reserveDeltaU,
        reserveAgeMin          = reserveAgeMin
    ),

    forensic = ForensicFields(
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
        bgStijgtNogFors        = bgStijgtNogFors,
        commitNrUsed           = commitNrUsed,
        iobOvershootFactor     = iobOvershootFactor
    ),

    burst = BurstFields(
        burstDelivered10m      = burstDelivered10m,
        burstCap10m            = burstCap10m,
        burstRemaining10m      = burstRemaining10m
    ),

    hypo = HypoFields(
        hypoActive             = hypoActive,
        hypoProjectedBg        = hypoProjectedBg,
        hypoDebtU              = hypoDebtU
    ),

    topGuard = TopGuardFields(
        topGuardActive         = topGuardActive,
        topGuardCapFactor      = topGuardCapFactor,
        topPlateauConfirmed    = topPlateauConfirmed
    ),

    aggression = AggressionFields(
        mealAggressionA        = mealAggressionA,
        mealAggressionMul      = mealAggressionMul
    ),

    peakBenadering = PeakBenaderingFields(
        peakIobBrakeActive     = peakIobBrakeActive,
        peakApproachFactor          = peakApproachFactor,
        afterloadFutureDrop60Scale  = afterloadFutureDrop60Scale,
        afterloadHighIobLateScale   = afterloadHighIobLateScale
    ),

    suppress = SuppressFields(
        suppressReason         = suppressReason,
        lockoutReason          = lockoutReason,
        commitBlockReason      = commitBlockReason
    ),

    marges = MargesFields(
        iobMarginToBrake       = iobMarginToBrake,
        iobMarginToLockout     = iobMarginToLockout,
        predMarginToWatching   = predMarginToWatching,
        predMarginToTarget     = predMarginToTarget,
        slopeMarginToBrake     = slopeMarginToBrake
    ),

    peakInternals = PeakInternalsFields(
        predictedPeakBallistic = predictedPeakBallistic,
        futureDrop60           = futureDrop60,
        peakFloorActive        = peakFloorActive,
        peakFloorValue         = peakFloorValue,
        hEff                   = hEff,
        iobScaleUsed           = iobScaleUsed,
        vUsed                  = vUsed
    ),

    doseerruimte = DoseerruimteFields(
        iobHeadroom            = iobHeadroom,
        doseSuppressedU        = doseSuppressedU,
        peakApproachActive     = peakApproachActive,
        earlyResetThisCycle    = earlyResetThisCycle,
        downtrendLocked        = downtrendLocked,
        sensorBlipActive       = sensorBlipActive
    )
)
