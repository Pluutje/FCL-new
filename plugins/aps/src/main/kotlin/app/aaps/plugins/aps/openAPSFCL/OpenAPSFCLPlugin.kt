package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.OapsProfileFCL
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.core.interfaces.aps.oapsProfileFCL
import app.aaps.core.interfaces.sharedPreferences.SP

@Singleton
open class OpenAPSFCLPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val glucoseStatusCalculatorFCL: GlucoseStatusCalculatorFCL,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalFCL: DetermineBasalFCL,
    private val profiler: Profiler,
    private val sp: SP,
    private val apsResultProvider: Provider<APSResult>
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .composeContent { plugin ->
            app.aaps.plugins.aps.openAPSFCL.compose.FCLComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil,
                preferences = preferences,
                sp = sp
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(R.string.openaps_fcl)
        .shortName(app.aaps.core.ui.R.string.fcl_shortname)
        .preferencesVisibleInSimpleMode(false)
        .showInList(showInList = { config.APS })
        .description(R.string.description_smb)
        .setDefault(),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences
), APS, PluginConstraints {

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.FCL
    override var lastAPSResult: APSResult? = null

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorFCL.getGlucoseStatusData(allowOldData)

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            true
        }
    }

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger)

        if (!hardLimits.checkHardLimits(profile.iCfg.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSFCLPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate.cU, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }

        val autosensResult = AutosensResult()
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        val oapsProfile = OapsProfileFCL(
            dia = 0.0,
            min_5m_carbimpact = 0.0,
            max_iob = preferences.get(DoubleKey.fcl_vnext_MaxIOB),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = 25.0,
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = profile.getIc(),
            sens = profile.getIsfMgdl("OpenAPSFCLPlugin"),
            autosens_adjust_targets = false,
            max_daily_safety_multiplier = 500.0,
            current_basal_safety_multiplier = 500.0,
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = false,
            sensitivity_raises_target = false,
            resistance_lowers_target = false,
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = 3,
            enableSMB_with_COB = true,
            enableSMB_with_temptarget = true,
            allowSMB_with_high_temptarget = false,
            enableSMB_always = true,
            enableSMB_after_carbs = true,
            maxSMBBasalMinutes = 240,
            maxUAMSMBBasalMinutes = 240,
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = 20,
            current_basal = activePlugin.activePump.baseBasalRate.cU,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = 0.0,
            insulinDivisor = 0,
            TDD = 0.0
        )
        val microBolusAllowed = true
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        aapsLogger.debug(LTag.APS, ">>> Invoking FCLvNext <<<")

        determineBasalFCL.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected
        ).also { rt ->
            val determineBasalResult = apsResultProvider.get().with(rt)
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfileFCL = oapsProfile
            determineBasalResult.mealData = mealData

            val usedIsfMgdl = rt.variable_sens?.takeIf { it > 0.0 } ?: oapsProfile.sens
            determineBasalResult.variableSens = usedIsfMgdl

            val fclResistanceRatio = rt.sensitivityRatio?.takeIf { it > 0.0 } ?: 1.0
            determineBasalResult.autosensResult = AutosensResult(
                ratio = fclResistanceRatio,
                ratioFromTdd = fclResistanceRatio,
                ratioFromCarbs = 1.0,
                sensResult = "FCLvNext"
            )





            lastAPSResult = determineBasalResult
            lastAPSRun = now

            aapsLogger.debug(LTag.APS, "FCL variableSens mg/dl: $usedIsfMgdl | profile sens mg/dl: ${oapsProfile.sens}")
            rxBus.send(EventAPSCalculationFinished())
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.fcl_vnext_MaxIOB)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            val maxBasal = 25.0
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)
            absoluteRate.setIfSmaller(25.0, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, 25.0, rh.gs(R.string.max_basal_multiplier)), this)
            absoluteRate.setIfSmaller(25.0, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, 25.0, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(true)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(true)
        return value
    }

    override fun configuration(): JsonObject = JsonObject(emptyMap())

    override fun applyConfiguration(configuration: JsonObject) {}

 /*   override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "fcl_vnext_settings",
        titleResId = R.string.openaps_fcl,
        items = listOf(
            PreferenceSubScreenDef(
                key = "fcl_vnext_general",
                titleResId = R.string.fcl_vnext_general_title,
                items = listOf(
                    DoubleKey.max_bolus_day,
                    DoubleKey.max_bolus_night,
                    DoubleKey.fcl_vnext_MaxIOB,
                    StringKey.fcl_vnext_dose_distribution_style,
                    StringKey.fcl_vnext_night_response_style
                )
            ),
            PreferenceSubScreenDef(
                key = "fcl_vnext_context",
                titleResId = R.string.fcl_vnext_context_title,
                items = listOf(
                    StringKey.WeekendDagen,
                    StringKey.OchtendStart,
                    StringKey.OchtendStartWeekend,
                    StringKey.NachtStart,
                    StringKey.fcl_vnext_resistance_behavior,
                    StringKey.fcl_vnext_resistance_stability,
                    StringKey.fcl_vnext_activity_behavior
                )
            )
        ),
        icon = pluginDescription.icon
    )   */
 override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
     key = "fcl_vnext_settings",
     titleResId = R.string.openaps_fcl,
     items = emptyList(),
     icon = pluginDescription.icon
 )
}
