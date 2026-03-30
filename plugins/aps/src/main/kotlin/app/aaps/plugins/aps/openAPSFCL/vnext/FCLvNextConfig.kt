package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import kotlin.Double

data class FCLvNextConfig(

    // =================================================
    // 🧭 UI PARAMETERS (via Preferences)
    // =================================================
    val gain: Double,
    val maxSMB: Double,
    val hybridPercentage: Int,
    val minDeliverDose: Double,
    val profielNaam: String,
    val mealDetectSpeed: String,
    val correctionStyle: String,
    val mealHandlingStyle: String,
    val hypoProtectionStyle: String,
    val doseDistributionStyle: String,
    val nightResponseStyle: String,


    // smoothing
    val bgSmoothingAlpha: Double,

    // IOB safety (UI, want jij logt/zet ze)
    val iobStart: Double,
    val iobMax: Double,
    val iobMinFactor: Double,

    // commit IOB curve apart (jij hebt key)
    val commitIobPower: Double,

    // =================================================
    // 🧠 LEARNING-BASE (startwaarden, adjuster mag erop)
    // =================================================

    // =================================================
    // 📊 PROFILE — DOSE STRENGTH (STRICT / BALANCED / AGGRESSIVE)
    // Beïnvloedt ALLEEN dosis-hoogte, niet timing of persistentie
    // =================================================

    val doseStrengthMul: Double,        // globale vermenigvuldiger op finalDose
    val maxCommitFractionMul: Double,   // schaal op commitFraction
    val microDoseMul: Double,

    // =================================================
    // ✅ DOSE DISTRIBUTION (4e as)
    // Beïnvloedt "vorm": basal-vs-SMB split + cap-vorm + tail dosing
    // =================================================
    val smallDoseThresholdU: Double,     // in executeDelivery: onder deze dosis → vooral basaal gedrag
    val microCapFracOfMaxSmb: Double,    // microCap = max(0.05, frac*maxSMB)
    val smallCapFracOfMaxSmb: Double,    // smallCap = max(0.10, frac*maxSMB)

    val kDelta: Double,
    val kSlope: Double,
    val kAccel: Double,

    // commit fractions (learning beïnvloedt ze via multiplier)
    val uncertainMinFraction: Double,
    val uncertainMaxFraction: Double,
    val confirmMinFraction: Double,
    val confirmMaxFraction: Double,

    // =================================================
    // 🛡️ CONSTANTS / LOGIC (vaste waarden in code/config)
    // =================================================

    // betrouwbaarheid
    val minConsistency: Double,
    val consistencyExp: Double,
    val episodeMinConsistency: Double,

    // execution
    val deliveryCycleMinutes: Int,
    val maxTempBasalRate: Double,

    // meal detect (wordt gebruikt in detectMealSignal)
    val mealSlopeMin: Double,
    val mealSlopeSpan: Double,
    val mealAccelMin: Double,
    val mealAccelSpan: Double,
    val mealDeltaMin: Double,
    val mealDeltaSpan: Double,
    val mealUncertainConfidence: Double,
    val mealConfirmConfidence: Double,
    val mealConfidenceSpeedMul: Double,

    // meal detect / timing scaling (uniform)
    val mealDetectThresholdMul: Double,   // beïnvloedt detectMealSignal
    val microRampThresholdMul: Double,     // beïnvloedt microRamp

    // commit logic
    val commitCooldownMinutes: Int,
    val minCommitDose: Double,

    // micro-correction hold + anti-drip
    val correctionHoldSlopeMax: Double,
    val correctionHoldAccelMax: Double,
    val correctionHoldDeltaMax: Double,
    val smallCorrectionMaxU: Double,
    val smallCorrectionCooldownMinutes: Int,

    // absorption / peak suppression
    val absorptionWindowMinutes: Int,
    val peakSlopeThreshold: Double,
    val peakAccelThreshold: Double,
    val absorptionDoseFactor: Double,

    // re-entry
    val reentryMinMinutesSinceCommit: Int,
    val reentryCooldownMinutes: Int,
    val reentrySlopeMin: Double,
    val reentryAccelMin: Double,
    val reentryDeltaMin: Double,

    // stagnation
    val stagnationDeltaMin: Double,
    val stagnationSlopeMaxNeg: Double,
    val stagnationSlopeMaxPos: Double,
    val stagnationAccelMaxAbs: Double,
    val stagnationEnergyBoost: Double,
    val persistentAggressionMul: Double,
    // persistent plateau detectie
    val persistentSlopeAbs: Double,
    val persistentAccelAbs: Double,


    // early-dose & fast-carb behavior (algorithmic tuning)
    val earlyPeakEscalationBonus: Double,
    val earlyStage1ThresholdMul: Double,
    val enableFastCarbOverride: Boolean,

    // peak prediction (updatePeakEstimate)
    val peakPredictionThreshold: Double,
    val peakConfirmCycles: Int,
    val peakMinConsistency: Double,
    val peakMinSlope: Double,
    val peakMinAccel: Double,
    val peakPredictionHorizonH: Double,
    val peakExitSlope: Double,
    val peakExitAccel: Double,

    val peakMomentumHalfLifeMin: Double,
    val peakMinMomentum: Double,
    val peakMomentumGain: Double,
    val peakRiseGain: Double,
    val peakUseMaxSlopeFrac: Double,
    val peakUseMaxAccelFrac: Double,
    val peakPredictionMaxMmol: Double,

    // trend persistence
    val trendConfirmCycles: Int,

    // =================================================
    // 🍽️ MEAL HANDLING (behandel-gedrag ná detectie)
    // Beïnvloedt hoe agressief WATCHING reageert
    // =================================================

// frontload gedrag (BALANCED defaults)
    val watchingFrontloadFrac: Double,     // fractie van normalDose
    val watchingMinSlope: Double,          // minimale slope voor frontload
    val watchingMinDeltaToTarget: Double,  // minimale overshoot
    val watchingMinPeakRise: Double,       // minimale peakRiseSinceStart
    val watchingMaxIobRatio: Double,        // safety cap



// hypo protection tuning knobs (config-driven)
    val hypoBlockThreshold: Double,     // bv 4.4..4.9
    val hypoInsulinFrac30: Double,      // impact fractie binnen 30m
    val hypoInsulinFrac60: Double,
    val hypoInsulinFrac90: Double

)

