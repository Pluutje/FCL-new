package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import kotlin.Double
import kotlin.math.roundToInt
import kotlin.math.abs
import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ConfigOverrideWriter

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
    val hypoInsulinFrac90: Double,

    // =================================================
    // 🔧 FINETUNING KANDIDATEN — IOB-remming & piek-bewaking
    // Hardcoded drempels die in de toekomst door de analyzer bijgesteld kunnen worden.
    // =================================================

    // ── peakIobBrake (evaluatePostPeak) ──────────────────────────────────
    val peakIobBrakeSuppressThreshold: Double,  // 0.42 — suppress actief zodra IOB deze ratio overschrijdt (finetuning: eerder/later beginnen remmen voor piek)
    val peakIobBrakeLockoutThreshold: Double,   // 0.55 — lockout (harde stop) drempel; altijd > suppressThreshold (finetuning: hoe agressief de harde stop is)

    // ── preCommitTop (evaluatePostPeak) ──────────────────────────────────
    val preCommitTopIobThreshold: Double,       // 0.45 — minimale IOB voor topvorming-detectie zonder dip (finetuning: gevoeligheid topherkenning)
    val preCommitTopAccelMax: Double,           // 0.06 — maximale versnelling waarbij topvorming nog herkend wordt (finetuning: hoe "vlak" de curve moet zijn)

    // ── tailSuppress (evaluatePostPeak) ──────────────────────────────────
    val tailSuppressIobMin: Double,             // 0.30 — minimale IOB voor staart-suppressie na piek (finetuning: bij lage ISF eerder supprimeren)

    // ── commitBlocked (evaluatePostPeak) ─────────────────────────────────
    val commitBlockIobThreshold: Double,        // 0.45 — IOB waarbij commit na versnellingsomkeer geblokkeerd wordt (finetuning: bescherming tegen te late commit bij hoge IOB)

    // ── shouldHardBlockTrajectory ─────────────────────────────────────────
    val trajectoryAbsorptionAccelThreshold: Double,  // -0.10 — hoe sterk de vertraging bij absorption moet zijn voor hard block (finetuning: gevoeligheid voor "echte" omkeer)
    val trajectoryAbsorptionIobMin: Double,          // 0.35  — minimale IOB voor absorption hard block (finetuning: alleen blokkeren als er echt risico is)
    val trajectoryHighIobThreshold: Double,          // 0.70  — IOB drempel voor non-meal hard block (finetuning: meer/minder ruimte buiten maaltijdcontext)

    // ── canCommitNow (vroege episode) ─────────────────────────────────────
    val earlyEpisodeCooldownIobMax: Double,     // 0.65 — max IOB-ratio waarbij vroege cooldown-korting nog geldt (finetuning: hoger = langer korte cooldown toestaan)
    val earlyEpisodeMinCooldownMinutes: Int,    // 5    — kortste toegestane cooldown in vroege episode (finetuning: sneller/trager ophopen in eerste 15 min)
    val earlyEpisodeWindowMinutes: Int,         // 15   — hoe lang de vroege episode override actief is (finetuning: aanpassen aan eigen maaltijdtijdprofiel)

    // ── computeLateBolusBlock ─────────────────────────────────────────────
    val lateBolusBlockIobMin: Double,           // 0.35 — minimale IOB voor late-bolus blokkering bij topnadering (finetuning: bij hoge ISF lager, bij lage ISF hoger)

    // ── computeTopGuard ───────────────────────────────────────────────────
    val topGuardMinIobRatio: Double,            // 0.30 — minimale IOB voordat TopGuard kan activeren (finetuning: eerder/later cap toepassen)
    val topGuardCapMin: Double,                 // 0.20 — onderste grens van TopGuard cap-factor (finetuning: hoe hard maximaal geknepen wordt)
    val topGuardCapMax: Double,                 // 0.65 — bovenste grens van TopGuard cap-factor (finetuning: minimale doorvoer bij actieve TopGuard)

    // ── heightEscalationFactor ────────────────────────────────────────────
    val heightEscalationIobCeiling: Double,     // 0.35 — boven deze IOB geen verdere escalatie (finetuning: eerder uitschakelen bij gevoelige patronen)

    // ── IOB-dempingscurve machtsvorm ─────────────────────────────────────
    val iobPowerDay: Double,                    // 2.1  — machtsvorm van de IOB-curve overdag (finetuning: hogere waarde = scherper afknijpen bij hoge IOB)
    val iobPowerNight: Double,                  // 2.3  — machtsvorm 's nachts iets conservatiever (finetuning: nacht-specifiek gedrag)

    // ── piek-nadering taper (getAdvice) ──────────────────────────────────
    val peakApproachIobThreshold: Double,       // 0.62 — IOB-drempel voor geleidelijke piek-nadering rem (finetuning: afstemmen op peakIobBrake drempel)
    val peakApproachMaxReduction: Double,       // 0.20 — maximale verlaging (0..1) door piek-nadering rem (finetuning: hoe agressief de taper is)

    // ── micro-ramp veiligheidsgrens ───────────────────────────────────────
    val microRampIobMax: Double,                // 0.45 — maximale IOB-ratio waarbij micro-ramp nog mag activeren (finetuning: hoger = micro-ramp ook bij iets hogere IOB)

    // =================================================
    // 🚀 EARLY CONFIDENCE BOOST
    // Vergroot de earlyTargetU met een instelbare factor voor de eerste
    // N commits van een episode, mits confidence hoog genoeg is.
    // Analyzer kan deze parameters bijsturen via config_override.json.
    // =================================================
    val earlyBoostFactor: Double,           // 1.0 = uit, 1.5 = 50% hogere vroege dosis. Bereik 1.0–2.0
    val earlyBoostMinConfidence: Double,    // minimale early-confidence om boost te activeren. Bereik 0.40–0.85
    val earlyBoostMaxCommits: Int,          // max aantal commits waarbij boost actief is (1–3)


    // ── Peak Prediction Calibration ── Analyzer-optimaliseerbaar ────────
    /** Ondergrens riseFrac aan het begin van episode. Was hardcoded 0.35.
     *  Hoger = langere vroege horizon. Bereik 0.35–0.85. */
    val earlyRiseFracMin: Double,
    /** Gewicht maxSlope in v-berekening: v = vBlended*(1-w) + maxSlope*w.
     *  0.0 = huidig gedrag. Bereik 0.0–0.60. */
    val peakMaxSlopeWeight: Double,

    // ── Frontload-shift: late commit demping na vroege boost ─────────
    // Na earlyBoost (boostCommitCount > 0), dempt commits als iobRatio
    // de drempel overschrijdt. Doel: insuline vroeg concentreren,
    // minder laat — lagere IOB op en na de piek.
    val lateCommitDecayFactor: Double,    // 0.0 = uit, bereik 0.0–1.0
    val lateCommitDecayThreshold: Double, // iobRatio drempel. Bereik 0.30–0.70

    // ── Sustained Rise Response ───────────────────────────────────────────
    // sustainedRiseSlopeMin: slopedrempel (mmol/min) voor de aanhoudende-stijging teller.
    //   Teller tikt op zolang slope > drempel. Lager = gevoeliger voor gestage stijgingen.
    //   DFLearner/Analyser stuurt dit bij via het JSON-override bestand.
    val sustainedRiseSlopeMin: Double,    // drempel, bijv. 0.35 mmol/min

    // sustainedRiseMinTarget: na hoeveel minuten aanhoudend stijgen bereikt
    //   sustainScore zijn maximum (1.0). Lager = eerder reageren.
    val sustainedRiseMinTarget: Int       // minuten, bijv. 10
)


