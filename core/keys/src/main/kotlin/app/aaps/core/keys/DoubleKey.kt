package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1("insulin_button_increment_1", 0.5, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement2("insulin_button_increment_2", 1.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement3("insulin_button_increment_3", 2.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    ActionsFillButton1("fill_button1", 0.3, 0.05, 20.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    ActionsFillButton2("fill_button2", 0.0, 0.05, 20.0, defaultedBySM = true),
    ActionsFillButton3("fill_button3", 0.0, 0.05, 20.0, defaultedBySM = true),
    SafetyMaxBolus("treatmentssafety_maxbolus", 3.0, 0.1, 60.0),
    ApsMaxBasal("openapsma_max_basal", 1.0, 0.1, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsSmbMaxIob("openapsmb_max_iob", 3.0, 0.0, 70.0, defaultedBySM = true, calculatedBySM = true),
    ApsAmaMaxIob("openapsma_max_iob", 1.5, 0.0, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsMaxDailyMultiplier("openapsama_max_daily_safety_multiplier", 3.0, 1.0, 10.0, defaultedBySM = true),
    ApsMaxCurrentBasalMultiplier("openapsama_current_basal_safety_multiplier", 4.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaBolusSnoozeDivisor("bolussnooze_dia_divisor", 2.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaMin5MinCarbsImpact("openapsama_min_5m_carbimpact", 3.0, 1.0, 12.0, defaultedBySM = true),
    ApsSmbMin5MinCarbsImpact("openaps_smb_min_5m_carbimpact", 8.0, 1.0, 12.0, defaultedBySM = true),
    AbsorptionCutOff("absorption_cutoff", 6.0, 4.0, 10.0),
    AbsorptionMaxTime("absorption_maxtime", 6.0, 4.0, 10.0),
    AutosensMin("autosens_min", 0.7, 0.1, 1.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    AutosensMax("autosens_max", 1.2, 0.5, 3.0, defaultedBySM = true),
    ApsAutoIsfMin("autoISF_min", 1.0, 0.3, 1.0, defaultedBySM = true),
    ApsAutoIsfMax("autoISF_max", 1.0, 1.0, 3.0, defaultedBySM = true),
    ApsAutoIsfBgAccelWeight("bgAccel_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfBgBrakeWeight("bgBrake_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfLowBgWeight("lower_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfHighBgWeight("higher_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioBgRange("openapsama_smb_delivery_ratio_bg_range", 0.0, 0.0, 100.0, defaultedBySM = true),
    ApsAutoIsfPpWeight("pp_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfDuraWeight("dura_ISF_weight", 0.0, 0.0, 3.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatio("openapsama_smb_delivery_ratio", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMin("openapsama_smb_delivery_ratio_min", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMax("openapsama_smb_delivery_ratio_max", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbMaxRangeExtension("openapsama_smb_max_range_extension", 1.0, 1.0, 5.0, defaultedBySM = true),

    // ── FCL vNext ─────────────────────────────────────────────────────────
    // Verwijderd: fcl_vnext_gain_day, fcl_vnext_gain_night
    //   → vervangen door IntKey.fcl_vnext_sterkte + IntKey.fcl_vnext_nacht_factor (percentages)
    // Verwijderd: fcl_vnext_meal_strength_*
    //   → IntKey.fcl_vnext_sterkte schaalt dit globaal

    stap_TT("stap_TT", 2.0, 0.5, 4.0),

    max_bolus_day("max_bolus_day", 1.25, 0.1, 8.0),
    max_bolus_night("max_bolus_night", 0.5, 0.1, 8.0),



    fcl_vnext_MaxIOB("fcl_vnext_MaxIOB", 4.0, 1.0, 25.0),

    // ── FCL vNext — Groep-A fijnafstelling (Analyzer-gestuurd) ───────────
    fcl_vnext_peak_prediction_threshold("fcl_vnext_peak_prediction_threshold", 12.5, 9.5, 14.0),
    fcl_vnext_watching_frontload_frac("fcl_vnext_watching_frontload_frac", 0.65, 0.40, 0.90),
    fcl_vnext_watching_min_delta("fcl_vnext_watching_min_delta", 2.0, 0.5, 3.5),
    fcl_vnext_peak_prediction_horizon_h("fcl_vnext_peak_prediction_horizon_h", 1.2, 0.8, 2.5),
    fcl_vnext_iob_start("fcl_vnext_iob_start", 0.40, 0.25, 0.55),
    fcl_vnext_peak_iob_brake_suppress("fcl_vnext_peak_iob_brake_suppress", 0.42, 0.28, 0.60),

    // ── FCL vNext — Early Confidence Boost ──────────────────────────────
    fcl_vnext_early_boost_factor("fcl_vnext_early_boost_factor", 1.0, 1.0, 2.0),
    fcl_vnext_early_boost_min_confidence("fcl_vnext_early_boost_min_confidence", 0.60, 0.40, 0.85),

    // ── FCL vNext — Peak Prediction Calibration (Optimizer) ─────────────
    fcl_vnext_early_rise_frac_min("fcl_vnext_early_rise_frac_min", 0.35, 0.35, 0.85),
    fcl_vnext_peak_max_slope_weight("fcl_vnext_peak_max_slope_weight", 0.0, 0.0, 0.60),
    fcl_vnext_late_commit_decay_factor("fcl_vnext_late_commit_decay_factor", 0.0, 0.0, 1.0),
    fcl_vnext_late_commit_decay_threshold("fcl_vnext_late_commit_decay_threshold", 0.55, 0.30, 0.70),

}