fun loadFCLvNextConfig(
    prefs: Preferences,
    isNight: Boolean
): FCLvNextConfig {

    // ── Config override (geschreven door FCL Analyzer na handmatige goedkeuring) ──
    // Als er een geldig JSON-bestand staat in Documents/AAPS/ANALYSE/ worden de
    // 5 as-waarden daaruit gelezen. Onbekende waarden vallen automatisch terug op prefs.
    // De rest van de config (veiligheidslogica, hardcoded waarden) is NOOIT extern aanpasbaar.
    val override = FCLvNextConfigOverride.load()

    val validProfiles    = setOf("VERY_STRICT","STRICT","BALANCED","AGGRESSIVE","VERY_AGGRESSIVE")
    val validSpeeds      = setOf("VERY_SLOW","SLOW","MODERATE","FAST","VERY_FAST")
    val validCorrection  = setOf("VERY_CAUTIOUS","CAUTIOUS","NORMAL","PERSISTENT","VERY_PERSISTENT")
    val validMealH       = setOf("VERY_CONSERVATIVE","CONSERVATIVE","BALANCED","ANTICIPATORY","AGGRESSIVE")
    val validHypo        = setOf("MINIMAL","RELAXED","BALANCED","SAFE","ULTRA_SAFE")

    val profileName       = override?.profile?.takeIf { it in validProfiles }
        ?: prefs.get(StringKey.fcl_vnext_profile)
    val mealDetectSpeed   = override?.mealDetectSpeed?.takeIf { it in validSpeeds }
        ?: prefs.get(StringKey.fcl_vnext_meal_detect_speed)
    val correctionStyle   = override?.correctionStyle?.takeIf { it in validCorrection }
        ?: prefs.get(StringKey.fcl_vnext_correction_style)
    val mealHandlingStyle = override?.mealHandlingStyle?.takeIf { it in validMealH }
        ?: prefs.get(StringKey.fcl_vnext_meal_handling_style)
    val hypoProtectionStyle = override?.hypoProtectionStyle?.takeIf { it in validHypo }
        ?: prefs.get(StringKey.fcl_vnext_hypo_protection_style)
    val doseDistributionStyle = prefs.get(StringKey.fcl_vnext_dose_distribution_style)
    val nightResponseStyle    = prefs.get(StringKey.fcl_vnext_night_response_style)


    val gain =
        if (isNight) prefs.get(DoubleKey.fcl_vnext_gain_night)
        else prefs.get(DoubleKey.fcl_vnext_gain_day)

    val maxSMB =
        if (isNight) prefs.get(DoubleKey.max_bolus_night)
        else prefs.get(DoubleKey.max_bolus_day)


    // ─────────────────────────────────────────────
    // Meal detect speed mapping (TIMING ONLY)
    // ─────────────────────────────────────────────

    val base = FCLvNextConfig(

        // =================================================
        // 🧭 UI PARAMETERS (ENKEL DEZE)
        // =================================================
        gain = gain,
        maxSMB = maxSMB,
        hybridPercentage = 50,
        minDeliverDose = 0.075,

        profielNaam = profileName,
        mealDetectSpeed = mealDetectSpeed,
        correctionStyle = correctionStyle,
        mealHandlingStyle = mealHandlingStyle,
        hypoProtectionStyle = hypoProtectionStyle,          // ✅ FIX: ontbrak
        doseDistributionStyle = doseDistributionStyle,
        nightResponseStyle = nightResponseStyle,

        // smoothing
        bgSmoothingAlpha = 0.40,

        // IOB safety
        iobStart = 0.40,
        iobMax = 0.75,
        iobMinFactor = 0.10,

        // commit IOB curve apart
        commitIobPower = 1.00,

        // =================================================
        // 📊 PROFILE — DOSE STRENGTH (default = BALANCED)
        // =================================================
        doseStrengthMul = 1.00,
        maxCommitFractionMul = 1.00,
        microDoseMul = 1.00,

        // ✅ Distribution base (BALANCED)
        smallDoseThresholdU = 0.30,
        microCapFracOfMaxSmb = 0.2,
        smallCapFracOfMaxSmb = 0.4,

        kDelta = 1.00,
        kSlope = 0.45,
        kAccel = 0.53,

        uncertainMinFraction = 0.45,
        uncertainMaxFraction = 0.70,
        confirmMinFraction = 0.70,
        confirmMaxFraction = 1.00,

        // betrouwbaarheid
        minConsistency = 0.18,
        consistencyExp = 1.00,
        episodeMinConsistency = 0.45,

        // execution
        deliveryCycleMinutes = 5,
        maxTempBasalRate = 15.0,

        // meal detect
        mealSlopeMin = 0.35,
        mealSlopeSpan = 0.8,
        mealAccelMin = 0.08,
        mealAccelSpan = 0.6,
        mealDeltaMin = 0.40,
        mealDeltaSpan = 1.0,
        mealUncertainConfidence = 0.2,
        mealConfirmConfidence = 0.45,
        mealConfidenceSpeedMul = 1.4,

        mealDetectThresholdMul = 0.9,
        microRampThresholdMul = 0.9,

        // commit logic
        commitCooldownMinutes = 15,
        minCommitDose = 0.30,

        // micro-correction hold + anti-drip
        correctionHoldSlopeMax = -0.28,
        correctionHoldAccelMax = 0.035,
        correctionHoldDeltaMax = 1.85,
        smallCorrectionMaxU = 0.28,
        smallCorrectionCooldownMinutes = 10,

        // absorption / peak
        absorptionWindowMinutes = 60,
        peakSlopeThreshold = 0.3,
        peakAccelThreshold = -0.05,
        absorptionDoseFactor = 0.15,

        // re-entry
        reentryMinMinutesSinceCommit = 25,
        reentryCooldownMinutes = 20,
        reentrySlopeMin = 1.0,
        reentryAccelMin = 0.10,
        reentryDeltaMin = 1.0,

        // stagnation
        stagnationDeltaMin = 0.80,
        stagnationSlopeMaxNeg = -0.25,
        stagnationSlopeMaxPos = 0.25,
        stagnationAccelMaxAbs = 0.06,
        stagnationEnergyBoost = 0.12,
        persistentAggressionMul = 1.08,
        persistentSlopeAbs = 0.32,
        persistentAccelAbs = 0.085,

        // early-dose & fast-carb behavior
        earlyPeakEscalationBonus = 0.10,
        earlyStage1ThresholdMul = 0.8,
        enableFastCarbOverride = true,

        // peak prediction
        peakPredictionThreshold = 12.5,
        peakConfirmCycles = 2,
        peakMinConsistency = 0.55,
        peakMinSlope = 0.5,
        peakMinAccel = -0.1,
        peakPredictionHorizonH = 1.2,
        peakExitSlope = 0.45,
        peakExitAccel = -0.08,

        peakMomentumHalfLifeMin = 25.0,
        peakMinMomentum = 0.35,
        peakMomentumGain = 2.8,
        peakRiseGain = 0.65,
        peakUseMaxSlopeFrac = 0.6,
        peakUseMaxAccelFrac = 0.5,
        peakPredictionMaxMmol = 25.0,

        // trend persistence
        trendConfirmCycles = 2,

        // watching/frontload
        watchingFrontloadFrac = 0.65,
        watchingMinSlope = 0.30,
        watchingMinDeltaToTarget = 2.0,
        watchingMinPeakRise = 0.6,
        watchingMaxIobRatio = 0.75,

        // ✅ FIX: hypo protection defaults (BALANCED)
        hypoBlockThreshold = 4.70,
        hypoInsulinFrac30 = 0.27,
        hypoInsulinFrac60 = 0.60,
        hypoInsulinFrac90 = 0.90
    )

    return base
        .let { applyProfileDoseStrength(it) }
        .let { applyMealDetectSpeed(it) }
        .let { applyCorrectionStyle(it) }
        .let { applyDoseDistributionStyle(it) }
        .let { applyMealHandlingStyle(it) }
        .let { applyMealHandlingGainScaling(it) } // 👈 nieuw
        .let { applyHypoProtectionStyle(it) }
        .let { applyNightResponseStyle(it, isNight) }
}