fun loadFCLvNextConfig(
    prefs: Preferences,
    isNight: Boolean
): FCLvNextConfig {

    // ── Override (geschreven door FCL Analyzer na goedkeuring) ────────────
    val override = FCLvNextConfigOverride.load()

    // ── S / T / V / N lezen ──────────────────────────────────────────────
    // Prioriteit: override-bestand → prefs → hardcoded default
    val sterkte        = (override?.sterkte        ?: prefs.get(IntKey.fcl_vnext_sterkte))
        .coerceIn(80, 125)
    val timing         = (override?.timing         ?: prefs.get(IntKey.fcl_vnext_timing))
        .coerceIn(80, 120)
    val volhoudendheid = (override?.volhoudendheid ?: prefs.get(IntKey.fcl_vnext_volhoudendheid))
        .coerceIn(70, 130)
    val nachtFactor    = (override?.nachtFactor    ?: prefs.get(IntKey.fcl_vnext_nacht_factor))
        .coerceIn(60, 110)

    // ── Schrijf terug naar prefs (StatusFormatter en UI lezen hieruit) ────
    if (override?.sterkte != null && sterkte != prefs.get(IntKey.fcl_vnext_sterkte))
        prefs.put(IntKey.fcl_vnext_sterkte, sterkte)
    if (override?.timing != null && timing != prefs.get(IntKey.fcl_vnext_timing))
        prefs.put(IntKey.fcl_vnext_timing, timing)
    if (override?.volhoudendheid != null && volhoudendheid != prefs.get(IntKey.fcl_vnext_volhoudendheid))
        prefs.put(IntKey.fcl_vnext_volhoudendheid, volhoudendheid)
    if (override?.nachtFactor != null && nachtFactor != prefs.get(IntKey.fcl_vnext_nacht_factor))
        prefs.put(IntKey.fcl_vnext_nacht_factor, nachtFactor)

    // ── Analyzer param-overrides persistent terugschrijven naar prefs ────
    // Zo blijven alle Analyzer-aanpassingen actief na het consumeren van
    // de config_override.json (consume_after_use: true).
    val po = override?.paramOverrides
    if (po != null) {
        po.peakPredictionThreshold?.let       { prefs.put(DoubleKey.fcl_vnext_peak_prediction_threshold, it) }
        po.watchingFrontloadFrac?.let         { prefs.put(DoubleKey.fcl_vnext_watching_frontload_frac, it) }
        po.watchingMinDeltaToTarget?.let      { prefs.put(DoubleKey.fcl_vnext_watching_min_delta, it) }
        po.commitCooldownMinutes?.let         { prefs.put(IntKey.fcl_vnext_commit_cooldown_minutes, it) }
        po.peakPredictionHorizonH?.let        { prefs.put(DoubleKey.fcl_vnext_peak_prediction_horizon_h, it) }
        po.iobStart?.let                     { prefs.put(DoubleKey.fcl_vnext_iob_start, it) }
        po.peakIobBrakeSuppressThreshold?.let { prefs.put(DoubleKey.fcl_vnext_peak_iob_brake_suppress, it) }
        po.earlyBoostFactor?.let              { prefs.put(DoubleKey.fcl_vnext_early_boost_factor, it) }
        po.earlyBoostMinConfidence?.let       { prefs.put(DoubleKey.fcl_vnext_early_boost_min_confidence, it) }
        po.earlyBoostMaxCommits?.let          { prefs.put(IntKey.fcl_vnext_early_boost_max_commits, it) }
        po.earlyRiseFracMin?.let              { prefs.put(DoubleKey.fcl_vnext_early_rise_frac_min, it) }
        po.peakMaxSlopeWeight?.let            { prefs.put(DoubleKey.fcl_vnext_peak_max_slope_weight, it) }
        po.lateCommitDecayFactor?.let         { prefs.put(DoubleKey.fcl_vnext_late_commit_decay_factor, it) }
        po.lateCommitDecayThreshold?.let      { prefs.put(DoubleKey.fcl_vnext_late_commit_decay_threshold, it) }
        po.sustainedRiseSlopeMin?.let         { prefs.put(DoubleKey.fcl_vnext_sustained_rise_slope_min, it) }
        po.sustainedRiseMinTarget?.let        { prefs.put(IntKey.fcl_vnext_sustained_rise_min_target, it) }
    }

    // MaxSmbLearner uitgeschakeld — maxSMB volgt S% direct
    if (override?.iobBrakeLearned != null) {
        val v = override.iobBrakeLearned.coerceIn(0.35, 0.55)
        if (abs(v - prefs.get(DoubleKey.fcl_vnext_iob_brake_learned)) > 0.001)
            prefs.put(DoubleKey.fcl_vnext_iob_brake_learned, v)
    }

    // ── Gain = S (dag) of S × N (nacht) ──────────────────────────────────
    // Vervangt de afzonderlijke fcl_vnext_gain_day / fcl_vnext_gain_night prefs.
    val s = sterkte.toDouble()     / 100.0
    val n = nachtFactor.toDouble() / 100.0
    val gain = if (isNight) (s * n) else s

    // MaxSMB koppeling aan S%:
    // maxSMB = manualMaxSmb × (S% / 100). Bij S=100% → maxSMB = handmatige instelling.
    // Bij S=115% → 15% meer cap. MaxSmbLearner is uitgeschakeld.
    // De frontload-override (watching/earlyBoost) blijft intact en kan deze cap overstijgen.
    val maxSMB =
        if (isNight) prefs.get(DoubleKey.max_bolus_night)
        else {
            val manualMax = prefs.get(DoubleKey.max_bolus_day)
            (manualMax * (sterkte.toDouble() / 100.0))
                .coerceIn(
                    manualMax * 0.50,  // vloer: nooit minder dan 50% van handmatig
                    manualMax * 1.50   // plafond: nooit meer dan 150% van handmatig
                )
        }

    // IobBrake override — overschrijft de DFMapping waarde voor peakIobBrakeSuppressThreshold
    // Prioriteit: geleerde persistent waarde > DFMapping param_override > prefs default
    val iobBrakeOverride: Double? = override?.iobBrakeLearned?.coerceIn(0.35, 0.55)
        ?: prefs.get(DoubleKey.fcl_vnext_iob_brake_learned).let {
            if (it > 0.001) it else null
        }

    val doseDistributionStyle = prefs.get(StringKey.fcl_vnext_dose_distribution_style)
    val nightResponseStyle    = prefs.get(StringKey.fcl_vnext_night_response_style)

    val base = FCLvNextConfig(
        gain             = gain,
        maxSMB           = maxSMB,
        hybridPercentage = 50,
        minDeliverDose   = 0.075,

        // Log-label toont actieve S/T/V/N
        profielNaam           = "S${sterkte}/T${timing}/V${volhoudendheid}/N${nachtFactor}",
        mealDetectSpeed       = "MODERATE",
        correctionStyle       = "NORMAL",
        mealHandlingStyle     = "BALANCED",
        hypoProtectionStyle   = "BALANCED",
        doseDistributionStyle = doseDistributionStyle,
        nightResponseStyle    = nightResponseStyle,

        bgSmoothingAlpha = 0.40,
        iobStart         = po?.iobStart ?: prefs.get(DoubleKey.fcl_vnext_iob_start),
        iobMax           = 0.70,      // was 0.75
        iobMinFactor     = 0.10,
        commitIobPower   = 1.00,

        doseStrengthMul      = 1.00,
        maxCommitFractionMul = 1.00,
        microDoseMul         = 1.00,

        smallDoseThresholdU  = 0.30,
        microCapFracOfMaxSmb = 0.2,
        smallCapFracOfMaxSmb = 0.4,

        kDelta = 1.00,
        kSlope = 0.45,
        kAccel = 0.53,

        uncertainMinFraction = 0.45,
        uncertainMaxFraction = 0.70,
        confirmMinFraction   = 0.70,
        confirmMaxFraction   = 1.00,

        minConsistency        = 0.18,
        consistencyExp        = 1.00,
        episodeMinConsistency = 0.45,

        deliveryCycleMinutes = 5,
        maxTempBasalRate     = 15.0,

        mealSlopeMin            = 0.35,
        mealSlopeSpan           = 0.8,
        mealAccelMin            = 0.08,
        mealAccelSpan           = 0.6,
        mealDeltaMin            = 0.40,
        mealDeltaSpan           = 1.0,
        mealUncertainConfidence = 0.2,
        mealConfirmConfidence   = 0.45,
        mealConfidenceSpeedMul  = 1.40,
        mealDetectThresholdMul  = 0.90,
        microRampThresholdMul   = 0.90,

        commitCooldownMinutes = po?.commitCooldownMinutes ?: prefs.get(IntKey.fcl_vnext_commit_cooldown_minutes),
        minCommitDose         = 0.30,

        correctionHoldSlopeMax         = -0.28,
        correctionHoldAccelMax         =  0.035,
        correctionHoldDeltaMax         =  1.85,
        smallCorrectionMaxU            =  0.28,
        smallCorrectionCooldownMinutes =  10,

        absorptionWindowMinutes = 60,
        peakSlopeThreshold      = 0.3,
        peakAccelThreshold      = -0.05,
        absorptionDoseFactor    = 0.15,

        reentryMinMinutesSinceCommit = 25,
        reentryCooldownMinutes       = 20,
        reentrySlopeMin              = 1.0,
        reentryAccelMin              = 0.10,
        reentryDeltaMin              = 1.0,

        stagnationDeltaMin      = 0.80,
        stagnationSlopeMaxNeg   = -0.25,
        stagnationSlopeMaxPos   =  0.25,
        stagnationAccelMaxAbs   =  0.06,
        stagnationEnergyBoost   =  0.12,
        persistentAggressionMul =  1.08,
        persistentSlopeAbs      =  0.32,
        persistentAccelAbs      =  0.085,

        earlyPeakEscalationBonus = 0.10,
        earlyStage1ThresholdMul  = 0.80,
        enableFastCarbOverride   = true,

        peakPredictionThreshold  = po?.peakPredictionThreshold ?: prefs.get(DoubleKey.fcl_vnext_peak_prediction_threshold),
        peakConfirmCycles        = 2,
        peakMinConsistency       = 0.55,
        peakMinSlope             = 0.5,
        peakMinAccel             = -0.1,
        peakPredictionHorizonH   = po?.peakPredictionHorizonH ?: prefs.get(DoubleKey.fcl_vnext_peak_prediction_horizon_h),
        peakExitSlope            = 0.45,
        peakExitAccel            = -0.08,
        peakMomentumHalfLifeMin  = 25.0,
        peakMinMomentum          = 0.35,
        peakMomentumGain         = 2.8,
        peakRiseGain             = 0.65,
        peakUseMaxSlopeFrac      = 0.6,
        peakUseMaxAccelFrac      = 0.5,
        peakPredictionMaxMmol    = 25.0,

        trendConfirmCycles = 2,

        watchingFrontloadFrac    = po?.watchingFrontloadFrac    ?: prefs.get(DoubleKey.fcl_vnext_watching_frontload_frac),
        watchingMinSlope         = 0.30,
        watchingMinDeltaToTarget = po?.watchingMinDeltaToTarget ?: prefs.get(DoubleKey.fcl_vnext_watching_min_delta),
        watchingMinPeakRise      = 0.6,
        watchingMaxIobRatio      = 0.75,

        hypoBlockThreshold = 4.70,
        hypoInsulinFrac30  = 0.27,
        hypoInsulinFrac60  = 0.60,
        hypoInsulinFrac90  = 0.90,

        // finetuning kandidaten — defaults gespiegeld aan huidige hardcoded waarden
        peakIobBrakeSuppressThreshold   = iobBrakeOverride
            ?: (po?.peakIobBrakeSuppressThreshold ?: prefs.get(DoubleKey.fcl_vnext_peak_iob_brake_suppress)),
        peakIobBrakeLockoutThreshold    = 0.55,
        preCommitTopIobThreshold        = 0.45,
        preCommitTopAccelMax            = 0.06,
        tailSuppressIobMin              = 0.30,
        commitBlockIobThreshold         = 0.45,
        trajectoryAbsorptionAccelThreshold = -0.10,
        trajectoryAbsorptionIobMin      = 0.35,
        trajectoryHighIobThreshold      = 0.70,
        earlyEpisodeCooldownIobMax      = 0.65,
        earlyEpisodeMinCooldownMinutes  = 5,
        earlyEpisodeWindowMinutes       = 15,
        lateBolusBlockIobMin            = 0.35,
        topGuardMinIobRatio             = 0.30,
        topGuardCapMin                  = 0.20,
        topGuardCapMax                  = 0.65,
        heightEscalationIobCeiling      = 0.35,
        iobPowerDay                     = 2.1,
        iobPowerNight                   = 2.3,
        peakApproachIobThreshold        = 0.62,
        peakApproachMaxReduction        = 0.20,
        microRampIobMax                 = 0.45,

        // Early Confidence Boost — standaard uitgeschakeld (factor=1.0)
        // Analyzer kan ophogen via config_override.json.
        // BELANGRIJK: lees direct uit po (override) als beschikbaar, anders uit prefs.
        // Reden: prefs.put() gevolgd door prefs.get() in dezelfde aanroep kan de
        // gecachte prefs-waarde teruggeven i.p.v. de zojuist geschreven waarde.
        earlyBoostFactor         = po?.earlyBoostFactor        ?: prefs.get(DoubleKey.fcl_vnext_early_boost_factor),
        earlyBoostMinConfidence  = po?.earlyBoostMinConfidence ?: prefs.get(DoubleKey.fcl_vnext_early_boost_min_confidence),
        earlyBoostMaxCommits     = po?.earlyBoostMaxCommits    ?: prefs.get(IntKey.fcl_vnext_early_boost_max_commits),
        earlyRiseFracMin         = po?.earlyRiseFracMin        ?: prefs.get(DoubleKey.fcl_vnext_early_rise_frac_min),
        peakMaxSlopeWeight       = po?.peakMaxSlopeWeight      ?: prefs.get(DoubleKey.fcl_vnext_peak_max_slope_weight),
        lateCommitDecayFactor    = po?.lateCommitDecayFactor   ?: prefs.get(DoubleKey.fcl_vnext_late_commit_decay_factor),
        lateCommitDecayThreshold = po?.lateCommitDecayThreshold ?: prefs.get(DoubleKey.fcl_vnext_late_commit_decay_threshold),

        sustainedRiseSlopeMin    = po?.sustainedRiseSlopeMin   ?: prefs.get(DoubleKey.fcl_vnext_sustained_rise_slope_min),
        sustainedRiseMinTarget   = po?.sustainedRiseMinTarget  ?: prefs.get(IntKey.fcl_vnext_sustained_rise_min_target)

    )

    return base
        .let { applySTVModel(it, sterkte, timing, volhoudendheid) }
        .let { applyDoseDistributionStyle(it) }
        .let { applyNightResponseStyle(it, isNight) }
        .let { applyParamOverrides(it, override?.paramOverrides) }
        .also { FCLvNextActiveParamsWriter.writeIfChanged(it, prefs, sterkte, timing, volhoudendheid, nachtFactor) }
}

