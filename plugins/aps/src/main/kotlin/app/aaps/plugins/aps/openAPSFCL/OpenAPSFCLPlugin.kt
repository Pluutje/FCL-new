package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File
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
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.RT
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
import app.aaps.core.interfaces.notifications.NotificationManager
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

import app.aaps.plugins.aps.openAPSFCL.vnext.deliveryHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.MAX_DELIVERY_HISTORY
import app.aaps.plugins.aps.openAPSFCL.vnext.lastCycleFclDelivered
import org.joda.time.DateTime

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
    private val notificationManager: NotificationManager,
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

    init {
        // Eén keer bij plugin-constructie: maakt de DI-geïnjecteerde
        // NotificationManager beschikbaar voor FclAiNotificationHelper
        // (dat zelf geen constructor-injectie heeft, zie FclNotificationManagerBridge.kt).
        app.aaps.plugins.aps.openAPSFCL.vnext.FclNotificationManagerBridge.set(notificationManager)

        // 08/07/2026 — waarborgt dat Documents/AAPS/ANALYSE bestaat, vóórdat
        // welke van de acht onafhankelijke schrijvers/lezers dan ook (FCLCycleLogRepository,
        // FclActivityLogger, FCLvNextParameterLogger, FclLearnerLogger,
        // FCLvNextActiveParamsWriter, FCLvNextConfigOverride, FclAiAdvisorDataCollector,
        // FclAiAdvisorHistoryRepository, FclAiAdvisorScheduler — elk met hun EIGEN
        // RELATIVE_PATH-constante, geen gedeelde helper) voor het eerst iets probeert
        // te lezen/schrijven. Draait bij elke plugin-constructie (dus ook na een verse
        // installatie of na het wissen van appdata) — mkdirs() is bewust idempotent:
        // bestaat de map al, dan doet dit niets.
        try {
            val analyseDir = File(Environment.getExternalStorageDirectory(), "Documents/AAPS/ANALYSE")
            if (!analyseDir.exists()) {
                val created = analyseDir.mkdirs()
                aapsLogger.info(LTag.APS, "FCLvNext: ANALYSE-map ontbrak, aangemaakt=$created (${analyseDir.absolutePath})")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "FCLvNext: kon ANALYSE-map niet controleren/aanmaken: ${e.message}")
        }
    }

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.FCL
    override var lastAPSResult: APSResult? = null

    // 08/07/2026 — zie invoke(): bij het EERSTE cyclus zonder glucosedata
    // (glucoseStatus == null) wordt de lopende tijdelijke basaal eenmalig
    // veiliggesteld (neutraal/nul); volgende, aaneengesloten cycli zonder
    // glucosedata worden overgeslagen (geen herhaalde ingreep nodig/gewenst).
    private var lastCycleHadMissingGlucose: Boolean = false

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

        // 08/07/2026 — naar voren gehaald: hangt alleen af van profile
        // (hierboven al non-null gecontroleerd) en dateUtil/processedTbrEbData,
        // niet van glucoseStatus. Nodig vóór de glucose-null-check hieronder,
        // om bij ontbrekende glucosedata alsnog een lopende hoge tijdelijke
        // basaal te kunnen corrigeren.
        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )

        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))

            // 08/07/2026 — veiligheidsfix: zonder glucosedata deed deze
            // functie voorheen NIETS behalve loggen en returnen — een op dat
            // moment lopende tijdelijke basaal (bijv. een hoge WatchingFrontload-
            // temp) bleef dan ongewijzigd doorlopen, mogelijk een half uur of
            // langer bij een aanhoudende sensor-uitval. Eenmalig, bij de EERSTE
            // cyclus zonder glucosedata, wordt een lopende hoge temp nu
            // vervangen door een neutrale/nul-temp — dezelfde aanpak als het
            // bestaande "CGM is calibrating/stale"-vangnet in DetermineBasalFCL.kt
            // (regel ~274), hier toegepast voor het geval dat glucoseStatus
            // zelf helemaal null is (dus dat vangnet nooit wordt bereikt, want
            // determine_basal() zelf wordt dan niet eens aangeroepen).
            // Bewust maar ÉÉN keer: aanhoudende cycli zonder glucosedata worden
            // daarna overgeslagen (geen herhaalde ingreep), zoals gevraagd.
            if (!lastCycleHadMissingGlucose && currentTemp.rate > 0.0) {
                val safeRt = RT(
                    algorithm = APSResult.Algorithm.FCL,
                    runningDynamicIsf = false,
                    timestamp = now,
                    consoleLog = mutableListOf(),
                    consoleError = mutableListOf()
                )
                safeRt.reason.append(
                    "Geen glucosedata beschikbaar — lopende tijdelijke basaal " +
                        "(${currentTemp.rate} U/u) eenmalig veiliggesteld naar neutraal/nul."
                )
                safeRt.deliverAt = now
                safeRt.duration = 30
                safeRt.rate = 0.0

                val safeResult = apsResultProvider.get().with(safeRt)
                safeResult.currentTemp = currentTemp
                lastAPSResult = safeResult
                lastAPSRun = now
                aapsLogger.warn(LTag.APS, "FCLvNext: geen glucosedata, lopende temp (${currentTemp.rate} U/u) veiliggesteld naar 0.")
                rxBus.send(EventAPSCalculationFinished())
                rxBus.send(EventOpenAPSUpdateGui())
            }
            lastCycleHadMissingGlucose = true
            return
        }
        lastCycleHadMissingGlucose = false

        val inputConstraints = ConstraintObject(0.0, aapsLogger)

        if (!hardLimits.checkHardLimits(profile.iCfg.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.diaRange())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.icRange()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSFCLPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.LIMIT_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate.cU, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        // now/tb/currentTemp: zie hierboven, vóór de glucose-null-check verplaatst (08/07/2026)
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG)
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG)
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG)
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG)
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG)
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
        }

        val autosensResult = AutosensResult()
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        val oapsProfile = OapsProfileFCL(
            dia = 0.0,
            min_5m_carbimpact = 0.0,
            max_iob = preferences.get(DoubleKey.fcl_vnext_MaxIOB),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = computeRealMaxBasalUh(),
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
            maxUAMSMBBasalMinutes = 300,
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = 20,
            current_basal = activePlugin.activePump.baseBasalRate.cU,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units   = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = 0.0,
            insulinDivisor = 0,
            TDD = 0.0
        )
        val microBolusAllowed = true
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        // Upstream veiligheidsfix overgenomen van OpenAPSSMBPlugin (23/07/2026,
        // de gebruiker, na een dev-update van die plugin). Reden identiek aan de
        // toelichting daar: carb_ratio voedt csf (sens/carb_ratio) in
        // DetermineBasalFCL.kt's carbsReq-berekening (het legacy oref0-
        // vangnetpad dat nog elke cyclus meedraait naast FCLvNext's eigen
        // pad — zie 'val csf = sens / profile.carb_ratio'), en
        // autosensResult.ratio wordt verderop gebruikt in de sensitivityRatio-
        // afleiding. Een niet-eindige of ≤0-waarde van sens/carb_ratio/
        // autosensRatio zou anders een NaN/Infinity carbsReq veroorzaken. De
        // bijbehorende round()-crash-backstop staat in DetermineBasalFCL.kt.
        // FCL gebruikt geen dynIsfMode (variable_sens/TDD/insulinDivisor zijn
        // hier altijd 0.0/0), dus die tak van de upstream-check is niet van
        // toepassing.
        val invalidInputs = !oapsProfile.sens.isFinite() || oapsProfile.sens <= 0.0 ||
            !oapsProfile.carb_ratio.isFinite() || oapsProfile.carb_ratio <= 0.0 ||
            !autosensResult.ratio.isFinite() || autosensResult.ratio <= 0.0
        if (invalidInputs) {
            val msg = "OpenAPSFCL aborting: invalid ISF inputs sens=${oapsProfile.sens} " +
                "carb_ratio=${oapsProfile.carb_ratio} autosensRatio=${autosensResult.ratio}"
            aapsLogger.error(LTag.APS, msg)

            // Zelfde "veiliggesteld naar neutraal"-aanpak als het bestaande
            // glucoseStatus==null-vangnet hierboven — een op dat moment lopende
            // tijdelijke basaal mag niet zomaar blijven doorlopen terwijl deze
            // cyclus wordt overgeslagen.
            if (currentTemp.rate > 0.0) {
                val safeRt = RT(
                    algorithm = APSResult.Algorithm.FCL,
                    runningDynamicIsf = false,
                    timestamp = now,
                    consoleLog = mutableListOf(),
                    consoleError = mutableListOf(msg)
                )
                safeRt.reason.append(
                    "Ongeldige ISF-invoer — lopende tijdelijke basaal " +
                        "(${currentTemp.rate} U/u) veiliggesteld naar neutraal/nul."
                )
                safeRt.deliverAt = now
                safeRt.duration = 30
                safeRt.rate = 0.0

                val safeResult = apsResultProvider.get().with(safeRt)
                safeResult.currentTemp = currentTemp
                lastAPSResult = safeResult
                lastAPSRun = now
            }
            rxBus.send(EventResetOpenAPSGui(msg))
            rxBus.send(EventOpenAPSUpdateGui())
            return
        }

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

            val fclResistanceRatio = 1.0  // FCLResistance verwijderd (18/06/2026)
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

            if (!lastCycleFclDelivered) {
                val aapsRate = (rt.rate ?: 0.0) * (5.0 / 60.0)
                val aapsBolus = rt.units ?: 0.0
                val aapsDose = aapsRate + aapsBolus
                if (aapsDose > 0.001) {
                    deliveryHistory.addFirst(Triple(DateTime(now), aapsDose, false))
                    while (deliveryHistory.size > MAX_DELIVERY_HISTORY) {
                        deliveryHistory.removeLast()
                    }
                }
            }

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

    // ── Werkelijke pomp-max-basaal, pomptype-onafhankelijk (22/07/2026) ──
    // Sommige pompen (bijv. Metrum) hanteren een vaste ABSOLUTE E/u-grens
    // (pumpDescription.maxTempAbsolute). Andere (bijv. Dana) hanteren een
    // PERCENTAGE van de huidige profiel-basaalstand (bijv. 500% — dus bij
    // 1,5 E/u profiel-basaal is de echte grens 7,5 E/u, niet een vast getal).
    // Beide stijlen hier herleiden tot ÉÉN effectieve E/u-waarde, zodat noch
    // FCLvNext.kt noch deze constraint-check zelf per pomptype hoeft te weten
    // hoe de grens is opgebouwd — en zodat dit generiek blijft werken voor
    // toekomstige gebruikers met een ander pomptype (geen hardcoded
    // per-gebruiker waarde, conform eerdere afspraak).
    // Was hiervoor een vaste 25.0 op alle plekken — die klopte toevallig
    // ongeveer voor een pomp als Metrum, maar was voor een percentage-pomp
    // als Dana veel te ruim (Dana's werkelijke grens ligt normaliter ruim
    // onder 25 E/u).
    private fun computeRealMaxBasalUh(): Double {
        return try {
            val pumpDesc = activePlugin.activePump.pumpDescription
            val currentBasalUh = activePlugin.activePump.baseBasalRate.cU
            val real =
                if (pumpDesc.tempBasalStyle == PumpDescription.ABSOLUTE) {
                    pumpDesc.maxTempAbsolute
                } else {
                    currentBasalUh * (pumpDesc.maxTempPercent / 100.0)
                }
            // Sanity-vloer: een kapotte/lege pump-description mag nooit een
            // 0- of negatieve grens opleveren (dat zou FCLvNext's basaal-pad
            // volledig dichtzetten). Val in dat geval terug op de oude 25.0.
            if (real > 0.5) real else 25.0
        } catch (e: Exception) {
            25.0
        }
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            // 22/07/2026 OPGELOST: de 50 E/u bleek GEEN bug — sinds AAPS'
            // U200-ondersteuning toont de pomp-max in echte eenheden. Deze Medtrum
            // kan mechanisch 0,25 mL/u; bij U200-insuline is dat 50E/u (bij U100
            // zou het 25E/u zijn geweest). computeRealMaxBasalUh() gaf dus gewoon
            // het juiste antwoord. Terug naar de pomp-bewuste berekening, ook hier
            // (dezelfde als FCLvNext's eigen basaal/SMB-verdeling) — geen reden meer
            // om deze buitenste check los te houden.
            val maxBasal = computeRealMaxBasalUh()
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.max_basal_multiplier)), this)
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.max_daily_basal_multiplier)), this)
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

    //   override fun configuration(): JsonObject = JsonObject(emptyMap())

    //   override fun applyConfiguration(configuration: JsonObject) {}


    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "fcl_vnext_settings",
        titleResId = R.string.openaps_fcl,
        items = listOf(
            PreferenceSubScreenDef(
                key = "fcl_vnext_general",
                titleResId = R.string.fcl_vnext_general_title,
                items = listOf(
                    DoubleKey.fcl_aaps_mulitplier_day,
                    DoubleKey.fcl_aaps_mulitplier_night,
                    /*   DoubleKey.max_bolus_night,
                       DoubleKey.fcl_vnext_MaxIOB,
                       StringKey.fcl_vnext_dose_distribution_style,
                       // StringKey.fcl_vnext_night_response_style verwijderd (18/06/2026)
                       // DoubleKey.fcl_vnext_nf_level   */
                )
            ),
            /*   PreferenceSubScreenDef(
                   key = "fcl_vnext_context",
                   titleResId = R.string.fcl_vnext_context_title,
                   items = listOf(
                       StringKey.WeekendDagen,
                       StringKey.OchtendStart,
                       StringKey.OchtendStartWeekend,
                       StringKey.NachtStart,
                       // fcl_vnext_resistance_behavior/stability verwijderd (18/06/2026)
                       StringKey.fcl_vnext_activity_behavior
                   )
               )   */
        ),
        icon = pluginDescription.icon
    )
    /*  override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
          key = "fcl_vnext_settings",
          titleResId = R.string.openaps_fcl,
          items = emptyList(),
          icon = pluginDescription.icon
      )   */
}