private fun applyProfileDoseStrength(
    cfg: FCLvNextConfig
): FCLvNextConfig {

    return when (cfg.profielNaam) {

        "VERY_STRICT" -> cfg.copy(
            doseStrengthMul = cfg.doseStrengthMul - 0.25,
            maxCommitFractionMul = cfg.maxCommitFractionMul - 0.35,
            microDoseMul = cfg.microDoseMul - 0.30
        )

        "STRICT" -> cfg.copy(
            doseStrengthMul = cfg.doseStrengthMul - 0.15,
            maxCommitFractionMul = cfg.maxCommitFractionMul - 0.20,
            microDoseMul = cfg.microDoseMul - 0.15
        )

        "AGGRESSIVE" -> cfg.copy(
            doseStrengthMul = cfg.doseStrengthMul + 0.15,
            maxCommitFractionMul = cfg.maxCommitFractionMul + 0.20,
            microDoseMul = cfg.microDoseMul + 0.15
        )

        "VERY_AGGRESSIVE" -> cfg.copy(
            doseStrengthMul = cfg.doseStrengthMul + 0.30,
            maxCommitFractionMul = cfg.maxCommitFractionMul + 0.40,
            microDoseMul = cfg.microDoseMul + 0.30
        )

        else -> cfg // BALANCED
    }
}