private fun applySTVModel(
    cfg: FCLvNextConfig,
    sterkte: Int,
    timing: Int,
    volhoudendheid: Int
): FCLvNextConfig {

    val s = sterkte.toDouble()        / 100.0
    val t = timing.toDouble()         / 100.0
    val v = volhoudendheid.toDouble() / 100.0

    return cfg.copy(
        // STERKTE (S/D) — dosis-hoogte en commit-fractie schaling
        // watchingFrontloadFrac is verwijderd: wordt uitsluitend bepaald door
        // DFMapping(F) → param_overrides → prefs → applyParamOverrides.
        doseStrengthMul      = (1.0 + (s - 1.0) * 1.50).coerceIn(0.60, 1.90),
        maxCommitFractionMul = (1.0 + (s - 1.0) * 2.00).coerceIn(0.50, 1.90),
        microDoseMul         = (1.0 + (s - 1.0) * 1.50).coerceIn(0.60, 1.90),

        // TIMING (T/F) — detectiesnelheid en commitment-drempels
        // watchingMinDeltaToTarget en commitCooldownMinutes zijn verwijderd:
        // worden uitsluitend bepaald door DFMapping(F) → param_overrides.
        mealDetectThresholdMul  = (0.90 / t).coerceIn(0.70, 1.20),
        microRampThresholdMul   = (0.90 / t).coerceIn(0.65, 1.20),
        earlyStage1ThresholdMul = (0.80 / t).coerceIn(0.55, 1.10),
        mealConfidenceSpeedMul  = (1.40 * t).coerceIn(1.05, 1.80),

        // VOLHOUDENDHEID (V/D) — persistentie en hypo-bescherming
        persistentAggressionMul = (1.08 * v).coerceIn(0.60, 1.60),
        correctionHoldDeltaMax  = (1.85 * v).coerceIn(1.10, 2.80),
        smallCorrectionMaxU     = (0.28 * v).coerceIn(0.12, 0.50),
        hypoBlockThreshold      = (4.70 - (v - 1.0) * 1.0).coerceIn(4.20, 5.20),
        hypoInsulinFrac30       = (0.27 * (2.0 - v)).coerceIn(0.12, 0.42),
        hypoInsulinFrac60       = (0.60 * (2.0 - v)).coerceIn(0.30, 0.80),
        hypoInsulinFrac90       = (0.90 * (2.0 - v)).coerceIn(0.60, 0.99)
    )
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
/**
 * Past individuele Groep A parameter-overrides toe NA de volledige as-keten.
 *
 * Volgorde is cruciaal: de as-keten (applyMealDetectSpeed, applyMealHandlingStyle, etc.)
 * mag deze waarden niet overschrijven. Door hier ná de keten toe te passen,
 * wint de fijnafstelling altijd van de as-logica.
 *
 * Veiligheid:
 * - Null = geen override, keten-waarde blijft intact
 * - Alle waarden worden geclamped op hun toegestane bereik
 * - Groep C parameters (hypoBlockThreshold, maxSMB, etc.) zitten niet in deze functie
 */
/**
 * Variant van applyParamOverrides die ConfigOverrideWriter.ParamOverrides accepteert.
 * Gebruikt voor D/F parameter overrides vanuit de analyzer.
 */
private fun applyParamOverridesFromWriter(
    cfg: FCLvNextConfig,
    overrides: ConfigOverrideWriter.ParamOverrides
): FCLvNextConfig = applyParamOverrides(
    cfg,
    FCLvNextConfigOverride.ParamOverrides(
        peakPredictionThreshold       = overrides.peakPredictionThreshold,
        watchingFrontloadFrac         = overrides.watchingFrontloadFrac,
        watchingMinDeltaToTarget      = overrides.watchingMinDeltaToTarget,
        commitCooldownMinutes         = overrides.commitCooldownMinutes,
        peakPredictionHorizonH        = overrides.peakPredictionHorizonH,
        iobStart                      = overrides.iobStart,
        peakIobBrakeSuppressThreshold = overrides.peakIobBrakeSuppressThreshold,
        earlyBoostFactor              = overrides.earlyBoostFactor,
        earlyBoostMinConfidence       = overrides.earlyBoostMinConfidence,
        earlyBoostMaxCommits          = overrides.earlyBoostMaxCommits,
        earlyRiseFracMin              = overrides.earlyRiseFracMin,
        peakMaxSlopeWeight            = overrides.peakMaxSlopeWeight,
        lateCommitDecayFactor         = overrides.lateCommitDecayFactor,
        lateCommitDecayThreshold      = overrides.lateCommitDecayThreshold,
        sustainedRiseSlopeMin         = overrides.sustainedRiseSlopeMin,
        sustainedRiseMinTarget        = overrides.sustainedRiseMinTarget
    )
)

private fun applyParamOverrides(
    cfg: FCLvNextConfig,
    overrides: FCLvNextConfigOverride.ParamOverrides?,
): FCLvNextConfig {
    // Alle 16 params komen uitsluitend van DFMapping → JSON → prefs.
    // applySTVModel raakt deze params niet meer — geen conflict, geen fallback nodig.
    // Prefs bevatten altijd de meest recent door de analyser geschreven waarden.
    // Bij overrides==null (geen JSON geconsumeerd deze cyclus): prefs-waarden zijn
    // al in de base config geladen via loadFCLvNextConfig → geen actie nodig.
    if (overrides == null) return cfg

    return cfg.copy(
        peakPredictionThreshold = overrides.peakPredictionThreshold
            ?.coerceIn(9.5, 14.0)
            ?: cfg.peakPredictionThreshold,

        watchingFrontloadFrac = overrides.watchingFrontloadFrac
            ?.coerceIn(0.40, 0.90)
            ?: cfg.watchingFrontloadFrac,

        watchingMinDeltaToTarget = overrides.watchingMinDeltaToTarget
            ?.coerceIn(0.5, 3.5)
            ?: cfg.watchingMinDeltaToTarget,

        commitCooldownMinutes = overrides.commitCooldownMinutes
            ?.coerceIn(5, 25)
            ?: cfg.commitCooldownMinutes,

        peakPredictionHorizonH = overrides.peakPredictionHorizonH
            ?.coerceIn(0.8, 1.8)
            ?: cfg.peakPredictionHorizonH,

        iobStart = overrides.iobStart
            ?.coerceIn(0.25, 0.55)
            ?: cfg.iobStart,

        peakIobBrakeSuppressThreshold = overrides.peakIobBrakeSuppressThreshold
            ?.coerceIn(0.28, 0.60)
            ?: cfg.peakIobBrakeSuppressThreshold,

        // Lockout blijft altijd > suppress drempel
        peakIobBrakeLockoutThreshold = overrides.peakIobBrakeSuppressThreshold
            ?.let { (it + 0.13).coerceIn(0.35, 0.75) }
            ?: cfg.peakIobBrakeLockoutThreshold,

        earlyBoostFactor = overrides.earlyBoostFactor
            ?.coerceIn(1.0, 2.0)
            ?: cfg.earlyBoostFactor,

        earlyBoostMinConfidence = overrides.earlyBoostMinConfidence
            ?.coerceIn(0.40, 0.85)
            ?: cfg.earlyBoostMinConfidence,

        earlyBoostMaxCommits = overrides.earlyBoostMaxCommits
            ?.coerceIn(1, 3)
            ?: cfg.earlyBoostMaxCommits,

        earlyRiseFracMin = overrides.earlyRiseFracMin
            ?.coerceIn(0.35, 0.85)
            ?: cfg.earlyRiseFracMin,

        peakMaxSlopeWeight = overrides.peakMaxSlopeWeight
            ?.coerceIn(0.0, 0.60)
            ?: cfg.peakMaxSlopeWeight,

        lateCommitDecayFactor = overrides.lateCommitDecayFactor
            ?.coerceIn(0.0, 1.0)
            ?: cfg.lateCommitDecayFactor,

        lateCommitDecayThreshold = overrides.lateCommitDecayThreshold
            ?.coerceIn(0.30, 0.70)
            ?: cfg.lateCommitDecayThreshold,

        sustainedRiseSlopeMin = overrides.sustainedRiseSlopeMin
            ?.coerceIn(0.15, 0.80)
            ?: cfg.sustainedRiseSlopeMin,

        sustainedRiseMinTarget = overrides.sustainedRiseMinTarget
            ?.coerceIn(5, 20)
            ?: cfg.sustainedRiseMinTarget
    )
}