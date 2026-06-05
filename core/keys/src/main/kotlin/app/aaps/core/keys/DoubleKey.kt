package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val unitType: UnitType = UnitType.NONE
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1(
        key = "insulin_button_increment_1",
        defaultValue = 0.5,
        min = -5.0,
        max = 5.0,
        titleResId = R.string.pref_title_insulin_button_increment_1,
        summaryResId = R.string.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN
    ),
    OverviewInsulinButtonIncrement2(
        key = "insulin_button_increment_2",
        defaultValue = 1.0,
        min = -5.0,
        max = 5.0,
        titleResId = R.string.pref_title_insulin_button_increment_2,
        summaryResId = R.string.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN
    ),
    OverviewInsulinButtonIncrement3(
        key = "insulin_button_increment_3",
        defaultValue = 2.0,
        min = -5.0,
        max = 5.0,
        titleResId = R.string.pref_title_insulin_button_increment_3,
        summaryResId = R.string.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN
    ),
    ActionsFillButton1(key = "fill_button1", defaultValue = 0.3, min = 0.05, max = 20.0, titleResId = R.string.pref_title_fill_button_1, defaultedBySM = true, hideParentScreenIfHidden = true, unitType = UnitType.INSULIN),
    ActionsFillButton2(key = "fill_button2", defaultValue = 0.0, min = 0.0, max = 20.0, titleResId = R.string.pref_title_fill_button_2, defaultedBySM = true, unitType = UnitType.INSULIN),
    ActionsFillButton3(key = "fill_button3", defaultValue = 0.0, min = 0.0, max = 20.0, titleResId = R.string.pref_title_fill_button_3, defaultedBySM = true, unitType = UnitType.INSULIN),
    SafetyMaxBolus(key = "treatmentssafety_maxbolus", defaultValue = 3.0, min = 0.1, max = 60.0, titleResId = R.string.pref_title_max_bolus, unitType = UnitType.INSULIN),
    ApsMaxBasal(
        key = "openapsma_max_basal",
        defaultValue = 1.0,
        min = 0.1,
        max = 25.0,
        titleResId = R.string.pref_title_max_basal,
        summaryResId = R.string.openapsma_max_basal_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN_RATE
    ),
    ApsSmbMaxIob(
        key = "openapsmb_max_iob",
        defaultValue = 3.0,
        min = 0.0,
        max = 70.0,
        titleResId = R.string.pref_title_smb_max_iob,
        summaryResId = R.string.openapssmb_max_iob_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN
    ),
    ApsAmaMaxIob(
        key = "openapsma_max_iob",
        defaultValue = 1.5,
        min = 0.0,
        max = 25.0,
        titleResId = R.string.pref_title_ama_max_iob,
        summaryResId = R.string.openapsma_max_iob_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN
    ),
    ApsMaxDailyMultiplier(
        key = "openapsama_max_daily_safety_multiplier",
        defaultValue = 3.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_max_daily_multiplier,
        summaryResId = R.string.openapsama_max_daily_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsMaxCurrentBasalMultiplier(
        key = "openapsama_current_basal_safety_multiplier",
        defaultValue = 4.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_current_basal_multiplier,
        summaryResId = R.string.openapsama_current_basal_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAmaBolusSnoozeDivisor(
        key = "bolussnooze_dia_divisor",
        defaultValue = 2.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_bolus_snooze_divisor,
        summaryResId = R.string.openapsama_bolus_snooze_dia_divisor_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAmaMin5MinCarbsImpact(
        key = "openapsama_min_5m_carbimpact",
        defaultValue = 3.0,
        min = 1.0,
        max = 12.0,
        titleResId = R.string.pref_title_ama_min_5m_carbs_impact,
        summaryResId = R.string.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsSmbMin5MinCarbsImpact(
        key = "openaps_smb_min_5m_carbimpact",
        defaultValue = 8.0,
        min = 1.0,
        max = 12.0,
        titleResId = R.string.pref_title_smb_min_5m_carbs_impact,
        summaryResId = R.string.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    AbsorptionCutOff(key = "absorption_cutoff", defaultValue = 6.0, min = 4.0, max = 10.0, titleResId = R.string.pref_title_absorption_cutoff, summaryResId = R.string.absorption_cutoff_summary, unitType = UnitType.HOURS_DOUBLE),
    AbsorptionMaxTime(key = "absorption_maxtime", defaultValue = 6.0, min = 4.0, max = 10.0, titleResId = R.string.pref_title_absorption_maxtime, summaryResId = R.string.absorption_max_time_summary, unitType = UnitType.HOURS_DOUBLE),
    AutosensMin(
        key = "autosens_min",
        defaultValue = 0.7,
        min = 0.1,
        max = 1.0,
        titleResId = R.string.pref_title_autosens_min,
        summaryResId = R.string.openapsama_autosens_min_summary,
        defaultedBySM = true,
        hideParentScreenIfHidden = true,
        unitType = UnitType.DOUBLE
    ),
    AutosensMax(key = "autosens_max", defaultValue = 1.2, min = 0.5, max = 3.0, titleResId = R.string.pref_title_autosens_max, summaryResId = R.string.openapsama_autosens_max_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfMin(key = "autoISF_min", defaultValue = 1.0, min = 0.3, max = 1.0, titleResId = R.string.pref_title_autoisf_min, summaryResId = R.string.openapsama_autoISF_min_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfMax(key = "autoISF_max", defaultValue = 1.0, min = 1.0, max = 3.0, titleResId = R.string.pref_title_autoisf_max, summaryResId = R.string.openapsama_autoISF_max_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfBgAccelWeight(
        key = "bgAccel_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_bg_accel_weight,
        summaryResId = R.string.openapsama_bgAccel_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfBgBrakeWeight(
        key = "bgBrake_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_bg_brake_weight,
        summaryResId = R.string.openapsama_bgBrake_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfLowBgWeight(
        key = "lower_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        titleResId = R.string.pref_title_low_bg_weight,
        summaryResId = R.string.openapsama_lower_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfHighBgWeight(
        key = "higher_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        titleResId = R.string.pref_title_high_bg_weight,
        summaryResId = R.string.openapsama_higher_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfSmbDeliveryRatioBgRange(
        key = "openapsama_smb_delivery_ratio_bg_range",
        defaultValue = 0.0,
        min = 0.0,
        max = 100.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_bg_range,
        summaryResId = R.string.openapsama_smb_delivery_ratio_bg_range_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfPpWeight(key = "pp_ISF_weight", defaultValue = 0.0, min = 0.0, max = 0.15, titleResId = R.string.pref_title_pp_weight, summaryResId = R.string.openapsama_pp_ISF_weight_summary, defaultedBySM = true, unitType = UnitType.DOUBLE_3),
    ApsAutoIsfDuraWeight(key = "dura_ISF_weight", defaultValue = 0.0, min = 0.0, max = 3.0, titleResId = R.string.pref_title_dura_weight, summaryResId = R.string.openapsama_dura_ISF_weight_summary, defaultedBySM = true, unitType = UnitType.DOUBLE_2),
    ApsAutoIsfSmbDeliveryRatio(
        key = "openapsama_smb_delivery_ratio",
        defaultValue = 0.5,
        min = 0.1,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio,
        summaryResId = R.string.openapsama_smb_delivery_ratio_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfSmbDeliveryRatioMin(
        key = "openapsama_smb_delivery_ratio_min",
        defaultValue = 0.5,
        min = 0.1,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_min,
        summaryResId = R.string.openapsama_smb_delivery_ratio_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfSmbDeliveryRatioMax(
        key = "openapsama_smb_delivery_ratio_max",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_max,
        summaryResId = R.string.openapsama_smb_delivery_ratio_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfSmbMaxRangeExtension(
        key = "openapsama_smb_max_range_extension",
        defaultValue = 1.0,
        min = 1.0,
        max = 5.0,
        titleResId = R.string.pref_title_smb_max_range_extension,
        summaryResId = R.string.openapsama_smb_max_range_extension_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),

    // ── FCL vNext — Groep 1: Pomp & veiligheid ───────────────────────────
    // Beheerd via FCL Analyzer JSON, niet zichtbaar in normale preferences UI.
    stap_TT(key = "stap_TT", defaultValue = 2.0, min = 0.5, max = 4.0, titleResId = R.string.fcl_vnext_internal),
    max_bolus_day(key = "max_bolus_day", defaultValue = 1.25, min = 0.1, max = 8.0, titleResId = R.string.max_bolus_day_title, unitType = UnitType.INSULIN),
    max_bolus_night(key = "max_bolus_night", defaultValue = 0.5, min = 0.1, max = 8.0, titleResId = R.string.max_bolus_night_title, unitType = UnitType.INSULIN),
    fcl_vnext_MaxIOB(key = "fcl_vnext_MaxIOB", defaultValue = 4.0, min = 1.0, max = 25.0, titleResId = R.string.fcl_vnext_MaxIOB_title, unitType = UnitType.INSULIN),
    // ── FCL vNext — Groep 2: MaxSmbLearner ───────────────────────────────
    // Default 0.0 = geen geleerde waarde, gebruik handmatige max_bolus_day.
    fcl_vnext_max_smb_day_learned(key = "fcl_vnext_max_smb_day_learned", defaultValue = 0.0, min = 0.0, max = 2.00, titleResId = R.string.fcl_vnext_internal, unitType = UnitType.INSULIN),
    fcl_vnext_iob_brake_learned(key = "fcl_vnext_iob_brake_learned", defaultValue = 0.0, min = 0.0, max = 0.55, titleResId = R.string.fcl_vnext_internal),

    // ── FCL vNext — Groep 3: Analyzer-gestuurde fijnafstelling ────────────
    fcl_vnext_peak_prediction_threshold(key = "fcl_vnext_peak_prediction_threshold", defaultValue = 12.5, min = 9.5, max = 14.0, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_watching_frontload_frac(key = "fcl_vnext_watching_frontload_frac", defaultValue = 0.64, min = 0.40, max = 0.90, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_watching_min_delta(key = "fcl_vnext_watching_min_delta", defaultValue = 1.50, min = 0.5, max = 3.5, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_peak_prediction_horizon_h(key = "fcl_vnext_peak_prediction_horizon_h", defaultValue = 1.2, min = 0.8, max = 2.5, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_iob_start(key = "fcl_vnext_iob_start", defaultValue = 0.40, min = 0.25, max = 0.55, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_peak_iob_brake_suppress(key = "fcl_vnext_peak_iob_brake_suppress", defaultValue = 0.42, min = 0.28, max = 0.60, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_early_boost_factor(key = "fcl_vnext_early_boost_factor", defaultValue = 1.0, min = 1.0, max = 2.0, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_early_boost_min_confidence(key = "fcl_vnext_early_boost_min_confidence", defaultValue = 0.50, min = 0.40, max = 0.85, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_early_rise_frac_min(key = "fcl_vnext_early_rise_frac_min", defaultValue = 0.35, min = 0.35, max = 0.85, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_peak_max_slope_weight(key = "fcl_vnext_peak_max_slope_weight", defaultValue = 0.0, min = 0.0, max = 0.60, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_late_commit_decay_factor(key = "fcl_vnext_late_commit_decay_factor", defaultValue = 0.0, min = 0.0, max = 1.0, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_late_commit_decay_threshold(key = "fcl_vnext_late_commit_decay_threshold", defaultValue = 0.55, min = 0.30, max = 0.70, titleResId = R.string.fcl_vnext_internal),
    fcl_vnext_sustained_rise_slope_min(key = "fcl_vnext_sustained_rise_slope_min", defaultValue = 0.40, min = 0.15, max = 0.80, titleResId = R.string.fcl_vnext_internal),

    fcl_aaps_mulitplier_day(key = "fcl_aaps_mulitplier_day", defaultValue = 1.0, min = 0.8, max = 2.0, titleResId = R.string.fcl_aaps_mulitplier_day_title, unitType = UnitType.INSULIN),
    fcl_aaps_mulitplier_night(key = "fcl_aaps_mulitplier_night", defaultValue = 1.0, min = 0.8, max = 2.0, titleResId = R.string.fcl_aaps_mulitplier_night_title, unitType = UnitType.INSULIN),
}