private fun applyMealDetectSpeed(
    cfg: FCLvNextConfig
): FCLvNextConfig {


    return when (cfg.mealDetectSpeed) {

        "VERY_SLOW" -> cfg.copy(
            mealSlopeMin = cfg.mealSlopeMin + 0.25,
            mealAccelMin = cfg.mealAccelMin + 0.06,
            mealDeltaMin = cfg.mealDeltaMin + 0.35,
            mealUncertainConfidence = (cfg.mealUncertainConfidence + 0.15).coerceIn(0.0, 1.0),
            mealConfirmConfidence = (cfg.mealConfirmConfidence + 0.10).coerceIn(0.0, 1.0),
            mealDetectThresholdMul = cfg.mealDetectThresholdMul + 0.20,
            microRampThresholdMul =  cfg.microRampThresholdMul + 0.15,
            mealConfidenceSpeedMul = cfg.mealConfidenceSpeedMul - 0.15,
            earlyStage1ThresholdMul = cfg.earlyStage1ThresholdMul + 0.10,
            commitCooldownMinutes = 15
        )

        "SLOW" -> cfg.copy(
            mealSlopeMin = cfg.mealSlopeMin + 0.15,
            mealAccelMin = cfg.mealAccelMin + 0.03,
            mealDeltaMin = cfg.mealDeltaMin + 0.20,
            mealUncertainConfidence = (cfg.mealUncertainConfidence + 0.10).coerceIn(0.0, 1.0),
            mealConfirmConfidence = (cfg.mealConfirmConfidence + 0.05).coerceIn(0.0, 1.0),
            mealDetectThresholdMul = cfg.mealDetectThresholdMul + 0.10,
            microRampThresholdMul = cfg.microRampThresholdMul + 0.08,
            mealConfidenceSpeedMul = cfg.mealConfidenceSpeedMul - 0.08,
            earlyStage1ThresholdMul = cfg.earlyStage1ThresholdMul + 0.05,
            commitCooldownMinutes = 10
        )

        "FAST" -> cfg.copy(
            mealSlopeMin = (cfg.mealSlopeMin - 0.15).coerceAtLeast(0.2),
            mealAccelMin = (cfg.mealAccelMin - 0.05).coerceAtLeast(0.03),
            mealDeltaMin = (cfg.mealDeltaMin - 0.20).coerceAtLeast(0.2),
            mealUncertainConfidence = (cfg.mealUncertainConfidence - 0.05).coerceIn(0.0, 1.0),
            mealConfirmConfidence = (cfg.mealConfirmConfidence - 0.10).coerceIn(0.0, 1.0),
            mealDetectThresholdMul = cfg.mealDetectThresholdMul - 0.10,
            microRampThresholdMul = cfg.microRampThresholdMul  - 0.08,
            mealConfidenceSpeedMul = cfg.mealConfidenceSpeedMul + 0.10,
            earlyStage1ThresholdMul = cfg.earlyStage1ThresholdMul - 0.10,
            commitCooldownMinutes = 5
        )

        "VERY_FAST" -> cfg.copy(
            mealSlopeMin = (cfg.mealSlopeMin - 0.30).coerceAtLeast(0.15),
            mealAccelMin = (cfg.mealAccelMin - 0.10).coerceAtLeast(0.015),
            mealDeltaMin = (cfg.mealDeltaMin - 0.35).coerceAtLeast(0.1),
            mealUncertainConfidence = (cfg.mealUncertainConfidence - 0.15).coerceIn(0.0, 1.0),
            mealConfirmConfidence = (cfg.mealConfirmConfidence - 0.20).coerceIn(0.0, 1.0),
            mealDetectThresholdMul = cfg.mealDetectThresholdMul - 0.20,
            microRampThresholdMul = cfg.microRampThresholdMul - 0.15,
            mealConfidenceSpeedMul = cfg.mealConfidenceSpeedMul + 0.25,
            earlyStage1ThresholdMul = cfg.earlyStage1ThresholdMul - 0.20,
            commitCooldownMinutes = 5
        )

        else -> cfg // MODERATE
    }
}


