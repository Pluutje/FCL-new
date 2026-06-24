package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.StringValidator
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec

enum class StringKey(
    override val key: String,
    override val defaultValue: String,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<String, Int> = emptyMap(),
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val isHashed: Boolean = false,
    override val exportable: Boolean = true,
    override val validator: StringValidator = StringValidator.NONE,
    override val visibility: ElementVisibility = ElementVisibility.ALWAYS,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS,
    override val sync: SyncSpec? = null
) : StringPreferenceKey {

    GeneralUnits(
        key = "units",
        defaultValue = "mg/dl",
        titleResId = R.string.pref_title_units,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "mg/dl" to R.string.units_mgdl,
            "mmol" to R.string.units_mmol
        ),
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    GeneralLanguage(
        key = "language",
        defaultValue = "default",
        titleResId = R.string.pref_title_language,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "default" to R.string.lang_default,
            "en" to R.string.lang_en,
            "af" to R.string.lang_af,
            "bg" to R.string.lang_bg,
            "cs" to R.string.lang_cs,
            "de" to R.string.lang_de,
            "dk" to R.string.lang_dk,
            "fr" to R.string.lang_fr,
            "nl" to R.string.lang_nl,
            "es" to R.string.lang_es,
            "el" to R.string.lang_el,
            "ga" to R.string.lang_ga,
            "it" to R.string.lang_it,
            "ko" to R.string.lang_ko,
            "lt" to R.string.lang_lt,
            "nb" to R.string.lang_nb,
            "pl" to R.string.lang_pl,
            "pt" to R.string.lang_pt,
            "pt_BR" to R.string.lang_pt_br,
            "ro" to R.string.lang_ro,
            "ru" to R.string.lang_ru,
            "sk" to R.string.lang_sk,
            "sv" to R.string.lang_sv,
            "tr" to R.string.lang_tr,
            "zh_TW" to R.string.lang_zh_tw,
            "zh_CN" to R.string.lang_zh_cn
        ),
        defaultedBySM = true
    ),
    GeneralPatientName(
        key = "patient_name",
        defaultValue = "",
        titleResId = R.string.pref_title_patient_name,
        summaryResId = R.string.pref_summary_patient_name,
        validator = StringValidator.personName()
    ),
    GeneralDarkMode(
        key = "use_dark_mode",
        defaultValue = "dark",
        titleResId = R.string.pref_title_app_color_scheme,
        summaryResId = R.string.pref_summary_theme_switcher,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "dark" to R.string.pref_dark_theme,
            "light" to R.string.pref_light_theme,
            "system" to R.string.pref_follow_system_theme
        ),
        defaultedBySM = true
    ),

    AapsDirectoryUri(key = "aaps_directory", defaultValue = "", titleResId = R.string.pref_title_aaps_directory),

    ProtectionMasterPassword(key = "master_password", defaultValue = "", titleResId = R.string.pref_title_master_password, isPassword = true, isHashed = true),
    ProtectionSettingsPassword(
        key = "settings_password", defaultValue = "", titleResId = R.string.pref_title_settings_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeSettings }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionSettingsPin(
        key = "settings_pin", defaultValue = "", titleResId = R.string.pref_title_settings_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeSettings }, ProtectionType.CUSTOM_PIN.ordinal)
    ),
    ProtectionApplicationPassword(
        key = "application_password", defaultValue = "", titleResId = R.string.pref_title_application_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeApplication }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionApplicationPin(
        key = "application_pin", defaultValue = "", titleResId = R.string.pref_title_application_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeApplication }, ProtectionType.CUSTOM_PIN.ordinal)
    ),
    ProtectionBolusPassword(
        key = "bolus_password", defaultValue = "", titleResId = R.string.pref_title_bolus_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeBolus }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionBolusPin(
        key = "bolus_pin", defaultValue = "", titleResId = R.string.pref_title_bolus_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeBolus }, ProtectionType.CUSTOM_PIN.ordinal)
    ),

    SafetyAge(key = "age", defaultValue = "adult", titleResId = R.string.pref_title_patient_age, preferenceType = PreferenceType.LIST, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    MaintenanceEmail(
        key = "maintenance_logs_email",
        defaultValue = "logs@aaps.app",
        titleResId = R.string.maintenance_email,
        defaultedBySM = true,
        validator = StringValidator.email()
    ),
    MaintenanceIdentification(key = "email_for_crash_report", defaultValue = "", titleResId = R.string.pref_title_identification),
    AutomationLocation(
        key = "location",
        defaultValue = "PASSIVE",
        titleResId = R.string.pref_title_automation_location,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "PASSIVE" to R.string.automation_location_passive,
            "NETWORK" to R.string.automation_location_network,
            "GPS" to R.string.automation_location_gps
        ),
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    SmsAllowedNumbers(
        key = "smscommunicator_allowednumbers",
        defaultValue = "",
        titleResId = R.string.smscommunicator_allowednumbers,
        summaryResId = R.string.smscommunicator_allowednumbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsOtpPassword(
        key = "smscommunicator_otp_password",
        defaultValue = "",
        titleResId = R.string.smscommunicator_otp_pin,
        summaryResId = R.string.smscommunicator_otp_pin_summary,
        dependency = BooleanKey.SmsAllowRemoteCommands,
        isPassword = true,
        validator = StringValidator.pinStrength()
    ),

    VirtualPumpType(key = "virtualpump_type", defaultValue = "Generic AAPS", titleResId = R.string.pref_title_virtual_pump_type, preferenceType = PreferenceType.LIST),

    NsClientUrl(
        key = "nsclientinternal_url",
        defaultValue = "",
        titleResId = R.string.ns_client_url_title,
        summaryResId = R.string.ns_client_url_summary,
        validator = StringValidator.httpsUrl()
    ),
    NsClientApiSecret(
        key = "nsclientinternal_api_secret",
        defaultValue = "",
        titleResId = R.string.ns_client_secret_title,
        summaryResId = R.string.ns_client_secret_summary,
        isPassword = true,
        validator = StringValidator.minLength(12)
    ),
    NsClientWifiSsids(
        key = "ns_wifi_ssids",
        defaultValue = "",
        titleResId = R.string.ns_wifi_ssids,
        summaryResId = R.string.ns_wifi_ssids_summary,
        dependency = BooleanKey.NsClientUseWifi
    ),
    NsClientAccessToken(
        key = "nsclient_token",
        defaultValue = "",
        titleResId = R.string.nsclient_token_title,
        summaryResId = R.string.nsclient_token_summary,
        isPassword = true,
        validator = StringValidator.minLength(17)
    ),

    // ── FCL vNext StringKeys ──────────────────────────────────────────────
    fcl_vnext_dose_distribution_style(
        key = "fcl_vnext_dose_distribution_style",
        defaultValue = "SMOOTH",
        titleResId = R.string.fcl_vnext_dose_distribution_style,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "VERY_SMOOTH" to R.string.fcl_dist_very_smooth,
            "SMOOTH"      to R.string.fcl_dist_smooth,
            "BALANCED"    to R.string.fcl_dist_balanced,
            "PULSED"      to R.string.fcl_dist_pulsed,
            "VERY_PULSED" to R.string.fcl_dist_very_pulsed
        )
    ),
    fcl_vnext_night_response_style(
        key = "fcl_vnext_night_response_style",
        defaultValue = "NORMAL",
        titleResId = R.string.fcl_vnext_night_response_style,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "VERY_GUARDED" to R.string.fcl_night_very_guarded,
            "GUARDED"      to R.string.fcl_night_guarded,
            "BALANCED"     to R.string.fcl_night_balanced,
            "RESPONSIVE"   to R.string.fcl_night_responsive,
            "PROACTIVE"    to R.string.fcl_night_proactive
        )
    ),
    fcl_vnext_resistance_behavior(
        key = "fcl_vnext_resistance_behavior",
        defaultValue = "MODERATE",
        titleResId = R.string.fcl_vnext_resistance_behavior,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "OFF"        to R.string.fcl_res_off,
            "LIGHT"      to R.string.fcl_res_light,
            "NORMAL"     to R.string.fcl_res_normal,
            "STRONG"     to R.string.fcl_res_strong,
            "AGGRESSIVE" to R.string.fcl_res_aggressive
        )
    ),
    fcl_vnext_resistance_stability(
        key = "fcl_vnext_resistance_stability",
        defaultValue = "MODERATE",
        titleResId = R.string.fcl_vnext_resistance_stability,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "VERY_STABLE" to R.string.fcl_stab_very_stable,
            "STANDARD"    to R.string.fcl_stab_standard,
            "RESPONSIVE"  to R.string.fcl_stab_responsive
        )
    ),
    fcl_vnext_activity_behavior(
        key = "fcl_vnext_activity_behavior",
        defaultValue = "NONE",
        titleResId = R.string.fcl_vnext_activity_behavior,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "OFF"    to R.string.fcl_act_off,
            "LIGHT"  to R.string.fcl_act_light,
            "NORMAL" to R.string.fcl_act_normal,
            "STRONG" to R.string.fcl_act_strong
        )
    ),
    OchtendStart(key = "fcl_ochtend_start", defaultValue = "07:00", titleResId = R.string.OchtendStart),
    OchtendStartWeekend(key = "fcl_ochtend_start_weekend", defaultValue = "08:00", titleResId = R.string.OchtendStartWeekend),
    NachtStart(key = "fcl_nacht_start", defaultValue = "23:00", titleResId = R.string.NachtStart),
    WeekendDagen(key = "fcl_weekend_dagen", defaultValue = "6,7", titleResId = R.string.WeekendDagen),

}
