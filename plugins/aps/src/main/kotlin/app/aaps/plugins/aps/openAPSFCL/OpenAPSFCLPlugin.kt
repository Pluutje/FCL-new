package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LongSparseArray
import androidx.core.util.forEach
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
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
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
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
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.aps.DetermineBasalResult
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPS.TddStatus
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.ln

@Singleton
open class OpenAPSFCLPlugin @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalFCL: DetermineBasalFCL,
    private val profiler: Profiler,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.openaps_fcl)
        .shortName(app.aaps.core.ui.R.string.fcl_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList(showInList = { config.APS })
        .description(R.string.description_smb)
        .setDefault(),
    aapsLogger, rh
), APS, PluginConstraints {

    override fun onStart() {
        super.onStart()
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
            if (variableSens > 0) dynIsfCache.put(key, variableSens)
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
    }

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: DetermineBasalResult? = null
    override fun supportsDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as ProfileSealed.EPS).value.originalPercentage / 100.0
        val sensitivity = calculateVariableIsf(start, multiplier)
        if (sensitivity.second == null)
            uiInteraction.addNotificationValidTo(
                Notification.DYN_ISF_FALLBACK, start,
                rh.gs(R.string.fallback_to_isf_no_tdd, sensitivity.first), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
        else
            uiInteraction.dismissNotification(Notification.DYN_ISF_FALLBACK)
        profiler.log(LTag.APS, "getIsfMgdl() multiplier=${multiplier} reason=${sensitivity.first} sensitivity=${sensitivity.second} caller=$caller", start)
        return sensitivity.second
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        dynIsfCache.forEach { key, value ->
            if (key in start..timestamp) {
                count++
                sum += value
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        try {
            val pump = activePlugin.activePump
            return pump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            return true
        }
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)

        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val smbAlwaysEnabled = preferences.get(BooleanKey.ApsUseSmbAlways)
        val uamEnabled = true //preferences.get(BooleanKey.ApsUseUam)
        val advancedFiltering = activePlugin.activeBgSource.advancedFilteringSupported()
        //val autoSensOrDynIsfSensEnabled = if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
        //    preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)
        //} else {
        //    preferences.get(BooleanKey.ApsUseAutosens)
        //}
        val autoSensOrDynIsfSensEnabled = false

        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAlways.key)?.isVisible = smbEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithCob.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithLowTt.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAfterCarbs.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsResistanceLowersTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsSensitivityRaisesTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<AdaptiveIntPreference>(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb.key)?.isVisible = smbEnabled && uamEnabled
    }

    private val dynIsfCache = LongSparseArray<Double>()

    @Synchronized
    private fun calculateVariableIsf(timestamp: Long, multiplier: Double): Pair<String, Double?> {
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity)) return Pair("OFF", null)

        val result = persistenceLayer.getApsResultCloseTo(timestamp)
        if (result?.variableSens != null && result.variableSens != 0.0) {
            //aapsLogger.debug("calculateVariableIsf $caller DB  ${dateUtil.dateAndTimeAndSecondsString(timestamp)} ${result.variableSens}")
            return Pair("DB", result.variableSens)
        }

        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        // Round down to 30 min and use it as a key for caching
        // Add BG to key as it affects calculation
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        val cached = dynIsfCache[key]
        if (cached != null && timestamp < dateUtil.now()) {
            //aapsLogger.debug("calculateVariableIsf $caller HIT ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $cached")
            return Pair("HIT", cached)
        }

        val dynIsfResult = calculateRawDynIsf(multiplier)
        if (!dynIsfResult.tddPartsCalculated()) return Pair("TDD miss", null)
        // no cached result found, let's calculate the value
        //aapsLogger.debug("calculateVariableIsf $caller CAL ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $sensitivity")
        dynIsfCache.put(key, dynIsfResult.variableSensitivity)
        if (dynIsfCache.size() > 1000) dynIsfCache.clear()
        return Pair("CALC", dynIsfResult.variableSensitivity)
    }

    internal class DynIsfResult {

        var tdd1D: Double? = null
        var tdd7D: Double? = null
        var tddLast24H: Double? = null
        var tddLast4H: Double? = null
        var tddLast8to4H: Double? = null
        var tdd: Double? = null
        var variableSensitivity: Double? = null
        var insulinDivisor: Int = 0

        var tddLast24HCarbs = 0.0
        var tdd7DDataCarbs = 0.0
        var tdd7DAllDaysHaveCarbs = false

        fun tddPartsCalculated() = tdd1D != null && tdd7D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null

        fun log() =
            "DynIsfResult: tdd1D=$tdd1D tdd7D=$tdd7D tddLast24H=$tddLast24H tddLast4H=$tddLast4H tddLast8to4H=$tddLast8to4H tdd=$tdd variableSensitivity=$variableSensitivity insulinDivisor=$insulinDivisor tdd7DDataCarbs=$tdd7DDataCarbs tdd7DAllDaysHaveCarbs=$tdd7DAllDaysHaveCarbs"
    }

    private fun calculateRawDynIsf(multiplier: Double): DynIsfResult {
        val dynIsfResult = DynIsfResult()
        // DynamicISF specific
        // without these values DynISF doesn't work properly
        // Current implementation is fallback to SMB if TDD history is not available. Thus calculated here
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        dynIsfResult.tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount
        tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.let {
            dynIsfResult.tdd7D = it.data.totalAmount
            dynIsfResult.tdd7DDataCarbs = it.data.carbs
            dynIsfResult.tdd7DAllDaysHaveCarbs = it.allDaysHaveCarbs
        }
        tddCalculator.calculateDaily(-24, 0)?.also {
            dynIsfResult.tddLast24H = it.totalAmount
            dynIsfResult.tddLast24HCarbs = it.carbs
        }
        dynIsfResult.tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        dynIsfResult.tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount

        val insulin = activePlugin.activeInsulin
        dynIsfResult.insulinDivisor = when {
            insulin.peak > 65 -> 55 // rapid peak: 75
            insulin.peak > 50 -> 65 // ultra rapid peak: 55
            else              -> 75 // lyumjev peak: 45
        }


        if (dynIsfResult.tddPartsCalculated() && glucoseStatus != null) {
            val tddStatus = TddStatus(dynIsfResult.tdd1D!!, dynIsfResult.tdd7D!!, dynIsfResult.tddLast24H!!, dynIsfResult.tddLast4H!!, dynIsfResult.tddLast8to4H!!)
            val tddWeightedFromLast8H = ((1.4 * tddStatus.tddLast4H) + (0.6 * tddStatus.tddLast8to4H)) * 3
            dynIsfResult.tdd = ((tddWeightedFromLast8H * 0.33) + (tddStatus.tdd7D * 0.34) + (tddStatus.tdd1D * 0.33)) * preferences.get(IntKey.ApsDynIsfAdjustmentFactor) / 100.0 * multiplier
            dynIsfResult.variableSensitivity = Round.roundTo(1800 / (dynIsfResult.tdd!! * (ln((glucoseStatus.glucose / dynIsfResult.insulinDivisor) + 1))), 0.1)
            aapsLogger.debug(LTag.APS, "multiplier=$multiplier dynIsfResult=${dynIsfResult.log()} glucoseStatus=${glucoseStatus.glucose} insulinDivisor=${dynIsfResult.insulinDivisor}")
        }
        return dynIsfResult
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
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

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSSMBPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        // End of check, start gathering data

        // val dynIsfMode = preferences.get(BooleanKey.ApsUseDynamicSensitivity) && hardLimits.checkHardLimits(preferences.get(IntKey.ApsDynIsfAdjustmentFactor).toDouble(), R.string.dyn_isf_adjust_title, IntKey.ApsDynIsfAdjustmentFactor.min.toDouble(), IntKey.ApsDynIsfAdjustmentFactor.max.toDouble())
        val dynIsfMode = false
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

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

        var autosensResult = AutosensResult()
        var dynIsfResult: DynIsfResult? = null
        // var variableSensitivity = 0.0
        // var tdd = 0.0
        // var insulinDivisor = 0
        dynIsfResult = calculateRawDynIsf((profile as ProfileSealed.EPS).value.originalPercentage / 100.0)
        if (dynIsfMode && !dynIsfResult.tddPartsCalculated()) {
            uiInteraction.addNotificationValidTo(
                Notification.SMB_FALLBACK, dateUtil.now(),
                rh.gs(R.string.fallback_smb_no_tdd), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
            inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).also {
                    it.set(false, rh.gs(R.string.fallback_smb_no_tdd), this)
                }
            )
            inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).apply {
                    set(true, "tdd1D=${dynIsfResult.tdd1D} tdd7D=${dynIsfResult.tdd7D} tddLast4H=${dynIsfResult.tddLast4H} tddLast8to4H=${dynIsfResult.tddLast8to4H} tddLast24H=${dynIsfResult.tddLast24H}", this)
                }
            )
        }
        if (dynIsfMode && dynIsfResult.tddPartsCalculated()) {
            uiInteraction.dismissNotification(Notification.SMB_FALLBACK)
            // Compare insulin consumption of last 24h with last 7 days average
            val tddRatio = if (preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)) dynIsfResult.tddLast24H!! / dynIsfResult.tdd7D!! else 1.0
            // Because consumed carbs affects total amount of insulin compensate final ratio by consumed carbs ratio
            // take only 60% (expecting 40% basal). We cannot use bolus/total because of SMBs
            val carbsRatio = if (
                preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity) &&
                dynIsfResult.tddLast24HCarbs != 0.0 &&
                dynIsfResult.tdd7DDataCarbs != 0.0 &&
                dynIsfResult.tdd7DAllDaysHaveCarbs
            ) ((dynIsfResult.tddLast24HCarbs / dynIsfResult.tdd7DDataCarbs - 1.0) * 0.6) + 1.0 else 1.0
            autosensResult = AutosensResult(
                ratio = tddRatio / carbsRatio,
                ratioFromTdd = tddRatio,
                ratioFromCarbs = carbsRatio
            )
        } else {
            if (constraintsChecker.isAutosensModeEnabled().value()) {
                val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
                if (autosensData == null) {
                    rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                    return
                }
                autosensResult = autosensData.autosensResult
            } else autosensResult.sensResult = "autosens disabled"
        }

        @Suppress("KotlinConstantConditions")
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        @Suppress("KotlinConstantConditions")
        val oapsProfile = OapsProfileFCL(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = profile.getIc(),
            sens = profile.getIsfMgdl("OpenAPSSMBPlugin"),
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = true, // smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
            enableSMB_after_carbs = true, //smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = 20, //preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = if (dynIsfMode) dynIsfResult.variableSensitivity ?: 0.0 else 0.0,
            insulinDivisor = dynIsfResult.insulinDivisor,
            TDD = dynIsfResult.tdd ?: 0.0
        )
        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal SMB <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "DynIsfMode:         $dynIsfMode")

        determineBasalFCL.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = dynIsfMode && dynIsfResult.tddPartsCalculated()
        ).also {
            val determineBasalResult = DetermineBasalResult(injector, it)
            // Preserve input data
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfileFCL = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = true // preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        } else {
            // SMB mode
            // val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            val enabled = false
            if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        }
        return value
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        // if (requiredKey != null && requiredKey != "absorption_smb_advanced") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "openapsfcl_settings"
            title = rh.gs(R.string.openaps_fcl)

            addPreference(
                AdaptiveIntentPreference(
                    ctx = context,
                    intentKey = IntentKey.ApsLinkToDocs,
                    intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.Algoritme_algemeen_doc)) },
                    summary = R.string.Info_FCL
                )
            )

            // Maak het FCL instellingenscherm en stel in om direct uitgevouwen te tonen
            val fclScreen = preferenceManager.createPreferenceScreen(context).apply {
                key = "FCL settings"
                title = "FCL settings"
                initialExpandedChildrenCount = Int.MAX_VALUE // Zorgt dat dit scherm direct uitgevouwen wordt

                addPreference(
                    AdaptiveIntentPreference(
                        ctx = context,
                        intentKey = IntentKey.ApsLinkToDocs,
                        intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.b_fcl_doc)) },
                        summary = R.string.Info_fcl
                    )
                )


                // safety Settings screen
                val safetyScreen = preferenceManager.createPreferenceScreen(context).apply {
                    key = "safety Settings"
                    title = "safety Settings"
                    initialExpandedChildrenCount = Int.MAX_VALUE

                    addPreference(
                        AdaptiveIntentPreference(
                            ctx = context,
                            intentKey = IntentKey.ApsLinkToDocs,
                            intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.b_fcl_doc)) },
                            summary = R.string.Info_fcl_safety
                        )
                    )

                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.Hourly_Sens_min, dialogMessage = R.string.Hourly_Sens_min_summary, title = R.string.Hourly_Sens_min_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.Hourly_Sens_max, dialogMessage = R.string.Hourly_Sens_max_summary, title = R.string.Hourly_Sens_max_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.hypoThresholdDay, dialogMessage = R.string.hypoThresholdDay_summary, title = R.string.hypoThresholdDay_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.hypoThresholdNight, dialogMessage = R.string.hypoThresholdNight_summary, title = R.string.hypoThresholdNight_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.hypoRecoveryBGRange, dialogMessage = R.string.hypoRecoveryBGRange_summary, title = R.string.hypoRecoveryBGRange_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.hypoRecoveryMinutes, dialogMessage = R.string.hypoRecoveryMinutes_summary, title = R.string.hypoRecoveryMinutes_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.hypo_recovery_aggressiveness, dialogMessage = R.string.hypo_recovery_aggressiveness_summary, title = R.string.hypo_recovery_aggressiveness_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.min_recovery_days, dialogMessage = R.string.min_recovery_days_summary, title = R.string.min_recovery_days_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.max_recovery_days, dialogMessage = R.string.max_recovery_days_summary, title = R.string.max_recovery_days_title))
                }
                addPreference(safetyScreen)

                // Bolus Settings screen
                val bolusScreen = preferenceManager.createPreferenceScreen(context).apply {
                    key = "Bolus Settings"
                    title = "Bolus Settings"
                    initialExpandedChildrenCount = Int.MAX_VALUE

                    addPreference(
                        AdaptiveIntentPreference(
                            ctx = context,
                            intentKey = IntentKey.ApsLinkToDocs,
                            intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.b_fcl_doc)) },
                            summary = R.string.Info_fcl_bolus
                        )
                    )

                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.max_bolus, dialogMessage = R.string.max_bolus_summary, title = R.string.max_bolus_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.bolus_perc_day, dialogMessage = R.string.bolus_perc_day_summary, title = R.string.bolus_perc_day_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.bolus_perc_night, dialogMessage = R.string.bolus_perc_night_summary, title = R.string.bolus_perc_night_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.bolus_perc_early, dialogMessage = R.string.bolus_perc_early_summary, title = R.string.bolus_perc_early_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.bolus_perc_late, dialogMessage = R.string.bolus_perc_late_summary, title = R.string.bolus_perc_late_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.peak_damping_percentage, dialogMessage = R.string.peak_damping_percentage_summary, title = R.string.peak_damping_percentage_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.hypo_risk_percentage, dialogMessage = R.string.hypo_risk_percentage_summary, title = R.string.hypo_risk_percentage_title))
                }
                addPreference(bolusScreen)

                // Learning Settings scherm
                val learningScreen = preferenceManager.createPreferenceScreen(context).apply {
                    key = "Learning Settings"
                    title = "Learning Settings"
                    initialExpandedChildrenCount = Int.MAX_VALUE

                    addPreference(
                        AdaptiveIntentPreference(
                            ctx = context,
                            intentKey = IntentKey.ApsLinkToDocs,
                            intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.b_fcl_doc)) },
                            summary = R.string.Info_fcl_learning
                        )
                    )

                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ResetLearning, summary = R.string.ResetLearning_summary, title = R.string.ResetLearning_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.CarbISF_min_Factor, dialogMessage = R.string.CarbISF_min_Factor_summary, title = R.string.CarbISF_min_Factor_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.CarbISF_max_Factor, dialogMessage = R.string.CarbISF_max_Factor_summary, title = R.string.CarbISF_max_Factor_title))
                }
                addPreference(learningScreen)

                // Carb Settings scherm
                val CarbScreen = preferenceManager.createPreferenceScreen(context).apply {
                    key = "Carb Settings"
                    title = "Carb Settings"
                    initialExpandedChildrenCount = Int.MAX_VALUE

                    addPreference(
                        AdaptiveIntentPreference(
                            ctx = context,
                            intentKey = IntentKey.ApsLinkToDocs,
                            intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.b_fcl_doc)) },
                            summary = R.string.Info_fcl_learning
                        )
                    )

                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.carb_percentage, dialogMessage = R.string.carb_percentage_summary, title = R.string.carb_percentage_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.meal_detection_sensitivity, dialogMessage = R.string.meal_detection_sensitivity_summary, title = R.string.meal_detection_sensitivity_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.tau_absorption_minutes, dialogMessage = R.string.tau_absorption_minutes_summary, title = R.string.tau_absorption_minutes_title))
                }
                addPreference(CarbScreen)



                // Resistentie instelling scherm
                val resistentieScreen = preferenceManager.createPreferenceScreen(context).apply {
                    key = "Day-Night settings"
                    title = "Day-Night settings"
                    initialExpandedChildrenCount = Int.MAX_VALUE

                    addPreference(
                        AdaptiveIntentPreference(
                            ctx = context,
                            intentKey = IntentKey.ApsLinkToDocs,
                            intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.e_dag_nacht_doc)) },
                            summary = R.string.Info_dag_nacht
                        )
                    )

                    addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.WeekendDagen, dialogMessage = R.string.WeekendDagen_summary, title = R.string.WeekendDagen_title))
                    addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.OchtendStart, dialogMessage = R.string.OchtendStart_summary, title = R.string.OchtendStart_title))
                    addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.OchtendStartWeekend, dialogMessage = R.string.OchtendStartWeekend_summary, title = R.string.OchtendStartWeekend_title))
                    addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.NachtStart, dialogMessage = R.string.NachtStart_summary, title = R.string.NachtStart_title))
                }
                addPreference(resistentieScreen)

                // Bg Persistent instelling scherm
                val persistentScreen = preferenceManager.createPreferenceScreen(context).apply {
                    key = "Bg Persistent instelling"
                    title = "Bg Persistent instelling"
                    initialExpandedChildrenCount = Int.MAX_VALUE

                    addPreference(
                        AdaptiveIntentPreference(
                            ctx = context,
                            intentKey = IntentKey.ApsLinkToDocs,
                            intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.persistent_doc)) },
                            summary = R.string.Info_Persistent
                        )
                    )

                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.PersistentAanUit, summary = R.string.PersistentAanUit_summary, title = R.string.PersistentAanUit_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.persistent_Dagdrempel, dialogMessage = R.string.persistent_Dagdrempel_summary, title = R.string.persistent_Dagdrempel_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.persistent_Dag_MaxBolus, dialogMessage = R.string.persistent_Dag_MaxBolus_summary, title = R.string.persistent_Dag_MaxBolus_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.persistent_Nachtdrempel, dialogMessage = R.string.persistent_Nachtdrempel_summary, title = R.string.persistent_Nachtdrempel_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.persistent_Nacht_MaxBolus, dialogMessage = R.string.persistent_Nacht_MaxBolus_summary, title = R.string.persistent_Nacht_MaxBolus_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.persistent_CoolDown, dialogMessage = R.string.persistent_CoolDown_summary, title = R.string.persistent_CoolDown_title))
                }
                addPreference(persistentScreen)

                // Activiteit/Stappen instelling scherm
                val activiteitScreen = preferenceManager.createPreferenceScreen(context).apply {
                    key = "Activity/Steps settings"
                    title = "Activity/Steps settings"
                    initialExpandedChildrenCount = Int.MAX_VALUE

                    addPreference(
                        AdaptiveIntentPreference(
                            ctx = context,
                            intentKey = IntentKey.ApsLinkToDocs,
                            intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.b_stappen_doc)) },
                            summary = R.string.Info_activiteit
                        )
                    )

                    addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.stappenAanUit, summary = R.string.stappenAanUit_summary, title = R.string.stappenAanUit_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.stap_activiteteitPerc, dialogMessage = R.string.stap_activiteteitPerc_summary, title = R.string.stap_activiteteitPerc_title))
                    addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.stap_TT, dialogMessage = R.string.stap_TT_summary, title = R.string.stap_TT_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.stap_5minuten, dialogMessage = R.string.stap_5minuten_summary, title = R.string.stap_5minuten_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.stap_retentie, dialogMessage = R.string.stap_retentie_summary, title = R.string.stap_retentie_title))
                }
                addPreference(activiteitScreen)
            }
            addPreference(fclScreen)

            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob, dialogMessage = R.string.openapssmb_max_iob_summary, title = R.string.openapssmb_max_iob_title))

            addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSensitivityRaisesTarget, summary = R.string.sensitivity_raises_target_summary, title = R.string.sensitivity_raises_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsResistanceLowersTarget, summary = R.string.resistance_lowers_target_summary, title = R.string.resistance_lowers_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb, summary = R.string.enable_smb_summary, title = R.string.enable_smb))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithHighTt, summary = R.string.enable_smb_with_high_temp_target_summary, title = R.string.enable_smb_with_high_temp_target))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways, summary = R.string.enable_smb_always_summary, title = R.string.enable_smb_always))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithLowTt, summary = R.string.enable_smb_with_temp_target_summary, title = R.string.enable_smb_with_temp_target))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxSmbFrequency, title = R.string.smb_interval_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxMinutesOfBasalToLimitSmb, title = R.string.smb_max_minutes_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsUamMaxMinutesOfBasalToLimitSmb, dialogMessage = R.string.uam_smb_max_minutes, title = R.string.uam_smb_max_minutes_summary))

            // Advanced settings scherm
            val advancedScreen = preferenceManager.createPreferenceScreen(context).apply {
                key = "absorption_smb_advanced"
                title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                initialExpandedChildrenCount = Int.MAX_VALUE

                addPreference(
                    AdaptiveIntentPreference(
                        ctx = context,
                        intentKey = IntentKey.ApsLinkToDocs,
                        intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.openapsama_link_to_preference_json_doc)) },
                        summary = R.string.openapsama_link_to_preference_json_doc_txt
                    )
                )
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAlwaysUseShortDeltas, summary = R.string.always_use_short_avg_summary, title = R.string.always_use_short_avg))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxDailyMultiplier, dialogMessage = R.string.openapsama_max_daily_safety_multiplier_summary, title = R.string.openapsama_max_daily_safety_multiplier))
                addPreference(
                    AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxCurrentBasalMultiplier, dialogMessage = R.string.openapsama_current_basal_safety_multiplier_summary, title = R.string.openapsama_current_basal_safety_multiplier)
                )
            }
            addPreference(advancedScreen)
        }
    }

}