private fun applyCorrectionStyle(
    cfg: FCLvNextConfig
): FCLvNextConfig {

    fun scaleCooldown(x: Int, mul: Double): Int =
        (x * mul).toInt().coerceAtLeast(1)

    return when (cfg.correctionStyle) {

        "VERY_CAUTIOUS" -> cfg.copy(
            smallCorrectionMaxU = (cfg.smallCorrectionMaxU * 0.55).coerceAtLeast(0.05),
            smallCorrectionCooldownMinutes = scaleCooldown(cfg.smallCorrectionCooldownMinutes, 2.2),
            correctionHoldSlopeMax = cfg.correctionHoldSlopeMax + 0.15,
            correctionHoldAccelMax = cfg.correctionHoldAccelMax + 0.05,
            correctionHoldDeltaMax = cfg.correctionHoldDeltaMax * 0.60,
            persistentAggressionMul = cfg.persistentAggressionMul - 0.30,
            persistentSlopeAbs = cfg.persistentSlopeAbs - 0.05,
            persistentAccelAbs = cfg.persistentAccelAbs - 0.02
        )

        "CAUTIOUS" -> cfg.copy(
            smallCorrectionMaxU = (cfg.smallCorrectionMaxU * 0.70).coerceAtLeast(0.05),
            smallCorrectionCooldownMinutes = scaleCooldown(cfg.smallCorrectionCooldownMinutes, 1.7),
            correctionHoldSlopeMax = cfg.correctionHoldSlopeMax + 0.10,
            correctionHoldAccelMax = cfg.correctionHoldAccelMax + 0.03,
            correctionHoldDeltaMax = cfg.correctionHoldDeltaMax * 0.70,
            persistentAggressionMul = cfg.persistentAggressionMul - 0.15,
            persistentSlopeAbs = cfg.persistentSlopeAbs - 0.02,
            persistentAccelAbs = cfg.persistentAccelAbs - 0.01
        )

        "PERSISTENT" -> cfg.copy(
            smallCorrectionMaxU = (cfg.smallCorrectionMaxU * 1.7).coerceAtMost(0.40),
            smallCorrectionCooldownMinutes = scaleCooldown(cfg.smallCorrectionCooldownMinutes, 0.6),
            correctionHoldSlopeMax = cfg.correctionHoldSlopeMax - 0.10,
            correctionHoldAccelMax = cfg.correctionHoldAccelMax - 0.03,
            correctionHoldDeltaMax = cfg.correctionHoldDeltaMax * 1.30,
            persistentAggressionMul = cfg.persistentAggressionMul + 0.20,
            persistentSlopeAbs = cfg.persistentSlopeAbs + 0.05,
            persistentAccelAbs = cfg.persistentAccelAbs + 0.02
        )

        "VERY_PERSISTENT" -> cfg.copy(
            smallCorrectionMaxU = (cfg.smallCorrectionMaxU * 2.2).coerceAtMost(0.60),
            smallCorrectionCooldownMinutes = scaleCooldown(cfg.smallCorrectionCooldownMinutes, 0.45),
            correctionHoldSlopeMax = cfg.correctionHoldSlopeMax - 0.15,
            correctionHoldAccelMax = cfg.correctionHoldAccelMax - 0.05,
            correctionHoldDeltaMax = cfg.correctionHoldDeltaMax * 1.50,
            persistentAggressionMul = cfg.persistentAggressionMul + 0.40,
            persistentSlopeAbs = cfg.persistentSlopeAbs + 0.10,
            persistentAccelAbs = cfg.persistentAccelAbs + 0.04
        )

        else -> cfg
    }
}

private fun applyMealHandlingStyle(
    cfg: FCLvNextConfig
): FCLvNextConfig {

    return when (cfg.mealHandlingStyle) {

        "VERY_CONSERVATIVE" -> cfg.copy(
            watchingFrontloadFrac = (cfg.watchingFrontloadFrac - 0.30).coerceIn(0.20, 0.80),
            watchingMinSlope = cfg.watchingMinSlope + 0.15,
            watchingMinDeltaToTarget = cfg.watchingMinDeltaToTarget + 1.5,
            watchingMinPeakRise = cfg.watchingMinPeakRise + 0.6,
            watchingMaxIobRatio = (cfg.watchingMaxIobRatio - 0.15).coerceIn(0.40, 0.90)
        )

        "CONSERVATIVE" -> cfg.copy(
            watchingFrontloadFrac = (cfg.watchingFrontloadFrac - 0.15).coerceIn(0.20, 0.80),
            watchingMinSlope = cfg.watchingMinSlope + 0.08,
            watchingMinDeltaToTarget = cfg.watchingMinDeltaToTarget + 0.8,
            watchingMinPeakRise = cfg.watchingMinPeakRise + 0.3,
            watchingMaxIobRatio = (cfg.watchingMaxIobRatio - 0.10).coerceIn(0.40, 0.90)
        )

        "ANTICIPATORY" -> cfg.copy(
            watchingFrontloadFrac = (cfg.watchingFrontloadFrac + 0.10).coerceIn(0.20, 0.85),
            watchingMinSlope = (cfg.watchingMinSlope - 0.05).coerceAtLeast(0.15),
            watchingMinDeltaToTarget = (cfg.watchingMinDeltaToTarget - 0.5).coerceAtLeast(0.5),
            watchingMinPeakRise = (cfg.watchingMinPeakRise - 0.2).coerceAtLeast(0.2),
            watchingMaxIobRatio = (cfg.watchingMaxIobRatio + 0.05).coerceIn(0.40, 0.90)
        )

        "AGGRESSIVE" -> cfg.copy(
            watchingFrontloadFrac = (cfg.watchingFrontloadFrac + 0.20).coerceIn(0.20, 0.90),
            watchingMinSlope = (cfg.watchingMinSlope - 0.10).coerceAtLeast(0.10),
            watchingMinDeltaToTarget = (cfg.watchingMinDeltaToTarget - 0.8).coerceAtLeast(0.3),
            watchingMinPeakRise = (cfg.watchingMinPeakRise - 0.3).coerceAtLeast(0.1),
            watchingMaxIobRatio = (cfg.watchingMaxIobRatio + 0.10).coerceIn(0.40, 0.95)
        )

        else -> cfg // BALANCED = baseline
    }
}

private fun applyMealHandlingGainScaling(
    cfg: FCLvNextConfig
): FCLvNextConfig {

    val scaledGain = when (cfg.mealHandlingStyle) {
        "VERY_CONSERVATIVE" -> cfg.gain * 0.95
        "CONSERVATIVE"      -> cfg.gain * 0.98
        "ANTICIPATORY"      -> cfg.gain * 1.02
        "AGGRESSIVE"        -> cfg.gain * 1.05
        else                -> cfg.gain
    }

    return cfg.copy(
        gain = scaledGain.coerceIn(0.2, 2.0)
    )
}

private fun applyHypoProtectionStyle(
    cfg: FCLvNextConfig
): FCLvNextConfig {

    return when (cfg.hypoProtectionStyle) {

        "MINIMAL" -> cfg.copy(
            hypoBlockThreshold = (cfg.hypoBlockThreshold - 0.25).coerceIn(4.2, 5.2),
            hypoInsulinFrac30 = (cfg.hypoInsulinFrac30 * 0.80).coerceIn(0.10, 0.40),
            hypoInsulinFrac60 = (cfg.hypoInsulinFrac60 * 0.85).coerceIn(0.30, 0.80),
            hypoInsulinFrac90 = (cfg.hypoInsulinFrac90 * 0.88).coerceIn(0.50, 0.95)
        )

        "RELAXED" -> cfg.copy(
            hypoBlockThreshold = (cfg.hypoBlockThreshold - 0.12).coerceIn(4.2, 5.2),
            hypoInsulinFrac30 = (cfg.hypoInsulinFrac30 * 0.92).coerceIn(0.10, 0.40),
            hypoInsulinFrac60 = (cfg.hypoInsulinFrac60 * 0.94).coerceIn(0.30, 0.80),
            hypoInsulinFrac90 = (cfg.hypoInsulinFrac90 * 0.95).coerceIn(0.50, 0.95)
        )

        "SAFE" -> cfg.copy(
            hypoBlockThreshold = (cfg.hypoBlockThreshold + 0.10).coerceIn(4.2, 5.2),
            hypoInsulinFrac30 = (cfg.hypoInsulinFrac30 * 1.08).coerceIn(0.10, 0.40),
            hypoInsulinFrac60 = (cfg.hypoInsulinFrac60 * 1.07).coerceIn(0.30, 0.90),
            hypoInsulinFrac90 = (cfg.hypoInsulinFrac90 * 1.05).coerceIn(0.50, 0.98)
        )

        "ULTRA_SAFE" -> cfg.copy(
            hypoBlockThreshold = (cfg.hypoBlockThreshold + 0.22).coerceIn(4.2, 5.2),
            hypoInsulinFrac30 = (cfg.hypoInsulinFrac30 * 1.18).coerceIn(0.10, 0.45),
            hypoInsulinFrac60 = (cfg.hypoInsulinFrac60 * 1.15).coerceIn(0.30, 0.95),
            hypoInsulinFrac90 = (cfg.hypoInsulinFrac90 * 1.10).coerceIn(0.50, 0.99)
        )

        else -> cfg // BALANCED = baseline uit base()
    }
}

private fun applyNightResponseStyle(
    cfg: FCLvNextConfig,
    isNight: Boolean
): FCLvNextConfig {

    if (!isNight) return cfg

    fun scaleCooldown(value: Int, factor: Double): Int =
        (value * factor).toInt().coerceAtLeast(1)

    return when (cfg.nightResponseStyle) {

        "VERY_GUARDED" -> cfg.copy(
            stagnationDeltaMin = cfg.stagnationDeltaMin + 0.35,
            stagnationEnergyBoost = cfg.stagnationEnergyBoost * 0.75,
            persistentAggressionMul = cfg.persistentAggressionMul - 0.12,
            smallCorrectionCooldownMinutes = scaleCooldown(cfg.smallCorrectionCooldownMinutes, 1.25),
            correctionHoldDeltaMax = cfg.correctionHoldDeltaMax * 0.85,
            absorptionDoseFactor = (cfg.absorptionDoseFactor * 0.85).coerceIn(0.08, 0.40)
        )

        "GUARDED" -> cfg.copy(
            stagnationDeltaMin = cfg.stagnationDeltaMin + 0.18,
            stagnationEnergyBoost = cfg.stagnationEnergyBoost * 0.88,
            persistentAggressionMul = cfg.persistentAggressionMul - 0.06,
            smallCorrectionCooldownMinutes = scaleCooldown(cfg.smallCorrectionCooldownMinutes, 1.10),
            correctionHoldDeltaMax = cfg.correctionHoldDeltaMax * 0.92,
            absorptionDoseFactor = (cfg.absorptionDoseFactor * 0.92).coerceIn(0.08, 0.40)
        )

        "RESPONSIVE" -> cfg.copy(
            stagnationDeltaMin = (cfg.stagnationDeltaMin - 0.15).coerceAtLeast(0.40),
            stagnationEnergyBoost = cfg.stagnationEnergyBoost * 1.12,
            persistentAggressionMul = cfg.persistentAggressionMul + 0.08,
            smallCorrectionCooldownMinutes = scaleCooldown(cfg.smallCorrectionCooldownMinutes, 0.90),
            correctionHoldDeltaMax = cfg.correctionHoldDeltaMax * 1.08,
            absorptionDoseFactor = (cfg.absorptionDoseFactor * 1.05).coerceIn(0.08, 0.40)
        )

        "PROACTIVE" -> cfg.copy(
            stagnationDeltaMin = (cfg.stagnationDeltaMin - 0.30).coerceAtLeast(0.40),
            stagnationEnergyBoost = cfg.stagnationEnergyBoost * 1.25,
            persistentAggressionMul = cfg.persistentAggressionMul + 0.16,
            smallCorrectionCooldownMinutes = scaleCooldown(cfg.smallCorrectionCooldownMinutes, 0.78),
            correctionHoldDeltaMax = cfg.correctionHoldDeltaMax * 1.18,
            absorptionDoseFactor = (cfg.absorptionDoseFactor * 1.10).coerceIn(0.08, 0.40)
        )

        else -> cfg // BALANCED
    }
}

// ─────────────────────────────────────────────
// 4) ✅ Dose distribution style (SMOOTH / BALANCED / PULSED)
// Doel: vorm van delivery merkbaar maken zonder timing/correction te vermengen.
// ─────────────────────────────────────────────
private fun applyDoseDistributionStyle(
    cfg: FCLvNextConfig
): FCLvNextConfig {

    return when (cfg.doseDistributionStyle) {

        "VERY_SMOOTH" -> cfg.copy(
            hybridPercentage = 85,
            smallDoseThresholdU = (cfg.smallDoseThresholdU * 1.50).coerceIn(0.35, 0.80),
            microCapFracOfMaxSmb = (cfg.microCapFracOfMaxSmb * 0.70).coerceIn(0.05, 0.15),
            smallCapFracOfMaxSmb = (cfg.smallCapFracOfMaxSmb * 0.70).coerceIn(0.10, 0.40),
            absorptionDoseFactor = (cfg.absorptionDoseFactor * 0.70).coerceIn(0.08, 0.20)
        )

        "SMOOTH" -> cfg.copy(
            hybridPercentage = 70,
            smallDoseThresholdU = (cfg.smallDoseThresholdU * 1.35).coerceIn(0.30, 0.70),
            microCapFracOfMaxSmb = (cfg.microCapFracOfMaxSmb * 0.85).coerceIn(0.05, 0.20),
            smallCapFracOfMaxSmb = (cfg.smallCapFracOfMaxSmb * 0.85).coerceIn(0.15, 0.50),
            absorptionDoseFactor = (cfg.absorptionDoseFactor * 0.85).coerceIn(0.10, 0.25)
        )

        "PULSED" -> cfg.copy(
            hybridPercentage = 30,
            smallDoseThresholdU = (cfg.smallDoseThresholdU * 0.75).coerceIn(0.20, 0.60),
            microCapFracOfMaxSmb = (cfg.microCapFracOfMaxSmb * 1.25).coerceIn(0.08, 0.25),
            smallCapFracOfMaxSmb = (cfg.smallCapFracOfMaxSmb * 1.20).coerceIn(0.20, 0.70),
            absorptionDoseFactor = (cfg.absorptionDoseFactor * 1.25).coerceIn(0.15, 0.35)
        )

        "VERY_PULSED" -> cfg.copy(
            hybridPercentage = 15,
            smallDoseThresholdU = (cfg.smallDoseThresholdU * 0.60).coerceIn(0.15, 0.50),
            microCapFracOfMaxSmb = (cfg.microCapFracOfMaxSmb * 1.50).coerceIn(0.10, 0.30),
            smallCapFracOfMaxSmb = (cfg.smallCapFracOfMaxSmb * 1.50).coerceIn(0.25, 0.80),
            absorptionDoseFactor = (cfg.absorptionDoseFactor * 1.50).coerceIn(0.20, 0.40)
        )

        else -> cfg // BALANCED
    }
}

















