package app.aaps.plugins.aps.openAPSFCL.vnext


import android.annotation.SuppressLint
import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.DoubleKey

import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextParameterLogger
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextProfileParameterSnapshot
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCsvLogRow
import app.aaps.plugins.aps.openAPSFCL.vnext.model.computeIobOvershootFactor
import app.aaps.plugins.aps.openAPSFCL.vnext.model.clampDoseByIob
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.openAPSFCL.vnext.database.toEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner

import kotlin.math.roundToInt

data class FCLvNextInput(
    val bgNow: Double,                          // mmol/L
    val bgHistory: List<Pair<DateTime, Double>>, // mmol/L
    val currentIOB: Double,
    val maxIOB: Double,
    val effectiveISF: Double,                   // mmol/L per U
    val targetBG: Double,                       // mmol/L
    val isNight: Boolean,
    val externalBolusU: Double = 0.0,           // gedetecteerde externe bolus (IOB-delta)
    // Werkelijk afgegeven insuline sinds de vorige cyclus (basaal en
    // bolus/SMB apart), via AAPS-behandelhistorie — zie FclRealDoseTracker.
    // Dekt zowel FCL's eigen doses als alles wat de oref0/SMB-fallback
    // aflevert wanneer FCL niet zelf ingrijpt. Default 0.0 voor andere/
    // oudere call sites.
    val realDeliveredBasalU: Double = 0.0,
    val realDeliveredBolusU: Double = 0.0,
    // IOB-lag compensatie: bolussen gegeven in 8-10 min geleden die nog niet
    // in de AAPS IOB-teller zijn verwerkt (pomp-lag Medtrum ~3-8 min).
    // Berekend via FclRealDoseTracker (AAPS persistenceLayer), dus inclusief
    // oref0-fallback SMBs — completer dan FCLvNext's interne deliveryHistory.
    val pendingBolusU10min: Double = 0.0,
    // Geprogrammeerde profiel-basaalstand (U/h) op dit moment — nodig om
    // realDeliveredBasalU te kunnen beoordelen (boven/op/onder profiel).
    val profileBasalUH: Double = 0.0,
    // Activiteit (stappen) — FCLActivityModule past sensMgdl en targetMgdl
    // elke cyclus aan; tot 19/06/2026 nergens gelogd.
    val activityActive: Boolean = false,
    val activityInsulinPct: Double = 100.0,
    val activityTargetAdjust: Double = 0.0,     // mmol/L
    // Schaalt alleen de oref0/SMB-fallback-laag (Laag 2), niet FCLvNext zelf.
    val aapsMultiplier: Double = 1.0
)

data class FCLvNextContext(
    val input: FCLvNextInput,

    // trends
    val slope: Double,          // mmol/L per uur
    val acceleration: Double,   // mmol/L per uur²
    val consistency: Double,    // 0..1

    // ✅ NEW short-term trend
    val recentSlope: Double,     // mmol/L per uur (laatste segment)
    val recentDelta5m: Double,   // mmol/L per 5 min

    // ✅ NEW curve-fit confidence (04/07/2026, Ecko) — zie FCLvNextTrends.kt
    // voor de uitleg waarom dit apart staat van `consistency`.
    val curveFitR2: Double,          // 0..1
    val curveAcceleration: Double,   // mmol/L per uur²

    // relatieve veiligheid
    val iobRatio: Double,       // currentIOB / maxIOB

    // afstand tot target
    val deltaToTarget: Double   // bgNow - targetBG
)

data class FCLvNextAdvice(
    val bolusAmount: Double,
    val basalRate: Double,
    val shouldDeliver: Boolean,

    // feedback naar determineBasal
    val effectiveISF: Double,
    val targetAdjustment: Double,

    // ✅ peak output (single source of truth for UI + logging)
    val predictedPeak: Double?,
    val peakBand: Int?,
    val peakState: String?,
    val secondDerivative: Double,

    // debug / UI
    val statusText: String
)

private data class DecisionResult(
    val allowed: Boolean,
    val force: Boolean,
    val dampening: Double,
    val reason: String
)

private data class ExecutionResult(
    val bolus: Double,
    val basalRate: Double,      // U/h (temp basal command; AAPS wordt elke cycle vernieuwd)
    val deliveredTotal: Double  // bolus + (basalRate * cycleHours)
)

private enum class MealState { NONE, UNCERTAIN, CONFIRMED }
private enum class TrendState {NONE, RISING_WEAK, RISING_CONFIRMED }
private enum class PeakCategory {NONE, MILD, MEAL, HIGH, EXTREME }
private enum class BgZone { LOW, IN_RANGE, MID, HIGH, EXTREME }
// ── RESCUE (hypo-prevent carbs) DETECTOR (persistent) ──
private enum class RescueState { IDLE, ARMED, CONFIRMED }
private enum class PeakPredictionState {IDLE, WATCHING, CONFIRMED }
private enum class DoseAccessLevel {BLOCKED, MICRO_ONLY, SMALL, NORMAL }
private enum class ReserveCause {PRE_UNCERTAIN_MEAL, POST_PEAK_TOP, SHORT_TERM_DIP }

private data class MealSignal(
    val state: MealState,
    val confidence: Double,     // 0..1
    val reason: String
)

private data class TrendDecision(
    val state: TrendState,
    val reason: String
)

private enum class DowntrendLock { OFF, LOCKED }

private var downtrendLock: DowntrendLock = DowntrendLock.OFF
private var downtrendConfirm: Int = 0
private var plateauConfirm: Int = 0

private var topPlateauConfirm: Int = 0
private var topPlateauHold: Int = 0

private data class DowntrendGate(
    val pauseThisCycle: Boolean,   // dipje → even pauze
    val locked: Boolean,           // echte daling → lock tot plateau
    val reason: String
)

/**
 * Stap 1: simpele trend-poort die snacks/ruis weert.
 * - WEAK: probe mag, maar geen grote acties
 * - CONFIRMED: grote acties toegestaan
 */
private fun classifyTrendState(ctx: FCLvNextContext, config: FCLvNextConfig): TrendDecision {

    // basis betrouwbaarheid
    if (ctx.consistency < config.minConsistency) {
        return TrendDecision(TrendState.NONE, "TREND none: low consistency")
    }

    // thresholds (startwaarden; later tunen op echte meals)
    val weakSlopeMin = 0.45
    val weakAccelMin = 0.10


    val (confSlopeMin, confDeltaMin) = when (config.profielNaam) {
        "AGGRESSIVE", "VERY_AGGRESSIVE" -> 0.65 to 1.2
        else -> 0.95 to 1.8
    }
    val confAccelMin = 0.18


    val slopeEff = maxOf(ctx.slope, ctx.recentSlope * 0.6)
    val accelEff = ctx.acceleration
    val delta = ctx.deltaToTarget

    // dalend/flat -> NONE
    if (slopeEff <= 0.15 && accelEff <= 0.05) {
        return TrendDecision(
            TrendState.NONE,
            "TREND none: slope=${"%.2f".format(slopeEff)} accel=${"%.2f".format(accelEff)}"
        )
    }

    // CONFIRMED (alleen als ook delta echt boven target is)
    val strongAcceleration =
        accelEff >= (confAccelMin * 1.2) &&
            delta >= (confDeltaMin * 0.8)

    val confirmed =
        (
            slopeEff >= confSlopeMin &&
                accelEff >= confAccelMin &&
                delta >= confDeltaMin
            )
            ||
            strongAcceleration

    if (confirmed) {
        return TrendDecision(
            TrendState.RISING_CONFIRMED,
            "TREND confirmed: slope=${"%.2f".format(slopeEff)} accel=${"%.2f".format(accelEff)} delta=${"%.2f".format(delta)}"
        )
    }

    // WEAK (voorzichtig)
    val weak =
        slopeEff >= weakSlopeMin &&
            accelEff >= weakAccelMin

    if (weak) {
        return TrendDecision(
            TrendState.RISING_WEAK,
            "TREND weak: slope=${"%.2f".format(slopeEff)} accel=${"%.2f".format(accelEff)} delta=${"%.2f".format(delta)}"
        )
    }

    return TrendDecision(
        TrendState.NONE,
        "TREND none: slope=${"%.2f".format(slopeEff)} accel=${"%.2f".format(accelEff)} delta=${"%.2f".format(delta)}"
    )
}


// ─────────────────────────────────────────────
// Meal-episode peak estimator (persistent over cycles)
// ─────────────────────────────────────────────

private data class PeakEstimatorContext(
    var active: Boolean = false,
    var startedAt: DateTime? = null,
    var startBg: Double = 0.0,

    // memory features
    var maxSlope: Double = 0.0,          // mmol/L/h
    var maxAccel: Double = 0.0,          // mmol/L/h²
    var posSlopeArea: Double = 0.0,      // mmol/L (∫ max(0,slope) dt)
    var momentum: Double = 0.0,          // mmol/L (decayed posSlopeArea)
    var lastAt: DateTime? = null,

    // state machine
    var state: PeakPredictionState = PeakPredictionState.IDLE,
    var confirmCounter: Int = 0
)

private data class PeakEstimate(
    val state: PeakPredictionState,
    val predictedPeak: Double,
    val peakBand: Int,
    val maxSlope: Double,
    val momentum: Double,
    val riseSinceStart: Double,
    val futureDrop60: Double,
    val predictedPeakBallistic: Double,
    val peakFloorActive: Boolean,
    val peakFloorValue: Double,
    val hEff: Double,
    val iobScaleUsed: Double,
    val vUsed: Double
)


private fun classifyPeak(predictedPeak: Double): PeakCategory {
    return when {
        predictedPeak >= 17.5 -> PeakCategory.EXTREME
        predictedPeak >= 14.5 -> PeakCategory.HIGH
        predictedPeak >= 11.8 -> PeakCategory.MEAL
        predictedPeak >= 9.8  -> PeakCategory.MILD
        else -> PeakCategory.NONE
    }
}


private fun computeBgZone(ctx: FCLvNextContext): BgZone {
    val delta = ctx.deltaToTarget  // bgNow - target

    return when {
        ctx.input.bgNow <= 4.4 -> BgZone.LOW                    // absolute hypo zone
        delta <= 0.6 -> BgZone.IN_RANGE                         // dicht bij target
        delta <= 2.0 -> BgZone.MID                              // licht/matig boven target
        delta <= 4.5 -> BgZone.HIGH                             // duidelijk hoog
        else -> BgZone.EXTREME                                  // zeer hoog
    }
}

// NIEUW:
private fun computeDoseAccessLevel(
    ctx: FCLvNextContext,
    bgZone: BgZone
): DoseAccessLevel {

    return when (bgZone) {

        BgZone.LOW ->
            DoseAccessLevel.BLOCKED

        BgZone.IN_RANGE -> when {
            // Multi-meting bevestiging: slope EN recentSlope EN acceleration moeten
            // alle drie stijging bevestigen. Dit sluit sensorstoring uit (1-2 punten
            // geven hoge slope maar lage recentSlope) en vereist minimaal 3 opeenvolgende
            // metingen met stijging voordat de access wordt verhoogd.
            //
            // Upgrade IN_RANGE → SMALL bij bevestigde aanhoudende stijging:
            //   slope >= 2.5 (duidelijke stijging langetermijn)
            //   recentSlope >= 2.0 (ook recente punten bevestigen)
            //   acceleration >= 0.06 (versnelling aanwezig, geen afvlakking)
            // Dit geeft de commit-pad toegang tot smallCap (i.p.v. microCap) en laat
            // de watching-frontload via Fix C de volledige target leveren.
            //
            // 29/06/2026 (Ecko): bij 10:41 UTC was BG=6.7 mmol (IN_RANGE),
            // slope=3.41, maar commit werd gecapped op microCap=0.12U terwijl
            // commit-pad correct 1.31U berekende. Multi-meting eis was al voldaan
            // maar access bleef MICRO_ONLY → watchingFrontload kon niet overrijden.
            ctx.slope >= 2.5 &&
                ctx.recentSlope >= 2.0 &&
                ctx.acceleration >= 0.06 ->
                DoseAccessLevel.SMALL

            // Bestaand: enkele meting met stijging → MICRO_ONLY
            maxOf(ctx.slope, ctx.recentSlope * 0.5) >= 0.6 && ctx.acceleration >= 0.06 ->
                DoseAccessLevel.MICRO_ONLY

            else ->
                DoseAccessLevel.BLOCKED
        }

        BgZone.MID -> {
            // Combineer lange-termijn en recente slope
            val effectiveSlope = maxOf(ctx.slope, ctx.recentSlope * 0.7)
            when {
                effectiveSlope < 0.5 -> DoseAccessLevel.MICRO_ONLY
                effectiveSlope < 0.9 -> DoseAccessLevel.SMALL
                else -> DoseAccessLevel.NORMAL
            }
        }

        BgZone.HIGH ->
            DoseAccessLevel.NORMAL

        BgZone.EXTREME ->
            DoseAccessLevel.NORMAL
    }
}


private val peakEstimator = PeakEstimatorContext()

private var lastCommitAt: DateTime? = null
private var lastCommitDose: Double = 0.0
private var lastCommitReason: String = ""
// Aparte tracking t.b.v. de post-big-commit afterload-laag: bewust los van
// lastCommitAt/lastCommitDose, want die laatste twee worden ook door kleine
// vervolgcommits (>= effectiveMinCommitDose, vaak 0.30U) overschreven —
// een kleine correctiedosis zou anders de herinnering aan een net-gegeven
// grote dosis (>= 1.5U) kunnen wissen, precies op het moment dat de extra
// rem het hardst nodig is. Wordt alleen bijgewerkt bij commits >= 1.5U.
private var lastBigCommitAt: DateTime? = null
private var lastBigCommitDose: Double = 0.0
// Tijdstip van de laatste earlyBoost-fire (stage2/3)
// Gebruikt om de 1-2 cycli daarna een verhoogde TBR mee te geven
// zodat de bedoelde dosis ook daadwerkelijk geleverd wordt.
private var lastEarlyBoostAt: DateTime? = null
private var lastEarlyBoostDoseU: Double = 0.0
// IOB-tracking voor externe bolus detectie
private var prevIobForExternalDetect: Double = -1.0
private var prevFclDoseForExternalDetect: Double = 0.0

private var lastReentryCommitAt: DateTime? = null

// ─────────────────────────────────────────────
// 🟧 RESERVE POOL (anti-false-dip safety)
// ─────────────────────────────────────────────
private const val RESERVE_TTL_MIN = 25          // houdbaarheid reserve
private const val RESERVE_RELEASE_CAP_FRAC = 0.35 // max % van maxSMB per cycle vrijgeven

private var reservedInsulinU: Double = 0.0
private var reserveAddedAt: DateTime? = null
private var reserveCause: ReserveCause? = null

// logging helpers (reset per cycle)
private var reserveActionThisCycle: String = "NONE"
private var reserveDeltaThisCycle: Double = 0.0

// ─────────────────────────────────────────────
// ⚡ FAST-CARB micro ramp (earlier IOB without commit)
// thresholds tuned from your CSV sample
// ─────────────────────────────────────────────
// ─────────────────────────────────────────────
// 🍽️ MEAL micro ramp (earlier IOB for NORMAL meals)
// ─────────────────────────────────────────────
private const val MEAL_RISE_DELTA5M = 0.06      // mmol/5m  (vroeg, “normale” start)
private const val MEAL_RISE_SLOPE_HR = 1.0      // mmol/L/h
private const val MEAL_RISE_ACCEL = 0.08        // mmol/L/h^2

private const val MEAL_ABORT_DELTA5M = -0.04
private const val MEAL_ABORT_SLOPE_HR = -0.15
private const val MEAL_ABORT_ACCEL = -0.06

private const val MEAL_MICRO_MIN_U = 0.08
private const val MEAL_MICRO_MAX_U = 0.14

// ─────────────────────────────────────────────
// ⚡ FAST micro ramp (snelle carbs → iets hoger, maar strakker abort)
// ─────────────────────────────────────────────
private const val FAST_RISE_DELTA5M = 0.20      // mmol/5m  (echte snelle stijging)
private const val FAST_RISE_SLOPE_HR = 2.8      // mmol/L/h
private const val FAST_RISE_ACCEL = 0.14        // mmol/L/h^2

private const val FAST_ABORT_DELTA5M = -0.03
private const val FAST_ABORT_SLOPE_HR = -0.12
private const val FAST_ABORT_ACCEL = -0.05

private const val FAST_MICRO_MIN_U = 0.08
private const val FAST_MICRO_MAX_U = 0.15

// ─────────────────────────────────────────────
// Curve-fit confidence (04/07/2026, Ecko)
// Gebruikt door computeEarlyBoostFactor() (peakPressureBonus, eerder/sterker
// bij bevestigde stijging) en de late-commit-decay (eerder/steiler afbouwen
// bij bevestigde, veilige afvlakking). Zie FCLvNextTrends.kt voor de fit zelf.
// ─────────────────────────────────────────────
private const val CURVE_FIT_MIN_R2 = 0.90            // onder deze R² wordt de fit niet vertrouwd
private const val CURVE_FIT_EARLY_TRIGGER_MMOL = 1.5 // max. vervroeging van de piekdruk-drempel
private const val TOPPING_OUT_HYPER_REF_MMOL = 10.0  // primaire streefgrens uit doel.txt
private const val TOPPING_OUT_MARGIN_MMOL = 1.5      // marge die "ruim onder" definieert
private const val TOPPING_OUT_MAX_DECAY_BOOST = 0.25 // max. extra steilheid op effectiveDecay

// ─────────────────────────────────────────────
// WatchingFrontload delta-to-target ramp (05/07/2026, Ecko)
// Vervangt de harde aan/uit-drempel op deltaToTarget door een kwadratische
// ease-in-opbouw, zodat de dosis vlak over de drempel laag begint en pas
// verderop (over WATCHING_DELTA_RAMP_WIDTH mmol) naar 100% doorgroeit.
// ─────────────────────────────────────────────
private const val WATCHING_DELTA_RAMP_WIDTH = 0.60 // mmol vanaf de drempel tot volle 100%
private const val WATCHING_DELTA_RAMP_FLOOR = 0.10 // fractie direct op de drempel (voorkomt harde sprong)

// ─────────────────────────────────────────────
// Veiligheid / gating
// ─────────────────────────────────────────────
private const val MICRO_IOB_MAX = 0.45          // micro ramp alleen als er nog ruimte is
private const val MICRO_MIN_CONS = 0.45

// ─────────────────────────────────────────────
// 🧯 EARLY RESET (stop early momentum bij afremmen)
// ─────────────────────────────────────────────
private const val EARLY_RESET_ACCEL = -0.02   // zodra accel negatief wordt (licht)
private const val EARLY_RESET_SLOPE = 0.00    // of zodra macro slope niet meer positief is


// ── EARLY DOSE CONTROLLER (persistent) ──
private data class EarlyDoseContext(
    var stage: Int = 0,              // 0=none, 1=probe, 2=boost
    var lastFireAt: DateTime? = null,
    var lastConfidence: Double = 0.0,
    var boostCommitCount: Int = 0    // telt hoeveel boosted commits er al zijn geweest
)

private var earlyDose = EarlyDoseContext()

// ── PRE-PEAK IMPULSE STATE ──
private var prePeakImpulseDone: Boolean = false
private var lastSegmentAt: DateTime? = null

private var lastSmallCorrectionAt: DateTime? = null

private var earlyConfirmDone: Boolean = false
private var lastEpisodeExitAt: org.joda.time.DateTime? = null  // voor grace-period herstart

// ── SensorBlip persistentie-teller ───────────────────────────────────────
// Telt hoeveel opeenvolgende cycli de blip-conditie (slowFalling+fastRising)
// aanhoudt. Een echte sensorblip is 1-2 cycli; EWMA-naloop na echte stijging
// houdt tientallen cycli aan. Na 2 cycli beschouwen we het als echte stijging.
private var sensorBlipStreakCount: Int = 0
// Ringebuffer: de laatste 3 ruwe BG-waarden voor de consistenticheck
private val recentBgHistory: ArrayDeque<Double> = ArrayDeque(3)

// ── Peak-brake deceleratie-tracking ──────────────────────────────────────
// recentSlope (fast lane) van de vórige cyclus, nodig om een knik/afvlakking
// te detecteren vóórdat ctx.slope (trage lane) zelf al gedaald is.
// Gebruikt door computePeakBrake() (30/06/2026, Ecko: consolidatie van
// peakIobBrake/watchingSlopeOk/peakApproachFactor naar één gedeelde rem).
private var prevRecentSlopeForBrake: Double? = null

// ── Omslag-anticipatie-tracking (12/07/2026, Ecko) ───────────────────────
// curveAcceleration van de vorige cyclus, nodig om een sterke cycle-op-cycle
// terugval te detecteren VOORDAT curveAcceleration zelf al <= 0.0 is (de
// harde, per-definitie-reactieve bevestigingsgrens van curveConfirmtOmslag).
// Zie gebruik bij bgStijgtNogFors hieronder - zelfde soort "trend, niet
// alleen niveau"-aanpak als prevRecentSlopeForBrake hierboven.
private var prevCurveAccelerationForOmslag: Double? = null


private val persistCtrl = PersistentCorrectionController(
    cooldownCycles = 3,        // of 2, jij kiest
    maxBolusFraction = 0.30
)


private data class RescueDetectionContext(
    var state: RescueState = RescueState.IDLE,
    var armedAt: DateTime? = null,
    var armedBg: Double = 0.0,
    var armedPred60: Double = 0.0,
    var armedSlope: Double = 0.0,
    var armedAccel: Double = 0.0,
    var armedIobRatio: Double = 0.0,
    var lastConfirmAt: DateTime? = null,
    var lastReason: String = "",
    var confidence: Double = 0.0
)

private var rescue = RescueDetectionContext()

const val MAX_DELIVERY_HISTORY = 7

val deliveryHistory: ArrayDeque<Triple<DateTime, Double, Boolean>> =
    ArrayDeque()
var lastCycleFclDelivered: Boolean = false

private data class EnergyResult(
    val total: Double,
    val delta: Double,
    val slope: Double,
    val accel: Double
)

private fun calculateEnergy(
    ctx: FCLvNextContext,
    kDelta: Double,
    kSlope: Double,
    kAccel: Double,
    config: FCLvNextConfig
): EnergyResult {

    val delta = ctx.deltaToTarget * kDelta
    val slope = ctx.slope * kSlope
    val accel = ctx.acceleration * kAccel

    val consistency = ctx.consistency
        .coerceAtLeast(config.minConsistency)
        .let { Math.pow(it, config.consistencyExp) }

    val total = (delta + slope + accel) * consistency

    return EnergyResult(total = total, delta = delta, slope = slope, accel = accel)
}

private fun calculateStagnationBoost(
    ctx: FCLvNextContext,
    config: FCLvNextConfig
): Double {

    val active =
        ctx.deltaToTarget >= config.stagnationDeltaMin &&
            ctx.slope > config.stagnationSlopeMaxNeg &&
            ctx.slope < config.stagnationSlopeMaxPos &&
            kotlin.math.abs(ctx.acceleration) <= config.stagnationAccelMaxAbs &&
            ctx.consistency >= config.minConsistency

    if (!active) return 0.0

    return config.stagnationEnergyBoost * ctx.deltaToTarget
}


private fun energyToInsulin(
    energy: Double,
    effectiveISF: Double,
    config: FCLvNextConfig
): Double {
    if (energy <= 0.0) return 0.0
    return (energy / effectiveISF) * config.gain
}


private fun decide(ctx: FCLvNextContext): DecisionResult {

    // === LAYER A — HARD STOPS ===
    if (ctx.consistency < 0.2) {
        return DecisionResult(
            allowed = false,
            force = false,
            dampening = 0.0,
            reason = "Hard stop: unreliable data"
        )
    }

    if (ctx.iobRatio > 1.1) {
        return DecisionResult(
            allowed = false,
            force = false,
            dampening = 0.0,
            reason = "Hard stop: IOB saturated"
        )
    }

    // === LAYER C — FORCE ALLOW ===
    if (ctx.slope > 2.0 && ctx.acceleration > 0.5 && ctx.consistency > 0.6) {
        return DecisionResult(
            allowed = true,
            force = true,
            dampening = 1.0,
            reason = "Force: strong rising trend"
        )
    }

    // === LAYER B — SOFT ALLOW ===

    val consistencyFactor =
        if (ctx.slope >= 0.6 && ctx.acceleration >= 0.15)
            1.0
        else
            ctx.consistency.coerceIn(0.3, 1.0)

    return DecisionResult(
        allowed = true,
        force = false,
        dampening = consistencyFactor,
        reason = "Soft allow"
    )
}

private fun executeDelivery(
    dose: Double,
    hybridPercentage: Int,
    cycleMinutes: Int = 5,           // AAPS-cycle (typisch 5 min)
    maxTempBasalRate: Double = 25.0, // pomp/driver limiet (later pref)
    bolusStep: Double = 0.05,        // SMB stap
    basalRateStep: Double = 0.05,    // rate stap in U/h
    minSmb: Double = 0.05,
    smallDoseThreshold: Double = 0.3
): ExecutionResult {

    val cycleH = (cycleMinutes / 60.0).coerceAtLeast(1.0 / 60.0) // nooit 0
    val maxBasalUnitsThisCycle = (maxTempBasalRate.coerceAtLeast(0.0) * cycleH).coerceAtLeast(0.0)

    // helper: zet units -> rate, clamp en round
    fun unitsToRoundedRate(units: Double): Double {
        if (units <= 0.0) return 0.0
        val wantedRate = units / cycleH
        val cappedRate = wantedRate.coerceAtMost(maxTempBasalRate.coerceAtLeast(0.0))
        return roundToStep(cappedRate, basalRateStep).coerceAtLeast(0.0)
    }

    // 0) niets te doen: stuur expliciet 0-rate zodat lopende temp basal niet doorloopt
    if (dose <= 0.0) {
        return ExecutionResult(
            bolus = 0.0,
            basalRate = 0.0,
            deliveredTotal = 0.0
        )
    }

    // 1) Alle doses < smallDoseThreshold → volledig basaal
    if (dose < smallDoseThreshold || hybridPercentage <= 0) {

        val basalUnitsPlanned = dose.coerceAtMost(maxBasalUnitsThisCycle)
        val basalRateRounded = unitsToRoundedRate(basalUnitsPlanned)
        val basalUnitsDelivered = basalRateRounded * cycleH

        // Eventueel restant (door cap/afronding) alsnog via SMB
        val missing = (dose - basalUnitsDelivered).coerceAtLeast(0.0)
        val bolusRounded =
            if (missing >= minSmb)
                roundToStep(missing, bolusStep).coerceAtLeast(minSmb)
            else
                0.0

        return ExecutionResult(
            bolus = bolusRounded,
            basalRate = basalRateRounded,
            deliveredTotal = basalUnitsDelivered + bolusRounded
        )
    }

    // 3) hybride split (units), maar: bolus-deel < minSmb => schuif naar basaal (geen SMB-only!)
    val hp = hybridPercentage.coerceIn(0, 100)
    var basalUnitsWanted = dose * (hp / 100.0)
    var bolusUnitsWanted = (dose - basalUnitsWanted).coerceAtLeast(0.0)

    if (bolusUnitsWanted in 0.0..(minSmb - 1e-9)) {
        basalUnitsWanted = dose
        bolusUnitsWanted = 0.0
    }

    // 4) bolus afronden (kan 0 worden als we alles basaal willen)
    var bolusRounded = if (bolusUnitsWanted >= minSmb) {
        roundToStep(bolusUnitsWanted, bolusStep)
            .coerceAtLeast(minSmb)
            .coerceAtMost(dose)
    } else 0.0

    // 5) resterende units naar basaal, maar cap op wat in deze cycle kan
    val remainingForBasal = (dose - bolusRounded).coerceAtLeast(0.0)
    val basalUnitsPlanned = remainingForBasal.coerceAtMost(maxBasalUnitsThisCycle)

    val basalRateRounded = unitsToRoundedRate(basalUnitsPlanned)
    val basalUnitsDelivered = basalRateRounded * cycleH

    // 6) wat we niet kwijt konden via basaal (cap/rounding) => als extra SMB proberen
    val missing = (remainingForBasal - basalUnitsDelivered).coerceAtLeast(0.0)

    if (missing >= minSmb) {
        val extraBolus = roundToStep(missing, bolusStep).coerceAtLeast(minSmb)
        bolusRounded = (bolusRounded + extraBolus).coerceAtMost(dose)
    }

    val deliveredTotal = bolusRounded + basalUnitsDelivered

    return ExecutionResult(
        bolus = bolusRounded,
        basalRate = basalRateRounded,
        deliveredTotal = deliveredTotal
    )
}


private fun iobDampingFactor(
    iobRatio: Double,
    config: FCLvNextConfig,
    power: Double
): Double {

    val r = iobRatio.coerceIn(0.0, 2.0)

    if (r <= config.iobStart) return 1.0
    if (r >= config.iobMax) return config.iobMinFactor

    val x = ((r - config.iobStart) /
        (config.iobMax - config.iobStart))
        .coerceIn(0.0, 1.0)

    val shaped = 1.0 - Math.pow(x, power)

    return (config.iobMinFactor +
        (1.0 - config.iobMinFactor) * shaped)
        .coerceIn(config.iobMinFactor, 1.0)
}

private fun roundToStep(value: Double, step: Double): Double {
    if (step <= 0.0) return value
    return (kotlin.math.round(value / step) * step)
}



private fun detectMealSignal(ctx: FCLvNextContext, config: FCLvNextConfig): MealSignal {

    // basisvoorwaarden: voldoende data
    if (ctx.consistency < config.minConsistency) {
        return MealSignal(MealState.NONE, 0.0, "Low consistency")
    }

    val thresholdMul = config.mealDetectThresholdMul

    val slopeMin = config.mealSlopeMin * thresholdMul
    val accelMin = config.mealAccelMin * thresholdMul
    val deltaMin = config.mealDeltaMin * thresholdMul


    // confidence: combineer factoren (simpel, maar werkt)
    val slopeEff = maxOf(ctx.slope, ctx.recentSlope * 0.6)
    val rising = slopeEff > slopeMin
    val accelerating = ctx.acceleration > accelMin
    val aboveTarget = ctx.deltaToTarget > deltaMin

    val slopeScore = ((ctx.slope - slopeMin) / config.mealSlopeSpan).coerceIn(0.0, 1.0)
    val accelScore = ((ctx.acceleration - accelMin) / config.mealAccelSpan).coerceIn(0.0, 1.0)
    val deltaScore = ((ctx.deltaToTarget - deltaMin) / config.mealDeltaSpan).coerceIn(0.0, 1.0)

    val confidence =
        (0.45 * slopeScore + 0.35 * accelScore + 0.20 * deltaScore)
            .let { it * config.mealConfidenceSpeedMul }
            .coerceIn(0.0, 1.0)

    // state
    val baseState = when {
        rising && accelerating && aboveTarget && confidence >= config.mealConfirmConfidence ->
            MealState.CONFIRMED

        (rising || accelerating) && aboveTarget && confidence >= config.mealUncertainConfidence ->
            MealState.UNCERTAIN

        else -> MealState.NONE
    }

    // Aanhoudende stijging detector: pakt trage-start koolhydraatrijke maaltijden
    // op die de normale slope/accel detector missen.
    // Criteria (gebaseerd op 30-daagse xDrip data analyse):
    //   - 3 opeenvolgende stijgende BG-punten
    //   - Minstens 2 van die 3 deltas > 0.3 mmol per 5 min
    //   - Totale stijging > 0.6 mmol over 15 min
    //   - BG huidig > 5.5 mmol
    // Filtert snoepjes eruit: typisch max 0.3-0.4 mmol/5min,
    // nooit 3 opeenvolgende stijgingen boven 0.3 mmol.
    val sustainedRiseConfirmed: Boolean = run {
        val hist = ctx.input.bgHistory
        if (hist.size < 4 || ctx.input.bgNow < 5.5) return@run false
        val sorted = hist.sortedByDescending { it.first.millis }.take(4)
        val bg0 = sorted[0].second
        val bg1 = sorted[1].second
        val bg2 = sorted[2].second
        val bg3 = sorted[3].second
        val d1 = bg0 - bg1
        val d2 = bg1 - bg2
        val d3 = bg2 - bg3
        val allRising = d1 > 0.0 && d2 > 0.0 && d3 > 0.0
        val strongCount = listOf(d1, d2, d3).count { it > 0.3 }
        val totalRise = bg0 - bg3
        allRising && strongCount >= 2 && totalRise > 0.6
    }

    val state = when {
        baseState == MealState.CONFIRMED || sustainedRiseConfirmed ->
            MealState.CONFIRMED
        baseState == MealState.UNCERTAIN -> MealState.UNCERTAIN
        else -> MealState.NONE
    }
    val srTag = if (sustainedRiseConfirmed && baseState != MealState.CONFIRMED) " +SR" else ""

    val reason = "MealSignal=$state$srTag conf=${"%.2f".format(confidence)}"
    return MealSignal(state, confidence, reason)
}


// NIEUW:
private fun canCommitNow(
    now: DateTime,
    ctx: FCLvNextContext,
    config: FCLvNextConfig,
    minutesSinceEpisodeStart: Int = 999   // 999 = geen actieve episode
): Boolean {
    val last = lastCommitAt ?: return true
    val minutes = org.joda.time.Minutes.minutesBetween(last, now).minutes
    val baseCooldown = config.commitCooldownMinutes

    // Dynamische cooldown: sneller bij hoge delta
    val effectiveCooldown = when {
        ctx.deltaToTarget >= 4.0 -> baseCooldown / 2
        ctx.deltaToTarget >= 3.0 -> (baseCooldown * 0.75).toInt()
        else -> baseCooldown
    }

    // ✅ NIEUW: vroege episode override — kortere cooldown bij bewezen maaltijdstijging
    // Alleen actief in eerste 15 minuten van een CONFIRMED episode, en alleen als IOB
    // nog niet zo hoog is dat de peakIobBrake (Patch 1) al zou moeten ingrijpen.
    val earlyEpisodeCooldown =
        if (minutesSinceEpisodeStart in 0..config.earlyEpisodeWindowMinutes &&
            ctx.iobRatio < config.earlyEpisodeCooldownIobMax) {
            effectiveCooldown.coerceAtMost(config.earlyEpisodeMinCooldownMinutes)
        } else {
            effectiveCooldown
        }


    return minutes >= earlyEpisodeCooldown
}

private data class CommitTrigger(
    val ok: Boolean,
    val reason: String
)

private fun accelFirstCommitTrigger(
    ctx: FCLvNextContext,
    peak: PeakEstimate,
    mealSignal: MealSignal,
    config: FCLvNextConfig,
    prePeakCommitWindow: Boolean,
    trend: TrendDecision
): CommitTrigger {

    // Basiseisen: data ok + boven target
    if (ctx.consistency < config.minConsistency) {
        return CommitTrigger(false, "COMMIT accel-first: low consistency")
    }
    if (ctx.deltaToTarget < 0.8) {
        return CommitTrigger(false, "COMMIT accel-first: delta too low")
    }

    // Fast-lane rise (must)
    val fastLane =
        (ctx.recentDelta5m >= 0.08) ||   // ~0.96 mmol/h equivalent
            (ctx.recentSlope >= 0.60)

    // Accel must be meaningful
    val accelOk =
        (ctx.acceleration >= 0.14) ||
            (ctx.acceleration >= 0.10 && ctx.recentDelta5m >= 0.12)

    // Meal “context” (any of these makes it more legit)
    val mealContext =
        mealSignal.state != MealState.NONE ||
            prePeakCommitWindow ||
            trend.state == TrendState.RISING_CONFIRMED ||
            peak.state == PeakPredictionState.WATCHING

    val ok = mealContext && fastLane && accelOk

    return CommitTrigger(
        ok = ok,
        reason = "COMMIT accel-first: mealCtx=$mealContext fast=$fastLane accelOk=$accelOk"
    )
}

private fun commitFractionZoneFactor(bgZone: BgZone, mealActive: Boolean = false): Double {
    return when (bgZone) {
        BgZone.LOW      -> 0.0
        BgZone.IN_RANGE -> if (mealActive) 0.75 else 0.55
        BgZone.MID      -> 0.75
        BgZone.HIGH     -> 1.00
        BgZone.EXTREME  -> 1.10
    }
}

private data class PreReserveDecision(
    val active: Boolean,
    val deliverNow: Double,
    val stash: Double,
    val reason: String
)

private fun computePreReserveSplit(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    commandedDose: Double,
    config: FCLvNextConfig,
    bgZone: BgZone
): PreReserveDecision {

    // Alleen bij meal-like maar onzeker
    if (mealSignal.state != MealState.UNCERTAIN) {
        return PreReserveDecision(false, commandedDose, 0.0, "")
    }

    // Geen kleine doses knijpen
    if (commandedDose < 0.5) {
        return PreReserveDecision(false, commandedDose, 0.0, "")
    }

    // Als IOB al hoog is → later mechanisme
    if (ctx.iobRatio >= 0.35) {
        return PreReserveDecision(false, commandedDose, 0.0, "")
    }

    // Als we al duidelijk afremmen → niet hier
    if (ctx.acceleration < 0.0) {
        return PreReserveDecision(false, commandedDose, 0.0, "")
    }

    // 🔹 Speciale versnelling voor echte maaltijdstijging in EXTREME
    val isAggressiveMealRise =
        bgZone == BgZone.EXTREME &&
            ctx.slope >= 0.9 &&
            ctx.acceleration >= 0.30 &&
            ctx.consistency >= config.episodeMinConsistency &&
            ctx.iobRatio < 0.60

    // Split: deliverFrac daalt vloeiend bij hogere doses
    val deliverFrac =
        if (isAggressiveMealRise) {
            0.85   // bijna alles leveren → sneller IOB
        } else {
            when {
                commandedDose <= 0.8 -> 0.65
                commandedDose >= 1.2 -> 0.50

                else                 -> {
                    // lineair van 0.65 → 0.50 tussen 0.8 en 1.2
                    val t = (commandedDose - 0.8) / (1.2 - 0.8)   // 0..1
                    0.65 - t * (0.65 - 0.50)
                }
            }
        }

    val deliverNow = (commandedDose * deliverFrac)
        .coerceAtMost(commandedDose)
        .coerceAtLeast(0.0)
    val stash = commandedDose - deliverNow

    return PreReserveDecision(
        active = true,
        deliverNow = deliverNow,
        stash = stash,
        reason =
            "PRE-RESERVE SPLIT: UNCERTAIN meal, low IOB (${ctx.iobRatio}), " +
                "dose=${"%.2f".format(commandedDose)}U → " +
                "${"%.2f".format(deliverNow)}U now / ${"%.2f".format(stash)}U reserve"
    )
}


private fun preMealRiseFloorU(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    bgZone: BgZone,
    suppressForPeak: Boolean,
    stagnationActive: Boolean,
    accessLevel: DoseAccessLevel,
    maxBolus: Double,
    config: FCLvNextConfig
): Double {

    // ── 1️⃣ WHEN: context & veiligheid ──

    // Alleen vóór meal confirm
    if (mealSignal.state == MealState.CONFIRMED) return 0.0

    // Geen floor tijdens peak/absorptie of stagnation
    if (suppressForPeak || stagnationActive) return 0.0

    if (ctx.recentDelta5m <= -0.06 || ctx.recentSlope <= -0.20) return 0.0

    // Respecteer harde blokkade
    if (accessLevel == DoseAccessLevel.BLOCKED) return 0.0

    // Duidelijke, consistente stijging boven target
    val rising =
        ctx.consistency >= config.episodeMinConsistency &&
            (
                // normale pre-meal rise
                (ctx.deltaToTarget >= 1.2 && ctx.slope >= 0.30)
                    ||
                    // 🔥 agressieve meal rise in EXTREME
                    (
                        bgZone == BgZone.EXTREME &&
                            ctx.deltaToTarget >= 2.0 &&
                            ctx.slope >= 0.60 &&
                            ctx.acceleration >= 0.20
                        )
                )

    if (!rising) return 0.0


    // ── 2️⃣ HOW MUCH: schaal via maxBolus ──

    val zoneFraction = when (bgZone) {
        BgZone.MID     -> 0.07
        BgZone.HIGH    -> 0.12
        BgZone.EXTREME -> 0.20
        else           -> 0.0
    }

    if (zoneFraction == 0.0) return 0.0

    val rawFloor = maxBolus * zoneFraction

    // Veiligheidsclamps
    return rawFloor
        .coerceAtLeast(0.05)
        .coerceAtMost(0.50)
}



private fun computeCommitFraction(
    signal: MealSignal,
    config: FCLvNextConfig
): Double = when (signal.state) {

    MealState.NONE -> 0.0

    MealState.UNCERTAIN -> {
        val t =
            ((signal.confidence - config.mealUncertainConfidence) /
                (config.mealConfirmConfidence - config.mealUncertainConfidence))
                .coerceIn(0.0, 1.0)

        config.uncertainMinFraction +
            t * (config.uncertainMaxFraction - config.uncertainMinFraction)
    }

    MealState.CONFIRMED -> {
        val t =
            ((signal.confidence - config.mealConfirmConfidence) /
                (1.0 - config.mealConfirmConfidence))
                .coerceIn(0.0, 1.0)

        config.confirmMinFraction +
            t * (config.confirmMaxFraction - config.confirmMinFraction)
    }
}


private fun minutesSince(ts: DateTime?, now: DateTime): Int {
    if (ts == null) return Int.MAX_VALUE
    return org.joda.time.Minutes.minutesBetween(ts, now).minutes
}

private fun isInAbsorptionWindow(now: DateTime, config: FCLvNextConfig): Boolean {
    val m = minutesSince(lastCommitAt, now)
    return m in 0..config.absorptionWindowMinutes
}


private data class HypoProtection(
    val active: Boolean,
    val projectedMin: Double,
    val projectedMinNoInsulin: Double,
    val projectedMinWithPlannedInsulin: Double,
    val reason: String
)

private fun hypoProtection(
    ctx: FCLvNextContext,
    plannedDoseU: Double,
    effectiveISF: Double,
    config: FCLvNextConfig,
    mealSignal: MealSignal? = null,
): HypoProtection {

    fun trendBgAt(min: Int): Double {
        val tHr = min / 60.0

        // Voor hypo-protect wil je “worst-case” daling zien:
        // neem de MEEST dalende indicatie (macro vs fast-lane),
        // maar clamp om CGM spikes niet absurd te maken.
        val vFast = (ctx.recentDelta5m * 12.0).coerceIn(-6.0, 6.0)
        val vMacro = ctx.slope.coerceIn(-6.0, 6.0)

        // Worst-case downtrend - MAAR bij snelle maaltijdstijging (recentSlope>=8.0)
        // is de worst-case NIET de macro-daling: die is een artefact van de vorige
        // episode. Gebruik dan de stijging (vFast) voor een realistische projectie.
        val vEff = if (ctx.recentSlope >= 8.0 && vFast > vMacro)
            maxOf(vMacro, vFast * 0.7)   // stijging, max 70% gewicht
        else
            minOf(vMacro, vFast * 0.9)   // origineel: worst-case daling

        val a = ctx.acceleration.coerceIn(-1.2, 1.2)

        return ctx.input.bgNow +
            vEff * tHr +
            0.5 * a * tHr * tHr
    }

    // Conservatieve “worst-case” insulin impact fracties
    // (veiligheids-gate: liever te streng dan te los).
    //
    // Bij actieve maaltijdstijging (CONFIRMED + recentSlope>=2.0 + bgNow>=7.0)
    // compenseert glucoseabsorptie ~45% van de insulinewerking in 90 min.
    // Zonder compensatie blokkeert de hypo-guard stage2 bij BG=9.2 stijgend.
    val mealCompensationFactor = if (
        mealSignal?.state == MealState.CONFIRMED &&
        ctx.recentSlope >= 2.0 &&
        ctx.input.bgNow >= 7.0
    ) 0.55 else 1.0

    fun insulinActionFrac(min: Int): Double = when {
        min <= 30 -> config.hypoInsulinFrac30 * mealCompensationFactor
        min <= 60 -> config.hypoInsulinFrac60 * mealCompensationFactor
        else      -> config.hypoInsulinFrac90 * mealCompensationFactor
    }

    val horizons = listOf(30, 60, 90)

    var projectedMinNoInsulin = Double.POSITIVE_INFINITY
    var projectedMinWithInsulin = Double.POSITIVE_INFINITY

    for (m in horizons) {
        val bgNoInsulin = trendBgAt(m)
        projectedMinNoInsulin = minOf(projectedMinNoInsulin, bgNoInsulin)

        val insulinImpact =
            if (plannedDoseU > 0.0) plannedDoseU * effectiveISF * insulinActionFrac(m) else 0.0

        val bgWithInsulin = bgNoInsulin - insulinImpact
        projectedMinWithInsulin = minOf(projectedMinWithInsulin, bgWithInsulin)
    }

    val projectedMin = minOf(projectedMinNoInsulin, projectedMinWithInsulin)

    // Safety threshold: iets hoger dan 4.4 om "net-niet" hypos te voorkomen.
    val blockThreshold = config.hypoBlockThreshold

    // fastLaneRising: bypas bij agressieve maaltijdstijging met lage IOB.
    // Fix2 (projectedMinWithInsulin >= 4.0) is de enige veiligheidscheck die
    // nodig is — die is gebaseerd op de werkelijke BG-projectie en werkt
    // correct ongeacht de aanleiding van de stijging (maaltijd of rebound).
    // fastLaneRising: bypas bij agressieve maaltijdstijging.
    // recentSlope-drempel verlaagd van 5.0 naar 3.0 voor de lage-IOB situatie
    // (iobRatio < 0.15): bij een verse episode met weinig IOB is een stijging
    // van 3+ mmol/uur voldoende bewijs. De hogere drempel van 5.0 blijft gelden
    // als er al meer IOB staat (0.15-0.25), want dan is de hypo-projectie
    // gevaarlijker bij een grote commit.
    val effectiveFastLaneSlope = if (ctx.iobRatio < 0.15) 3.0 else 5.0
    // projectedMinNoInsulin >= 4.8 was onhaalbaar bij hoge IOB (na frontload):
    // iob=3.39*ISF4.7 geeft altijd negatieve no-insulin projectie.
    // Vervangen door: projectedMinWithInsulin >= 2.0 (MET dosis minimaal veilig)
    // EN recentSlope >= 8.0 (alleen bij sterke stijging, niet bij ruis).
    val fastLaneRising =
        mealSignal != null &&
            mealSignal.state == MealState.CONFIRMED &&
            ctx.recentSlope >= effectiveFastLaneSlope &&
            ctx.recentDelta5m >= 0.30 &&
            ctx.iobRatio < 0.25 &&
            ctx.input.bgNow >= 5.5 &&
            projectedMinWithInsulin >= 2.0   // was projectedMinNoInsulin>=4.8

    if (fastLaneRising) {
        return HypoProtection(
            active = false,
            projectedMin = projectedMin,
            projectedMinNoInsulin = projectedMinNoInsulin,
            projectedMinWithPlannedInsulin = projectedMinWithInsulin,
            reason = "HYPO PROTECT BYPASSED (fast-lane meal rise, no recent hypo)"
        )
    }
    // ── einde fast-lane bypass ────────────────────────────────────────────

    // ── MEAL-CONTEXT VRIJSTELLING ──────────────────────────────────────────
    // Blokkeer NIET als alle voorwaarden tegelijk gelden:
    //   1) meal is CONFIRMED (niet UNCERTAIN — extra zekerheid vereist)
    //   2) BG stijgt duidelijk op beide tijdschalen (macro + fast-lane)
    //   3) zelfs zónder de geplande dosis is er geen hypo-risico
    //   4) ook MET de geplande dosis blijft de projectie veilig
    // clearlyRisingMealContext: bypas bij bevestigde stijging.
    // Origineel eiste ctx.slope >= 0.6 (macro-slope). Na een sensorwissel herstelt
    // de macro-slope trager dan de 5-min delta, waardoor een echte stijging van 10+
    // mmol/uur werd geblokkeerd terwijl de macro-slope nog negatief was.
    // Fix: macro-slope >= 0.6 OR (recentSlope >= 3.0 AND recentDelta5m > 0.0).
    // De veiligheidseis projectedMinWithInsulin >= 4.0 blijft intact: die blokkeert
    // de bypass als de geplande dosis zelf een hypo projecteert.
    val risingOnEitherTimescale =
        ctx.slope >= 0.6 ||
            (ctx.recentSlope >= 3.0 && ctx.recentDelta5m > 0.0)
    // projectedMinWithInsulin drempel: normaal 4.0 maar bij zeer steile stijging
    // (recentSlope >= 8.0) is 2.0 voldoende veilig. Zo wordt niet geblokkeerd
    // als een grote IOB (na frontload) de no-insulin projectie negatief maakt
    // terwijl de BG snel stijgt en het hyporisico minimaal is.
    val safeProjectionThreshold = if (ctx.recentSlope >= 8.0) 2.0 else 4.0
    val clearlyRisingMealContext = mealSignal != null &&
        mealSignal.state == MealState.CONFIRMED &&
        risingOnEitherTimescale &&
        ctx.recentDelta5m >= 0.0 &&
        ctx.iobRatio < 0.50 &&
        projectedMinWithInsulin >= safeProjectionThreshold
    // projectedMinNoInsulin verwijderd: onhaalbaar bij hoge IOB na frontload

    if (clearlyRisingMealContext) {
        return HypoProtection(
            active = false,
            projectedMin = projectedMin,
            projectedMinNoInsulin = projectedMinNoInsulin,
            projectedMinWithPlannedInsulin = projectedMinWithInsulin,
            reason = "HYPO PROTECT BYPASSED (confirmed meal, rising, safe no-insulin projection)"
        )
    }

    // strongRisingWithIob bypass: sterke stijging terwijl IOB al hoog is.
    // Na een frontload stijgt BG soms door ondanks hoge IOB.
    // projectedMinWithInsulin is dan negatief door hoge IOB maar dat
    // overschat het risico: de bestaande IOB werkt pas volledig na 60-90 min.
    // Sleutel: projNoInsulin >= 5.0 bewijst dat BG zonder extra dosis
    // niet hypo gaat. Die check is conservatief genoeg als veiligheidsgate.
    val strongRisingWithIob =
        mealSignal != null &&
            mealSignal.state == MealState.CONFIRMED &&
            ctx.recentSlope >= 12.0 &&
            ctx.recentDelta5m >= 0.50 &&
            ctx.iobRatio < 0.50 &&
            ctx.input.bgNow >= 7.0 &&
            projectedMinNoInsulin >= 5.0

    if (strongRisingWithIob) {
        return HypoProtection(
            active = false,
            projectedMin = projectedMin,
            projectedMinNoInsulin = projectedMinNoInsulin,
            projectedMinWithPlannedInsulin = projectedMinWithInsulin,
            reason = "HYPO PROTECT BYPASSED (strong rise with existing IOB, no-insulin safe)"
        )
    }

    // lowIobHighBg bypass: BG ruim boven target, IOB laag, niet hard dalend.
    // Situatie: BG 7.5+ na maaltijdpiek met bijna uitgewerkte IOB (iobRatio < 0.12).
    // FCLvNext was hier geblokkeerd terwijl AAPS kleine correcties gaf.
    // Veiligheid: rawProjection = bg - iob*isf > 2.0 garandeert geen hypo.
    //
    // Uitbreiding: de 5m-slope kan negatief zijn door een overgangsoscillatie
    // (dalende maaltijdstaart + nieuwe stijging). Als de recentSlope (langere
    // termijn) >= 3.0 mmol/h aangeeft dat de BG structureel stijgt, laten we
    // de bypass ook toe bij negatieve 5m-slope.
    val rawNoInsulinProjection = ctx.input.bgNow - ctx.input.currentIOB * ctx.input.effectiveISF
    val slopeOk = ctx.slope >= -0.5 || ctx.recentSlope >= 3.0
    // iobRatio drempel verhoogd van 0.12 naar 0.18:
    // Bij 0.12 was de bypass net te krap (0.14 overschreed de drempel na AAPS-bolus).
    // 0.18 dekt ook de overgangszone waarbij externe bolussen de IOB kort verhogen.
    // Veiligheid blijft gewaarborgd via rawNoInsulinProjection > 2.0.
    val lowIobHighBg =
        ctx.input.bgNow >= 7.5 &&
            ctx.iobRatio < 0.18 &&
            slopeOk &&
            rawNoInsulinProjection > 2.0

    if (lowIobHighBg) {
        return HypoProtection(
            active = false,
            projectedMin = projectedMin,
            projectedMinNoInsulin = projectedMinNoInsulin,
            projectedMinWithPlannedInsulin = projectedMinWithInsulin,
            reason = "HYPO PROTECT BYPASSED (low IOB, high BG, no-insulin projection safe)"
        )
    }
    // ── einde meal-context vrijstelling ───────────────────────────────────

    val (active, reason) = if (projectedMin < blockThreshold) {
        val mode =
            if (projectedMinWithInsulin < projectedMinNoInsulin && plannedDoseU > 0.0)
                "WITH_PLANNED_INSULIN"
            else
                "TREND_ONLY"

        true to
            "HYPO PROTECT ($mode planned=${"%.2f".format(plannedDoseU)}U " +
            "minProj=${"%.2f".format(projectedMin)})"
    } else {
        false to ""
    }

    return HypoProtection(
        active = active,
        projectedMin = projectedMin,
        projectedMinNoInsulin = projectedMinNoInsulin,
        projectedMinWithPlannedInsulin = projectedMinWithInsulin,
        reason = reason
    )
}


private data class RescueSignal(
    val state: RescueState,
    val armed: Boolean,
    val confirmed: Boolean,
    val confidence: Double,
    val reason: String,
    val pred60: Double
)

/**
 * Soft inference: "rescue carbs likely"
 * Fase 1: alleen logging/labeling (geen dosing impact).
 */
private fun updateRescueDetection(
    ctx: FCLvNextContext,
    now: DateTime,
    config: FCLvNextConfig,
    deliveredThisCycle: Double,
    pred60: Double
): RescueSignal {

    // --- Tunable thresholds (start conservatief) ---
    val armPred60 = 4.6          // arm bij voorspelde hypo-risk
    val armBgNow = 5.2           // of al laag-ish
    val armSlope = -0.9          // stevige daling
    val minIobToCare = 0.25      // alleen als er iob aanwezig is (anders kan het "gewoon" dalen door geen carbs)

    val confirmMinMinutes = 8
    val confirmMaxMinutes = 30

    // Rebound kenmerken: van dalend naar duidelijk herstel
    val reboundAccelMin = 0.18
    val reboundSlopeMin = 0.25

    // “Geen insulin verklaart rebound”: totaal sinds armedAt moet heel klein zijn
    val maxDeliveredSinceArmed = 0.10  // U (totaal) — start strikt

    // Cooldown na confirm (zodat je niet elke cycle confirmed blijft loggen)
    val confirmCooldownMin = 45

    // --- helper: total delivered since armedAt ---
    fun deliveredSince(t0: DateTime?): Double {
        if (t0 == null) return 0.0
        var sum = 0.0
        for ((t, u, _) in deliveryHistory) {
            if (t.isAfter(t0) || t.isEqual(t0)) sum += u
        }
        return sum
    }

    // --- Reset rule: na confirm cooldown terug naar IDLE ---
    if (rescue.state == RescueState.CONFIRMED) {
        val since = minutesSince(rescue.lastConfirmAt, now)
        if (since >= confirmCooldownMin) {
            rescue = RescueDetectionContext() // reset alles
        }
    }

    // --- ARM criteria (hypo risk) ---
    val armByPred = pred60 <= armPred60
    val armByDynamics =
        (ctx.input.bgNow <= armBgNow && ctx.slope <= armSlope && ctx.iobRatio >= minIobToCare && ctx.consistency >= config.minConsistency)

    val shouldArm = (armByPred || armByDynamics) && ctx.consistency >= config.minConsistency

    when (rescue.state) {
        RescueState.IDLE -> {
            if (shouldArm) {
                rescue.state = RescueState.ARMED
                rescue.armedAt = now
                rescue.armedBg = ctx.input.bgNow
                rescue.armedPred60 = pred60
                rescue.armedSlope = ctx.slope
                rescue.armedAccel = ctx.acceleration
                rescue.armedIobRatio = ctx.iobRatio
                rescue.lastReason = "ARM: pred60=${"%.2f".format(pred60)} bg=${"%.2f".format(ctx.input.bgNow)} slope=${"%.2f".format(ctx.slope)} iobR=${"%.2f".format(ctx.iobRatio)}"
                rescue.confidence = 0.35
            }
        }

        RescueState.ARMED -> {
            val t0 = rescue.armedAt
            val dt = minutesSince(t0, now)

            // Als risico verdwijnt heel snel (bv sensor ruis) -> terug naar IDLE
            val riskGone = pred60 > 5.2 && ctx.slope > -0.2 && dt >= 10
            if (riskGone) {
                rescue = RescueDetectionContext()
            } else {
                // Confirm window + rebound + no extra insulin
                val inWindow = dt in confirmMinMinutes..confirmMaxMinutes
                val rebound = (ctx.acceleration >= reboundAccelMin && ctx.slope >= reboundSlopeMin)

                val deliveredTotalSince = deliveredSince(t0) + deliveredThisCycle
                val noInsulin = deliveredTotalSince <= maxDeliveredSinceArmed

                if (inWindow && rebound && noInsulin) {
                    rescue.state = RescueState.CONFIRMED
                    rescue.lastConfirmAt = now

                    // confidence bouwen (simpel)
                    val predSeverity = ((4.6 - rescue.armedPred60) / 1.0).coerceIn(0.0, 1.0) // lager pred60 => meer
                    val reboundStrength = ((ctx.acceleration - reboundAccelMin) / 0.25).coerceIn(0.0, 1.0)
                    val insulinClean = (1.0 - (deliveredTotalSince / maxDeliveredSinceArmed)).coerceIn(0.0, 1.0)

                    rescue.confidence = (0.45 * predSeverity + 0.35 * reboundStrength + 0.20 * insulinClean).coerceIn(0.0, 1.0)

                    rescue.lastReason =
                        "CONFIRM: dt=${dt}m pred60@arm=${"%.2f".format(rescue.armedPred60)} " +
                            "→ rebound slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)} " +
                            "delivSince=${"%.2f".format(deliveredTotalSince)}U conf=${"%.2f".format(rescue.confidence)}"
                }
            }
        }

        RescueState.CONFIRMED -> {
            // niks: cooldown reset doet het werk
        }
    }

    return RescueSignal(
        state = rescue.state,
        armed = rescue.state == RescueState.ARMED,
        confirmed = rescue.state == RescueState.CONFIRMED,
        confidence = rescue.confidence,
        reason = rescue.lastReason,
        pred60 = pred60
    )
}


private fun predictBg60(ctx: FCLvNextContext): Double {
    val h = 1.0

    // Gebruik short-term info om “achterlopende” macro-slope te corrigeren.
    // recentDelta5m (mmol/5m) -> mmol/h:
    val vFast = (ctx.recentDelta5m * 12.0).coerceIn(-6.0, 12.0)
    val vMacro = ctx.slope.coerceIn(-6.0, 12.0)

    // Neem de “meest stijgende” indicatie (maar niet te extreem)
    val vEff = maxOf(vMacro, vFast * 0.8)

    val a = ctx.acceleration.coerceIn(-1.0, 1.5)

    val pred = ctx.input.bgNow + vEff * h + 0.5 * a * h * h

    // Als fast-lane stijgt, laat pred60 niet onder bgNow zakken
    return if (ctx.recentDelta5m > 0.02 || ctx.recentSlope > 0.2) maxOf(pred, ctx.input.bgNow) else pred
}


private data class MicroRampResult(
    val active: Boolean,
    val microU: Double,
    val tier: String,
    val reason: String
)

private fun isMacroRising(ctx: FCLvNextContext, peak: PeakEstimate, config: FCLvNextConfig): Boolean {
    return (
        peakEstimator.active &&
            peak.riseSinceStart >= 1.0 &&          // ≥ 1 mmol stijging
            ctx.deltaToTarget >= 1.5 &&             // duidelijk boven target
            ctx.consistency >= config.episodeMinConsistency
        )
}


private fun computeMicroRamp(ctx: FCLvNextContext, config: FCLvNextConfig, peak: PeakEstimate): MicroRampResult {
    val mul = config.microRampThresholdMul
    // algemene safety: meteen stoppen als fast lane draait
    val hardAbort =
        ctx.recentDelta5m <= MEAL_ABORT_DELTA5M ||
            ctx.recentSlope <= MEAL_ABORT_SLOPE_HR ||
            ctx.acceleration <= MEAL_ABORT_ACCEL

    if (hardAbort && !isMacroRising(ctx, peak, config)) {
        return MicroRampResult(false, 0.0, "NONE", "MICRO blocked (abort)")
    }

    val safe =
        ctx.consistency >= MICRO_MIN_CONS &&
            ctx.iobRatio <= config.microRampIobMax &&
            ctx.recentDelta5m > 0.0


    if (!safe) {
        return MicroRampResult(false, 0.0, "NONE", "MICRO none (safe=false)")
    }

    // Tier detectie
    val fast =
        ctx.recentDelta5m >= FAST_RISE_DELTA5M * mul ||
            (ctx.recentSlope >= FAST_RISE_SLOPE_HR * mul && ctx.acceleration >= FAST_RISE_ACCEL * mul)

    val meal =
        // alleen zinvol als we echt boven target zitten
        ctx.deltaToTarget >= 0.8 * mul && (
            // eerder triggeren op fast-lane rise
            ctx.recentDelta5m >= 0.06 * mul ||
                ctx.recentSlope >= 0.60 * mul ||
                ( ctx.recentSlope >= MEAL_RISE_SLOPE_HR * mul &&  ctx.acceleration >= MEAL_RISE_ACCEL * mul  ) ||
                ctx.recentDelta5m >= MEAL_RISE_DELTA5M * mul  )


    if (!fast && !meal) {
        return MicroRampResult(false, 0.0, "NONE", "MICRO none")
    }

    // Extra strakke abort alleen voor FAST-tier
    if (fast) {
        val fastAbort =
            ctx.recentDelta5m <= FAST_ABORT_DELTA5M ||
                ctx.recentSlope <= FAST_ABORT_SLOPE_HR ||
                ctx.acceleration <= FAST_ABORT_ACCEL

        if (fastAbort) {
            return MicroRampResult(false, 0.0, "FAST", "FAST-MICRO blocked (fast abort)")
        }
    }

    // Schalen: we schalen op recentDelta5m (stabielste snelle indicator)
    val minU: Double
    val maxU: Double
    val t0: Double
    val t1: Double
    val tierName: String

    if (fast) {
        minU = FAST_MICRO_MIN_U
        maxU = FAST_MICRO_MAX_U
        t0 = FAST_RISE_DELTA5M
        t1 = 0.55
        tierName = "FAST"
    } else {
        minU = MEAL_MICRO_MIN_U
        maxU = MEAL_MICRO_MAX_U
        t0 = MEAL_RISE_DELTA5M
        t1 = 0.35
        tierName = "MEAL"
    }


    val tt = smooth01((ctx.recentDelta5m - t0) / (t1 - t0))
    val micro = ((minU + (maxU - minU) * tt) * config.microDoseMul).coerceIn(minU * 0.5, maxU * 1.5)


    return MicroRampResult(
        active = true,
        microU = micro,
        tier = tierName,
        reason = "MICRO-$tierName: Δ5m=${"%.2f".format(ctx.recentDelta5m)} slope=${"%.2f".format(ctx.recentSlope)} accel=${"%.2f".format(ctx.acceleration)} iobR=${"%.2f".format(ctx.iobRatio)}"
    )
}

private data class TopGuard(
    val active: Boolean,
    val capFactor: Double,   // 0..1 (hoe hard knijpen)
    val reason: String
)

private fun computeTopGuard(
    ctx: FCLvNextContext,
    peak: PeakEstimate,
    mealSignal: MealSignal,
    config: FCLvNextConfig
): TopGuard {

    val reliable = ctx.consistency >= config.minConsistency

    // Fast-lane plateau / topvorming (leidend!)
    val fastPlateau =
        ctx.recentSlope <= 0.30 &&
            kotlin.math.abs(ctx.recentDelta5m) <= 0.04

    // "dicht bij top": predictedPeak ligt niet veel hoger dan huidige BG
    val nearPeak =
        peak.predictedPeak >= 9.5 &&
            (peak.predictedPeak - ctx.input.bgNow) <= 0.8

    // afremmen begint, maar macro trend kan nog "positief" lijken
    val braking =
        ctx.acceleration <= 0.10 || ctx.recentSlope <= 0.15

    // IOB al betekenisvol
    val hasIob = ctx.iobRatio >= config.topGuardMinIobRatio


    val episodeLike =
        mealSignal.state != MealState.NONE || peakEstimator.active || peak.state != PeakPredictionState.IDLE

    val shouldGuard = reliable && episodeLike && hasIob && fastPlateau && (nearPeak || braking) && ctx.deltaToTarget >= 0.8

    if (!shouldGuard) return TopGuard(false, 1.0, "TOPGUARD off")

    // CapFactor: hoe harder als we dichter bij top zijn / meer IOB hebben
    val iobSeverity = smooth01((ctx.iobRatio - 0.30) / 0.40)           // 0..1
    val peakCloseness = 1.0 - smooth01(((peak.predictedPeak - ctx.input.bgNow) - 0.2) / (0.8 - 0.2))
    val severity = (0.55 * iobSeverity + 0.45 * peakCloseness).coerceIn(0.0, 1.0)

    val cap = (1.0 - 0.70 * severity).coerceIn(config.topGuardCapMin, config.topGuardCapMax)

    return TopGuard(true, cap, "TOPGUARD: plateau+nearPeak (cap=${"%.2f".format(cap)})")
}

private data class LateBolusBlock(
    val block: Boolean,
    val stashInstead: Boolean,
    val reason: String
)

private fun computeLateBolusBlock(
    ctx: FCLvNextContext,
    peak: PeakEstimate,
    mealSignal: MealSignal,
    postPeak: PostPeakSummary,
    topPlateauConfirmed: Boolean,

    config: FCLvNextConfig
): LateBolusBlock {




    // Alleen in episode/meal context
    val episodeLike =
        mealSignal.state != MealState.NONE || peakEstimator.active || peak.state != PeakPredictionState.IDLE

    if (!episodeLike) return LateBolusBlock(false, false, "LATEBOLUS off (no episode)")

    // Rond de top: WATCHING/CONFIRMED (of postPeak suppress/lockout al actief)
    val peakContext =
        peak.state == PeakPredictionState.WATCHING ||
            peak.state == PeakPredictionState.CONFIRMED ||
            postPeak.suppress ||
            postPeak.lockout

    if (!peakContext) return LateBolusBlock(false, false, "LATEBOLUS off (not peak context)")

    // Fast-lane is leidend voor “top/afremmen”
    val fastPlateau =
        ctx.recentSlope <= 0.30 && kotlin.math.abs(ctx.recentDelta5m) <= 0.04

    val braking =
        ctx.acceleration <= 0.05 || ctx.recentSlope <= 0.15 || ctx.recentDelta5m <= 0.02

    // “Bijna bij predicted top”
    val nearTop =
        peak.predictedPeak >= 9.5 && (peak.predictedPeak - ctx.input.bgNow) <= 0.8

    // Genoeg IOB aanwezig dat extra bolus juist hypo-risico creëert
    val hasMeaningfulIob =
        ctx.iobRatio >= config.lateBolusBlockIobMin

    val shouldBlock =
        hasMeaningfulIob &&
            (fastPlateau || topPlateauConfirmed) &&
            (braking || nearTop)

    if (!shouldBlock) return LateBolusBlock(false, false, "LATEBOLUS off")

    return LateBolusBlock(
        block = true,
        stashInstead = true, // ✅ behoud insulin intent maar niet nu leveren
        reason =
            "LATE-BOLUS BLOCK: peak=${peak.state} nearTop=$nearTop " +
                "fastPlateau=$fastPlateau braking=$braking iobR=${"%.2f".format(ctx.iobRatio)} " +
                "rSlope=${"%.2f".format(ctx.recentSlope)} rΔ5m=${"%.2f".format(ctx.recentDelta5m)} " +
                "accel=${"%.2f".format(ctx.acceleration)}"
    )
}

private fun deliveredInLastMinutes(now: DateTime, minutes: Int): Double {
    val cutoff = now.minusMinutes(minutes)
    var sum = 0.0
    for ((t, u, _) in deliveryHistory) {
        if (t.isAfter(cutoff) || t.isEqual(cutoff)) sum += u
    }
    return sum
}


private fun smooth01(x: Double): Double {
    val t = x.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)   // smoothstep
}


private fun lerp(a: Double, b: Double, t: Double): Double =
    a + (b - a) * t.coerceIn(0.0, 1.0)

private data class MealAggression(
    val a: Double,          // 0..1
    val reason: String
)

private fun computeMealAggression(
    ctx: FCLvNextContext,
    peak: PeakEstimate,
    mealSignal: MealSignal,
    config: FCLvNextConfig,
    earlyPeakBiasMmol: Double = 0.0,
    minutesSinceMealStart: Int = 999
): MealAggression {

    // Fast-lane (dominant voor timing)
    val d5 = ctx.recentDelta5m
    val v5 = (d5 * 12.0)                      // mmol/L/h equivalent
    val vFastScore = smooth01((v5 - 0.8) / (4.0 - 0.8))           // 0..1

    // Accel (dominant voor “meal momentum”)
    val accelScore = smooth01((ctx.acceleration - 0.06) / (0.30 - 0.06))

    // Delta boven target (druk)
    val deltaScore = smooth01((ctx.deltaToTarget - 0.6) / (3.5 - 0.6))

    // Betrouwbaarheid
    val consScore = smooth01((ctx.consistency - config.minConsistency) / (0.85 - config.minConsistency))

    // MealSignal geeft extra vertrouwen
    val mealBonus = when (mealSignal.state) {
        MealState.CONFIRMED -> 0.18
        MealState.UNCERTAIN -> 0.10
        MealState.NONE -> 0.0
    }

    // ── Vroege piek-bias-correctie (zelflerend) ──────────────────────────
    // Data-analyse (16-06-2026, 31 episodes) toonde een structurele
    // onderschatting van predictedPeak in de eerste 20 minuten van een
    // maaltijd-episode: gemiddeld -0.80 mmol (std 0.89), bij 17/31 episodes
    // groter dan -0.5 mmol. Die onderschatting correleerde met een lage
    // firstBigCommitFrac (0.31) en een hogere uiteindelijke piek (9.18 mmol)
    // — het algoritme doseerde te terughoudend omdat het de stijging vroeg
    // nog niet "zag aankomen".
    // earlyPeakBiasMmol is een door FrontloadLearner geleerde correctie
    // (zie REF_PEAK_BIAS in DFMapping) die alleen wordt toegepast in het
    // vroege venster (0-20 min na meal-start) — buiten dat venster is de
    // voorspelling al accuraat genoeg (predFout20_40 gemiddeld -0.34) en
    // is een correctie niet onderbouwd.
    val biasEffectief = if (minutesSinceMealStart in 0..20) earlyPeakBiasMmol else 0.0
    val effectivePredictedPeak = peak.predictedPeak + biasEffectief

    // Peak pressure (optioneel, mild) — gebruikt de gecorrigeerde piek
    val peakPressure = smooth01((effectivePredictedPeak - 11.0) / (17.0 - 11.0)) * 0.10

    var a =
        0.30 * vFastScore +
            0.32 * accelScore +
            0.22 * deltaScore +
            0.16 * consScore +
            mealBonus +
            peakPressure

    // Hard clamps
    a = a.coerceIn(0.0, 1.0)

    return MealAggression(
        a = a,
        reason = "AGGR a=${"%.2f".format(a)} (v5=${"%.2f".format(v5)} accel=${"%.2f".format(ctx.acceleration)} delta=${"%.2f".format(ctx.deltaToTarget)} cons=${"%.2f".format(ctx.consistency)} peakBias=${"%.2f".format(biasEffectief)})"
    )
}

private data class EarlyDoseDecision(
    val active: Boolean,
    val stageToFire: Int,
    val confidence: Double,
    val targetU: Double,
    val reason: String,
    val remainingDebtU: Double = 0.0,
    val boostActive: Boolean = false,
    val effectiveBoostFactor: Double = 1.0,
    val boostCommitNr: Int = 0
)

private fun computeEarlyDoseDecision(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    trend: TrendDecision,
    bgZone : BgZone,
    now: DateTime,
    config: FCLvNextConfig,
    sustainedHighSlopeMinutes: Double = 0.0,
    // Acceleratie-afname t.o.v. ~18 min geleden — zie kdoc bij
    // FCLvNext.updateAccelHistoryAndGetDecline(). Positief = decelererend.
    accelDeclineSinceUncertain: Double = 0.0,
    // Voor hypo-debt compensatie: projectie zónder insuline uit de meest recente
    // hypo-bescherming check. Geeft zekerheid dat compensatie veilig is.
    hypoProjectedMinNoInsulin: Double = Double.POSITIVE_INFINITY,
    // Opgebouwde hypo-schuld in huidige episode (achtergehouden insuline door hypo-rem).
    // Wordt als parameter meegegeven omdat deze functie buiten de klasse staat.
    episodeHypoDebtU: Double = 0.0
): EarlyDoseDecision {

    if (ctx.consistency < config.episodeMinConsistency) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY: low consistency", remainingDebtU = episodeHypoDebtU)
    }

    // Fastlane veto: CGM laat al dip zien → geen early push
    if (ctx.recentDelta5m <= -0.06 || ctx.recentSlope <= -0.20) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY blocked: fastlane dip", remainingDebtU = episodeHypoDebtU)
    }

    if ((bgZone == BgZone.LOW || bgZone == BgZone.IN_RANGE) && ctx.iobRatio >= 0.55) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY blocked: low BG zone", remainingDebtU = episodeHypoDebtU)
    }


    if (peak.state == PeakPredictionState.CONFIRMED) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY: peak confirmed", remainingDebtU = episodeHypoDebtU)
    }

    val slopeScore = smooth01((ctx.slope - 0.20) / (1.20 - 0.20))
    val accelScore = smooth01((ctx.acceleration - (-0.02)) / (0.15 - (-0.02)))
    val deltaScore = smooth01((ctx.deltaToTarget - 0.0) / 1.6)
    val consistScore = smooth01((ctx.consistency - 0.45) / 0.35)
    val iobRoom = 1.0 - smooth01((ctx.iobRatio - 0.20) / 0.50)

    // ── Sustained Rise Score ──────────────────────────────────────────────
    // Vult accelScore aan voor TYPE B stijgingen: slope is lang hoog maar
    // versnelt nauwelijks (bijv. brood, rijst). Na config.sustainedRiseMinTarget
    // minuten aanhoudend boven drempel bereikt sustainScore zijn maximum.
    // Gewicht 0.12: bescheiden maar genoeg om de conf-drempel te halen bij
    // gestage stijging zonder versnelling.
    val sustainScore = smooth01(
        sustainedHighSlopeMinutes / config.sustainedRiseMinTarget.toDouble()
    )

    // ── Persistentie-check (decelereert de stijging, of houdt ze aan?) ─────
    // Onderscheidt "stijging topt vanzelf uit" (accelDeclineSinceUncertain duidelijk
    // positief, zoals het ontbijt van 20/06: piek 7,2 — terecht klein
    // gebleven) van "stijging houdt aan" (accelDeclineSinceUncertain ~0, zoals de
    // maaltijd van 21/06 10:15: piek 13,2 — had eerder mogen escaleren).
    // declineTolerance is bewust klein: bij het ontbijt was de afname al na
    // ~10-15 min duidelijk meetbaar; ruis in een enkele cyclus mag de score
    // niet meteen op 0 zetten.
    val declineTolerance = 0.015
    val persistScore = smooth01((declineTolerance - accelDeclineSinceUncertain) / declineTolerance)

    // Gefaseerd: CONFIRMED telt zoals voorheen voluit mee. UNCERTAIN telt nu
    // ÓÓK mee, maar alleen als de persistentie-check de stijging als
    // "aanhoudend" beoordeelt, én met een verlaagd gewicht (0.65) — extra
    // voorzichtigheid omdat de maaltijdherkenning zelf nog niet CONFIRMED is.
    // NONE telt nooit mee. Zie analyse 21/06/2026 in het overdrachtsdocument:
    // dit signaal stond eerder ~25 minuten lang op 0 tijdens precies het
    // venster waarin de maaltijd van 21/06 had moeten escaleren.
    val UNCERTAIN_SUSTAIN_WEIGHT = 0.65
    val effectiveSustainScore = when (mealSignal.state) {
        MealState.CONFIRMED -> sustainScore
        MealState.UNCERTAIN -> sustainScore * persistScore * UNCERTAIN_SUSTAIN_WEIGHT
        MealState.NONE      -> 0.0
    }

    val watchingBonus =
        if (peak.state == PeakPredictionState.WATCHING) 0.10 else 0.0

    val mealBonus = when (mealSignal.state) {
        MealState.CONFIRMED -> 0.18
        MealState.UNCERTAIN -> 0.10
        MealState.NONE -> 0.0
    }

    val fastRiseBonus = when {
        ctx.recentSlope >= 10.0 -> 0.30  // Zeer snelle stijging
        ctx.recentSlope >= 5.0 -> 0.15   // Snelle stijging
        else -> 0.0
    }

    // Gewichten: accel iets omlaag (0.30→0.22), sustain krijgt 0.08,
    // iobRoom iets omlaag (0.10→0.08) zodat totaal gelijk blijft.
    // Type A (snelle stijging): accelScore hoog → conf hoog → vroeg vuren
    // Type B (gestage stijging): sustainScore hoog → conf hoog → ook vroeg vuren
    var conf =
        0.32 * slopeScore +
            0.22 * accelScore +
            0.08 * effectiveSustainScore +
            0.18 * deltaScore +
            0.12 * consistScore +
            0.08 * iobRoom +
            watchingBonus +
            mealBonus +
            fastRiseBonus

    val peakEscalation =
        if (peak.predictedPeak >= 12.5 &&
            ctx.iobRatio <= 0.45 &&
            ctx.consistency >= config.minConsistency
        ) config.earlyPeakEscalationBonus else 0.0

    conf += peakEscalation

    conf = conf.coerceIn(0.0, 1.0)

    val baseStage1Min = 0.28
// Stage2 eerder als de voorspelde piek groot is
    // Avondmaaltijdanalyse 25/05: stage2 vuurde te laat (pred_peak 9-10, BG al 10.2).
    // Verlaging naar 0.45 bij pred_peak >= 9.5 zodat stage2 ~10 min eerder kan vuren.
    val stage2MinByPeak = when {
        peak.predictedPeak >= 16.0 -> 0.45
        peak.predictedPeak >= 12.5 -> 0.47
        peak.predictedPeak >=  9.5 -> 0.50  // nieuw: ook bij piek >= 9.5
        else                       -> 0.55
    }

    // ── Stage2 eerder bij bevestigde directe stijging (kip-ei-fix) ────────────
    // Probleem (30/06/2026, Ecko): predictedPeak is een ballistische extrapolatie
    // van de HUIDIGE snelheid (bgNow + v×hEff). Aan het begin van een stijging is
    // v per definitie nog laag, ook al gaat de stijging straks doorzetten — de
    // extrapolatie kan de toekomstige versnelling niet kennen. Hierdoor blijft
    // predictedPeak kunstmatig laag (<9.5) net op het moment dat je vroeg wilt
    // toeslaan, en stage2Min blijft op 0.55 hangen tot de piekvoorspelling
    // zichzelf "inhaalt" — maar dan is de vroege fase van de stijging al voorbij.
    //
    // Voorbeeld 30/06 ontbijt: bij BG=4.5 (05:00 UTC) was predictedPeak nog maar
    // 5.3 mmol (stage2Min=0.55, conf=0.48 → gesloten). Vijf minuten later bij
    // BG=6.5 sprong predictedPeak naar 11.2 (stage2Min=0.50) — maar toen was de
    // vroege fase (BG 4.0-6.5) al voorbij. Resultaat: kleine vroege commits
    // (0.53+0.08+0.21+0.30=1.12U), gevolgd door 3 grote late commits (7.54U)
    // tussen BG 6.5-7.3 — laat in de stijging, na de helft van de excursie.
    //
    // Oplossing: stage2Min verlagen op basis van het DIRECTE versnellingssignaal
    // (recentSlope + acceleration), onafhankelijk van de nog-onzekere
    // piekvoorspelling. Dit vertrouwt op "de stijging versnelt aantoonbaar"
    // in plaats van te wachten tot de extrapolatie dat zelf concludeert.
    //
    // Voorwaarden (bewust strenger dan de stage1-trigger, want dit opent de
    // GROTE stage2-commit, niet de kleine stage1):
    //   recentSlope >= 2.5 mmol/h  → duidelijke, actuele stijging
    //   acceleration >= 0.20       → stijging neemt nog toe, geen afvlakking
    //   consistency >= minConsistency → geen ruisartefact, sensordata betrouwbaar
    // Drempel verlaagd 0.20→0.15 (12/07/2026, Ecko): bij een reële, snelle
    // stijging (recentSlope 3,5-5,4 mmol/u, ruim boven de 2.5-eis hierboven)
    // bungelde accel structureel net onder 0.20 (0,15-0,19) — de "kip-ei"-fix
    // ontgrendelde daardoor niet, en stage2Min bleef vasthangen op de hogere,
    // piek-gebaseerde route totdat predictedPeak zichzelf had ingehaald.
    // Praktijkvoorbeeld 11/07/2026 avond: 20 minuten kleine commits (0,13-
    // 0,36U) bij een BG die intussen al van 4,8 naar 6,5 was doorgestegen.
    val stage2MinByAccel = if (
        ctx.recentSlope >= 2.5 &&
        ctx.acceleration >= 0.15 &&
        ctx.consistency >= config.minConsistency
    ) 0.45 else 1.0  // 1.0 = "doet niet mee" in de minOf() hieronder

    // Laagste van de twee routes wint: predictedPeak-route (zoals voorheen)
    // OF de nieuwe directe-acceleratie-route, welke ook het eerst opengaat.
    val stage2Min = minOf(stage2MinByPeak, stage2MinByAccel)

// jouw bestaande fast-carb versneller (blijft bestaan)
    val fastCarbStage1Mul =
        if (ctx.acceleration >= 0.35 && ctx.iobRatio <= 0.25) 0.75 else 1.0

// profiel beïnvloedt alleen timing (threshold)
    val slopeBasedStage1Min = when {
        ctx.recentSlope >= 8.0 -> 0.15  // Snellere stage1 bij zeer snelle stijging
        ctx.recentSlope >= 5.0 -> 0.22  // Versnelde stage1 bij snelle stijging
        else -> baseStage1Min
    }

    var dynamicStage1Min = (slopeBasedStage1Min * fastCarbStage1Mul * config.earlyStage1ThresholdMul)
        .coerceIn(0.12, 0.45)


    // 🔹 EXTREME-zone: stage-1 iets eerder toestaan
    if (
        bgZone == BgZone.EXTREME &&
        ctx.slope >= 0.8 &&
        ctx.acceleration >= 0.25 &&
        ctx.consistency >= config.episodeMinConsistency &&
        ctx.deltaToTarget >= 2.5
    ) {
        dynamicStage1Min = (dynamicStage1Min / config.mealConfidenceSpeedMul).coerceIn(0.10, 0.50)
    }


    val minutesSinceLastFire =
        minutesSince(earlyDose.lastFireAt, now)

    val allowLarge = (trend.state == TrendState.RISING_CONFIRMED)

// stageToFire:
    // Stage 3: tweede grote boosted commit, 5 min na stage 2.
    // Alleen als BG nog stijgt (slope >= 0.50) en IOB nog niet te hoog.
    // iobRatio drempel: 0.55 normaal, maar 0.65 bij pred_peak >= 11.5
    // want de piek-voorspelling geeft extra zekerheid dat de dosis nodig is.
    // stage3IobMax: verhoogd van 0.55 naar 0.65 (normaal) en 0.73 (hoge piek).
    // Reden: na stage2 schoot iobRatio naar 0.62-0.67, waardoor stage3 36 min
    // werd uitgesteld. Stage3 is de derde pre-bolus commit — die hoort in de
    // stijgingsfase, niet op de piek. Veilig: de absoluteUcap (maxSMB*1.5)
    // en de iobPenalty in de dosis-berekening remmen bij hoge IOB vanzelf af.
    val stage3IobMax = if (peak.predictedPeak >= 11.5) 0.73 else 0.65

    // ── Second Boost Window: versoepel stage3 na een grote stage2 ────────────
    // Probleem: na een grote stage2 commit (bijv. 3.5U) schiet iobRatio direct
    // omhoog naar 0.55-0.70. Stage3 vereist iobRatio < 0.55 en is daarmee
    // structureel geblokkeerd. Het systeem valt terug op 4-5 kleine watching
    // commits die samen evenveel insuline geven maar later gespreid:
    // hogere IOB op de piek, meer staartrisico.
    //
    // Second Boost Window: als alle volgende voorwaarden gelden mag
    // stage3 vuren ondanks verhoogde iobRatio:
    //   1. Stage2 is < 20 min geleden gevuurd (we zitten in frontload-fase)
    //   2. BG stijgt nog actief (slope >= 0.50 EN recentSlope >= 3.0)
    //   3. PredictedPeak >= 9.5 (algoritme verwacht substantiële stijging)
    //   4. iobRatio < 0.75 (ruimere drempel, maar niet onbeperkt)
    //   5. rawNoInsulinProjection > 3.5 mmol (strengere veiligheidsmarge
    //      dan normaal omdat IOB al hoog is)
    //
    // De dosis wordt beperkt door de verhoogde iobPenalty die al actief is
    // (iobRatio 0.56 → penalty ~0.35, factor geschaald naar ~0.65× normaal).
    // Plus: de cap10m burst-rem voorkomt dat te snel na de stage2 een nieuwe
    // grote dosis wordt gegeven — minimaal 10 min tussenruimte.
    val rawNoInsulinProjectionStage3 = ctx.input.bgNow - ctx.input.currentIOB * ctx.input.effectiveISF
    val minutesSinceStage2 = lastEarlyBoostAt?.let {
        org.joda.time.Minutes.minutesBetween(it, now).minutes
    } ?: Int.MAX_VALUE
    val inSecondBoostWindow =
        earlyDose.stage == 2 &&
            minutesSinceStage2 in 5..20 &&
            ctx.slope >= 0.50 &&
            ctx.recentSlope >= 3.0 &&
            peak.predictedPeak >= 9.5 &&
            ctx.iobRatio < 0.75 &&
            rawNoInsulinProjectionStage3 > 3.5

    val effectiveStage3IobMax = if (inSecondBoostWindow) 0.75 else stage3IobMax
    val allowStage3 = allowLarge &&
        ctx.slope >= 0.50 &&
        ctx.iobRatio < effectiveStage3IobMax &&
        ctx.recentSlope >= 3.0
    // ── Commits budget: debt + hoge BG rise ──────────────────────────────────
    // Berekend voor highBgContinuation (die er direct onder staat) en later
    // ook voor boostActive. Afhankelijkheden: alleen ctx, peak, episodeHypoDebtU
    // — allemaal al bekend op dit punt.
    val extraCommitsFromDebt = if (episodeHypoDebtU > 0.01)
        (episodeHypoDebtU / 0.30).toInt().coerceIn(0, 2)
    else 0

    val extraCommitsFromBgRise = if (
        ctx.input.bgNow >= 10.5 &&
        ctx.slope >= 2.0 &&
        ctx.recentSlope >= 3.0 &&
        ctx.input.bgNow > peak.predictedPeak - 1.5
    ) {
        // 10.5 mmol en stijgend: +1 commit
        // 12.5 mmol en stijgend: +2 commits
        // 14.5 mmol en stijgend: +3 commits (max)
        // Achtergrond 28/06/2026 (Ecko): drempel was 12.0 en recentSlope 4.0.
        // Bij de episoden van 27 juni (piek 11.5) en 28 juni ochtend (piek 13.9)
        // was BG al 10-11 mmol met slope 6-8 mmol/h maar kreeg de EarlyBoost
        // geen extra commits omdat de drempel te hoog lag. De IOB-gate (iobRatio
        // > 0.50) stopte de EarlyBoost sowieso al, maar extraMaxCommits geeft
        // alleen ruimte; de eigenlijke IOB-gate in boostActive blijft gelden.
        ((ctx.input.bgNow - 10.5) / 2.0).toInt().coerceIn(0, 3)
    } else 0

    val effectiveMaxCommits = config.earlyBoostMaxCommits + extraCommitsFromDebt + extraCommitsFromBgRise

    // ── High-BG continuation: hervatting van earlyBoost na stage3 ───────────
    // Na stage3 is de earlyBoost normaal klaar (geen stage4+ gedefinieerd).
    // Maar bij extreme maaltijden (BG >= 12.0 mmol en stijgend) is de
    // gebruikelijke 3 commits onvoldoende.
    //
    // Hervatting toegestaan als:
    //   1. stage3 al gevuurd (earlyDose.stage >= 3)
    //   2. BG >= 12.0 mmol — expliciet hoge glycemie, niet een normale piek
    //   3. slope >= 2.0 — stijging is nog actief en substantieel
    //   4. iobRatio < 0.80 — enige ruimte voor meer insuline
    //      (veilig: bij BG=12 heeft een extra commit van 2.45U nauwelijks
    //       hypo-risico — maximale BG-daling 2.45×4.7=11.5 mmol over 90 min,
    //       maar de bestaande iobRatio-penalty in de dosisberekening remt dit af)
    //   5. Minimaal 8 min na de laatste commit (cooldown, iets langer dan normaal)
    //   6. boostCommitCount < effectiveMaxCommits (de dynamische grens respecteren)
    val highBgContinuation =
        earlyDose.stage >= 3 &&
            ctx.input.bgNow >= 12.0 &&
            ctx.slope >= 2.0 &&
            ctx.iobRatio < 0.80 &&
            minutesSinceLastFire >= 8 &&
            earlyDose.boostCommitCount < effectiveMaxCommits

    // ── Minimale wachttijd stage 1 → stage 2 ────────────────────────────────
    // Standaard 5 minuten. Bij sterke, bevestigde stijging mag dit korter:
    // de wachttijd dient om te voorkomen dat twee grote doses direct na elkaar
    // gaan, maar als de BG al 1-2 mmol is gestegen in de vorige 5 minuten
    // én de confidence hoog is, is het signaal betrouwbaar genoeg.
    //
    // Minimumgrens: 3 minuten (één CGM-punt). Nooit lager — dan zou stage 2
    // kunnen vuren op basis van hetzelfde CGM-punt als stage 1.
    //
    // Logica:
    //   conf >= 0.90 EN slope >= 3.0 → 3 min (maximale versnelling)
    //   conf >= 0.80 EN slope >= 2.0 → 4 min
    //   anders → 5 min (huidig gedrag)
    //
    // Stage 3 wachttijd ongewijzigd op 5 min: stage 3 volgt na stage 2 die
    // al groot is — hier is haast minder geboden dan bij de stage 1→2 overgang.
    //
    // Achtergrond 28/06/2026 (Ecko): analyse toont dat stage 2 (de grootste
    // commit) typisch op t+20 min zit na begin BG-stijging, terwijl het ideaal
    // t+10 min zou zijn. Stage 1 vuurt op t+10-15, dan wacht het systeem 5 min
    // waardoor stage 2 op t+15-20 valt. Bij snelle stijging (slope >3, conf >0.9)
    // is die extra 2 minuten wachttijd niet nodig — het signaal is al volledig
    // bevestigd door de CGM-reeks.
    val minWachttijdStage2 = when {
        conf >= 0.90 && ctx.slope >= 3.0 -> 3
        conf >= 0.80 && ctx.slope >= 2.0 -> 4
        else -> 5
    }

    val stageToFire = when {
        earlyDose.stage == 0 && conf >= dynamicStage1Min -> 1
        earlyDose.stage == 1 && conf >= stage2Min &&
            minutesSinceLastFire >= minWachttijdStage2 && allowLarge -> 2
        earlyDose.stage == 2 && conf >= stage2Min &&
            minutesSinceLastFire >= 5 && allowStage3 -> 3
        highBgContinuation -> 3  // hergebruik stage3 factor voor vervolg-commits
        else -> 0
    }
    if (earlyDose.stage == 1 && conf >= stage2Min && minutesSinceLastFire >= minWachttijdStage2 && !allowLarge) {
        return EarlyDoseDecision(false, 0, conf, 0.0, "EARLY: stage2 blocked (trend=${trend.state})", remainingDebtU = episodeHypoDebtU)
    }

    if (stageToFire == 0) {
        return EarlyDoseDecision(false, 0, conf, 0.0, "EARLY: no fire", remainingDebtU = episodeHypoDebtU)
    }

    val (minF, maxF) = when (stageToFire) {
        1    -> 0.40 to 0.70
        2    -> 0.55 to 0.90
        // stage 3 normaal: groter dan watching maar kleiner dan stage 2
        // stage 3 second boost window: groter dan normaal, proportioneel aan
        // de nog verwachte stijging (predictedPeak - bgNow)
        else -> if (inSecondBoostWindow) 0.60 to 0.85 else 0.45 to 0.65
    }

    var factor = lerp(minF, maxF, conf)

    val iobPenalty = smooth01((ctx.iobRatio - 0.35) / 0.40)
    factor *= (1.0 - 0.35 * iobPenalty)

    val minEarlyFrac =
        if (
            stageToFire == 1 &&
            ctx.deltaToTarget >= 0.8 &&
            ctx.slope >= 0.35 &&
            ctx.consistency >= config.episodeMinConsistency
        ) {
            // EARLY stage-1 floor:
            // slope ~0.35  -> ~0.20 * maxSMB
            // slope >=1.0  -> ~0.30 * maxSMB
            val t = smooth01((ctx.slope - 0.35) / (1.00 - 0.35))
            0.20 + t * 0.10
        } else {
            0.0
        }

    val minEarlyU =
        (config.maxSMB * minEarlyFrac)
            .coerceIn(0.20, config.maxSMB * 0.40)


    val boostActive =
        config.earlyBoostFactor > 1.0 + 1e-9 &&
            conf >= config.earlyBoostMinConfidence &&
            earlyDose.boostCommitCount < effectiveMaxCommits &&
            // IOB-rem: als er al substantieel insuline actief is, heeft een
            // versterkte vroege dosis geen zin meer — die werkt pas na de piek
            // en versterkt de daling erna alleen maar.
            // Drempel is bewust iets hoger dan peakIobBrakeSuppressThreshold (+0.08):
            //   - peakIobBrakeSuppressThreshold (0.42): algemene "begin rustig aan"
            //   - EarlyBoost mag nog één cyclus langer doorgaan (tot ~0.50) zodat
            //     er één afnemende vervolgdosis volgt op de grote frontload, in
            //     plaats van de sprong direct naar de kleine, gedecayede normale
            //     commits. Achtergrond 24/06/2026 (Ecko): met exacte suppress-drempel
            //     gaf dit 3,98 → 0,12 → 0,09U (te abrupt); met +0.08 marge wordt
            //     het 3,98 → 1,06 → 0,09U (geleidelijk aflopend zoals gewenst).
            ctx.iobRatio < (config.peakIobBrakeSuppressThreshold + 0.08).coerceAtMost(0.55)

    val effectiveBoostFactor = if (!boostActive) {
        1.0
    } else {
        // ── Afnemende boost over opeenvolgende EarlyBoost-commits ──────────
        // Ontwerp 26/06/2026 (Ecko): elke volgende commit wordt bewust kleiner.
        //
        // Redenering: bij commit 1 is de IOB nog minimaal en is alle urgentie
        // aanwezig ("geef nu alles vroeg"). Bij commit 2 is de frontload al
        // geleverd en bouwt de IOB op; de boost is minder kritisch. Bij 3 en 4
        // loopt de IOB al fors — de boost dient dan bijna geen doel meer.
        // Dit spiegelt de lateDecayMul voor normale commits, maar dan intern
        // in het EarlyBoost-pad.
        //
        // Decay op het EXTRA deel boven 1.0 (zodat de factor nooit onder 1.0 zakt):
        //   commit 1: 100% van de geconfigureerde extra
        //   commit 2:  60% → duidelijk minder maar nog substantieel
        //   commit 3:  30% → klein, lateDecayMul op normale commits pakt de rest
        //   commit 4+: 10% → quasi-neutraal, IOB-gate sluit sowieso al eerder
        val boostDecay = when (earlyDose.boostCommitCount) {
            0    -> 1.00
            1    -> 0.60
            2    -> 0.30
            else -> 0.10
        }
        val extraBoost = config.earlyBoostFactor - 1.0  // het deel boven 1.0

        // ── Piekdruk-bonus (vervangt heightEscalationFactor) ───────────────
        // heightEscalationFactor was een losse vermenigvuldiger die reageerde op
        // slope, accel, deltaToTarget, consistency en predictedPeak — exact
        // dezelfde signalen als EarlyBoost zelf. Door ze samen te voegen is er
        // één mechanisme per scenario in plaats van twee ongecoördineerde.
        //
        // Bonus alleen op commit 1 (boostCommitCount == 0): de urgentie van een
        // hoge verwachte piek geldt voor de EERSTE grote dosis, niet voor de
        // kleinere vervolgcommits. Actief als predictedPeak >= 11.0 mmol/L.
        //
        // Maximale bonus: +0.35 op de boost-extra (d.w.z. bij earlyBoostFactor=1.75
        // en piekdruk = max: 0.75 + 0.35 = 1.10 extra boven 1.0 → totaal 2.10).
        // Gekoppeld aan dezelfde IOB-ceiling als heightEscalation had (0.35).
        //
        // ── Curve-fit confidence-boost (04/07/2026, Ecko) ──────────────────
        // Bij een overtuigend schone, bevestigd VERSNELLENDE curve (hoge
        // curveFitR2 én curveAcceleration > 0) mag de piekdrukbonus eerder
        // aanslaan — dus bij een lagere predictedPeak dan de vaste 11.0 mmol
        // drempel — en iets sterker uitpakken. Dit werkt uitsluitend
        // VERSTERKEND: fitConfidenceBoost is 0.0 zodra de fit zwak is of de
        // curve niet aantoonbaar versnelt, en dan verandert er niets t.o.v.
        // het bestaande gedrag hierboven. Er is geen pad waarlangs dit de
        // bonus kan verzwakken of vertragen — dat is expliciet de eis: nooit
        // remmen tijdens een bevestigde stijging.
        val fitConfident = ctx.curveFitR2 >= CURVE_FIT_MIN_R2 && ctx.curveAcceleration > 0.0
        val fitConfidenceBoost = if (fitConfident)
            smooth01((ctx.curveFitR2 - CURVE_FIT_MIN_R2) / (0.98 - CURVE_FIT_MIN_R2))
        else 0.0
        // Drempel zakt tot max CURVE_FIT_EARLY_TRIGGER_MMOL eerder (1.5 mmol)
        // bij volledig vertrouwen in de fit — nooit hoger dan de basis 11.0.
        val peakPressureThreshold = 11.0 - CURVE_FIT_EARLY_TRIGGER_MMOL * fitConfidenceBoost

        val peakPressureBonus = if (earlyDose.boostCommitCount == 0 &&
            peak.predictedPeak >= peakPressureThreshold &&
            ctx.iobRatio <= config.peakPressureBonusMaxIob
        ) {
            val peakPressure = smooth01((peak.predictedPeak - peakPressureThreshold) / (17.0 - peakPressureThreshold))
            val momentumScore = run {
                val slopeS   = smooth01((ctx.slope        - 0.35) / (1.40 - 0.35))
                val accelS   = smooth01((ctx.acceleration - 0.05) / (0.35 - 0.05))
                val deltaS   = smooth01((ctx.deltaToTarget - 0.8) / (3.50 - 0.80))
                val consSc   = smooth01((ctx.consistency  - 0.45) / (0.80 - 0.45))
                // fitConfidenceBoost telt hier extra mee, bovenop de bestaande
                // vier signalen — het meet iets anders (curve-fit-kwaliteit
                // i.p.v. segment-consistentie) en vervangt dus geen van hen.
                0.30 * slopeS + 0.30 * accelS + 0.13 * deltaS + 0.12 * consSc + 0.15 * fitConfidenceBoost
            }
            // Alleen piekdrukbonus als het momentum ook overtuigend is
            peakPressure * momentumScore * 0.35
        } else 0.0

        (1.0 + extraBoost * boostDecay + peakPressureBonus)
            .coerceIn(1.0, config.earlyBoostFactor + 0.40)
    }

    // ── Hypo-debt compensatie ─────────────────────────────────────────────
    // Als er eerder in deze episode insuline is achtergehouden door hypo-bescherming,
    // dan wordt de eerste vrije dosis versterkt om de achterstand deels in te halen.
    //
    // Voorwaarden (alle drie vereist):
    //   1. Er is een opgebouwde schuld (episodeHypoDebtU > 0)
    //   2. De stijging is CONFIRMED — zwak signaal mag niet compenseren
    //   3. Projectie zonder insuline blijft veilig (geen nieuwe hypo)
    //
    // De compensatie is proportioneel aan de schuld: grotere schuld = grotere bonus,
    // maar nooit meer dan +50% van de basiskaart (voorzichtig ophalen, niet inhalen).
    // Na toepassing daalt de schuld met de geleverde compensatie.
    //
    // Voorbeeld: schuld=0.40U, basis=0.80U → bonus = min(0.50*0.80, 0.40) = 0.40U
    //            effectieve dosis = 0.80 + 0.40 = 1.20U (nog steeds ≤ 1.5×maxSMB cap)
    val debtCompensationU = if (episodeHypoDebtU > 0.01 &&
        mealSignal.state == MealState.CONFIRMED &&
        hypoProjectedMinNoInsulin >= 4.8) {
        val baseTargetU = config.maxSMB * factor * config.doseStrengthMul * effectiveBoostFactor
        val maxBonus = baseTargetU * 0.50          // max 50% van de basiskaart als bonus
        minOf(maxBonus, episodeHypoDebtU)           // nooit meer dan de schuld zelf
    } else 0.0

    val targetU =
        maxOf(
            config.maxSMB * factor * config.doseStrengthMul * effectiveBoostFactor + debtCompensationU,
            minEarlyU
        ).coerceAtMost(config.maxSMB * 1.5)   // absolute cap: nooit meer dan 1.5× maxSMB

    // Bereken resterende schuld na compensatie — de klasse schrijft dit terug
    // naar episodeHypoDebtU na de aanroep (functie kan klasselid niet muteren).
    val remainingDebtU = (episodeHypoDebtU - debtCompensationU).coerceAtLeast(0.0)

    val debtReason = if (debtCompensationU > 0.01)
        " DEBT+${"%.2f".format(debtCompensationU)}U(rest=${"%.2f".format(remainingDebtU)}U)"
    else ""

    val boostReason = if (boostActive)
        " BOOST×${"%.2f".format(effectiveBoostFactor)}(${earlyDose.boostCommitCount+1}/${effectiveMaxCommits}${if (extraCommitsFromDebt > 0) "+${extraCommitsFromDebt}debt" else ""})"
    else ""

    val sustainReason = if (effectiveSustainScore > 0.05)
        " SUST=${sustainedHighSlopeMinutes.toInt()}m(×${"%.2f".format(effectiveSustainScore)})" else ""

    return EarlyDoseDecision(
        active = true,
        stageToFire = stageToFire,
        confidence = conf,
        targetU = targetU,
        reason = "EARLY: stage=$stageToFire conf=${"%.2f".format(conf)} s2wait=${minWachttijdStage2}m$boostReason$debtReason$sustainReason",
        remainingDebtU = remainingDebtU,
        boostActive = boostActive,
        effectiveBoostFactor = effectiveBoostFactor,
        boostCommitNr = earlyDose.boostCommitCount + 1
    )
}

private data class PostPeakSummary(
    val suppress: Boolean,
    val lockout: Boolean,
    val commitBlocked: Boolean,
    val commitFactor: Double,
    val noStash: Boolean,
    val sensorBlip: Boolean,
    val reason: String,
    val suppressReason: String,
    val lockoutReason: String,
    val commitBlockReason: String,
    val peakBrake: PeakBrakeResult
)

// ── Gedeelde piek-naderingsrem (vlak vóór/op de piek geen insuline meer) ──
// Consolidatie (30/06/2026, Ecko): vervangt drie losse implementaties die
// allemaal dezelfde zwakte hadden — ze vereisten ctx.slope <= 0.50 vóórdat
// ze konden ingrijpen, ook bij al torenhoge IOB. Incident 30/06 14:15 UTC:
// iobRatio=0.57, ctx.slope=3.92 (ver boven 0.50) → geen van de drie remmen
// kon afgaan, terwijl recentSlope al duidelijk aan het knikken was
// (5.67→4.55). Resultaat: 1.45U gecommit vlak op de feitelijke piek.
//
// Eén gedeelde functie i.p.v. drie aparte plekken — makkelijker te tunen.
//
// Twee signalen:
//  1. IOB-afhankelijke slope-ceiling: bij hogere IOB mag de rem ook bij een
//     hogere (nog stijgende) slope al ingrijpen, in plaats van te wachten
//     tot de trage lane zelf bijna vlak is.
//  2. RecentSlope-deceleratie: een duidelijke knik in de snelle lane
//     t.o.v. de vorige cyclus telt ook als piek-nadering, los van het
//     absolute slope-niveau.
//
// Twee niveaus:
//  - softBrake: continue/dynamische taper (severity 0..1), geen harde cliff.
//  - hardBrake: vanaf lockoutThreshold volledige stop (commandedDose=0),
//    niet alleen het WFF/consolidatie-deel.
//
// LET OP: maxReduction=0.45 en fullBrakeIobRatio/dropMin zijn een eerste,
// beredeneerde inschatting (geen historische tuning-data voor déze brede
// ceiling) — kan bij een volgende update nog bijgesteld worden, met name
// als blijkt dat langzame/vlakke (vet/eiwitrijke) stijgingen hierdoor
// onderbedeeld raken.
private data class PeakBrakeResult(
    val softBrakeFactor: Double,   // 1.0 = geen reductie, lager = taper
    val hardBrake: Boolean,        // true = commandedDose volledig naar 0
    val severity: Double,          // 0..1, voor logging/debug
    val slopeCeiling: Double,      // voor logging/debug
    val recentSlopeDrop: Double,   // voor logging/debug
    val reason: String
)

private fun computePeakBrake(
    ctx: FCLvNextContext,
    peak: PeakEstimate,
    config: FCLvNextConfig,
    prevRecentSlope: Double?
): PeakBrakeResult {

    val suppressThreshold = config.peakIobBrakeSuppressThreshold   // nu actief 0.30
    val lockoutThreshold = config.peakIobBrakeLockoutThreshold     // 0.55
    val fullBrakeIobRatio = 0.65   // net boven lockoutThreshold — vanaf hier ceiling=max
    val dropMin = 0.8              // mmol/L/u knik t.o.v. vorige cyclus
    val maxSlopeCeiling = 6.0
    val maxReduction = 0.45        // ⚠️ eerste inschatting, evt. bijstellen bij volgende update

    val recentSlopeDrop = (prevRecentSlope ?: ctx.recentSlope) - ctx.recentSlope

    if (peak.state == PeakPredictionState.IDLE || ctx.iobRatio < suppressThreshold) {
        return PeakBrakeResult(1.0, false, 0.0, 0.50, recentSlopeDrop, "NONE")
    }

    val t = smooth01((ctx.iobRatio - suppressThreshold) / (fullBrakeIobRatio - suppressThreshold))
    val slopeCeiling = 0.50 + t * (maxSlopeCeiling - 0.50)

    // BUGFIX (10/07/2026, Ecko): recentSlope is een kort/ruizig signaal — een
    // tijdelijke knik daarin (bijv. één cyclus lager door meetruis) werd tot nu
    // toe zonder tegencontrole als "aan het afvlakken" behandeld, ook als de
    // BG (en het gladdere slope-veld) gewoon stevig bleef doorstijgen. Concreet
    // voorbeeld: BG 11,3→11,5→11,9→12,8→14,2 mmol, slope bleef 8,6-9,9, maar
    // recentSlope zakte één cyclus van 12,71 naar 8,79 — genoeg om de rem 2
    // cycli (10 min) te activeren terwijl curveAcceleration op dat moment nog
    // gewoon +6,93 was (dus nog altijd versnellend). Zelfde patroon en
    // dezelfde oplossing als bij bgStijgtNogFors: alleen als de curve-fit
    // (betrouwbaarder, minder ruizig) de omslag BEVESTIGT, telt de knik mee.
    val curveConfirmtOmslag = ctx.curveFitR2 >= CURVE_FIT_MIN_R2 && ctx.curveAcceleration <= 0.0
    val decelTriggered = recentSlopeDrop >= dropMin && ctx.iobRatio >= suppressThreshold &&
        curveConfirmtOmslag
    val brakeCondition =
        (ctx.slope <= slopeCeiling || decelTriggered) && ctx.deltaToTarget >= 0.8

    if (!brakeCondition) {
        return PeakBrakeResult(1.0, false, 0.0, slopeCeiling, recentSlopeDrop, "NONE")
    }

    val hardBrake = ctx.iobRatio >= lockoutThreshold

    val severity = smooth01(
        0.5 * ((ctx.iobRatio - suppressThreshold) / (lockoutThreshold - suppressThreshold)) +
            0.3 * ((slopeCeiling - ctx.slope) / slopeCeiling).coerceIn(0.0, 1.0) +
            0.2 * (recentSlopeDrop / 2.0).coerceIn(0.0, 1.0)
    )
    val softBrakeFactor = 1.0 - severity * maxReduction

    val reason = if (hardBrake) "HARD_BRAKE" else "SOFT_BRAKE"
    return PeakBrakeResult(softBrakeFactor, hardBrake, severity, slopeCeiling, recentSlopeDrop, reason)
}

private fun evaluatePostPeak(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    now: DateTime,
    config: FCLvNextConfig,

    minutesSinceEpisodeStart: Int = 999,
    sensorBlipStreak: Int = 0,          // huidig aantal opeenvolgende blip-cycli
    bgRising3Cycles: Boolean = false,    // waren de laatste 3 BG-delta's alle positief?
    prevRecentSlope: Double? = null      // voor computePeakBrake() deceleratie-signaal
): PostPeakSummary {

    val inAbsorption = isInAbsorptionWindow(now, config)
    val reliable = ctx.consistency >= config.minConsistency

// ✅ NIEUW: veilige nieuwe maaltijdstijging override
    // Laat absorptiewindow-suppress niet triggeren bij bewezen nieuwe maaltijd
    // terwijl de vorige IOB grotendeels weg is.
    //
    // Veiligheidsbasis: bij een vals signaal (geen echte maaltijd) moet de BG
    // hoog genoeg zijn dat de volledige IOB-staart geen hypo veroorzaakt.
    // deltaToTarget >= 3.0 is relatief t.o.v. het ingestelde target en werkt
    // daardoor voor elk target-niveau. De absolute BG-ondergrens is altijd 3.9 mmol.
    //
    // Vier eisen samen voorkomen false positives:
    // 1. meal CONFIRMED (niet op één artefact-cyclus)
    // 2. episode >= 5 min actief (artefact bereikt nooit 5 min CONFIRMED duur)
    // 3. deltaToTarget >= 2.0 + recentSlope >= 3.0 bij lage IOB (was >= 3.0 altijd).
    //    Reden verlaging: bevestigde stijging bij BG 7.8 (delta=2.28, recentSlope=10,
    //    iobRatio=0.04) werd ten onrechte geblokkeerd door ABSORPTION-suppress terwijl
    //    er geen werkelijk hypo-risico was. Extra veiligheidseis: iobRatio < 0.15.
    // 4. recentDelta5m >= 0.30 (meetbare snelle stijging, geen CGM-drift)
    val newMealOverride =
        mealSignal.state == MealState.CONFIRMED &&
            minutesSinceEpisodeStart >= 5 &&
            ctx.recentDelta5m >= 0.30 &&
            (ctx.deltaToTarget >= 3.0 ||
                (ctx.deltaToTarget >= 2.0 && ctx.recentSlope >= 3.0))

    // slowCarbOverride: doorbreekt absorptionWindow bij slow-carb stijging
    // (bier, kaas, dessert) waarbij UNCERTAIN voldoende is maar BG al hoog is.
    // Vereisten: BG >= 8.0 (echt te hoog), aanhoudende stijging >= 30 min,
    // lage IOB (geen hypo-risico), zowel 5m als langere trend positief.
    val slowCarbOverride =
        mealSignal.state in listOf(MealState.CONFIRMED, MealState.UNCERTAIN) &&
            minutesSinceEpisodeStart >= 30 &&
            ctx.input.bgNow >= 8.0 &&
            ctx.recentSlope >= 2.5 &&
            ctx.recentDelta5m >= 0.10 &&
            ctx.iobRatio < 0.20

    // episode-like: ook zonder absorption kunnen we een top herkennen
    val episodeLike =
        mealSignal.state != MealState.NONE || peakEstimator.active || peak.state != PeakPredictionState.IDLE

// FAST-lane top/plateau: macro slope kan nog hoog zijn door historie, dus fast-lane is leidend
    val fastPlateau =
        ctx.recentSlope <= 0.20 &&
            kotlin.math.abs(ctx.recentDelta5m) <= 0.03

// "Top forming" pre-commit: voldoende IOB + (bijna) vlak → dan geen extra insulin pushen
    val preCommitTop =
        episodeLike && reliable &&
            ctx.iobRatio >= config.preCommitTopIobThreshold &&
            ctx.deltaToTarget >= 1.0 &&
            ctx.acceleration <= config.preCommitTopAccelMax &&
            fastPlateau


    // ── SensorBlip guard — 3-laags discriminator ─────────────────────────────────
    //
    // Laag 1 — Basisconditie (ongewijzigd):
    //   slowFalling (EWMA ≤ -0.30) EN fastRising (recentSlope ≥ 1.50)
    //   = twee tegenstrijdige signalen tegelijk → mogelijke blip.
    //
    // Laag 2 — Streak-limiet:
    //   Echte sensorblip: 1-2 cycli (5-10 min max).
    //   EWMA-naloop na echte stijging: tientallen cycli.
    //   Na streak > 2 → blipconditie opgeheven, echte stijging aangenomen.
    //   Maximale blokkade per event: 2 cycli = 10 minuten.
    //
    // Laag 3 — Stijgingsconsistentie:
    //   3 opeenvolgend positieve BG-delta's = echte stijging, nooit een blip.
    //   Een sensorblip geeft 1 hoge meting dan direct terugval.
    val slowFalling = ctx.slope <= -0.30
    val fastRising = (ctx.recentSlope >= 1.50) || (ctx.recentDelta5m >= 0.20)

    val blipBaseCondition =

        episodeLike && reliable &&
            ctx.deltaToTarget >= 1.0 &&
            ctx.iobRatio >= 0.25 &&
            slowFalling && fastRising

    // Laag 2: na 2 opeenvolgende blip-cycli beschouwen we het als echte stijging
    val streakExceeded = sensorBlipStreak > 2

    // Laag 3: 3 opeenvolgend stijgende BG-metingen = echte stijging
    val consistentRise = bgRising3Cycles

    val sensorBlip = blipBaseCondition && !streakExceeded && !consistentRise

    val risingAgainTail =
        ctx.recentSlope >= 0.35 ||
            ctx.recentDelta5m >= 0.08

    val tailSuppress =
        episodeLike && reliable &&
            peak.riseSinceStart >= 1.0 &&
            ctx.deltaToTarget >= 1.0 &&
            ctx.iobRatio >= config.tailSuppressIobMin &&
            fastPlateau &&
            !risingAgainTail

    // ✅ GECONSOLIDEERD (30/06/2026): predictieve IOB-rem via computePeakBrake().
    // Vervangt de oude losse peakIobBrake-conditie (ctx.slope <= 0.50 vaste grens).
    val peakBrake = computePeakBrake(ctx, peak, config, prevRecentSlope)
    val peakIobBrake = peakBrake.reason != "NONE"   // soft of hard → telt als brake-signaal


    // Basale post-peak kenmerken
    val flattening = ctx.acceleration <= 0.05
    val notRising = ctx.slope < 0.60
    val highIob = ctx.iobRatio >= 0.55

    // SUPPRESS: mild, alleen tijdens absorption
    val suppress =
        reliable && (
            // bestaande post-commit suppress — niet bij bewezen nieuwe maaltijdstijging
            (inAbsorption && !newMealOverride && !slowCarbOverride &&
                (ctx.slope <= config.peakSlopeThreshold || ctx.acceleration <= config.peakAccelThreshold)
                )
                // bestaande pre-commit top/plateau suppress
                || preCommitTop
                // ✅ NIEUW
                || tailSuppress
                // ✅ NIEUW
                || sensorBlip
                // ✅ NIEUW: predictieve IOB-rem vóór formele piek-detectie
                || peakIobBrake
            )

    // LOCKOUT: hard, ook tijdens absorption
    val dynamicIobThreshold =
        (0.30 + 0.07 * ctx.deltaToTarget).coerceIn(0.30, 0.70)
    val lockout =
        reliable && (
            (inAbsorption && !newMealOverride && !slowCarbOverride &&
                ((ctx.slope <= config.peakSlopeThreshold) || (ctx.acceleration <= config.peakAccelThreshold)) &&
                (ctx.iobRatio >= dynamicIobThreshold)
                )
                || (preCommitTop && ctx.iobRatio >= 0.55)
                // ✅ NIEUW: bij sensorBlip liever hard stoppen met pushen
                || sensorBlip
                // ✅ NIEUW: harde stop als IOB echt hoog is vlak voor piek
                || peakBrake.hardBrake
            )


    // COMMIT BLOCK: voorkomen van “commit na omkeer”
    val commitBlocked =
        reliable && (
            (ctx.acceleration < -0.05 && ctx.iobRatio >= config.commitBlockIobThreshold)
                || (preCommitTop && ctx.iobRatio >= config.commitBlockIobThreshold)
                // ✅ NIEUW
                || sensorBlip
            )


    val commitFactor =
        if (!episodeLike) 1.0
        else if (!(highIob && flattening && notRising && reliable)) 1.0
        else {
            val iobSeverity = smooth01((ctx.iobRatio - 0.55) / 0.35)
            val accelSeverity = smooth01((0.05 - ctx.acceleration) / 0.15)
            val slopeSeverity = smooth01((0.60 - ctx.slope) / 0.80)
            val severity = (0.45 * iobSeverity + 0.35 * accelSeverity + 0.20 * slopeSeverity).coerceIn(0.0, 1.0)
            (1.0 - 0.65 * severity).coerceIn(0.35, 1.0)
        }

    // NO-STASH window: als je al in post-peak afvlak zit, niet nog extra potjes maken
    val noStash =
        highIob && flattening && reliable && fastPlateau

    val reason =
        "POSTPEAK: suppress=$suppress lockout=$lockout commitBlocked=$commitBlocked " +
            "commitFactor=${"%.2f".format(commitFactor)} noStash=$noStash sensorBlip=$sensorBlip " +
            "iobR=${"%.2f".format(ctx.iobRatio)} slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)} " +
            "rSlope=${"%.2f".format(ctx.recentSlope)} rΔ5m=${"%.2f".format(ctx.recentDelta5m)}"

    val suppressReason = when {
        !suppress -> "NONE"
        peakIobBrake -> "PEAK_IOB_BRAKE"
        sensorBlip -> "SENSOR_BLIP"
        tailSuppress -> "TAIL"
        preCommitTop -> "PRE_COMMIT_TOP"
        else -> "ABSORPTION"
    }

    val lockoutReason = when {
        !lockout -> "NONE"
        peakIobBrake && ctx.iobRatio >= 0.70 -> "PEAK_IOB_BRAKE_HIGH"
        sensorBlip -> "SENSOR_BLIP"
        preCommitTop && ctx.iobRatio >= 0.55 -> "PRE_COMMIT_TOP"
        else -> "ABSORPTION"
    }

    val commitBlockReason = when {
        !commitBlocked -> "NONE"
        sensorBlip -> "SENSOR_BLIP"
        preCommitTop && ctx.iobRatio >= 0.45 -> "PRE_COMMIT_TOP"
        else -> "DECEL_HIGH_IOB"
    }

    return PostPeakSummary(
        suppress = suppress,
        lockout = lockout,
        commitBlocked = commitBlocked,
        commitFactor = commitFactor,
        noStash = noStash,
        sensorBlip = sensorBlip,
        reason = reason,
        suppressReason = suppressReason,
        lockoutReason = lockoutReason,
        commitBlockReason = commitBlockReason,
        peakBrake = peakBrake
    )

}


private fun trajectoryDampingFactor(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    bgZone: BgZone,
    config: FCLvNextConfig,
    peak: PeakEstimate,
    suppressForPeak: Boolean            // ✅ NIEUW
): Double {



    // Betrouwbaarheid
    if (ctx.consistency < config.minConsistency) return 1.0

    val delta = ctx.deltaToTarget        // mmol boven target
    val iobR  = ctx.iobRatio             // 0.. ~1+
    val slope = ctx.slope                // mmol/L/h
    val accel = ctx.acceleration         // mmol/L/h^2


    // 1) BG/delta: hoe hoger boven target, hoe minder remming
    //    delta=0 -> 0, delta>=6 -> 1
    val deltaScore = smooth01((delta - 0.0) / 6.0)

    // 2) IOB: hoe hoger, hoe meer remming
    //    iob<=0.35 -> ~0 rem, iob>=0.85 -> ~1 rem
    val iobPenalty = smooth01((iobR - 0.35) / (0.85 - 0.35))

    // 3) Slope: dalend/flat geeft remming, stijgend haalt remming weg
    //    slope<=-0.6 -> 1 rem, slope>=+1.0 -> 0 rem
    val slopePenalty = when (bgZone) {
        BgZone.EXTREME, BgZone.HIGH ->
            1.0 - smooth01((slope - (-0.8)) / (1.2 - (-0.8)))

        BgZone.MID ->
            1.0 - smooth01((slope - (-0.6)) / (1.0 - (-0.6)))

        BgZone.IN_RANGE, BgZone.LOW ->
            1.0 - smooth01((slope - (-0.2)) / (0.6 - (-0.2)))
    }

    // 4) Accel: negatief (afremmen/omkeren) geeft remming
    //    accel<=-0.10 -> 1 rem, accel>=+0.15 -> 0 rem
    val accelPenalty = when (bgZone) {
        BgZone.EXTREME, BgZone.HIGH ->
            1.0 - smooth01((accel - (-0.15)) / (0.20 - (-0.15)))

        BgZone.MID ->
            1.0 - smooth01((accel - (-0.10)) / (0.15 - (-0.10)))

        BgZone.IN_RANGE, BgZone.LOW ->
            1.0 - smooth01((accel - (-0.02)) / (0.08 - (-0.02)))
    }

    // 5) Meal: als we in meal staan, minder streng (want stijging kan “legit” zijn)
    val baseMealRelax = when (mealSignal.state) {
        MealState.NONE -> 1.0
        MealState.UNCERTAIN -> 0.75
        MealState.CONFIRMED -> 0.55
    }

// ✅ NIEUW: na (post-)peak/afremmen géén “relax” — juist strenger remmen
    val mealRelax =
        if (suppressForPeak || ctx.slope <= 0.0 || ctx.acceleration < 0.0) 1.0
        else baseMealRelax


    // Combineer:
    // - Penalties versterken elkaar
    // - deltaScore werkt “tegen” penalties in (hoge delta laat meer toe)
    val combinedPenalty =
        (0.20 * iobPenalty + 0.45 * slopePenalty + 0.35 * accelPenalty)
            .coerceIn(0.0, 1.0)

    // Baseline factor: 1 - penalty
    var factor = (1.0 - combinedPenalty).coerceIn(0.0, 1.0)

    // Delta laat factor weer oplopen (bij hoge delta minder rem)
    // deltaScore=0 -> geen uplift, deltaScore=1 -> uplift tot +0.35
    factor = (factor + 0.35 * deltaScore).coerceIn(0.0, 1.0)

    // Meal relax (vermindert penalties) -> factor omhoog
    factor = (factor / mealRelax).coerceAtMost(1.0)

    // Extra bescherming: hoge IOB + geen meal + geen echte stijging → factor sterk omlaag
    if (!isMacroRising(ctx, peak, config) &&
        mealSignal.state == MealState.NONE &&
        ctx.iobRatio >= 0.65 &&
        ctx.slope < 0.8
    ) {
        factor *= 0.25
    }


    // 🔒 LOW-BG HARD CLAMP
    if (bgZone == BgZone.LOW && ctx.slope <= 0.0) {
        return 0.0
    }

    return factor
}

private fun isEarlyProtectionActive(
    earlyStage: Int,
    ctx: FCLvNextContext,
    peak: PeakEstimate
): Boolean {

    if (earlyStage <= 0) return false

    // ❗ ZODRA AFREM MEN BEGINT → early protection UIT
    if (ctx.acceleration < 0.0) return false

    if (peak.state == PeakPredictionState.CONFIRMED) return false

    return true
}

private fun maybeResetEarlyOnDecel(
    ctx: FCLvNextContext,
    peak: PeakEstimate,
    now: DateTime,
    status: StringBuilder
): Boolean {

    // alleen zinvol als early al “aan” stond
    if (earlyDose.stage <= 0) return false

// ─────────────────────────────────────────────
// 🧠 Momentum fade detection (agressiever lerend)
// ─────────────────────────────────────────────

    val classicalDecel =
        (ctx.acceleration <= EARLY_RESET_ACCEL) ||
            (ctx.slope <= EARLY_RESET_SLOPE && ctx.recentSlope <= 0.0)


// Nieuwe, gevoeligere momentum detectie
    val momentumFade =
        ctx.acceleration <= 0.0 &&                  // versnelling weg
            ctx.recentDelta5m <= -0.02 &&                 // 5m delta niet meer positief
            ctx.iobRatio >= (0.30 + 0.05 * smooth01(ctx.deltaToTarget / 4.0)) &&     // al insuline aan boord
            ctx.deltaToTarget >= 0.8 &&                 // nog duidelijk boven target
            peak.state != PeakPredictionState.CONFIRMED // niet al in peak-lock

    val decel = classicalDecel || momentumFade


    if (!decel) return false

    // reset state + voorkom follow-up “impulse” gedrag
    earlyDose = EarlyDoseContext()
    earlyConfirmDone = false
    sensorBlipStreakCount = 0
    recentBgHistory.clear()

    status.append(
        "EARLY RESET (deceleration): " +
            "slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)} " +
            "peakState=${peak.state}\n"
    )
    return true
}


private fun updateDowntrendGate(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    config: FCLvNextConfig
): DowntrendGate {

    // --- Drempels (startwaarden; later eventueel in config) ---
    val minCons = 0.45

    // “dipje” → pauze (1 cycle), maar niet locken
    val pauseSlopeHr = -0.25          // mmol/L/h
    val pauseDelta5m = -0.10          // mmol/5m

    // “echte daling ingezet” → LOCKED na confirm cycles
    val lockSlopeHr = -0.60           // mmol/L/h
    val lockDelta5m = -0.20           // mmol/5m
    val lockConfirmCycles = 2

    // “plateau” → unlock (hysterese)
    val plateauSlopeAbs = 0.15        // |mmol/L/h|
    val plateauDelta5mAbs = 0.05      // |mmol/5m|
    val plateauConfirmCycles = 2

    val reliable = ctx.consistency >= minCons

    // We baseren daling op fast lane:
    val fallingHard = reliable &&
        (ctx.recentSlope <= lockSlopeHr || ctx.recentDelta5m <= lockDelta5m) &&
        ctx.deltaToTarget > 0.3 // vermijd lock rond target door mini-ruis

    val macroRising =
        ctx.slope >= 0.6 &&
            ctx.deltaToTarget >= 1.0 &&
            ctx.consistency >= config.episodeMinConsistency

    val fallingSoft =
        reliable &&
            !macroRising &&
            (ctx.recentSlope <= pauseSlopeHr || ctx.recentDelta5m <= pauseDelta5m) &&
            ctx.deltaToTarget > 0.3

    val plateau = reliable &&
        kotlin.math.abs(ctx.recentSlope) <= plateauSlopeAbs &&
        kotlin.math.abs(ctx.recentDelta5m) <= plateauDelta5mAbs

    val risingAgain = reliable && (ctx.recentSlope >= 0.20 || ctx.recentDelta5m >= 0.06)

    // --- state machine ---
    when (downtrendLock) {

        DowntrendLock.OFF -> {
            if (fallingHard) {
                downtrendConfirm++
                if (downtrendConfirm >= lockConfirmCycles) {
                    downtrendLock = DowntrendLock.LOCKED
                    plateauConfirm = 0
                    return DowntrendGate(
                        pauseThisCycle = false,
                        locked = true,
                        reason = "DOWNTREND LOCKED: recentSlope=${"%.2f".format(ctx.recentSlope)} recentΔ5m=${"%.2f".format(ctx.recentDelta5m)}"
                    )
                }
            } else {
                downtrendConfirm = 0
            }

            // dipje: pauze 1 cycle, maar geen lock
            if (fallingSoft) {
                return DowntrendGate(
                    pauseThisCycle = true,
                    locked = false,
                    reason = "DOWNTREND PAUSE: recentSlope=${"%.2f".format(ctx.recentSlope)} recentΔ5m=${"%.2f".format(ctx.recentDelta5m)}"
                )
            }

            return DowntrendGate(false, false, "DOWNTREND OFF")
        }

        DowntrendLock.LOCKED -> {

            // unlock pas bij plateau OF duidelijke hernieuwde stijging
            if (risingAgain) {
                downtrendLock = DowntrendLock.OFF
                downtrendConfirm = 0
                plateauConfirm = 0
                return DowntrendGate(false, false, "DOWNTREND UNLOCK (rising again)")
            }

            if (plateau) {
                plateauConfirm++
                if (plateauConfirm >= plateauConfirmCycles) {
                    downtrendLock = DowntrendLock.OFF
                    downtrendConfirm = 0
                    plateauConfirm = 0
                    return DowntrendGate(false, false, "DOWNTREND UNLOCK (plateau)")
                }
            } else {
                plateauConfirm = 0
            }

            return DowntrendGate(
                pauseThisCycle = false,
                locked = true,
                reason = "DOWNTREND LOCKED (holding)"
            )
        }
    }
}




private fun shouldHardBlockTrajectory(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    earlyStage: Int,
    peak: PeakEstimate,
    now: DateTime,
    config: FCLvNextConfig
): Boolean {

    // ✅ Extra harde regel: duidelijke omkeer + al redelijk IOB -> meteen blokkeren
    if (isInAbsorptionWindow(now, config) &&
        ctx.acceleration <= config.trajectoryAbsorptionAccelThreshold &&
        ctx.iobRatio >= config.trajectoryAbsorptionIobMin &&
        ctx.consistency >= 0.45
    ) return true
    // early bescherming alleen zolang we nog niet afremmen / piek hebben
    if (isEarlyProtectionActive(earlyStage, ctx, peak)) return false

    // nooit hard block bij meal
    if (mealSignal.state != MealState.NONE) return false

    if (earlyStage > 0) return false

    val highIob = ctx.iobRatio >= config.trajectoryHighIobThreshold
    val notReallyRising = ctx.slope < 0.6
    val decelerating = ctx.acceleration <= -0.05
    val reliable = ctx.consistency >= 0.5

    return highIob && notReallyRising && decelerating && reliable
}

private fun shouldBlockMicroCorrections(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peakCategory: PeakCategory,
    earlyStage: Int,
    peak: PeakEstimate,
    config: FCLvNextConfig
): Boolean {

    if (isEarlyProtectionActive(earlyStage, ctx, peak)) return false


    // Alleen voor "geen-meal" correcties
    if (mealSignal.state != MealState.NONE) return false

    // Als het echt een meal/high episode is, niet blokkeren
    if (peakCategory >= PeakCategory.MEAL) return false

    val fallingOrFlat =
        ctx.slope <= config.correctionHoldSlopeMax &&   // bv <= -0.20
            ctx.acceleration <= config.correctionHoldAccelMax && // bv <= 0.05
            ctx.consistency >= config.minConsistency

    // Als BG nog maar weinig boven target zit -> zeker wachten
    val notFarAboveTarget =
        ctx.deltaToTarget <= config.correctionHoldDeltaMax  // bv <= 1.5

    return fallingOrFlat && notFarAboveTarget
}

/**
 * Re-entry: tweede gang / dessert.
 * Alleen toestaan als:
 * - genoeg tijd sinds commit
 * - én duidelijke nieuwe stijging (slope/accel/delta)
 * - én reentry cooldown gerespecteerd
 */
private fun isReentrySignal(
    ctx: FCLvNextContext,
    now: DateTime,
    config: FCLvNextConfig
): Boolean {
    val sinceCommit = minutesSince(lastCommitAt, now)
    if (sinceCommit < config.reentryMinMinutesSinceCommit) return false

    val sinceReentry = minutesSince(lastReentryCommitAt, now)
    if (sinceReentry < config.reentryCooldownMinutes) return false

    val rising = ctx.slope >= config.reentrySlopeMin
    val accelerating = ctx.acceleration >= config.reentryAccelMin
    val aboveTarget = ctx.deltaToTarget >= config.reentryDeltaMin
    val reliable = ctx.consistency >= config.minConsistency

    return reliable && aboveTarget && rising && accelerating
}






class FCLvNext(
    private val preferences: Preferences,
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
    private val context: android.content.Context,
    private val cycleLogRepository: app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository
) {

    /**
     * Callback die wordt aangeroepen bij elke nieuwe episodestart.
     * Geregistreerd door DetermineBasalFCL zodat FCLvNext zelf geen
     * dependency op PersistenceLayer nodig heeft.
     * Gebruik: onEpisodeStarted = { params -> FclActivityLogger.logEpisodeStart(...) }
     * (02/07/2026, Ecko — activiteitslogger fase 1)
     */
    var onEpisodeStarted: ((episodeId: Long, bgMmol: Double, targetMmol: Double,
                            iobRatio: Double, iobAbsU: Double, isNight: Boolean,
                            externalBolusU: Double) -> Unit)? = null

    // ── Episode-teller (04/07/2026, Ecko) ──────────────────────────────────
    // Was: private var mealEpisodeCounter: Long = 0
    // Probleem: elke AAPS-herstart reset de teller → duplicate episode_ids
    // in de ActivityLogger → koppeling aan LearnerLog onmogelijk.
    // Fix: teller wordt persistent opgeslagen in SharedPreferences en
    // opgehaald bij initialisatie. Elke herstart gaat verder waar hij gebleven was.
    private val EPISODE_PREFS = "fcl_episode_counter"
    private val EPISODE_KEY   = "meal_episode_counter"

    private fun loadEpisodeCounter(): Long =
        context.getSharedPreferences(EPISODE_PREFS, android.content.Context.MODE_PRIVATE)
            .getLong(EPISODE_KEY, 0L)

    private fun saveEpisodeCounter(value: Long) =
        context.getSharedPreferences(EPISODE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putLong(EPISODE_KEY, value).apply()

    private var mealEpisodeCounter: Long = loadEpisodeCounter()
    private var activeMealEpisodeId: Long = -1
    private var mealEpisodeStartTime: DateTime? = null

    // ── Maaltijd-anticipatie geschiedenis (05/07/2026, Ecko) ────────────────
    // Opslag zelf leeft in FclMealTimeAnticipation.kt (gedeeld met
    // DetermineBasalFCL.kt, die de daadwerkelijke target-verlaging toepast —
    // zie de toelichting daar). FCLvNext.kt is hier alleen de SCHRIJVER: het
    // legt een nieuw event vast zodra een episode voor het eerst CONFIRMED is.
    private fun loadMealTimeHistory(): FclMealTimeAnticipation.History =
        FclMealTimeAnticipation.loadFrom(context)

    private fun saveMealTimeHistory(h: FclMealTimeAnticipation.History) =
        FclMealTimeAnticipation.saveTo(context, h)

    private var mealTimeHistory: FclMealTimeAnticipation.History = loadMealTimeHistory()

    // Voorkomt dat dezelfde episode meerdere cycli achter elkaar (zolang hij
    // CONFIRMED blijft) opnieuw wordt vastgelegd — precies één event per episode.
    private var lastRecordedMealTimeEpisodeId: Long = -1
    private var mealEpisodeStartBg: Double? = null
    // Frontload-shift tracking: bijgehouden per episode
    private var episodeCommitCount: Int = 0    // volgnummer commit (1=eerste)
    private var episodeBoostBudgetU: Double = 0.0  // extra U gegeven door earlyBoost
    // 08/07/2026 (Ecko) — hoogst gecommitteerde dosis deze episode, referentiepunt
    // voor de afbouw van finalDose (zie de maxOf(finalDose, commitDose)-fix hieronder).
    // Mag GROEIEN als een latere commit terecht groter is (echte, doorzettende
    // versnelling — bgStijgtNogFors) — de afbouw daarna gaat dan vanaf dát nieuwe,
    // hogere punt verder, niet vanaf de oorspronkelijke eerste commit.
    private var episodePeakCommitU: Double = 0.0
    // 11/07/2026 (Ecko) — puur diagnostisch, geen invloed op dosering. Vastgelegd
    // zodat een volgend "laat commit slaat de afbouw over"-incident (zoals
    // 11/07 06:42-07:12) exact te herleiden is uit de CSV, i.p.v. te moeten
    // reconstrueren uit indirecte signalen. Gezet in het cappedFinalDose-blok,
    // gelezen bij het wegschrijven van logRow verderop.
    private var lastBgStijgtNogFors: Boolean = false
    private var lastCommitNrUsed: Int = 0

    // ── Snelle-afremming guard (zie updateRapidDecelGate) ──────────────────
    // Houdt de hoogste recentSlope sinds episode-start bij, om een relatieve
    // terugval te kunnen detecteren los van de absolute downtrend-drempels.
    private var episodePeakRecentSlope: Double = 0.0
    private var rapidDecelLocked: Boolean = false
    private var rapidDecelConfirm: Int = 0

    // ── Hypo-debt tracking ────────────────────────────────────────────────
    // Bijhoudt hoeveel insuline in de vroege fase van deze episode is
    // achtergehouden door hypo-bescherming. Dit wordt verrekend als een
    // gecompenseerde early boost zodra de maaltijdstijging bevestigd is:
    //   - De eerste vrije dosis na de hypo-rem krijgt een bonus proportioneel
    //     aan de opgebouwde schuld
    //   - Alleen actief als de stijging CONFIRMED is (sterk genoeg signaal)
    //   - Veiligheidsgrens: maximale compensatie = 1× de normale dosis
    //   - Reset bij episode-start en episode-einde
    private var episodeHypoDebtU: Double = 0.0  // achtergehouden insuline door hypo-rem

    // ── Sustained Rise tracking ───────────────────────────────────────────
    // Telt hoeveel minuten de slope al aanhoudend boven de drempel is.
    // Niet gereset bij episode-grenzen — meet puur de actuele BG-trend.
    // Reset naar 0 zodra slope onder de drempel zakt.
    private var sustainedHighSlopeMinutes: Double = 0.0
    private var sustainedLastUpdateAt: DateTime? = null

    // ── Persistentie van een stijging (deceleratie-detectie) ───────────────
    // sustainedHighSlopeMinutes meet alleen HOELANG slope al hoog is — niet
    // OF de stijging aan het uittoppen is. Twee stijgingen kunnen allebei een
    // verzadigde sustainScore hebben terwijl de ene vanzelf afremt (acceleratie
    // daalt duidelijk) en de andere gewoon doorzet (acceleratie blijft ~gelijk).
    // Bevinding 21/06/2026: ontbijt 20/06 (terecht klein gebleven, piek 7,2)
    // en de maaltijd van 21/06 10:15 (had eerder mogen escaleren, piek 13,2)
    // hadden BEIDE een verzadigde sustainScore tijdens hun MealState.UNCERTAIN-
    // venster — het onderscheidende signaal zat in de accel-trend, niet in
    // de slope-duur.
    //
    // Eerste opzet (vergelijk met accel van een vast aantal minuten terug)
    // bleek de aanloopfase van de stijging zelf te raken: bij een net
    // gestarte stijging valt "X min geleden" vaak nog middenin de eerste,
    // altijd-aanwezige versnellingspiek, wat ten onrechte als "decelererend"
    // werd gelezen. In plaats daarvan wordt nu de acceleratie bevroren op
    // het MOMENT dat de classificatie van CONFIRMED terugvalt naar UNCERTAIN
    // — en alles daarna binnen diezelfde UNCERTAIN-periode vergeleken met
    // dát ankerpunt. Bij het ontbijt was de afname binnen 5-10 minuten na
    // dat omslagpunt al duidelijk meetbaar; bij de maaltijd van 21/06 bleef
    // de acceleratie de hele UNCERTAIN-periode nagenoeg gelijk. Geverifieerd
    // tegen beide episodes vóór implementatie.
    private var accelAtUncertainEntry: Double? = null
    private var lastMealState: MealState? = null

    /**
     * Werkt de UNCERTAIN-ankerwaarde bij en geeft de afname sinds het moment
     * van binnenkomst in UNCERTAIN (positief = afgenomen/decelererend, 0 of
     * negatief = gelijk gebleven of toegenomen). Buiten een UNCERTAIN-periode
     * altijd 0.0 (neutraal).
     */
    private fun updateAccelDeclineSinceUncertain(currentState: MealState, currentAccel: Double): Double {
        val decline = if (currentState == MealState.UNCERTAIN) {
            if (lastMealState != MealState.UNCERTAIN) {
                accelAtUncertainEntry = currentAccel
            }
            (accelAtUncertainEntry ?: currentAccel) - currentAccel
        } else {
            accelAtUncertainEntry = null
            0.0
        }
        lastMealState = currentState
        return decline
    }

    var lastActiveConfig: FCLvNextConfig? = null
        private set



    private fun computeFutureInsulinDrop60m(
        now: DateTime,
        effectiveISF: Double
    ): Double {

        val profile = kotlinx.coroutines.runBlocking {
            profileFunction.getProfile(now.millis)
        } ?: return 0.0

        val nowIob = kotlinx.coroutines.runBlocking {
            iobCobCalculator.calculateFromTreatmentsAndTemps(now.millis, profile)
        }
        val futureTime = now.plusMinutes(60)
        val futureIob = kotlinx.coroutines.runBlocking {
            iobCobCalculator.calculateFromTreatmentsAndTemps(futureTime.millis, profile)
        }

        val deltaIob = (nowIob.iob - futureIob.iob).coerceAtLeast(0.0)

        return deltaIob * effectiveISF
    }

    private fun updatePeakEstimate(
        config: FCLvNextConfig,
        ctx: FCLvNextContext,
        mealSignal: MealSignal,
        now: DateTime
    ): PeakEstimate {

        // ── episode start condities (flexibel, maar bewust niet te streng) ──
        val episodeShouldBeActive =
            mealSignal.state != MealState.NONE ||
                ( ctx.consistency >= config.episodeMinConsistency &&
                    ( ctx.deltaToTarget >= 0.6 ||
                        (ctx.acceleration >= 0.12 && ctx.slope >= 0.15)
                        )
                    )

        // ── Grace period herstart ───────────────────────────────────────
        // Als de episode recent (< 10 min) is geëxiteerd terwijl BG nog
        // boven target staat en slope positief is, mag de episode direct
        // hervatten zonder de volledige startconditie.
        // Voorkomt dat een korte slope-dip (1 cyclus) de episode reset.
        val minutesSinceLastExit = lastEpisodeExitAt?.let {
            org.joda.time.Minutes.minutesBetween(it, now).minutes
        } ?: Int.MAX_VALUE
        val graceRestart =
            !peakEstimator.active &&
                minutesSinceLastExit <= 10 &&
                ctx.deltaToTarget >= 0.5 &&
                ctx.slope >= 0.0 &&
                ctx.iobRatio < 0.60

        // ── episode init/reset ──
        if (!peakEstimator.active && (episodeShouldBeActive || graceRestart)) {
            peakEstimator.active = true
            peakEstimator.startedAt = now
            peakEstimator.startBg = ctx.input.bgNow
            peakEstimator.maxSlope = ctx.slope.coerceAtLeast(0.0)
            peakEstimator.maxAccel = ctx.acceleration.coerceAtLeast(0.0)
            peakEstimator.posSlopeArea = 0.0
            peakEstimator.momentum = 0.0
            peakEstimator.lastAt = now
            peakEstimator.state = PeakPredictionState.IDLE
            peakEstimator.confirmCounter = 0
            // nieuw segment → nieuwe pre-peak impuls toegestaan
            prePeakImpulseDone = false
            lastSegmentAt = now
            // Bewaar boostCommitCount over de reset heen — commits die voor de
            // peak-estimator-start zijn gegeven (UNCERTAIN fase) tellen mee voor
            // earlyBoostMaxCommits en het budget. Zonder dit wordt de eerste
            // geboostte commit vergeten en telt die niet mee in de decay.
            val savedBoostCount = earlyDose.boostCommitCount
            earlyDose = EarlyDoseContext()
            earlyDose.boostCommitCount = savedBoostCount
            earlyConfirmDone = false
            sensorBlipStreakCount = 0
            recentBgHistory.clear()
        }

        // ── episode exit (niet te snel!) ──
        // Episode leeftijd (voor alle exit-criteria)
        val episodeAgeMinutes = peakEstimator.startedAt?.let {
            org.joda.time.Minutes.minutesBetween(it, now).minutes
        } ?: 0
        val minutesSinceLastCommitForExit = minutesSince(lastCommitAt, now)

        // Exit A: IOB-gebaseerd (ontleend aan analyzer EpisodeDetector)
        // Episode eindigt als IOB laag is EN geen maaltijdsignaal EN geen stijging.
        // Werkt ongeacht BG-niveau: lost het probleem op waarbij deltaToTarget
        // nooit < 0.2 komt bij stabiele nacht-BG van 7+ mmol.
        // False positive rate: 0.3% (1 van 372 CONFIRMED rijen in testdata).
        val iobBasedExit = peakEstimator.active &&
            ctx.iobRatio < 0.10 &&
            mealSignal.state == MealState.NONE &&
            ctx.recentSlope < 2.0 &&
            minutesSinceLastCommitForExit >= 30

        // Exit B: stabiele BG zonder maaltijddynamiek
        // Plateau-situatie: BG stabiel op 7.0 mmol, geen meal, IOB daalt langzaam.
        // episodeAgeMinutes >= 90 voorkomt te vroeg exit na maaltijdstart.
        // False positive rate: 0% in testdata.
        val stableExhaustedExit = peakEstimator.active &&
            ctx.iobRatio < 0.20 &&
            mealSignal.state == MealState.NONE &&
            kotlin.math.abs(ctx.slope) < 0.20 &&
            ctx.recentSlope < 1.0 &&
            episodeAgeMinutes > 90 &&
            minutesSinceLastCommitForExit >= 60

        // Exit C: harde timeout (vangnet)
        val staleEpisode = peakEstimator.active &&
            episodeAgeMinutes > 240 &&
            ctx.recentSlope < 1.0 &&
            ctx.iobRatio < 0.30

        if (iobBasedExit || stableExhaustedExit || staleEpisode) {
            lastEpisodeExitAt = now  // bewaar voor grace-period herstart
            peakEstimator.active = false
            peakEstimator.state = PeakPredictionState.IDLE
            peakEstimator.confirmCounter = 0
            earlyDose = EarlyDoseContext()
            earlyConfirmDone = false
            sensorBlipStreakCount = 0
            recentBgHistory.clear()
            activeMealEpisodeId = -1
            mealEpisodeStartTime = null
            mealEpisodeStartBg = null
            episodeCommitCount = 0
            episodeBoostBudgetU = 0.0
            episodePeakCommitU = 0.0
            episodeHypoDebtU = 0.0
            episodePeakRecentSlope = 0.0
            rapidDecelLocked = false
            rapidDecelConfirm = 0
        }
        if (peakEstimator.active && !episodeShouldBeActive) {
            // Originele exit: duidelijke daling of BG dicht bij target
            val fallingClearly = ctx.slope <= -0.6 && ctx.consistency >= config.episodeMinConsistency
            val lowDelta = ctx.deltaToTarget < 0.2 && ctx.acceleration <= 0.0
            if (fallingClearly || lowDelta) {
                peakEstimator.active = false
                peakEstimator.state = PeakPredictionState.IDLE
                peakEstimator.confirmCounter = 0
                earlyDose = EarlyDoseContext()
                earlyConfirmDone = false
                sensorBlipStreakCount = 0
                recentBgHistory.clear()
            }
        }

        // ── update memory features ──
        val last = peakEstimator.lastAt ?: now
        val dtMin = org.joda.time.Minutes.minutesBetween(last, now).minutes.coerceAtLeast(0)
        val dtH = (dtMin / 60.0).coerceAtMost(0.2) // cap dt om rare jumps te dempen

        peakEstimator.lastAt = now

        if (peakEstimator.active && dtH > 0.0) {
            peakEstimator.maxSlope = maxOf(peakEstimator.maxSlope, ctx.slope.coerceAtLeast(0.0))
            peakEstimator.maxAccel = maxOf(peakEstimator.maxAccel, ctx.acceleration.coerceAtLeast(0.0))

            val pos = maxOf(0.0, ctx.slope) * dtH             // mmol/L
            peakEstimator.posSlopeArea += pos

            // momentum met half-life (zodat korte plateaus niet meteen alles resetten)
            val halfLifeMin = config.peakMomentumHalfLifeMin
            val decay = Math.pow(0.5, dtMin / halfLifeMin.coerceAtLeast(1.0))
            peakEstimator.momentum = peakEstimator.momentum * decay + pos
        }

        val riseSinceStart =
            if (peakEstimator.active) (ctx.input.bgNow - peakEstimator.startBg).coerceAtLeast(0.0) else 0.0

// ── peak voorspelling (v2): iob-aware ballistic met adaptive horizon ──

// We gebruiken jouw bestaande riseSinceStart (die hierboven al is berekend)
        val bgNow = ctx.input.bgNow

// 0.35..1.0 op basis van hoe “ver” de episode al is (2.0 mmol stijging = volledig)
        val riseFrac = (riseSinceStart / 2.0).coerceIn(0.35, 1.0)

// iob hoog => kortere horizon (insuline gaat de stijging waarschijnlijk afremmen).
        // Coëfficiënt verlaagd van 0.6 naar 0.35: bij hoge IOB tijdens een actieve
        // maaltijd duurt de rise vaak nog 45-60 min. Een te korte horizon
        // onderschat de piek structureel.
        val iobScale = (1.0 - 0.35 * ctx.iobRatio).coerceIn(0.35, 1.0)

// effectieve horizon (uren)
        val hEff = config.peakPredictionHorizonH * riseFrac * iobScale

// v5: korte-termijn snelheid uit delta5m (mmol/5m -> mmol/uur)
        val v5Raw = ctx.recentDelta5m * 12.0

// Conservatief: geen negatieve stijging gebruiken voor peak,
// maar laat v5 ook niet compleet naar 0 klappen door mini-ruis
        val v5 = v5Raw.coerceIn(0.0, 6.0)
        val vMacro = ctx.slope.coerceIn(-2.0, 6.0)

// Veiligste snelheid blijft: kleinste van macro en short-term
// (maar met een heel kleine vloer zodat predictedPeak niet "stuck" raakt)
        // Detecteer herstel: recent stijgt, macro nog niet mee
        val recoveryMode = v5 > 2.0 && vMacro < v5 * 0.5 && peakEstimator.active

        val v = when {
            recoveryMode -> v5 * 0.85  // Vertrouw meer op recent bij herstel
            ctx.consistency >= config.minConsistency && v5 > vMacro ->
                maxOf(0.10, vMacro * 0.3 + v5 * 0.7)  // 70/30, niet 50/50
            else ->
                maxOf(0.10, minOf(vMacro, v5))
        }

// ✅ Conservatief verbeteren: negatieve accel deels meenemen.
// Bij afremmen (accel < 0) mag predictedPeak sneller omlaag.
// We nemen bijv. 50% van negatieve accel mee, maar clampen hard.
        val aPos = ctx.acceleration.coerceAtLeast(0.0)
        val aNeg = ctx.acceleration.coerceAtMost(0.0)
        val a = (aPos + 0.50 * aNeg).coerceIn(-0.25, 0.60)

// Ballistic projectie
        var predictedPeakBallistic =
            bgNow + v * hEff + 0.5 * a * hEff * hEff

// ─────────────────────────────────────────────
// 🧠 IOB-aware correctie (AAPS future projection)
// ─────────────────────────────────────────────

        val futureDrop60 =
            computeFutureInsulinDrop60m(
                now = now,
                effectiveISF = ctx.input.effectiveISF
            )

// horizon factor (0..1) — als hEff 0.4h is, dan is het raar om 1.0h drop volledig af te trekken
        val horizonFrac = hEff.coerceIn(0.15, 1.0)

// alleen een deel van de 60m-drop aftrekken
        val futureDropScaled = futureDrop60 * horizonFrac

// cap: future drop mag niet vrijwel alle ballistic “rise” cancellen
        val ballisticRise = (predictedPeakBallistic - bgNow).coerceAtLeast(0.0)
        val futureDropCapped = futureDropScaled.coerceAtMost(ballisticRise * 0.85 + 0.4)

        var predictedPeak = predictedPeakBallistic - futureDropCapped

        // Extra floor zolang macro echt stijgt (voorkomt "pred=bg" bij meal-rise).
        // Schaal de floor omlaag naarmate IOB hoger is: bij hoge IOB is de
        // verwachte extra stijging van 1.2 mmol onzeker — de insuline kan de
        // maaltijdrise al aan het ombuigen zijn. Floor verdwijnt geleidelijk
        // tussen iobRatio=0.50 (vol) en 0.80 (bijna weg).
        var peakFloorActive = false
        var peakFloorValue = 0.0
        if (ctx.slope >= 0.8 && ctx.acceleration >= 0.0 && ctx.deltaToTarget >= 1.0) {
            val floorScale = (1.0 - smooth01((ctx.iobRatio - 0.50) / 0.30))
            val floorExtra = 1.2 * floorScale
            if (floorExtra > 0.01) {                          // alleen als er een betekenisvolle floor is
                val floorCandidate = bgNow + floorExtra
                if (floorCandidate > predictedPeak) {
                    peakFloorActive = true
                    peakFloorValue = floorExtra
                    predictedPeak = floorCandidate
                }
            }
        }

        // ─────────────────────────────────────────────
// ✅ PEAK STATE TRANSITIONS (NOW ACTUALLY CHANGES state)
// Place: right after predictedPeak is finalized, BEFORE band calculation
// ─────────────────────────────────────────────

// 1) Basic clamps so predictedPeak can't be below bgNow (prevents weird "nearPeak" logic)
        predictedPeak = maxOf(predictedPeak, bgNow)

// 2) Signals
        val strongRise =
            ctx.slope >= 0.6 ||
                ctx.recentSlope >= 0.8 ||
                ctx.recentDelta5m >= 0.10

        val flattening =
            (ctx.acceleration <= 0.05) &&
                (ctx.recentSlope <= 0.25) &&
                (kotlin.math.abs(ctx.recentDelta5m) <= 0.05)

        val nearPredictedTop =
            (predictedPeak - bgNow) <= 0.8

// 3) State machine
        when (peakEstimator.state) {

            PeakPredictionState.IDLE -> {
                // Start watching only when episode is active AND predicted peak is meaningfully high
                if (
                    peakEstimator.active &&
                    ctx.consistency >= config.minConsistency &&
                    predictedPeak >= 9.0 &&
                    strongRise
                ) {
                    peakEstimator.state = PeakPredictionState.WATCHING
                    peakEstimator.confirmCounter = 0
                }
            }

            PeakPredictionState.WATCHING -> {
                // Confirm only when we're near the top AND flattening is real (not one random sample)
                if (peakEstimator.active && nearPredictedTop && flattening) {
                    peakEstimator.confirmCounter += 1
                    if (peakEstimator.confirmCounter >= 2) {   // 2 cycles confirm hysteresis
                        peakEstimator.state = PeakPredictionState.CONFIRMED
                    }
                } else {
                    peakEstimator.confirmCounter = 0
                }

                if (!peakEstimator.active) {
                    peakEstimator.state = PeakPredictionState.IDLE
                    peakEstimator.confirmCounter = 0
                }
            }

            PeakPredictionState.CONFIRMED -> {
                if (!peakEstimator.active) {
                    peakEstimator.state = PeakPredictionState.IDLE
                    peakEstimator.confirmCounter = 0
                }
            }
        }



        val band = when {
            predictedPeak >= 20.0 -> 20
            predictedPeak >= 15.0 -> 15
            predictedPeak >= 12.0 -> 12
            predictedPeak >= 9.0  -> 10
            else -> 0
        }

        return PeakEstimate(
            state = peakEstimator.state,
            predictedPeak = predictedPeak,
            peakBand = band,
            maxSlope = peakEstimator.maxSlope,
            momentum = peakEstimator.momentum,
            riseSinceStart = riseSinceStart,
            futureDrop60 = futureDrop60,
            predictedPeakBallistic = predictedPeakBallistic,
            peakFloorActive = peakFloorActive,
            peakFloorValue = peakFloorValue,
            hEff = hEff,
            iobScaleUsed = iobScale,
            vUsed = v
        )
    }

    private val profileParamLogger =
        FCLvNextParameterLogger(
            context = context,
            fileName = "FCLvNext_ProfileParameters.csv"
        ) {
            FCLvNextProfileParameterSnapshot.collect(preferences)
        }


    private fun buildContext(input: FCLvNextInput, config: FCLvNextConfig): FCLvNextContext {
        val filteredHistory = FCLvNextBgFilter.ewma(
            data = input.bgHistory,
            alpha = config.bgSmoothingAlpha
        )
        val sortedHistory = input.bgHistory.sortedBy { it.first.millis }

        val rawPoints = sortedHistory.map { (t, bg) -> FCLvNextTrends.BGPoint(t, bg) }
        val filteredPoints = filteredHistory.sortedBy { it.first.millis }
            .map { (t, bg) -> FCLvNextTrends.BGPoint(t, bg) }


        val trends = FCLvNextTrends.calculateTrends(
            rawData = rawPoints,
            filteredData = filteredPoints
        )


        val iobRatio = if (input.maxIOB > 0.0) {
            (input.currentIOB / input.maxIOB).coerceIn(0.0, 1.5)
        } else 0.0

        // ── IOB-lag compensatie via AAPS persistenceLayer ─────────────────────
        // De Medtrum patch-pomp heeft een vertraging van 3–8 min tussen levering
        // en verwerking in de AAPS IOB-teller. Bij snelle opeenvolgende commits
        // loopt de gerapporteerde IOB daardoor sterk achter op de werkelijk
        // geleverde insuline.
        //
        // pendingBolusU10min: berekend in DetermineBasalFCL via FclRealDoseTracker
        // (AAPS persistenceLayer — identiek aan AAPS IOB-bron, inclusief oref0-SMBs).
        // = bolussen gegeven 8-10 min geleden minus wat al in 0-8 min staat.
        // Dit is nauwkeuriger dan FCLvNext's interne deliveryHistory die alleen
        // FCL-eigen doses bijhoudt (max 7 entries, geen oref0-fallback).
        //
        // Analyse 29/06/2026 (Ecko): bij 17:00 UTC was iobRatio=0.21 terwijl
        // al 5.70U was gegeven. Met pendingBolusU10min=2.62U → effectiveIobRatio=0.46
        // → commit werd sterk geremd. Zie ook Fix 1 (wffHypoBlokkade).
        val pendingIob = input.pendingBolusU10min

        val effectiveIobRatio = if (input.maxIOB > 0.0 && pendingIob > 0.05) {
            val effectiveIob = input.currentIOB + pendingIob
            (effectiveIob / input.maxIOB).coerceIn(iobRatio, 1.5) // nooit lager dan gerapporteerd
        } else iobRatio

        return FCLvNextContext(
            input = input,
            slope = trends.firstDerivative,
            acceleration = trends.secondDerivative,
            consistency = trends.consistency,
            recentSlope = trends.recentSlope,
            recentDelta5m = trends.recentDelta5m,
            curveFitR2 = trends.curveFitR2,
            curveAcceleration = trends.curveAcceleration,
            iobRatio = effectiveIobRatio,   // lag-gecorrigeerde iobRatio
            deltaToTarget = input.bgNow - input.targetBG
        )
    }

    private fun handleReserveResetAndTtl(
        didCommitThisCycle: Boolean,
        now: DateTime,
        status: StringBuilder
    ) {
        if (reservedInsulinU <= 0.0) return

        if (didCommitThisCycle) {
            status.append("RESERVE RESET: new commit this cycle\n")
            reservedInsulinU = 0.0
            reserveAddedAt = null
            reserveCause = null
            return
        }

        val ageMin = minutesSince(reserveAddedAt, now)
        if (ageMin >= RESERVE_TTL_MIN) {
            status.append(
                "RESERVE EXPIRED: age=${ageMin}m >= ${RESERVE_TTL_MIN}m " +
                    "(cause=${reserveCause})\n"
            )
            reservedInsulinU = 0.0
            reserveAddedAt = null
            reserveCause = null
        }
    }


    // Externe bolus schatting (IOB-delta methode)
    // Roep aan vanuit DetermineBasalFCL VOOR getAdvice().
    // Geeft de geschatte externe bolus (U) terug op basis van IOB-stijging
    // boven wat FCLvNext zelf de vorige cyclus heeft gegeven.
    fun estimateExternalBolus(currentIob: Double): Double {
        val result = if (prevIobForExternalDetect >= 0.0) {
            val iobDelta = currentIob - prevIobForExternalDetect
            val external = (iobDelta - prevFclDoseForExternalDetect).coerceAtLeast(0.0)
            external
        } else 0.0
        prevIobForExternalDetect = currentIob
        prevFclDoseForExternalDetect = 0.0
        return result
    }

    // ================================================    Get Advice ++++++++++++++++++++++++++++++++++++++
    @SuppressLint("SuspiciousIndentation") fun getAdvice(input: FCLvNextInput): FCLvNextAdvice {
        // reset reserve logging per cycle
        reserveActionThisCycle = "NONE"
        reserveDeltaThisCycle = 0.0

        // Snapshot vóór deze cyclus' eigen commit-beslissing: de post-big-
        // commit afterload-laag (zie hieronder) moet reageren op de VORIGE
        // grote commit, niet op een commit die deze cyclus zelf zojuist
        // heeft gezet — anders zou elke grote eerste bolus zichzelf
        // onmiddellijk afremmen.
        val lastBigCommitAtSnapshot = lastBigCommitAt
        val lastBigCommitDoseSnapshot = lastBigCommitDose

        val now = DateTime.now()
        val logRow = FCLvNextCsvLogRow(
            ts = now,
            isNight = input.isNight,
            bg = input.bgNow,
            target = input.targetBG,
            realDeliveredBasalU = input.realDeliveredBasalU,
            realDeliveredBolusU = input.realDeliveredBolusU,
            profileBasalUH = input.profileBasalUH,
            activityActive = input.activityActive,
            activityInsulinPct = input.activityInsulinPct,
            activityTargetAdjust = input.activityTargetAdjust,
            aapsMultiplier = input.aapsMultiplier
        )
        val status = StringBuilder()


        // ─────────────────────────────────────────────
        // 1️⃣ Config & context (trends, IOB, delta)
        var config = loadFCLvNextConfig(preferences, input.isNight, input.effectiveISF)
        lastActiveConfig = config

        // ─────────────────────────────────────────────
        // 🍽️⏰ Maaltijd-tijd-anticipatie — ALLEEN de leerkant zit hier
        // (05/07/2026, Ecko, herzien na architectuurcorrectie) ──────────────
        // De daadwerkelijke target-verlaging gebeurt in DetermineBasalFCL.kt,
        // exact zoals FCLActivityModule dat al doet voor activiteit: lokaal
        // op targetMgdl toegepast vóórdat FCLvNextInput wordt gebouwd — dus
        // input.targetBG komt hier al aangepast binnen als het venster actief
        // is. FCLvNext.kt hoeft dat venster dus niet zelf opnieuw te
        // evalueren; het bewaart alleen `isWeekendNow` voor de opname-hook
        // verderop (die legt vast WANNEER een CONFIRMED maaltijd plaatsvond,
        // los van of de anticipatie deze cyclus toevallig actief was).
        val isWeekendNow = app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextDayNightHelper.isWeekendDay(
            now.dayOfWeek, preferences.get(StringKey.WeekendDagen)
        )

        // ─────────────────────────────────────────────

        val ctx = buildContext(input, config)
        var pred60 = predictBg60(ctx)

        val zoneEnum = computeBgZone(ctx)

        logRow.guardIobLimited = false
        logRow.guardPeakLimited = false
        logRow.guardMaxSmbLimited = false
        logRow.guardMinDeliverClipped = false
        logRow.guardZoneLimited = false


        // S/T/V/N percentages — actieve waarden uit prefs
        logRow.sterktePct        = preferences.get(IntKey.fcl_vnext_sterkte)
        logRow.timingPct         = preferences.get(IntKey.fcl_vnext_timing)
        logRow.volhoudendheidPct = preferences.get(IntKey.fcl_vnext_volhoudendheid)
        logRow.nachtFactorPct    = DFLearner.getNfLevel(context).toInt()  // nfLevel als proxy (legacy kolom, lage precisie — zie nf_level_geleerd/effectief hieronder)
        logRow.doseDistributionStyle = config.doseDistributionStyle
        logRow.nightResponseStyle    = "NF${config.nfLevel.toInt()}"  // nfLevel als label

        // NF: geleerd vs effectief (incl. handmatige Nacht-Agressiviteit-
        // offset) apart loggen, met volle precisie — i.t.t. nachtFactorPct
        // hierboven (Int, en alleen de geleerde waarde). Pas toegevoegd
        // 19/06/2026 bij het loskoppelen van schuif en geleerde waarde; dat
        // onderscheid was tot nu toe nergens zichtbaar in de log.
        logRow.nfLevelGeleerd    = DFLearner.getNfLevel(context)
        logRow.nfLevelEffectief  = config.nfLevel
        logRow.nachtAggressiviteit = DFLearner.getNachtAggressiviteit(context)

        // De 6 sub-parameters die NF 's nachts daadwerkelijk afleidt
        // (applyNightResponseStyle in FCLvNextConfig.kt) — overdag staan ze
        // op hun ongemoeide basiswaarde (geen NF-effect), 's nachts tonen ze
        // het NF-gemoduleerde resultaat. Tot nu toe alleen impliciet
        // herleidbaar via de formule, nooit rechtstreeks gelogd.
        logRow.nightStagnationDeltaMin        = config.stagnationDeltaMin
        logRow.nightStagnationEnergyBoost     = config.stagnationEnergyBoost
        logRow.nightPersistentAggressionMul   = config.persistentAggressionMul
        logRow.nightCooldownMinutes           = config.smallCorrectionCooldownMinutes
        logRow.nightCorrectionHoldDeltaMax    = config.correctionHoldDeltaMax
        logRow.nightAbsorptionDoseFactor      = config.absorptionDoseFactor

        logRow.bgZone = zoneEnum.name
        logRow.iob = input.currentIOB
        logRow.iobRatio = ctx.iobRatio   // lag-gecorrigeerd via effectiveIobRatio

        // Log IOB-lag compensatie als die actief is (zichtbaar in decision_reason)
        val pendingIobLog = input.pendingBolusU10min
        if (pendingIobLog > 0.05) {
            val rawIobRatio = if (input.maxIOB > 0.0) input.currentIOB / input.maxIOB else 0.0
            status.append(
                "IOB-lag: gerapporteerd=${"%.2f".format(input.currentIOB)}U " +
                    "pending=${"%.2f".format(pendingIobLog)}U " +
                    "effectief=${"%.2f".format(input.currentIOB + pendingIobLog)}U " +
                    "(ratio ${"%.2f".format(rawIobRatio)}→${"%.2f".format(ctx.iobRatio)})\n"
            )
        }

        logRow.slope = ctx.slope
        logRow.accel = ctx.acceleration
        logRow.recentSlope = ctx.recentSlope
        logRow.recentDelta5m = ctx.recentDelta5m
        logRow.consistency = ctx.consistency
        logRow.curveFitR2 = ctx.curveFitR2
        logRow.curveAcceleration = ctx.curveAcceleration

        logRow.watchingFrontloadTriggered = false
        logRow.watchingFrontloadTargetU = 0.0
        logRow.watchingSlopeOk = false
        logRow.watchingDeltaOk = false
        logRow.watchingPeakRiseOk = false
        logRow.watchingIobOk = false


        status.append("S=${preferences.get(IntKey.fcl_vnext_sterkte)} T=${preferences.get(IntKey.fcl_vnext_timing)} V=${preferences.get(IntKey.fcl_vnext_volhoudendheid)} NF=${DFLearner.getNfLevel(context).toInt()}\n")
        status.append("DIST=${config.doseDistributionStyle} NF=${config.nfLevel.toInt()}\n")


        // ─────────────────────────────────────────────
        // 3️⃣ Energie-model (positie + snelheid + versnelling)
        // ─────────────────────────────────────────────
        val energyResult = calculateEnergy(
            ctx = ctx,
            kDelta = config.kDelta,
            kSlope = config.kSlope,
            kAccel = config.kAccel,
            config = config
        )
        var energy = energyResult.total

        // ─────────────────────────────────────────────
        // 🔒 ENERGY EXHAUSTION GATE (post-rise hard stop)
        // ─────────────────────────────────────────────
        val energyExhausted =
            ctx.deltaToTarget >= 4.5 &&            // duidelijk boven target
                ctx.acceleration in -0.05..0.05 &&             // versnelling vrijwel weg
                ctx.slope >= 0.8 &&                     // slope hoog door historie
                ctx.iobRatio >= 0.55 &&                 // al voldoende insulin aan boord
                ctx.consistency >= config.minConsistency



        if (energyExhausted) {
            status.append("ENERGY EXHAUSTED (log-only)\n")
            // energy blijft onaangetast
        }

        val stagnationBoost =
            calculateStagnationBoost(ctx, config)

        val energyTotal = energy + stagnationBoost

        status.append(
            "StagnationBoost=${"%.2f".format(stagnationBoost)}\n"
        )


        // ─────────────────────────────────────────────
        // 4️⃣ Ruwe dosis uit energie
        // ─────────────────────────────────────────────
        val rawDose = energyToInsulin(
            energy = energyTotal,
            effectiveISF = input.effectiveISF,
            config = config
        )
        status.append("RawDose=${"%.2f".format(rawDose)}U\n")

        // ─────────────────────────────────────────────
        // 5️⃣ Beslissingslaag (hard stop / force / soft allow)
        // ─────────────────────────────────────────────
        val decision = decide(ctx)

        val decidedDose = when {
            !decision.allowed -> 0.0
            decision.force -> rawDose
            else -> rawDose * decision.dampening
        }

        status.append(
            "Decision=${decision.reason} → ${"%.2f".format(decidedDose)}U\n"
        )

        // ─────────────────────────────────────────────
        // 6️⃣ IOB-remming (centraal, altijd toepassen)
        // ─────────────────────────────────────────────
        // ─────────────────────────────────────────────
        // 6a️⃣ Peak prediction (voor IOB-remming)
        // ─────────────────────────────────────────────


        val mealSignal = detectMealSignal(ctx, config)
        // Peak-estimator mag ook actief worden zonder mealSignal,
        // maar niet bij lage betrouwbaarheid
        if (ctx.consistency < config.minConsistency) {
            peakEstimator.active = false
        }
        val peak = updatePeakEstimate(config, ctx, mealSignal, now)

        // ── IOB-gecorrigeerde pred60 ──────────────────────────────────────
        // Corrigeer de trend-only pred60 met het bekende IOB-effect.
        // Bij een actieve maaltijd werkt carb-absorptie tégen de IOB-drop in,
        // daarom schalen we de correctie af op basis van meal state:
        //   CONFIRMED → 25% (carbs domineren, IOB-drop grotendeels geneutraliseerd)
        //   UNCERTAIN → 50% (onzeker: halve correctie)
        //   NONE      → 100% (geen carbs: volledige IOB-drop verwacht)
        // Dit maakt pred60 realistischer voor rescue-detectie en statusweergave.
        val iobCorrectionFrac = when (mealSignal.state) {
            MealState.CONFIRMED -> 0.25
            MealState.UNCERTAIN -> 0.50
            MealState.NONE      -> 1.00
        }
        pred60 -= peak.futureDrop60 * iobCorrectionFrac
        // Floor: pred60 mag niet onder bgNow zakken bij stijgende trend
        if (ctx.recentDelta5m > 0.02 || ctx.recentSlope > 0.2) {
            pred60 = maxOf(pred60, ctx.input.bgNow)
        }

        val predictedPeak = peak.predictedPeak
        val peakCategory = classifyPeak(predictedPeak)

        // ─────────────────────────────────────────────
// 🍽️ MEAL EPISODE TRACKING (for CSV analysis)
// ─────────────────────────────────────────────

        val episodeTrigger =
            mealSignal.state != MealState.NONE ||
                peakEstimator.active

// START nieuwe episode
        if (episodeTrigger && activeMealEpisodeId == -1L) {

            mealEpisodeCounter += 1
            activeMealEpisodeId = mealEpisodeCounter
            saveEpisodeCounter(mealEpisodeCounter)   // persistent — overleeft herstart
            mealEpisodeStartTime = now
            mealEpisodeStartBg = ctx.input.bgNow
            episodeCommitCount = 0
            episodeBoostBudgetU = 0.0
            episodePeakCommitU = 0.0
            episodeHypoDebtU = 0.0
            episodePeakRecentSlope = 0.0
            rapidDecelLocked = false
            rapidDecelConfirm = 0

            status.append("MEAL EPISODE START id=$activeMealEpisodeId\n")

            // Activity-logger callback (fire-and-forget, mag nooit dosering blokkeren)
            try {
                onEpisodeStarted?.invoke(
                    activeMealEpisodeId,
                    ctx.input.bgNow,
                    input.targetBG,
                    ctx.iobRatio,
                    input.currentIOB,
                    input.isNight,
                    input.externalBolusU
                )
            } catch (_: Exception) { /* stil falen */ }
        }

// EINDE episode
        if (!episodeTrigger && activeMealEpisodeId != -1L) {

            status.append("MEAL EPISODE END id=$activeMealEpisodeId\n")

            activeMealEpisodeId = -1
            mealEpisodeStartTime = null
            mealEpisodeStartBg = null
            episodeCommitCount = 0
            episodeBoostBudgetU = 0.0
            episodePeakCommitU = 0.0
            episodeHypoDebtU = 0.0
            episodePeakRecentSlope = 0.0
            rapidDecelLocked = false
            rapidDecelConfirm = 0
        }

        // ── Maaltijd-anticipatie: episode vastleggen zodra CONFIRMED (05/07/2026, Ecko) ──
        // Precies één keer per episode (dedupe via lastRecordedMealTimeEpisodeId),
        // op het moment dat mealSignal voor het eerst CONFIRMED is — niet bij
        // UNCERTAIN, om te voorkomen dat een later weer wegvallende hypothese
        // het geleerde patroon vervuilt. Gebruikt mealEpisodeStartTime (het
        // begin van de stijging) als het te leren tijdstip, niet het moment
        // waarop het algoritme zeker werd — dat laatste kan bij een langzame
        // stijging aanzienlijk later liggen.
        if (mealSignal.state == MealState.CONFIRMED &&
            activeMealEpisodeId != -1L &&
            activeMealEpisodeId != lastRecordedMealTimeEpisodeId &&
            mealEpisodeStartTime != null
        ) {
            mealTimeHistory = FclMealTimeAnticipation.record(
                mealTimeHistory,
                mealEpisodeStartTime!!.millis,
                isWeekendNow
            )
            saveMealTimeHistory(mealTimeHistory)
            lastRecordedMealTimeEpisodeId = activeMealEpisodeId
            status.append("MAALTIJD-ANTICIPATIE: episode $activeMealEpisodeId vastgelegd voor tijdpatroon-leren\n")
        }

        // ── Maaltijdtype update (elke cyclus tijdens episode) ─────────────
        if (activeMealEpisodeId != -1L && mealEpisodeStartTime != null) {
            val minSinceStart = org.joda.time.Minutes.minutesBetween(mealEpisodeStartTime, now).minutes
        }

        // ─────────────────────────────────────────────
        // 🧯 DOWN-TREND GATE (short-term trend lockout)
        // ─────────────────────────────────────────────
        val downGate = updateDowntrendGate(ctx, mealSignal, peak, config)
        status.append(downGate.reason + "\n")

        val trend = classifyTrendState(ctx, config)
        status.append(trend.reason + "\n")

        // ─────────────────────────────────────────────
        // 🔴 LONG-SLOPE SAFETY BLOCK (anti-hypo)
        // Blokkeer dosing bij sterke structurele daling,
        // ook als short-term ruis het maskeert.
        //
        // KERING-DETECTIE: het blok wordt opgeheven als BG aantoonbaar keert.
        // Vereist TWEE opeenvolgende stijgende metingen (niet één — sensorspike
        // geeft maar één verhoogde meting, een echte kering geeft twee of meer).
        // ─────────────────────────────────────────────

        val watchingOrConfirmed =
            (peak.state == PeakPredictionState.WATCHING || peak.state == PeakPredictionState.CONFIRMED)

        val allowDespiteLongSlope =
            zoneEnum == BgZone.EXTREME && watchingOrConfirmed && ctx.recentSlope > 0.0

        // Kering-detectie: minimaal 2 opeenvolgende stijgende BG-metingen
        // vereist om het blok op te heffen. Dit filtert sensorspikes eruit
        // (één afwijkende meting) maar herkent een echte BG-kering tijdig.
        val bgHistory = ctx.input.bgHistory
        val bgRisingCount = if (bgHistory.size >= 3) {
            // Tel hoeveel van de laatste metingen aaneengesloten stijgend zijn
            var count = 0
            for (i in bgHistory.indices.reversed().drop(1)) {
                if (i + 1 < bgHistory.size && bgHistory[i].second > bgHistory[i + 1].second) {
                    count++
                } else break
            }
            count
        } else 0
        val confirmedReversal = bgRisingCount >= 2

        val hardNoDelivery =
            downGate.pauseThisCycle ||
                (
                    !allowDespiteLongSlope &&
                        !confirmedReversal &&          // blok opgeheven bij bevestigde kering
                        ctx.slope <= -1.0 &&
                        ctx.recentSlope <= 0.0 &&
                        ctx.recentDelta5m <= 0.0 &&
                        ctx.deltaToTarget <= 3.0 &&
                        ctx.consistency >= 0.55
                    )

        if (hardNoDelivery) {

            val reason =
                if (downGate.pauseThisCycle && !isMacroRising(ctx, peak, config)) {
                    "SHORT-TERM DIP: recentSlope=${"%.2f".format(ctx.recentSlope)} " +
                        "recentΔ5m=${"%.2f".format(ctx.recentDelta5m)}"
                } else {
                    "LONG-SLOPE BLOCK: slope=${"%.2f".format(ctx.slope)} " +
                        "delta=${"%.2f".format(ctx.deltaToTarget)} rising=${bgRisingCount}"
                }

            status.append("$reason → handoff to AAPS\n")

            // ⬇️ LOGROW VULLEN
            logRow.decisionReason = reason
            logRow.finalDose = 0.0
            logRow.commandedDose = 0.0
            logRow.deliveredTotal = 0.0
            logRow.bolus = 0.0
            logRow.basalRate = 0.0
            logRow.shouldDeliver = false

            // ⬇️ LOGGEN

            cycleLogRepository.insert(logRow.toEntity())
            // ⬇️ ÉÉN return

            return FCLvNextAdvice(
                bolusAmount = 0.0,
                basalRate = 0.0,
                shouldDeliver = false,
                effectiveISF = input.effectiveISF,
                targetAdjustment = 0.0,

                predictedPeak = predictedPeak,
                peakBand = peak.peakBand,
                peakState = peak.state.name,
                secondDerivative = ctx.acceleration,

                statusText = status.toString()
            )
        }


// downGate.locked: NIET returnen, maar later dose=0 afdwingen (zie last line)



        status.append(
            "PeakEstimate=${peak.state} " +
                "pred=${"%.2f".format(peak.predictedPeak)} " +
                "cat=$peakCategory " +
                "band=${peak.peakBand} " +
                "maxSlope=${"%.2f".format(peak.maxSlope)} " +
                "mom=${"%.2f".format(peak.momentum)}\n"
        )

        // ✅ Pre-peak commit window: helpt IOB eerder opbouwen vóór de top
        // Sta vroege commits toe bij duidelijke herstel-signalen, ook zonder active episode
        val prePeakCommitWindow =
            (peak.state == PeakPredictionState.WATCHING ||
                (peak.state == PeakPredictionState.IDLE &&
                    ctx.recentSlope >= 1.5 &&
                    ctx.deltaToTarget >= 1.5)) &&
                peak.predictedPeak >= 9.5 &&  // ← Lager, 13.0 is te conservatief
                ctx.consistency >= 0.50 &&      // ← Iets lager
                ctx.iobRatio <= 0.60 &&          // ← Hoger, 0.50 is te strikt bij herstel
                ctx.acceleration >= -0.05         // ← Iets negatief toestaan bij herstel

        status.append("PrePeakCommitWindow=${if (prePeakCommitWindow) "YES" else "NO"}\n")

        val peakIobBoost = when (peakCategory) {
            PeakCategory.EXTREME -> 1.55
            PeakCategory.HIGH    -> 1.40
            PeakCategory.MEAL    -> 1.25
            PeakCategory.MILD    -> 1.10
            PeakCategory.NONE    -> 1.00
        }

        val boostedIobRatio =
            (ctx.iobRatio / peakIobBoost).coerceAtLeast(0.0)

        val iobPower = if (input.isNight) config.iobPowerNight else config.iobPowerDay
        val iobFactor = iobDampingFactor(
            iobRatio = boostedIobRatio,
            config = config,
            power = iobPower
        )

        val commitIobFactor = iobDampingFactor(
            iobRatio = ctx.iobRatio,
            config = config,
            power = config.commitIobPower   // NIEUW, milder
        )
        logRow.commitIobFactor = commitIobFactor


        var finalDose =
            (decidedDose * iobFactor * config.doseStrengthMul)
                .coerceAtLeast(0.0)

        if (mealSignal.state == MealState.NONE && ctx.acceleration > 0.2 && ctx.iobRatio >= 0.75) {
            status.append("RISING IOB CAP → finalDose limited\n")
            finalDose = minOf(finalDose, 0.6 * config.maxSMB)
        }


        var accessLevel = computeDoseAccessLevel(ctx, zoneEnum)
        val mealAccessOverride =
            mealSignal.state != MealState.NONE || prePeakCommitWindow
        if (mealAccessOverride && accessLevel == DoseAccessLevel.BLOCKED && zoneEnum != BgZone.LOW) {
            status.append("ACCESS OVERRIDE: meal-like in-range → MICRO_ONLY\n")
            accessLevel = DoseAccessLevel.MICRO_ONLY
        }

        status.append("DoseAccess=$accessLevel\n")


// Universele caps als fractie van maxSMB
        val microCap = maxOf(0.05, config.microCapFracOfMaxSmb * config.maxSMB)
        val smallCap = maxOf(0.10, config.smallCapFracOfMaxSmb * config.maxSMB)

        val accessCap = when (accessLevel) {
            DoseAccessLevel.BLOCKED -> 0.0
            DoseAccessLevel.MICRO_ONLY -> microCap
            DoseAccessLevel.SMALL -> smallCap
            DoseAccessLevel.NORMAL -> Double.POSITIVE_INFINITY


        }

        when (accessLevel) {

            DoseAccessLevel.BLOCKED -> {
                status.append("ACCESS BLOCKED → finalDose=0\n")
                finalDose = 0.0
                logRow.guardZoneLimited = true
            }

            DoseAccessLevel.MICRO_ONLY -> {
                if (finalDose > microCap) {
                    status.append("ACCESS MICRO cap ${"%.2f".format(microCap)}\n")
                    finalDose = microCap
                    logRow.guardZoneLimited = true
                }
            }

            DoseAccessLevel.SMALL -> {
                if (finalDose > smallCap) {
                    status.append("ACCESS SMALL cap ${"%.2f".format(smallCap)}\n")
                    finalDose = smallCap
                    logRow.guardZoneLimited = true
                }
            }

            DoseAccessLevel.NORMAL -> {
                // geen beperking
            }
        }


// ─────────────────────────────────────────────
// 📈 SUSTAINED RISE TRACKING
// Bijhouden hoe lang slope al boven de drempel is.
// Wordt doorgegeven aan computeEarlyDoseDecision als sustainScore-input.
// ─────────────────────────────────────────────

        val sustainSlopeMin = config.sustainedRiseSlopeMin  // drempel (bijv. 0.35)
        val minutesSinceSustainUpdate = minutesSince(sustainedLastUpdateAt, now)
            .coerceIn(0, 15).toDouble()  // max 15 min per stap (beschermt tegen lange pauzes)

        if (ctx.slope >= sustainSlopeMin && ctx.slope > 0.0) {
            // Slope boven drempel: tel op hoeveel tijd is verstreken
            sustainedHighSlopeMinutes += minutesSinceSustainUpdate
        } else {
            // Slope gedaald onder drempel: reset teller
            sustainedHighSlopeMinutes = 0.0
        }
        sustainedLastUpdateAt = now

        // Veiligheidsreset: als IOB al hoog is, is de stijging vermoedelijk
        // al gecompenseerd → sustained trigger niet meer zinvol
        if (ctx.iobRatio >= 0.40) {
            sustainedHighSlopeMinutes = 0.0
        }

// ─────────────────────────────────────────────
// 📉 ACCELERATIE-TREND (decelereert de stijging, of houdt ze aan?)
// Zie kdoc bij accelAtUncertainEntry/updateAccelDeclineSinceUncertain hierboven.
// ─────────────────────────────────────────────

        val accelDeclineSinceUncertain = updateAccelDeclineSinceUncertain(mealSignal.state, ctx.acceleration)
        logRow.accelDeclineSinceUncertain = accelDeclineSinceUncertain

        logRow.sustainedHighSlopeMinutes = sustainedHighSlopeMinutes

// ─────────────────────────────────────────────
// 🚀 EARLY DOSE CONTROLLER (move earlier in pipeline)
// ─────────────────────────────────────────────

        // Bereken alvast de no-insulin projectie voor de hypo-debt compensatie.
        // De volledige hypoProtection check volgt later — dit is alleen de
        // projectie zónder insuline, puur voor de veiligheidscheck in earlyBoost.
        val hypoNoInsulinProjection = hypoProtection(
            ctx = ctx,
            plannedDoseU = 0.0,   // geen geplande dosis → pure trend-projectie
            effectiveISF = input.effectiveISF,
            config = config,
            mealSignal = mealSignal
        ).projectedMinNoInsulin

        val early = computeEarlyDoseDecision(
            ctx = ctx,
            mealSignal = mealSignal,
            peak = peak,
            trend = trend,
            bgZone = zoneEnum,
            now = now,
            config = config,
            sustainedHighSlopeMinutes = sustainedHighSlopeMinutes,
            accelDeclineSinceUncertain = accelDeclineSinceUncertain,
            hypoProjectedMinNoInsulin = hypoNoInsulinProjection,
            episodeHypoDebtU = episodeHypoDebtU
        )
        // Schrijf resterende schuld terug — de functie mag klasselid niet muteren
        episodeHypoDebtU = early.remainingDebtU
        status.append(early.reason + "\n")

        // Forceer early stage bij zeer snelle stijging
        if (ctx.recentSlope >= 8.0 && earlyDose.stage == 0) {
            earlyDose.stage = 1  // Forceer naar stage1
            status.append("EARLY FORCED to stage1 (recentSlope=${"%.1f".format(ctx.recentSlope)})\n")
        }

// candidate stage (zodat micro-hold early niet per ongeluk blokkeert)
        val earlyStageCandidate = maxOf(earlyDose.stage, early.stageToFire)

// ── Micro-correction hold: niet drip-feeden als BG al daalt/vlak is ──
        if (shouldBlockMicroCorrections(
                ctx,
                mealSignal,
                peakCategory,
                earlyStageCandidate,
                peak,
                config
            )
        ) {
            status.append(
                "HoldCorrections: slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)} " +
                    "delta=${"%.2f".format(ctx.deltaToTarget)} → finalDose=0\n"
            )
            finalDose = 0.0
        }


        val episodeMinutesForPostPeak =
            mealEpisodeStartTime?.let {
                org.joda.time.Minutes.minutesBetween(it, now).minutes
            } ?: 999

        // ── SensorBlip: BG-history bijhouden en streak tellen ────────────
        // Ringebuffer: bewaar laatste 3 ruwe BG-waarden
        if (recentBgHistory.size >= 3) recentBgHistory.removeFirst()
        recentBgHistory.addLast(ctx.input.bgNow)

        // bgRising3Cycles: alle 3 recentste delta's positief (elke meting hoger dan vorige)
        // Fallback: als de deque nog niet vol is (bijv. na sensor-gap of episode-reset),
        // gebruik dan recentSlope als proxy — een zeer snelle stijging is nooit een blip.
        val bgRising3Cycles = when {
            recentBgHistory.size >= 3 ->
                recentBgHistory[1] > recentBgHistory[0] &&
                    recentBgHistory[2] > recentBgHistory[1]
            recentBgHistory.size == 2 ->
                // Deque net opgebouwd na gap: twee stijgende metingen én snelle stijging
                recentBgHistory[1] > recentBgHistory[0] && ctx.recentSlope >= 3.0
            else ->
                // Slechts één meting: vertrouw op recentSlope (>= 5 = zeker geen blip)
                ctx.recentSlope >= 5.0
        }

        // Extra blip-guard: bij een zeer snelle stijging (> 0.8 mmol in 5 min) is het
        // biologisch onmogelijk dat dit een sensorblip is — schepijs/snelle koolhydraten
        // geven wel degelijk deze stijgsnelheid. Blip-detectie uitschakelen.
        val explosiveRise = ctx.recentDelta5m >= 0.80

        // blipBaseCondition voorberekening (zelfde logica als in evaluatePostPeak)
        val blipBaseNow = ctx.slope <= -0.30 &&
            ((ctx.recentSlope >= 1.50) || (ctx.recentDelta5m >= 0.20))
        if (blipBaseNow && !bgRising3Cycles && !explosiveRise) sensorBlipStreakCount++
        else sensorBlipStreakCount = 0

        val postPeak = evaluatePostPeak(
            ctx, mealSignal, peak, now, config,
            episodeMinutesForPostPeak,
            sensorBlipStreak = sensorBlipStreakCount,
            bgRising3Cycles  = bgRising3Cycles || explosiveRise,
            prevRecentSlope  = prevRecentSlopeForBrake
        )
        status.append(postPeak.reason + "\n")
        // bijwerken voor de volgende cyclus (computePeakBrake deceleratie-signaal)
        prevRecentSlopeForBrake = ctx.recentSlope

        // ✅ NIEUW: early reset zodra afremmen/omkeer start
        val earlyResetThisCycle = maybeResetEarlyOnDecel(ctx, peak, now, status)

        val suppressForPeak = postPeak.suppress

        // Sensor-blip guard (3-laags discriminator)
        if (postPeak.sensorBlip) {
            status.append(
                "SENSOR-BLIP GUARD: slope=${"%.2f".format(ctx.slope)} " +
                    "recentSlope=${"%.2f".format(ctx.recentSlope)} " +
                    "streak=$sensorBlipStreakCount/2 → finalDose=0\n"
            )
            finalDose = 0.0
        } else if (blipBaseNow) {
            // Blip-conditie aanwezig maar opgeheven door discriminator
            status.append(
                "SENSOR-BLIP LIFTED: streak=$sensorBlipStreakCount " +
                    "bgRising3=$bgRising3Cycles → dosering toegestaan\n"
            )
        }


// ─────────────────────────────────────────────
// 🍽️ MICRO RAMP (earlier IOB, no commit)
// ─────────────────────────────────────────────
        val microRamp = computeMicroRamp(ctx, config, peak)
        status.append(microRamp.reason + "\n")

        if (
            microRamp.active &&
            !suppressForPeak &&
            !postPeak.lockout
        ) {
            // Respecteer ACCESS-cap (MICRO_ONLY/SMALL/NORMAL)
            val microCapped = minOf(microRamp.microU, accessCap)

            if (microCapped > 0.0 && finalDose < microCapped) {
                status.append(
                    "MICRO APPLY (${microRamp.tier}): ${"%.2f".format(finalDose)}→${"%.2f".format(microCapped)}U\n"
                )
                finalDose = microCapped
            }
        }


        // ─────────────────────────────────────────────
        // 🟡 PRE-MEAL RISE MICRO-FLOOR (laatste vangnet)
        // ─────────────────────────────────────────────
        val preMealFloor = preMealRiseFloorU(
            ctx = ctx,
            mealSignal = mealSignal,
            bgZone = zoneEnum,
            suppressForPeak = suppressForPeak,
            stagnationActive = (stagnationBoost > 0.0),
            accessLevel = accessLevel,
            maxBolus = config.maxSMB,
            config = config
        )

        if (preMealFloor > 0.0 && finalDose < preMealFloor) {
            status.append(
                "PRE-MEAL FLOOR: ${"%.2f".format(finalDose)}→${"%.2f".format(preMealFloor)}U\n"
            )
            finalDose = preMealFloor
        }

// ── Trajectory damping: continu remmen o.b.v. BG / IOB / slope / accel ──
        if (shouldHardBlockTrajectory(
                ctx,
                mealSignal,
                earlyStageCandidate,
                peak,
                now,
                config
            )
        ) {
            status.append("Trajectory HARD BLOCK → finalDose=0\n")
            finalDose = 0.0
            logRow.trajectoryHardBlock = true
        } else {
            val fastLaneDip = (ctx.recentDelta5m <= -0.06 || ctx.recentSlope <= -0.20)
            val trajFactor =
                if (earlyStageCandidate > 0 && !fastLaneDip && !suppressForPeak && !postPeak.sensorBlip) {
                    status.append("Trajectory BYPASS (earlyStage=$earlyStageCandidate)\n")
                    1.0
                } else {
                    trajectoryDampingFactor(ctx, mealSignal, zoneEnum, config, peak, suppressForPeak)
                }


            val before = finalDose
            finalDose *= trajFactor
            logRow.trajectoryFactor = trajFactor
            logRow.trajectoryHardBlock = false
            status.append(
                "TrajectoryFactor=${"%.2f".format(trajFactor)} " +
                    "${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        }


        if (early.active && early.targetU > accessCap) {
            status.append("EARLY capped by ACCESS (${accessLevel})\n")
        }
        // ===============================================================================


        var earlyFiredThisCycle = false  // wordt true als early floor deze cyclus vuurt
// Apply early floor AFTER dampers (maar vóór cap/commit)
// ✅ NIET toepassen als we al aan het afremmen zijn (accel < 0)
        // Snapshot vóór deze cyclus' eigen boost-budget-update: de geboost
        // maxSMB-cap hieronder moet reageren op het budget dat AL was
        // opgebouwd door eerdere fires in deze episode, niet op het budget
        // inclusief de fire die nu net wordt berekend (kip-ei).
        val episodeBoostBudgetUSnapshot = episodeBoostBudgetU
        if (
            early.active &&
            early.targetU > 0.0 &&
            ctx.acceleration >= 0.0 &&
            !suppressForPeak &&
            !postPeak.sensorBlip &&
            !earlyResetThisCycle
        ) {

            val cappedEarly = minOf(early.targetU, accessCap)

            if (cappedEarly < early.targetU) {
                status.append(
                    "EARLY capped by ACCESS ($accessLevel): " +
                        "${"%.2f".format(early.targetU)}→${"%.2f".format(cappedEarly)}U\n"
                )
            }

            val before = finalDose
            finalDose = maxOf(finalDose, cappedEarly)

            earlyDose.stage = maxOf(earlyDose.stage, early.stageToFire)
            earlyDose.lastFireAt = now
            earlyDose.lastConfidence = early.confidence
            // Tel boosted commits mee zodat earlyBoostMaxCommits gerespecteerd wordt
            if (config.earlyBoostFactor > 1.0 + 1e-9 &&
                early.confidence >= config.earlyBoostMinConfidence) {
                earlyDose.boostCommitCount++
            }
            // Budget bijhouden voor de late-commit-decay (07/07/2026, Ecko — herzien).
            //
            // WAS: alleen het EarlyBoost-toeschrijfbare deel (boostExtraU) telde mee.
            // Zodra EarlyBoost zelf stopte met bijdragen (bijv. earlyBoostMaxCommits
            // bereikt), stopte deze teller met groeien — óók als er via de
            // basis-energieformule of highBgContinuation gewoon insuline bleef
            // toegediend. Gevolg, teruggevonden in de praktijkdata: bij episodes met
            // 5-6 commits na elkaar bleef effectiveDecay/lateDecayMul steken op
            // ongeveer hetzelfde niveau — vrijwel geen afname tussen de eerste en
            // de laatste commit, ook al bleef er in totaal veel insuline bijkomen.
            //
            // NU: telt de VOLLEDIGE, daadwerkelijk toegediende dosis deze cyclus
            // (finalDose), ongeacht welk mechanisme 'm veroorzaakte — zodat de
            // afbouw altijd een compleet beeld heeft van "hoeveel is er al gegeven".
            if (finalDose > 0.01) {
                episodeBoostBudgetU += finalDose
                status.append(
                    "BOOST BUDGET +${"%.2f".format(finalDose)}U " +
                        "→ totaal=${"%.2f".format(episodeBoostBudgetU)}U\n"
                )
            }

            // Early floor telt als 'commit 1' voor de decay-teller:
            // latere commit-path bolussen zijn dan commitNr ≥ 2 → decay actief
            if (finalDose > before) {
                episodeCommitCount++
                earlyFiredThisCycle = true
                // Onthoud tijdstip en grootte voor post-boost TBR-continuatie
                lastEarlyBoostAt = now
                lastEarlyBoostDoseU = finalDose
            }

            status.append(
                "EARLY FLOOR: ${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        } else if (early.active && early.targetU > 0.0 && earlyResetThisCycle) {
            status.append("EARLY FLOOR skipped (early reset this cycle)\n")
        } else if (early.active && early.targetU > 0.0 && (suppressForPeak || postPeak.sensorBlip)) {
            status.append("EARLY FLOOR skipped (postPeak suppress/sensorBlip)\n")
        }


        // ─────────────────────────────────────────────
        // 🟥 HEIGHT ESCALATION (single, smooth factor)
        // ─────────────────────────────────────────────
        // heightEscalationFactor is verwijderd (26/06/2026, Ecko): de piekdruk-
        // en momentum-logica is geabsorbeerd in effectiveBoostFactor binnen
        // computeEarlyDoseDecision() — zie commentaar aldaar. Eén mechanisme
        // per scenario is beter te onderhouden dan twee ongecoördineerde.


        // ─────────────────────────────────────────────
        // 🟦 PERSISTENT CORRECTION LOOP (dag + nacht)
        // ─────────────────────────────────────────────
        val baseMinDelta =
            if (ctx.input.isNight) 1.5 else 1.7

        val baseConfirmCycles = 2
        val pMul = config.persistentAggressionMul
        val effectiveMinDelta = (baseMinDelta / pMul).coerceIn(0.8, baseMinDelta)
        val effectiveConfirmCycles = (baseConfirmCycles / pMul).roundToInt().coerceAtLeast(1)

        val persistResult = persistCtrl.tickAndMaybeFire(
            tsMillis = now.millis,
            bgMmol = ctx.input.bgNow,
            targetMmol = ctx.input.targetBG,
            deltaToTarget = ctx.deltaToTarget,
            slope = ctx.slope,
            accel = ctx.acceleration,
            consistency = ctx.consistency,
            iob = ctx.input.currentIOB,
            iobRatio = ctx.iobRatio,

            maxBolusU = config.maxSMB,

            minDeltaToTarget = effectiveMinDelta,
            stableSlopeAbs = config.persistentSlopeAbs,
            stableAccelAbs = config.persistentAccelAbs,
            minConsistency = 0.45,
            confirmCycles = effectiveConfirmCycles,

            minDoseU = 0.05,
            iobRatioHardStop = 0.45
        )

        // ── V-learner input: log elke cyclus binnen een persistent-cluster ──
        // (active==true), zowel fired als cooldown-cycli. Aparte database
        // (FCLPersistDatabase) zodat dit onafhankelijk van de hoofd-CSV
        // geïtereerd kan worden zonder de 7-dagen cyclus-log te raken.
        if (persistResult.active) {
            cycleLogRepository.logPersistEvent(
                app.aaps.plugins.aps.openAPSFCL.vnext.persist.FCLPersistEventEntity(
                    timestampMs       = now.millis,
                    bgMmol            = ctx.input.bgNow,
                    targetMmol        = ctx.input.targetBG,
                    deltaToTarget     = ctx.deltaToTarget,
                    slope             = ctx.slope,
                    iobRatio          = ctx.iobRatio,
                    fired             = persistResult.fired,
                    doseU             = persistResult.doseU,
                    cooldownLeft      = persistResult.cooldownLeft,
                    persistentCounter = persistResult.persistentCounter,
                    escalationFactor  = persistResult.escalationFactor,
                    effectiveMinDelta = effectiveMinDelta,
                    stableSlopeAbs    = config.persistentSlopeAbs,
                    vExtraAtFire      = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
                        .getVExtra(context)
                )
            )
        }


        if (persistResult.active ) {
            status.append("PERSIST: ${persistResult.reason}\n")

            if (persistResult.fired) {
                finalDose = persistResult.doseU
                status.append(
                    "PERSIST APPLY: finalDose=${"%.2f".format(finalDose)}U\n"
                )
            } else {
                finalDose = 0.0
                status.append("PERSIST HOLD: finalDose=0\n")
            }
        }


        // 🔒 markeer dat persistent de leiding heeft
        val persistentOverrideActive = persistResult.active

        if (persistentOverrideActive) {
            status.append(
                "PERSIST MODE: HARD (cooldown=${persistResult.cooldownLeft})\n"
            )
        }




        // ─────────────────────────────────────────────
        // ⚡ EARLY CONFIRM IMPULSE (bridges early → commit)
        // ─────────────────────────────────────────────
        val earlyConfirm =
            !earlyConfirmDone &&
                earlyDose.stage >= 2 &&                 // we hadden al een early boost
                (trend.state == TrendState.RISING_CONFIRMED) &&     // <-- nieuw: harde gate
                ctx.slope >= 1.0 &&                     // duidelijke stijging
                ctx.acceleration >= 0.20 &&             // versnelling bevestigd
                ctx.deltaToTarget >= 2.0 &&             // echt boven target
                ctx.iobRatio <= 0.45 &&                 // nog ruimte
                ctx.consistency >= config.minConsistency &&
                peak.state != PeakPredictionState.CONFIRMED

        if (earlyConfirm) {
            val impulse =
                (0.6 * config.maxSMB)
                    .coerceAtLeast(0.3)
                    .coerceAtMost(config.maxSMB)

            val before = finalDose
            finalDose = maxOf(finalDose, impulse)

            earlyConfirmDone = true

            status.append(
                "EARLY-CONFIRM IMPULSE: slope=${"%.2f".format(ctx.slope)} " +
                    "accel=${"%.2f".format(ctx.acceleration)} → " +
                    "${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        }


        // ─────────────────────────────────────────────
        // 8️⃣ Absolute max SMB cap
        // ─────────────────────────────────────────────
        // EarlyBoost cap: als earlyBoost actief was en gevuurd heeft in deze cyclus,
        // mag finalDose oplopen tot maxSMB × earlyBoostFactor.
        // Zonder deze uitzondering heeft earlyBoostFactor nul effect — de ongebooste
        // target zit al dicht bij maxSMB en de cap pakt de verhoging er direct af.
        // Veiligheidsgrens: nooit meer dan maxSMB × 1.8, en alleen als iobRatio < 0.35.
        //
        // Budget-afbouw (17/06-incident): zonder correctie kreeg een TWEEDE
        // fire binnen dezelfde episode (13:50 en 14:15, 25 min uit elkaar)
        // opnieuw de volle 1.8× ruimte, ook al was de eerste fire net
        // gegeven. Bij een korte, niet-aanhoudende stijging (snoep/drop)
        // stapelde dit tot een te grote totale dosis. De eerste fire in een
        // episode behoudt de volle 1.8× (episodeBoostBudgetUSnapshot=0);
        // naarmate er binnen de episode al meer boost-budget is opgebouwd
        // (via eerdere fires), boogt de extra ruimte geleidelijk af richting
        // de normale maxSMB. Bij budget >= 0.5×maxSMB is de escape dicht.
        //
        // LET OP — bewuste afweging, geen garantie: vergelijkbare episodes
        // met meerdere grote, legitieme boost-fires komen wel degelijk voor
        // (bv. 11/06 14:00, vrijwel identieke curve/uitkomst als het 17/06-
        // incident, zonder gemeld probleem). Deze taper raakt zulke
        // episodes ook — dat is een geaccepteerd risico, geen blinde vlek.
        // Divisor 0.5× (i.p.v. een agressievere 0.3×) is bewust gekozen om
        // de impact op de eerste 1-2 vervolgfires te beperken; de eerste
        // fire in elke episode blijft altijd volledig ongewijzigd.
        val boostBudgetTaper =
            (1.0 - episodeBoostBudgetUSnapshot / (config.maxSMB.coerceAtLeast(0.01) * 0.5)).coerceIn(0.0, 1.0)
        val effectiveMaxSmb = if (
            early.boostActive &&
            earlyFiredThisCycle &&
            config.earlyBoostFactor > 1.01 &&
            ctx.iobRatio < 0.35
        ) {
            val boostedCap = minOf(config.maxSMB * config.earlyBoostFactor, config.maxSMB * 1.8)
            // Lerp tussen normale cap (taper=0) en volle boosted cap (taper=1)
            config.maxSMB + (boostedCap - config.maxSMB) * boostBudgetTaper
        } else {
            config.maxSMB
        }

        if (finalDose > effectiveMaxSmb) {
            val boosted = effectiveMaxSmb > config.maxSMB
            status.append(
                "Cap maxSMB ${"%.2f".format(finalDose)} → ${"%.2f".format(effectiveMaxSmb)}U" +
                    (if (boosted) " (earlyBoost verhoogd, budgetTaper=${"%.2f".format(boostBudgetTaper)})" else "") + "\n"
            )
            finalDose = effectiveMaxSmb
            logRow.guardMaxSmbLimited = !boosted  // alleen markeren als normale cap
        }

        // ─────────────────────────────────────────────
        // 🍽️ WATCHING FRONTLOAD (mealHandlingStyle)
        // ─────────────────────────────────────────────


// 1) Splits de voorwaarden in losse booleans (zodat we ze kunnen loggen)
        val watchingSlopeOk = (ctx.slope >= config.watchingMinSlope)
        val watchingDeltaOk = (ctx.deltaToTarget >= config.watchingMinDeltaToTarget)
        val watchingPeakRiseOk = (peak.riseSinceStart >= config.watchingMinPeakRise)
        // watchingMaxIobRatio wordt verlaagd als futureDrop60 hoog is:
        // Als het algoritme al weet dat er een grote IOB-gedreven daling verwacht wordt,
        // moet watching eerder stoppen om te voorkomen dat er te veel insuline
        // gegeven wordt terwijl de daling al gebakken zit in de huidige IOB.
        // futureDrop60 in mmol: bij 0.56 mmol geen effect, bij 1.36 mmol max -0.15 verlaging.
        val fd60ForWatching = peak.futureDrop60  // mmol
        val watchingIobAdjustment = if (fd60ForWatching > 0.56) {
            ((fd60ForWatching - 0.56) / 0.80).coerceIn(0.0, 1.0) * 0.15
        } else 0.0
        val effectiveWatchingMaxIobRatio = config.watchingMaxIobRatio - watchingIobAdjustment
        val watchingIobOk = (ctx.iobRatio <= effectiveWatchingMaxIobRatio)

        // earlyConfirmedRise: WFF mag ook triggeren vóór peak.state==WATCHING
        // als meermaals bevestigde stijging aanwezig is (Fix C, 29/06/2026).
        val earlyConfirmedRise =
            mealSignal.state == MealState.CONFIRMED &&
                ctx.slope >= 2.5 &&
                ctx.recentSlope >= 2.0 &&
                ctx.acceleration >= 0.06 &&
                ctx.iobRatio <= config.watchingMaxIobRatio

        // Vroege hypo-guard voor WFF: als de trend-projectie zónder insuline al
        // richting hypo wijst, mag de WFF niet triggeren.
        // hypoNoInsulinProjection is al berekend vóór dit punt (puur trend-projectie,
        // geen geplande dosis). Bij een actieve hypo-dreiging is elke extra insuline
        // gevaarlijk, ook als de BG tijdelijk stijgt door snoep of maaltijd.
        //
        // Analyse 29/06/2026 (Ecko): WFF vuurde om 17:25 en 17:50 UTC met
        // hypo_active=True en IOB 4.35-5.93U. De HypoProtection zette commitDose=0
        // maar watchingTarget=2.39U won via max() — de WFF bypaste de hypo-rem.
        // Totaal 3.86U extra insuline bij een al hyperinsulinemische situatie →
        // nadir 3.6 mmol om 20:54 UTC.
        //
        // Drempel: hypoNoInsulinProjection < hypoBlockThreshold (standaard 4.8 mmol).
        // Gebruik de no-insulin projectie zodat we reageren op de daadwerkelijke
        // IOB-trend, niet op een momentopname van de BG.
        val wffHypoBlokkade = hypoNoInsulinProjection < config.hypoBlockThreshold

        val watchingContextOk =
            ((peak.state == PeakPredictionState.WATCHING) || earlyConfirmedRise) &&
                !suppressForPeak &&
                !postPeak.lockout &&
                !postPeak.sensorBlip &&
                (zoneEnum != BgZone.LOW) &&
                !wffHypoBlokkade   // WFF geblokkeerd bij dreigende hypo

// 2) Bepaal target (ook als het niet triggert -> handig voor analyse)
        // WFF-target schalen op basis van earlyBoost budget:
        // Als earlyBoost substantieel heeft gegeven in de vroege fase, dan
        // geeft WFF minder — insuline is al naar voren gehaald, de late fase
        // hoeft minder bij te dragen. Dit geeft het gewenste afnemende patroon:
        //   zonder budget: WFF = maxSMB × wff_frac (bijv. 1.25 × 0.74 = 0.93U)
        //   budget = 0.5U: schaling = max(0.50, 1.0 - 0.5/2.50) = 0.80 → 0.74U
        //   budget = 1.0U: schaling = max(0.50, 1.0 - 1.0/2.50) = 0.60 → 0.56U
        //   budget = 2.0U: schaling = max(0.50, 1.0 - 2.0/2.50) = 0.50 → 0.47U (minimum 50%)
        // wffBudgetScaling minimum: normaal 0.50 maar bij hoge predicted peak
        // (>=11.0 mmol) wordt het minimum 0.65. Dit voorkomt dat de watching
        // frontload target te klein wordt juist als de piek hoog wordt verwacht.
        // Bij pred_peak>=12.5: minimum 0.75 voor nog meer ruimte.
        val wffScalingMin = when {
            peak.predictedPeak >= 12.5 -> 0.75
            peak.predictedPeak >= 11.0 -> 0.65
            else                       -> 0.50
        }
        val wffBudgetScaling = if (episodeBoostBudgetU > 0.1) {
            (1.0 - episodeBoostBudgetU / (config.maxSMB * 2.0)).coerceIn(wffScalingMin, 1.0)
        } else 1.0
        // ── Watching consolidation: vergroot watching commits na grote frontload ──
        // Probleem: na een stage2 commit van bijv. 3.5U geeft het systeem 4-5
        // kleine watching commits (~0.70U elk) terwijl één geconsolideerde
        // tweede commit van ~1.5U effectiever zou zijn:
        //   - Insuline werkt eerder op de BG-stijging
        //   - Lagere IOB op de piek → minder hypo-staartrisico
        //
        // VEREENVOUDIGING 26/06/2026 (Ecko, architectuurreview):
        // wffIobPenalty (was: extra IOB-rem specifiek voor watching-frontload)
        // is verwijderd. commitIobFactor verderop in de commit-formule dempt
        // al op basis van IOB — een tweede IOB-penalty hier is overtollig en
        // bleek de oorzaak van ongewenst kleine doses (0.10-0.15U) bij
        // maaltijden met moderate IOB (ratio 0.50-0.70), terwijl BG gewoon
        // doorsteeg naar 8+ mmol. De gecombineerde factor was 0.68× bij
        // iobRatio=0.60 (= 0.795 commitIobFactor × 0.860 wffIobPenalty),
        // nu nog 0.795× — één consistente IOB-rem via één mechanisme.

        // Probleem: na een stage2 commit van bijv. 3.5U geeft het systeem 4-5
        // kleine watching commits (~0.70U elk) terwijl één geconsolideerde
        // tweede commit van ~1.5U effectiever zou zijn:
        //   - Insuline werkt eerder op de BG-stijging
        //   - Lagere IOB op de piek → minder hypo-staartrisico
        //
        // Consolidatiefactor actief als:
        //   1. Grote frontload < 20 min geleden (lastEarlyBoostDoseU >= 1.5U)
        //   2. BG nog stijgend (slope >= 0.40 en recentSlope >= 2.0)
        //   3. iobRatio < 0.80 (voorkomen bij extreem hoge IOB)
        //   4. predictedPeak >= 8.5 (systeem verwacht nog substantiële stijging)
        //
        // Factor: 1.0 (geen effect) tot max 1.6 afhankelijk van hoe vroeg
        // we na de frontload zitten en hoe sterk de stijging is.
        // Na 20 min valt de factor lineair terug naar 1.0.
        val minsNaFrontload = lastEarlyBoostAt?.let {
            org.joda.time.Minutes.minutesBetween(it, now).minutes
        } ?: Int.MAX_VALUE
        // Consolidatievenster: 5-30 min (was 5-20)
        // Langere window zodat de hogere watching commits ook de cycli
        // op t=20-30 min bereiken — die zijn vanochtend nog relevant.
        val inConsolidationWindow = lastEarlyBoostDoseU >= 1.5 &&
            minsNaFrontload in 5..30 &&
            ctx.slope >= 0.40 &&
            ctx.recentSlope >= 2.0 &&
            ctx.iobRatio < 0.85 &&
            peak.predictedPeak >= 8.5
        val watchingConsolidationFactor = if (inConsolidationWindow) {
            // Lineair aflopend: t=5min → factor 1.6, t=30min → factor 1.0
            val t = ((minsNaFrontload - 5).toDouble() / 25.0).coerceIn(0.0, 1.0)
            1.6 - t * 0.6  // 1.6 → 1.0 over 25 minuten
        } else 1.0

        // In het consolidatievenster geldt dezelfde aanpak: geen aparte IOB-penalty,
        // commitIobFactor regelt de IOB-rem consistent voor alle commits.
        val watchingFrontloadTargetU =
            (config.maxSMB * config.watchingFrontloadFrac * wffBudgetScaling
                * watchingConsolidationFactor)
                .coerceAtMost(config.maxSMB)

        // ── Delta-to-target ramp (05/07/2026, Ecko) ──────────────────────
        // WatchingFrontload sprong voorheen in één cyclus van 0 naar de volle
        // watchingFrontloadTargetU zodra deltaToTarget de drempel passeerde —
        // een harde aan/uit-drempel. Vervangen door een kwadratische ease-in-
        // opbouw: vlak na de drempel blijft de dosis laag (WATCHING_DELTA_RAMP_FLOOR),
        // en pas naarmate deltaToTarget verder oploopt (over WATCHING_DELTA_RAMP_WIDTH
        // mmol) bouwt de dosis versneld op naar 100%. Dat geeft bedenktijd vlak over
        // de drempel (kan ruis/een tijdelijke uitschieter zijn) zonder de reactie bij
        // een duidelijke, aanhoudende stijging te vertragen.
        //
        // Alleen de DOSISHOOGTE wordt geraamd. De trigger zelf (watchingDeltaOk,
        // dus WANNEER WFF in aanmerking komt) blijft op dezelfde drempel — de
        // andere WFF-gates (slope/peakRise/iob) zijn hier niet door geraakt.
        val watchingDeltaRampX = ((ctx.deltaToTarget - config.watchingMinDeltaToTarget) / WATCHING_DELTA_RAMP_WIDTH)
            .coerceIn(0.0, 1.0)
        val watchingDeltaRampFrac = WATCHING_DELTA_RAMP_FLOOR +
            (1.0 - WATCHING_DELTA_RAMP_FLOOR) * watchingDeltaRampX * watchingDeltaRampX
        val watchingFrontloadTargetUEffective = watchingFrontloadTargetU * watchingDeltaRampFrac

// 3) Echte triggerconditie
        val watchingFrontloadTriggered =
            watchingContextOk &&
                watchingSlopeOk &&
                watchingDeltaOk &&
                watchingPeakRiseOk &&
                watchingIobOk

// 4) ✅ LogRow velden gegroepeerd vullen (hier is alle info beschikbaar)
        logRow.watchingSlopeOk = watchingSlopeOk
        logRow.watchingDeltaOk = watchingDeltaOk
        logRow.watchingPeakRiseOk = watchingPeakRiseOk
        logRow.watchingIobOk = watchingIobOk
        // Logt de daadwerkelijk toegepaste (na-ramp) waarde — het ceiling-bedrag
        // vóór de ramp staat in de status-tekst hieronder voor analysedoeleinden.
        logRow.watchingFrontloadTargetU = watchingFrontloadTargetUEffective
        logRow.watchingFrontloadTriggered = watchingFrontloadTriggered

        // Log WFF hypo-blokkade zodat het zichtbaar is in de CSV
        if (wffHypoBlokkade) {
            status.append(
                "WFF-HYPO-BLOKKADE: proj=${
                    "%.1f".format(hypoNoInsulinProjection)
                } < floor=${
                    "%.1f".format(config.hypoBlockThreshold)
                } → WFF niet gevuurd\n"
            )
        }

// 5) Pas dosing toe als triggered
        if (watchingFrontloadTriggered) {

            if (finalDose < watchingFrontloadTargetUEffective) {
                status.append(
                    "WATCHING FRONTLOAD: " +
                        "${"%.2f".format(finalDose)}→${"%.2f".format(watchingFrontloadTargetUEffective)}U " +
                        "(ceiling=${"%.2f".format(watchingFrontloadTargetU)}U ramp=${"%.0f".format(watchingDeltaRampFrac * 100)}%)\n"
                )
                finalDose = watchingFrontloadTargetUEffective
            }
        }


// ─────────────────────────────────────────────
// 9️⃣ Meal detectie & commit/observe + peak suppression + re-entry
// ─────────────────────────────────────────────


        val firstCommitBypass =
            lastCommitAt == null && mealSignal.state == MealState.CONFIRMED

        val episodeMinutesForCommit =
            mealEpisodeStartTime?.let {
                org.joda.time.Minutes.minutesBetween(it, now).minutes
            } ?: 999

        val commitAllowed = firstCommitBypass || canCommitNow(now, ctx, config, episodeMinutesForCommit)
        logRow.commitAllowed = commitAllowed
// ─────────────────────────────────────────────
// 🧠 LEARNING: commit fraction (single source)
// ─────────────────────────────────────────────

        val baseCommitFraction =
            if (mealSignal.state != MealState.NONE) {
                computeCommitFraction(signal = mealSignal, config = config)
            } else 0.0
        logRow.baseCommitFraction = baseCommitFraction

        val aggr = computeMealAggression(
            ctx, peak, mealSignal, config,
            earlyPeakBiasMmol = config.earlyPeakBiasMmol,
            minutesSinceMealStart = episodeMinutesForCommit
        )
        status.append(aggr.reason + "\n")

// aggression → multiplier 0.85 .. 1.25 (mild, safe)
// (als je agressiever wil: max 1.35)
        val aggrMul = lerp(0.85, 1.25, aggr.a)
        logRow.mealAggressionA = aggr.a
        logRow.mealAggressionMul = aggrMul

        val commitFraction = (baseCommitFraction * aggrMul).coerceIn(0.0, 1.0)


        var didCommitThisCycle = false

        status.append(mealSignal.reason + "\n")
        status.append("CommitAllowed=${if (commitAllowed) "YES" else "NO"}\n")

        var commandedDose = finalDose

        // BUGFIX (13/07/2026, Ecko): zie kdoc bij de UNIVERSELE TAPER-CLAMP
        // verderop — dit vlag onderscheidt "commandedDose komt uit de
        // commit-tak zelf (al taper-aware)" van "commandedDose is nog de
        // ongemoeide finalDose-fallback". Nodig omdat de taper-clamp anders
        // ook een net door de commit-tak zelf goedgekeurde, kleinere dosis
        // opnieuw (en dan ten onrechte) verder afknijpt tijdens de vroege
        // frontload-fase van een maaltijd — zie incident 13/07 05:17-05:33 UTC.
        var commandedDoseIsFromCommit = false

        var lateDecayMul = 1.0  // bijgehouden voor logging; wordt in commit-blok ingesteld

        // ── Anti-drip: kleine correcties niet elke cyclus ──
        if (commandedDose > 0.0 &&
            commandedDose <= config.smallCorrectionMaxU &&
            mealSignal.state == MealState.NONE &&
            earlyDose.stage == 0   // ⬅️ EARLY DOSE UITZONDEREN
        ) {
            val minutesSinceSmall = minutesSince(lastSmallCorrectionAt, now)
            if (minutesSinceSmall < config.smallCorrectionCooldownMinutes) {
                status.append("SmallCorrectionCooldown: ${minutesSinceSmall}m < ${config.smallCorrectionCooldownMinutes}m → dose=0\n")
                commandedDose = 0.0
            } else {
                lastSmallCorrectionAt = now
            }
        }


// 9a) Peak/absorption suppression: stop of reduce rond/na piek

        if (suppressForPeak) {
            val reduced = (finalDose * config.absorptionDoseFactor).coerceAtLeast(0.0)
            commandedDose = reduced
            status.append("ABSORBING/PEAK: suppression → ${"%.2f".format(commandedDose)}U\n")
            logRow.guardPeakLimited = true
        }

// 9b) Re-entry: tweede gang (mag suppression overrulen als het écht weer stijgt)
        val reentry = isReentrySignal(ctx, now, config)
        if (reentry) {
            // nieuw segment binnen episode
            // Bewaar boostCommitCount — re-entry is een nieuw segment binnen
            // dezelfde maaltijdepisode, eerdere boosts tellen nog steeds mee.
            prePeakImpulseDone = false
            lastSegmentAt = now
            val savedBoostCountReentry = earlyDose.boostCommitCount
            earlyDose = EarlyDoseContext()
            earlyDose.boostCommitCount = savedBoostCountReentry
            earlyConfirmDone = false
            sensorBlipStreakCount = 0
            recentBgHistory.clear()

            status.append("SEGMENT: re-entry → new impulse window\n")
        }
// 🔒 POST-PEAK COMMIT BLOCK: nooit committen na curve-omkeer
        val postPeakCommitBlocked = postPeak.commitBlocked && !reentry
        if (postPeakCommitBlocked) status.append("POST-PEAK: commit blocked\n")

// 9c) Commit logic (alleen als we niet in peak-suppress zitten, OF als re-entry waar is)
        val allowCommitPath =
            ((!suppressForPeak) || reentry) &&
                !postPeakCommitBlocked



        val fastCarbOverride =
            config.enableFastCarbOverride &&
                peak.predictedPeak >= 12.0 &&
                ctx.slope >= 1.5 &&
                ctx.acceleration >= 0.25 &&
                ctx.consistency >= config.minConsistency


        val mealForCommit =
            mealSignal.state != MealState.NONE || fastCarbOverride

        if (fastCarbOverride) {
            status.append("FAST-CARB override: mealForCommit=TRUE\n")
        }


        if (downGate.locked) {
            status.append("DOWNTREND: commit skipped (LOCKED)\n")
        } else if (allowCommitPath && mealForCommit) {

            val accelFirst = accelFirstCommitTrigger(
                ctx = ctx,
                peak = peak,
                mealSignal = mealSignal,
                config = config,
                prePeakCommitWindow = prePeakCommitWindow,
                trend = trend
            )
            status.append(accelFirst.reason + "\n")

            val allowCommitBoost = accelFirst.ok


            val effectiveCommitAllowed =
                if (reentry) true else commitAllowed
            logRow.effectiveCommitAllowed = effectiveCommitAllowed

            status.append(
                "EffectiveCommitAllowed=${if (effectiveCommitAllowed) "YES" else "NO"}\n"
            )

            if (effectiveCommitAllowed) {


                val zoneFactor = commitFractionZoneFactor(zoneEnum, mealActive = mealForCommit)
                logRow.commitZoneFactor = zoneFactor
                val fraction =
                    (commitFraction * zoneFactor * config.maxCommitFractionMul)
                        .coerceIn(0.0, 1.0)

                status.append(
                    "CommitFraction: base=${"%.2f".format(commitFraction)} zoneFactor=${"%.2f".format(zoneFactor)} → ${"%.2f".format(fraction)}\n"
                )

                val commitAccessOk = (accessLevel == DoseAccessLevel.NORMAL)
                if (!commitAccessOk) {
                    status.append("COMMIT limited by ACCESS ($accessLevel)\n")
                }

// ✅ blijft bestaan: pre-peak commit window geeft iets minder agressief committen
                val prePeakMul = if (prePeakCommitWindow) 0.85 else 1.0

// Plateau/top penalty om te voorkomen dat commit nog doorduwt als fast-lane al afvlakt
                val nearPeak =
                    peak.predictedPeak >= 9.5 &&
                        (peak.predictedPeak - ctx.input.bgNow) <= 0.8

                val braking =
                    ctx.acceleration <= 0.10 ||
                        ctx.recentSlope <= 0.15

                val rawPlateauPenalty =
                    if ((nearPeak || braking) &&
                        ctx.recentDelta5m <= 0.02 &&
                        ctx.recentSlope <= 0.20 &&
                        ctx.iobRatio >= 0.25
                    ) {
                        val sev = smooth01((ctx.iobRatio - 0.25) / 0.35)
                        (1.0 - 0.75 * sev).coerceIn(0.25, 0.85)
                    } else 1.0
                logRow.commitRawPlateauPenalty = rawPlateauPenalty

                if (rawPlateauPenalty < 1.0) {
                    status.append("COMMIT rawPlateauPenalty=${"%.2f".format(rawPlateauPenalty)}\n")
                }

                // commitAggressionMul: schaalt de commit op/neer op basis van de
                // agressiviteitsinstelling. Maximaal 1.20 (meest agressief).
                // BUGFIX 23/06/2026 (Ecko): bij hoge IOB terwijl BG al dicht bij
                // de voorspelde piek zit (bijv. BG=9.1, pred_peak=10.3, iob=7.5)
                // stond commitAggressionMul nog steeds op 1.20 — precies het
                // tegenovergestelde van wat gewenst is. Als er al veel insuline
                // Bevinding 25/06/2026 (Ecko diner): de voorspelling onderschatte
                // de piek met ~2 mmol/L bij een sterke stijging (slope ≥ 6 mmol/h).
                // Bij bg/pred_peak=0.85 remde de aggressiviteit al af op ~73% van
                // de werkelijke piek (0.85 × 11.4 = 9.7 mmol, terwijl de echte
                // piek 12.9+ werd). 0.92 geeft meer marge voor de onderschatting:
                // de rem treedt nu pas in als de BG minimaal 92% van de voorspelde
                // piek heeft bereikt, wat bij de typische onderschatting neerkomt
                // op ±80% van de werkelijke piek — een betere veiligheidsmarge.
                // VEREENVOUDIGING 26/06/2026 (Ecko, architectuurreview):
                // commitAggressionMul was lerp(0.90, 1.20, aggr.a) — maar aggrMul
                // in de fraction-berekening (lerp 0.85-1.25) past aggressiviteit
                // al toe. De combinatie gaf bij sterkte=81% een factor ×1.19 en
                // bij sterkte=100% zelfs ×1.50 — dat is onbedoelde dubbelwerking.
                // commitAggressionMul is nu een pure veiligheidspoort: 0.85 zodra
                // de BG te dicht bij de voorspelde piek is bij hoge IOB, anders 1.0.
                // De 15% korting bij nearPeakWithHighIob vervangt de ×1.0-cap die
                // er eerder zat (capping at 1.0 = cap aggressiviteit; nu ook van
                // 1.0→0.85 als het systeem al te gretig wil doseren nabij de piek).
                val nearPeakWithHighIob = ctx.iobRatio >= 0.35 &&
                    peak.predictedPeak > 0 &&
                    ctx.input.bgNow / peak.predictedPeak >= 0.92
                val commitAggressionMul = if (nearPeakWithHighIob) 0.85 else 1.0
                logRow.commitAggressionMul = commitAggressionMul

                logRow.commitPostPeakFactor = postPeak.commitFactor

                // ── Episode-commit decay met boost-budget compensatie ────────────   export settings
                //
                // Twee componenten schalen de decay samen:
                //   1. lateCommitDecayFactor (basis, configureerbaar)
                //   2. episodeBoostBudgetU   (extra, proportioneel aan earlyBoost)
                //
                // effectiveDecay = lateCommitDecayFactor + budget/(maxSMB×2)
                // → Hoe meer earlyBoost gaf, hoe harder latere commits krimpen
                // → Totaal insuline blijft hierdoor bij benadering gelijk
                //
                // Voorbeeld (earlyBoost=1.25, budget=0.23U, maxSMB=1.25, lcd=0.20):
                //   effectiveDecay = 0.20 + 0.23/2.50 = 0.29
                //   commit 2: ×0.71  commit 3: ×0.42

                val commitNr = episodeCommitCount + 1
                // Noemer verhoogd van maxSMB*2.0 naar maxSMB*4.0 (07/07/2026, Ecko):
                // episodeBoostBudgetU telt nu de VOLLEDIGE toegediende dosis (zie
                // hierboven), niet meer alleen het kleine EarlyBoost-extra-deel — een
                // grote maaltijd kan makkelijk 5-10U cumulatief bereiken. Zonder deze
                // aanpassing zou budgetDecay al na 1-2 commits verzadigen op 0.50.
                val budgetDecay = (episodeBoostBudgetU / (config.maxSMB * 4.0))
                    .coerceIn(0.0, 0.50)

                // ── Curve-fit "topping out"-bonus (04/07/2026, Ecko) ─────────
                // Spiegelbeeld van de fitConfidenceBoost in computeEarlyBoostFactor:
                // waar die de VROEGE dosis eerder/harder maakt bij een bevestigde
                // stijging, maakt dit de AFBOUW eerder/steiler bij een bevestigde,
                // veilige daling. Voorwaarden (alle drie vereist):
                //   1. curveFitR2 overtuigend hoog — geen ruis, één schone curve
                //   2. curveAcceleration <= 0 — de parabool-fit zelf toont al
                //      afvlakking/daling, niet alleen de EWMA-slope
                //   3. predictedPeak blijft ruim (>=1.5 mmol marge) onder de
                //      primaire streefgrens van 10 mmol (doel.txt)
                // Voorwaarde 2 sluit een actieve maaltijdstijging per definitie
                // uit (die heeft per definitie curveAcceleration > 0 zolang hij
                // aan de gang is), dus dit kan de stijging zelf nooit vertragen —
                // het versnelt alleen de afbouw ná een bevestigde piek.
                val toppingOutConfident = ctx.curveFitR2 >= CURVE_FIT_MIN_R2 &&
                    ctx.curveAcceleration <= 0.0 &&
                    peak.predictedPeak <= (TOPPING_OUT_HYPER_REF_MMOL - TOPPING_OUT_MARGIN_MMOL)
                val toppingOutBoost = if (toppingOutConfident) {
                    val fitScore = smooth01((ctx.curveFitR2 - CURVE_FIT_MIN_R2) / (0.98 - CURVE_FIT_MIN_R2))
                    val marginScore = smooth01(
                        ((TOPPING_OUT_HYPER_REF_MMOL - TOPPING_OUT_MARGIN_MMOL) - peak.predictedPeak) / TOPPING_OUT_MARGIN_MMOL
                    )
                    fitScore * marginScore * TOPPING_OUT_MAX_DECAY_BOOST
                } else 0.0

                val effectiveDecay = (config.lateCommitDecayFactor + budgetDecay + toppingOutBoost)
                    .coerceIn(0.0, 0.70)
                val lateDecayActive = effectiveDecay > 0.01 && commitNr > 1

                // ── IOB-afhankelijke vloer voor lateDecayMul ──────────────────
                // Bevinding 23/06/2026 (Ecko): bij grote maaltijden botste de
                // vloer van 0.35 al bij commit 3-4 waarna ELKE volgende commit
                // nog 35% van maxSMB leverde, ook als IOB al zeer hoog was en
                // de BG dicht bij de voorspelde piek zat. De vaste vloer van
                // 0.35 is bedoeld om de alleerlaatste commit niet tot nul te
                // laten zakken, maar werkt averechts als er nog 3+ commits
                // volgen — dan is de vloer de bottleneck, niet de decay-factor.
                // Oplossing: de vloer schaalt mee met de IOB — hoe meer insuline
                // al actief is, hoe lager de minimaal nog toegestane factor.
                // coerceAtLeast(0.10): nooit volledig naar nul.
                //
                // Aanvulling 28/06/2026 (Ecko): als BG nog substantieel stijgt
                // (slope >= 1.5) én significant boven target zit (> target + 3),
                // is de commit-teller géén goed criterium om te stoppen met geven.
                // De vloer wordt dan hoger zodat commits bij 0.20-0.45 blijven
                // i.p.v. naar 0.10 te zakken terwijl BG nog naar 13-14 gaat.
                // De IOB-rem (commitIobFactor) en de afterload remmen het totaal
                // al voldoende; de decayFloor mag hier niet de bottleneck zijn.
                //
                // BUGFIX (09/07/2026, Ecko): ctx.slope is een traag/gemiddeld
                // signaal — bij een omslag (piek net gepasseerd) kan het nog 1-2
                // cycli "nog stijgend" blijven aangeven terwijl de BG zelf al
                // daalt. Concreet voorbeeld: bij een BG-piek gaf slope=4.37 (ruim
                // boven de 1.5-drempel) terwijl curveAcceleration al -1.46 was
                // (met r²=0.96) — de curve-fit had de omslag dus al correct
                // gezien, maar het trage slope-signaal liet het vluchtventiel
                // toch nog een grote, ongeplafonneerde dosis doorlaten. Vandaar
                // nu ook een check op curveAcceleration — dezelfde, elders al
                // vertrouwde curve-fit-drempel (CURVE_FIT_MIN_R2) als bij
                // peakPressureBonus/de topping-out-boost, geen nieuw mechanisme.
                val curveConfirmtOmslag = ctx.curveFitR2 >= CURVE_FIT_MIN_R2 &&
                    ctx.curveAcceleration <= 0.0

                // BUGFIX (12/07/2026, Ecko): curveConfirmtOmslag hierboven is een
                // harde grens (curveAcceleration <= 0.0) die per constructie pas
                // NA de feitelijke omslag kan bevestigen. Incident 12/07 15:12 UTC:
                // curveAcceleration liep 22,70 -> 19,99 -> 10,74 (r2=0,989, dus geen
                // ruis) en pas de cyclus daarna (15:17) naar -2,67 - precies die
                // laatste cyclus (10,74, nog altijd > 0) liet bgStijgtNogFors nog
                // een volledig ongeplafonneerde commit (2,89U) door, recht op de
                // feitelijke piek. Een sterke cycle-op-cycle terugval (>=45%) in
                // curveAcceleration, terwijl de curve-fit al betrouwbaar is, is een
                // sterk vroeg signaal dat de omslag er binnen een cyclus aankomt.
                // Dit is EXTRA op curveConfirmtOmslag (die blijft de definitieve,
                // harde bevestiging) - geen vervanging, puur een eerdere aftrap van
                // dezelfde vluchtklep-sluiting. Drempel (0.55x vorige) is een eerste
                // inschatting, evt. bijstellen als dit elders vals-positief blijkt
                // bij een reele, grillige doorzettende stijging.
                val curveAccelDecelerating = prevCurveAccelerationForOmslag?.let { prev ->
                    ctx.curveFitR2 >= CURVE_FIT_MIN_R2 &&
                        ctx.curveAcceleration > 0.0 &&
                        prev > 0.0 &&
                        ctx.curveAcceleration <= prev * 0.55
                } ?: false
                prevCurveAccelerationForOmslag = ctx.curveAcceleration
                val omslagBijnaBevestigd = curveAccelDecelerating && !curveConfirmtOmslag

                val bgStijgtNogFors = ctx.slope >= 1.5 &&
                    ctx.input.bgNow > ctx.input.targetBG + 3.0 &&
                    !curveConfirmtOmslag &&
                    !omslagBijnaBevestigd
                // 11/07/2026 (Ecko) — diagnostisch vastleggen, zie kdoc bij
                // lastBgStijgtNogFors hierboven.
                lastBgStijgtNogFors = bgStijgtNogFors
                lastCommitNrUsed = commitNr
                val decayFloorBase = if (bgStijgtNogFors) {
                    // Stijgende BG ver boven target: hogere vloer
                    // iobRatio=0.55 → 0.225, iobRatio=0.65 → 0.175, nooit onder 0.18
                    (0.45 * (1.0 - ctx.iobRatio * 1.0)).coerceIn(0.18, 0.40)
                } else {
                    // Normaal: IOB-afhankelijke vloer
                    (0.35 * (1.0 - ctx.iobRatio * 1.2)).coerceIn(0.10, 0.35)
                }
                // Vloer blijft meebewegen met het aantal commits (07/07/2026, Ecko).
                // BEVINDING: de vloer hierboven hangt alleen af van iobRatio, niet van
                // commitNr. Zodra de berekende decay (1 - effectiveDecay*(commitNr-1))
                // onder deze vloer zakt — vaak al bij commit 2-3 — kregen ALLE
                // volgende commits letterlijk dezelfde waarde: geen verder onderscheid
                // tussen commit 3, 4, 5 en 6, ook al liep de episode nog lang door.
                // Kleine, aflopende correctie per extra commit (max 5×0.02=0.10) zorgt
                // dat er ook ná het bereiken van de vloer nog een geleidelijke afbouw
                // blijft plaatsvinden, zonder de vloer als veiligheidsgrens los te laten.
                val decayFloor = (decayFloorBase - (commitNr - 1).coerceAtMost(5) * 0.02)
                    .coerceAtLeast(0.10)

                lateDecayMul = if (lateDecayActive) {
                    (1.0 - effectiveDecay * (commitNr - 1).toDouble())
                        .coerceIn(decayFloor, 1.0)
                } else 1.0

                logRow.toppingOutBoost = toppingOutBoost
                if (lateDecayActive) {
                    val toppingOutSuffix = if (toppingOutBoost > 0.001)
                        " toppingOut=+${"%.2f".format(toppingOutBoost)}(R²=${"%.2f".format(ctx.curveFitR2)})"
                    else ""
                    status.append(
                        "COMMIT DECAY ×${"%.2f".format(lateDecayMul)} (#$commitNr decay=${"%.2f".format(effectiveDecay)} budget=${"%.2f".format(episodeBoostBudgetU)}U$toppingOutSuffix)\n"
                    )
                }
                logRow.commitPostPeakFactor = postPeak.commitFactor
                val commitDose =
                    if (allowCommitBoost && commitAccessOk)
                        (config.maxSMB * fraction * commitIobFactor * prePeakMul * postPeak.commitFactor * rawPlateauPenalty * commitAggressionMul * lateDecayMul)
                            .coerceAtMost(config.maxSMB)
                    else 0.0
                logRow.commitDoseRaw = commitDose

                // ── Voorkom dat finalDose de late-commit-afbouw omzeilt (08/07/2026, Ecko) ──
                // PROBLEEM: committedDose = maxOf(finalDose, commitDose) liet finalDose
                // (het basis-energiemodel — weet niets van commit-nummer of afbouw)
                // altijd winnen zodra de BG genoeg was gestegen, wat bij een echte
                // maaltijd al snel gebeurt. lateDecayMul daalde intussen keurig
                // (zichtbaar in de logs), maar had in de praktijk geen effect omdat
                // finalDose het gewoon overnam — de afbouw was daardoor decoratief.
                //
                // OPLOSSING, met Ecko's kanttekening verwerkt: alleen als de stijging
                // daadwerkelijk nog doorzet (bgStijgtNogFors — dezelfde voorwaarde die
                // decayFloor hierboven al verhoogt) mag finalDose een NIEUW, hoger
                // piekpunt zetten. Commit 2 mag dus best groter zijn dan commit 1 als
                // de versnelling dat rechtvaardigt — maar zonder die doorzettende
                // stijging wordt finalDose begrensd op het hoogste punt dat deze
                // episode al is bereikt (episodePeakCommitU) keer lateDecayMul, zodat
                // de afbouw daadwerkelijk bindend wordt. Na een eventuele nieuwe piek
                // (bij een echte versnelling) gaat de afbouw van latere commits weer
                // verder vanaf dát nieuwe, hogere punt — niet vanaf de oorspronkelijke
                // eerste commit. De eerste commit zelf (commitNr <= 1) wordt nooit
                // begrensd door deze regel.
                val cappedFinalDose = if (bgStijgtNogFors || commitNr <= 1) {
                    finalDose
                } else {
                    finalDose.coerceAtMost(episodePeakCommitU * lateDecayMul)
                }

                val committedDose =
                    if (peakCategory >= PeakCategory.HIGH)
                        maxOf(cappedFinalDose, commitDose * 1.15)
                    else
                        maxOf(cappedFinalDose, commitDose)
                logRow.commitDoseFinal = committedDose
                episodePeakCommitU = maxOf(episodePeakCommitU, committedDose)


                val effectiveMinCommitDose = when {
                    // al bestaande
                    ctx.deltaToTarget >= 3.0 && ctx.iobRatio < 0.3 ->
                        config.minCommitDose * 0.7

                    // ✅ nieuw: duidelijke meal-trend, nog voldoende iob-ruimte
                    (mealSignal.state != MealState.NONE) &&
                        ctx.slope >= 0.8 &&
                        ctx.acceleration >= 0.15 &&
                        ctx.iobRatio < 0.35 ->
                        config.minCommitDose * 0.6   // 0.18U als minCommitDose=0.30

                    else ->
                        config.minCommitDose
                }

                if (committedDose >= effectiveMinCommitDose) {

                    commandedDose = committedDose
                    commandedDoseIsFromCommit = true

                    // Verlengt absorptionWindow alleen bij substantiele commits (>= 0.40U).
                    // Kleine correctie-commits (0.20-0.35U) mogen de suppress-window
                    // niet eindeloos verlengen: dat blokkeerde de borrel-stijging.
                    if (committedDose >= 0.40) lastCommitAt = now
                    lastCommitDose = committedDose
                    lastCommitReason = "${mealSignal.state} frac=${"%.2f".format(fraction)}"
                    if (committedDose >= 1.5) {
                        lastBigCommitAt = now
                        lastBigCommitDose = committedDose
                    }

                    didCommitThisCycle = true
                    // Alleen tellen als early floor deze cyclus niet al telde
                    if (!earlyFiredThisCycle) episodeCommitCount++

                    if (reentry) {
                        lastReentryCommitAt = now
                        status.append("RE-ENTRY COMMIT set\n")
                    }

                    status.append(
                        "COMMIT ${"%.2f".format(committedDose)}U " +
                            "(${mealSignal.state}, conf=${"%.2f".format(mealSignal.confidence)})\n"
                    )

                } else {
                    status.append("COMMIT skipped (below minCommitDose)\n")
                }

            } else {
                status.append("OBSERVE (commit cooldown)\n")
            }
        }
// ─────────────────────────────────────────────
// 🟧 RESERVE POOL LOGIC
// 1) Reset / TTL
// 2) Controlled release
// 3) Capture (stash)
// ─────────────────────────────────────────────
// 0) TTL + commit reset (houdbaarheid)
        handleReserveResetAndTtl(
            didCommitThisCycle = didCommitThisCycle,
            now = now,
            status = status
        )



        val hypoProj = hypoProtection(
            ctx = ctx,
            plannedDoseU = commandedDose,
            effectiveISF = input.effectiveISF,
            config = config,
            mealSignal = mealSignal
        )
        logRow.hypoActive = hypoProj.active
        logRow.hypoProjectedBg = hypoProj.projectedMin
        logRow.hypoDebtU = episodeHypoDebtU  // schuld na eventuele opbouw deze cyclus

        val reserveReleaseBlocked = hypoProj.active

        if (hypoProj.active) {
            status.append("${hypoProj.reason} → commandedDose=0\n")

            // Bouw hypo-debt op: we houden bij hoeveel insuline is achtergehouden
            // door hypo-bescherming terwijl er een maaltijdepisode actief was.
            // Alleen tellen tijdens een actieve episode (startBg bekend) zodat
            // nacht-hypo's of correctie-blokkades niet worden meegeteld.
            if (activeMealEpisodeId != -1L && commandedDose > 0.0) {
                val debtIncrement = commandedDose.coerceIn(0.0, config.maxSMB)
                episodeHypoDebtU = (episodeHypoDebtU + debtIncrement)
                    .coerceAtMost(config.maxSMB * 2.0)  // absolute cap: nooit > 2× maxSMB
                status.append(
                    "HYPO DEBT: +${"%.2f".format(debtIncrement)}U " +
                        "totaal=${"%.2f".format(episodeHypoDebtU)}U\n"
                )
            }

            commandedDose = 0.0
        }


// 1) Release rule: als reserve bestaat en trend draait weer omhoog → geef gecontroleerd vrij
        if (reservedInsulinU > 0.0) {

            val risingAgain =
                ctx.recentDelta5m >= 0.06 || ctx.recentSlope >= 0.20

            val safeToRelease =
                !reserveReleaseBlocked &&
                    ctx.acceleration >= 0.0 &&
                    ctx.input.bgNow >= 4.8 &&
                    ctx.consistency >= config.minConsistency


            if (risingAgain && safeToRelease) {

                // per cycle: max "extra" reserve die we willen toestaan
                val perCycleCap = (config.maxSMB * RESERVE_RELEASE_CAP_FRAC).coerceAtLeast(0.05)

                // ✅ NIET stapelen bovenop een nieuwe commandedDose:
                // release alleen als er headroom is t.o.v. perCycleCap
                val headroom = (perCycleCap - commandedDose).coerceAtLeast(0.0)

                val releaseU = minOf(reservedInsulinU, headroom)

                if (releaseU > 0.0) {
                    val before = commandedDose
                    commandedDose += releaseU

                    reservedInsulinU -= releaseU
                    reserveActionThisCycle = "RELEASE"
                    reserveDeltaThisCycle -= releaseU

                    if (reservedInsulinU <= 1e-9) {
                        reservedInsulinU = 0.0
                        reserveAddedAt = null
                        reserveCause = null
                    }

                    status.append(
                        "RESERVE RELEASE: +${"%.2f".format(releaseU)}U " +
                            "cmd ${"%.2f".format(before)}→${"%.2f".format(commandedDose)}U " +
                            "remain=${"%.2f".format(reservedInsulinU)}U " +
                            "cause=${reserveCause}\n"
                    )
                } else {
                    status.append(
                        "RESERVE RELEASE SKIP: no headroom " +
                            "(cmd=${"%.2f".format(commandedDose)} cap=${"%.2f".format(perCycleCap)} " +
                            "remain=${"%.2f".format(reservedInsulinU)}U cause=${reserveCause})\n"
                    )
                }

            } else {
                status.append(
                    "RESERVE HOLD: remain=${"%.2f".format(reservedInsulinU)}U " +
                        "(recentΔ5m=${"%.2f".format(ctx.recentDelta5m)} " +
                        "recentSlope=${"%.2f".format(ctx.recentSlope)} " +
                        "accel=${"%.2f".format(ctx.acceleration)} " +
                        "cause=${reserveCause})\n"
                )
            }

        }


// 2) Capture rule: als we nu willen doseren, maar top/dip-condities → stash in reserve
        if (commandedDose > 0.0) {

            // Alleen relevant in “meal-like” situaties (waar jij dit vooral wil)
            val mealLikeForReserve =
                mealSignal.state != MealState.NONE ||
                    prePeakCommitWindow ||
                    earlyDose.stage > 0

            // Niet stashen als we in harde stijging zitten
            val strongRisingNow =
                ctx.recentDelta5m >= 0.10 || ctx.recentSlope >= 0.45

            // A) klassieke false-dip / korte terugval
            val shortTermDip =
                (ctx.recentDelta5m <= -0.06 || ctx.recentSlope <= -0.20) &&
                    ctx.acceleration <= 0.0 &&
                    ctx.consistency >= config.minConsistency

            // 🟠 NIEUW: topvorming zonder dip (zoals 09:42 / 09:48)
            val peakTopForming =
                peak.state == PeakPredictionState.WATCHING &&
                    ctx.acceleration <= -0.02 &&                 // afremmen begint
                    ctx.iobRatio >= 0.35 &&                       // al insuline aan boord
                    predictedPeak >= ctx.input.bgNow + 0.6 &&    // piek ligt nog boven ons
                    ctx.consistency >= config.minConsistency


            // B) ✅ TOPVORMING / "net over de top" situatie (jouw issue rond 09:42 / 09:48):
            // - relatief hoge IOB
            // - accel zwakt af (maar je bent nog niet per se dalend)
            // - nog boven target
            val topForming =
                ctx.iobRatio >= 0.60 &&
                    ctx.acceleration <= 0.10 &&
                    ctx.deltaToTarget >= 1.2 &&
                    ctx.consistency >= config.minConsistency &&
                    ctx.recentSlope <= 0.35          // ✅ short-term stijging moet al “weg” zijn


            val postPeakNoStash = postPeak.noStash
            if (postPeakNoStash) {
                status.append("POSTPEAK → NO-STASH window\n")
            }



            // stash-conditie: dip OF topvorming, maar niet tijdens sterke hernieuwde stijging
            val shouldStash =
                !postPeakNoStash &&
                    mealLikeForReserve &&
                    (shortTermDip || peakTopForming || topForming) &&
                    !strongRisingNow


            if (shouldStash) {

                reservedInsulinU += commandedDose
                reserveAddedAt = reserveAddedAt ?: now
                reserveActionThisCycle = "STASH"
                reserveDeltaThisCycle += commandedDose

                reserveCause =
                    when {
                        topForming || peakTopForming -> ReserveCause.POST_PEAK_TOP
                        else -> ReserveCause.SHORT_TERM_DIP
                    }

                status.append(
                    "RESERVE STASH (${reserveCause}): " +
                        "${"%.2f".format(commandedDose)}U → " +
                        "reserved=${"%.2f".format(reservedInsulinU)}U\n"
                )

                commandedDose = 0.0
            }

        }

// ─────────────────────────────────────────────
// 🟠 PRE-COMMIT RESERVE SPLIT (early overshoot protection)
// ─────────────────────────────────────────────
        run {
            val preReserve = computePreReserveSplit(
                ctx = ctx,
                mealSignal = mealSignal,
                commandedDose = commandedDose,
                config = config,
                bgZone = zoneEnum
            )

            if (preReserve.active && preReserve.stash > 0.0) {

                status.append(preReserve.reason + "\n")

                // stash deel
                reservedInsulinU += preReserve.stash
                reserveAddedAt = reserveAddedAt ?: now
                reserveCause = ReserveCause.PRE_UNCERTAIN_MEAL
                reserveActionThisCycle = "PRE-STASH"
                reserveDeltaThisCycle += preReserve.stash

                // reduceer wat we NU gaan leveren
                commandedDose = preReserve.deliverNow
            }
        }

// ── TOP PLATEAU CONFIRM (hysterese) ──
        val plateauNow =
            ctx.recentSlope <= 0.30 && kotlin.math.abs(ctx.recentDelta5m) <= 0.04

        val risingAgainNow =
            ctx.recentDelta5m >= 0.06 || ctx.recentSlope >= 0.20

        if (risingAgainNow) {
            // zodra fast lane weer stijgt: meteen reset → voorkomt “te laat weer gas”
            topPlateauConfirm = 0
            topPlateauHold = 0
        } else if (plateauNow) {
            topPlateauConfirm = (topPlateauConfirm + 1).coerceAtMost(5)
            topPlateauHold = 2  // houd 2 cycles vast als we eenmaal plateau zagen
        } else {
            // geen plateau, maar hold kan nog even doorlopen
            if (topPlateauHold > 0) topPlateauHold--
            if (topPlateauHold == 0) topPlateauConfirm = 0
        }
        val topPlateauConfirmed = topPlateauConfirm >= 2 || topPlateauHold > 0

        // ── Intent vóór final guards ──
        logRow.desiredDosePreGuards = commandedDose

// ─────────────────────────────────────────────
// HARD SAFETY BLOCKS (final gate before delivery)
// ─────────────────────────────────────────────
// Tweede hypo-check alleen nodig als commandedDose na reserve-logica
        // nog groter is dan nul én de eerste check niet al heeft geblokkeerd.
        if (commandedDose > 0.0 && !logRow.hypoActive) {
            val hypoFinal = hypoProtection(
                ctx = ctx,
                plannedDoseU = commandedDose,
                effectiveISF = input.effectiveISF,
                config = config,
                mealSignal = mealSignal
            )
            logRow.hypoActive = hypoFinal.active
            if (hypoFinal.active) {
                status.append(hypoFinal.reason + " → commandedDose=0\n")
                commandedDose = 0.0
            }
        }

        if (postPeak.lockout) {
            status.append("POSTPEAK LOCKOUT → commandedDose=0\n")
            commandedDose = 0.0
            earlyConfirmDone = false
            logRow.guardPeakLimited = true
        }

        val topGuard =
            if (topPlateauConfirmed) computeTopGuard(ctx, peak, mealSignal, config)
            else TopGuard(false, 1.0, "TOPGUARD off (not confirmed)")
        logRow.topGuardActive = topGuard.active
        logRow.topGuardCapFactor = topGuard.capFactor
        logRow.topPlateauConfirmed = topPlateauConfirmed
        if (topGuard.active && commandedDose > 0.0) {
            val before = commandedDose
            commandedDose = minOf(commandedDose, config.maxSMB * topGuard.capFactor)
            status.append("${topGuard.reason}: ${"%.2f".format(before)}→${"%.2f".format(commandedDose)}U\n")
            logRow.guardPeakLimited = true
        }

        // ─────────────────────────────────────────────
        // 🧨 LATE-BOLUS HARD BLOCK (avoid hypo after/at peak)
        // If triggered: do NOT deliver now; stash into reserve instead.
        // ─────────────────────────────────────────────
        val lateBolus = computeLateBolusBlock(
            ctx = ctx,
            peak = peak,
            mealSignal = mealSignal,
            postPeak = postPeak,
            topPlateauConfirmed = topPlateauConfirmed,

            config = config
        )

        if (lateBolus.block && commandedDose > 0.0) {

            status.append(lateBolus.reason + " → stash & commandedDose=0\n")

            // stash i.p.v. deliver (zelfde reserve-mechanisme dat je al hebt)
            if (lateBolus.stashInstead) {
                reservedInsulinU += commandedDose
                reserveAddedAt = reserveAddedAt ?: now
                reserveCause = ReserveCause.POST_PEAK_TOP
                reserveActionThisCycle = "STASH"
                reserveDeltaThisCycle += commandedDose

                status.append(
                    "RESERVE STASH (POST_PEAK_TOP): " +
                        "${"%.2f".format(commandedDose)}U → reserved=${"%.2f".format(reservedInsulinU)}U\n"
                )
            }

            commandedDose = 0.0
            earlyConfirmDone = false
            logRow.guardPeakLimited = true
        }

        // 10-min burst cap
        if (commandedDose > 0.0) {

            val delivered10m = deliveredInLastMinutes(now, 10)
            logRow.burstDelivered10m = delivered10m

            // basis cap: max ~1.1×maxSMB per 10m
            // maar iets ruimer als BG echt hoog is (zone HIGH/EXTREME) én we nog niet plateau zijn
            val fastPlateau =
                ctx.recentSlope <= 0.30 && kotlin.math.abs(ctx.recentDelta5m) <= 0.04

            val highRoom = if ((zoneEnum == BgZone.HIGH || zoneEnum == BgZone.EXTREME) && !fastPlateau) 1.45 else 1.10
            var cap10m = highRoom * config.maxSMB

            // Als FCLvNext zelf kort geleden een grote earlyBoost heeft gegeven
            // (stage2/3), dan is die dosis al 'ingepland' en telt hij mee in
            // burst_delivered_10m. Stage3 en de eerste watching commit krijgen
            // daardoor onterecht een reacquire-cap van 0.35U.
            // Fix: verhoog de cap tijdelijk met de earlyBoost-dosis zodat
            // geplande stage-doses gewoon doorgang vinden.
            val minsSinceBoost = lastEarlyBoostAt?.let {
                org.joda.time.Minutes.minutesBetween(it, now).minutes
            } ?: 999
            if (minsSinceBoost <= 10 && lastEarlyBoostDoseU >= 1.0) {
                cap10m += lastEarlyBoostDoseU
            }

            logRow.burstCap10m = cap10m

            val remaining = (cap10m - delivered10m).coerceAtLeast(0.0)
            logRow.burstRemaining10m = remaining

            // Detecteer hernieuwde duidelijke stijging
            val risingAgain =
                ctx.recentDelta5m >= 0.08 || ctx.recentSlope >= 0.30

            if (remaining <= 0.02) {
                val midOrHigher = (zoneEnum == BgZone.MID || zoneEnum == BgZone.HIGH || zoneEnum == BgZone.EXTREME)
                if (risingAgain && midOrHigher && ctx.deltaToTarget >= 1.0) {

                    // Sta beperkte her-acquire toe ondanks burstcap
                    val reacquire =
                        (0.25 * config.maxSMB)
                            .coerceAtLeast(0.10)
                            .coerceAtMost(0.35)

                    val before = commandedDose
                    commandedDose = minOf(commandedDose, reacquire)

                    status.append(
                        "BURSTCAP REACQUIRE: risingAgain → " +
                            "${"%.2f".format(before)}→${"%.2f".format(commandedDose)}U\n"
                    )

                } else {

                    status.append(
                        "BURSTCAP: delivered10m=${"%.2f".format(delivered10m)}U " +
                            ">= cap10m=${"%.2f".format(cap10m)}U → commandedDose=0\n"
                    )

                    commandedDose = 0.0
                    earlyConfirmDone = false
                }
            }

        }


        // ─────────────────────────────────────────────
        // 🧯 UNIVERSELE TAPER-CLAMP (12/07/2026, Ecko)
        // ─────────────────────────────────────────────
        // PROBLEEM (terugkerend §3-patroon): commandedDose kan via meerdere,
        // ONAFHANKELIJKE paden tot stand komen — de finalDose-fallback bij een
        // overgeslagen commit (var commandedDose = finalDose hierboven), de
        // bgStijgtNogFors-vluchtklep, reserve-release, burstcap-reacquire — en
        // elk pad heeft zijn EIGEN, losse rem. Geen van die remmen is absoluut:
        // als een pad zelf geen aanleiding ziet om te temperen, gaat de dosis
        // ongedempt door, ook als de episode allang aan het afbouwen is.
        //
        // Incident 12/07 18:17 UTC: de commit-tak sloeg terecht over (committed
        // dose was maar 0,10U, ver onder minCommitDose — de afbouw zag de
        // naderende piek dus prima), maar de finalDose-fallback zelf was niet
        // aan die afbouw gebonden → 1,44U ongedempt geleverd, exact op het
        // plateau (7,5→7,5→7,2 mmol), ruim vóórdat suppressForPeak/
        // top_plateau_confirmed een cyclus later alsnog bevestigde.
        //
        // OPLOSSING: ÉÉN bovengrens die ALTIJD geldt zodra er al minstens één
        // commit is geweest deze episode (episodePeakCommitU > 0), ongeacht
        // welk pad hierboven commandedDose heeft gezet — dezelfde
        // episodePeakCommitU/lateDecayMul-koppel die de commit-tak al gebruikt
        // (zie cappedFinalDose), zodat de afbouw niet langer decoratief is voor
        // paden die niet via de commit-tak lopen. Bewust NIET toegepast als
        // bgStijgtNogFors (de bewuste, expliciete vluchtklep voor een écht nog
        // doorzettende stijging) actief is — die mag welbewust een nieuw,
        // hoger piekpunt zetten; zie kdoc daar.
        //
        // BUGFIX (13/07/2026, Ecko): ook NIET toepassen als commandedDose al
        // via de commit-tak zelf tot stand kwam (commandedDoseIsFromCommit).
        // Incident 13/07 05:17-05:33 UTC: de commit-tak berekende zelf al
        // keurig getaperde, oplopende commits (0,52-0,62U) tijdens de vroege,
        // nog altijd versnellende fase van een maaltijd (peakState nog IDLE,
        // BG pas 1-2 mmol boven target — bgStijgtNogFors dus nog niet actief,
        // dat vereist >3 mmol boven target). Zonder dit vlag greep de clamp
        // hier alsnog in, met dezelfde episodePeakCommitU/lateDecayMul die de
        // commit-tak net had gebruikt — en dus met een (te) lage, verouderde
        // vloer nog vóórdat er ook maar een piek in zicht was. Effectief werd
        // zo elke frontload-commit ná de eerste teruggeknepen naar bijna de
        // grootte van díe eerste — precies het "frontload komt te laat/te
        // zwak"-gevoel. De clamp blijft wel volledig actief voor de
        // finalDose-fallback (commandedDoseIsFromCommit=false; het
        // oorspronkelijke 18/07-scenario) en voor elk ander pad hieronder.
        if (commandedDose > 0.0 && episodePeakCommitU > 0.0 &&
            !lastBgStijgtNogFors && !commandedDoseIsFromCommit) {
            val taperCeiling = (episodePeakCommitU * lateDecayMul)
                .coerceAtLeast(config.minCommitDose * 0.5)
            if (commandedDose > taperCeiling) {
                val before = commandedDose
                commandedDose = taperCeiling
                status.append(
                    "TAPER CLAMP: ${"%.2f".format(before)}→${"%.2f".format(commandedDose)}U " +
                        "(episodePeakCommitU=${"%.2f".format(episodePeakCommitU)} " +
                        "×lateDecayMul=${"%.2f".format(lateDecayMul)})\n"
                )
            }
        }


        // ─────────────────────────────────────────────
        // 🛡️ IOB HARD CAP + DYNAMIC OVERSHOOT
        // ─────────────────────────────────────────────



        if (commandedDose > 0.0) {

            // Dynamische overshoot: 0..10% op basis van smoothed trend
            val iobOvershootFactor =
                computeIobOvershootFactor(
                    smoothedSlope = ctx.slope,
                    deltaToTarget = ctx.deltaToTarget,
                    maxOvershootPct = 0.10      // later eventueel preference
                )
            logRow.iobOvershootFactor = iobOvershootFactor

            val before = commandedDose

            commandedDose =
                clampDoseByIob(
                    commandedDose = commandedDose,
                    currentIob = input.currentIOB,
                    maxIob = input.maxIOB,
                    overshootFactor = iobOvershootFactor
                )

// ✅ GECONSOLIDEERD (30/06/2026): piek-nadering taper via postPeak.peakBrake
            // i.p.v. eigen losse conditie (was: ctx.slope in -0.10..0.50, te laat
            // bij hoge IOB — zie computePeakBrake() voor de volledige toelichting).
            // hardBrake wordt hier niet apart afgehandeld: postPeak.lockout (dat
            // peakBrake.hardBrake meeneemt) zet commandedDose elders al op 0 bij
            // een harde stop; deze taper is alleen voor de softBrake-situatie.
            if (commandedDose > 0.0) {
                val peakApproachFactor: Double =
                    if (!postPeak.peakBrake.hardBrake) postPeak.peakBrake.softBrakeFactor else 1.0

                logRow.peakApproachFactor = peakApproachFactor
                if (peakApproachFactor < 1.0 - 1e-9) {
                    val headroom = (input.maxIOB - input.currentIOB).coerceAtLeast(0.0)
                    val reducedHeadroom = headroom * peakApproachFactor
                    val beforeTaper = commandedDose
                    commandedDose = minOf(commandedDose, reducedHeadroom)
                    if (commandedDose < beforeTaper - 1e-9) {
                        status.append(
                            "PEAK-APPROACH TAPER: iobR=${"%.2f".format(ctx.iobRatio)} " +
                                "severity=${"%.2f".format(postPeak.peakBrake.severity)} " +
                                "factor=${"%.2f".format(peakApproachFactor)} " +
                                "→ dose ${"%.2f".format(beforeTaper)}→${"%.2f".format(commandedDose)}U\n"
                        )
                    }
                }
            }

            if (commandedDose < before - 1e-9) {
                logRow.guardIobLimited = true
                status.append(
                    "IOB CLAMP: dose ${"%.2f".format(before)}→${"%.2f".format(commandedDose)}U " +
                        "(iob=${"%.2f".format(input.currentIOB)} " +
                        "max=${"%.2f".format(input.maxIOB)} " +
                        "×${"%.2f".format(iobOvershootFactor)})\n"
                )
            }
        }

        // ─────────────────────────────────────────────
        // 🛡️ AFTERLOAD GUARD: begrens late/nafase dosering
        //
        // Twee lagen die onafhankelijk de dosis schalen:
        //
        // Laag 1 — futureDrop60Scale:
        //   futureDrop60 in mmol (= deltaIob × effectiveISF). Drempel 0.56 mmol.
        //   Bereik 0.56-1.36 mmol, max 80% reductie. Normale frontload heeft
        //   fd60 < 0.10 mmol → guard volledig inactief.
        //
        // Laag 2 — highIobLateWaveScale:
        //   Actief als iob_ratio > 0.70 ÉN > 40 min na maaltijdstart.
        //   Bereik 0.70-1.00, max 60% reductie.
        //
        // Laag 3 — watchingMaxIobRatio verlaging:
        //   Watching stopt eerder als fd60 hoog is — blokkeert grote doses
        //   voordat de afterload guard ze hoeft te schalen.
        // ─────────────────────────────────────────────
        if (commandedDose > 0.0) {

            // Laag 1: futureDrop60 guard
            // futureDrop60 is in mmol/L (deltaIob * effectiveISF).
            // Drempel: 0.56 mmol (= 10 mg/dL equivalent).
            // Bereik: 0.80 mmol span (0.56-1.36 mmol) met max 80% reductie.
            // Steile curve: bij fd60=0.99 mmol (typische piek) al 57% reductie.
            // Vloer 20%: nooit meer dan 80% reductie zodat er altijd een kleine
            // correctiedosis mogelijk blijft bij echte tweede gang.
            // Normale frontload heeft fd60 < 0.10 mmol → guard volledig inactief.
            val futureDrop60Mmol = peak.futureDrop60   // in mmol/L
            // iob_ratio bepaalt hoe sterk de fd60 guard actief is:
            //   iob_r < 0.50: frontload nog volop nodig → guard inactief
            //   iob_r 0.50-0.65: overgang → guard gradueel actief
            //   iob_r > 0.65: genoeg insuline aan boord → guard volledig actief
            // Dit onderscheidt correctie: bij episode 1 (iob_r 0.62-0.83)
            // remt de guard terecht, bij episode 2 met lage iob_r (0.47-0.56)
            // laat de guard de frontload vrij.
            val fd60IobFactor: Double = when {
                ctx.iobRatio < 0.50 -> 0.0
                ctx.iobRatio <= 0.65 -> (ctx.iobRatio - 0.50) / 0.15
                else -> 1.0
            }
            // Slope-demper: als BG nog fors stijgt is de futureDrop60 voorspelling
            // wel correct (IOB gáát de BG doen dalen), maar die daling komt PAS
            // nadat de BG zijn piek heeft bereikt. Ondertussen moet het systeem nog
            // kunnen doseren. De guard wordt daarom deels opgeschort bij actieve stijging:
            //   slope >= 3.0 mmol/h → guard op 30% (BG stijgt harder dan insuline werkt)
            //   slope >= 2.0 mmol/h → guard op 55%
            //   slope >= 1.0 mmol/h → guard op 80%
            //   slope < 1.0         → guard volledig actief (BG stabiel/dalend)
            // Achtergrond 28/06/2026 (Ecko): bij de maaltijden van 25–28 juni remde
            // de guard al op 0.43–0.65 terwijl BG met 5–8 mmol/h steeg naar 12–14 mmol.
            // De guard combineert dan met late_decay_mul=0.10 en commitIobFactor=0.22 tot
            // een effectieve dosis van <0.05U op het commit-pad — de EarlyBoost is dan
            // de enige route, maar die stopt bij commit 4 (iobRatio > 0.50).
            val slopeAfterloadDemper = when {
                ctx.slope >= 3.0 -> 0.30
                ctx.slope >= 2.0 -> 0.55
                ctx.slope >= 1.0 -> 0.80
                else             -> 1.0
            }
            val effectiveFd60IobFactor = fd60IobFactor * slopeAfterloadDemper

            val futureDrop60Scale: Double = if (futureDrop60Mmol <= 8.0 || effectiveFd60IobFactor == 0.0) {
                1.0
            } else {
                val baseReductie = when {
                    futureDrop60Mmol <= 12.0 -> ((futureDrop60Mmol - 8.0) / 4.0) * 0.40
                    else -> (0.40 + ((futureDrop60Mmol - 12.0) / 8.0) * 0.40).coerceAtMost(0.80)
                }
                (1.0 - baseReductie * effectiveFd60IobFactor).coerceAtLeast(0.20)
            }

            // Laag 1b: tijdelijke verzwaring direct na een grote commit
            //
            // Achtergrond (17/06-incident): een korte, scherpe stijging
            // (snoep/drop) gaf in twee opeenvolgende cycli 4.69U + 2.60U.
            // Op het moment van de tweede dosis was futureDrop60 nog maar
            // net over de 8.0-drempel gekomen (de IOB-sprong van de eerste
            // dosis was nog vers), waardoor laag 1 maar 11% afremde — te
            // weinig om de stapeling te voorkomen. Analyse van 51 historische
            // episodes toonde geen betrouwbaar vroeg kinematisch signaal om
            // zo'n korte, "lege" stijging te onderscheiden van een echte
            // maaltijd op het moment zelf — daarom grijpt deze laag niet in
            // op basis van curve-vorm, maar puur op recente dosis-grootte:
            // vlak na een forse commit is voorzichtigheid altijd verstandig,
            // ongeacht de oorzaak van de stijging.
            //
            // Werking: binnen ~10 min (2 cycli) na een commit >= 1.5U wordt
            // dezelfde fd60-curve als laag 1 toegepast, maar met een lagere
            // bodem (max 90% reductie i.p.v. 80%) — kortdurend en zelf-
            // beperkend, want zodra het venster verstrijkt of futureDrop60
            // weer onder 8.0 zakt is deze laag net als laag 1 inactief.
            // Normale, doorlopende maaltijden met geleidelijk oplopende IOB
            // raken deze laag niet: die houden futureDrop60 doorgaans onder
            // de 8.0-drempel totdat het venster al verstreken is.
            val minutesSinceLastBigCommit = lastBigCommitAtSnapshot?.let {
                org.joda.time.Minutes.minutesBetween(it, now).minutes
            } ?: 999
            val postBigCommitActive =
                lastBigCommitDoseSnapshot >= 1.5 && minutesSinceLastBigCommit in 0..10
            val postBigCommitScale: Double = if (postBigCommitActive && futureDrop60Mmol > 8.0 && fd60IobFactor > 0.0) {
                val baseReductie = when {
                    futureDrop60Mmol <= 12.0 -> ((futureDrop60Mmol - 8.0) / 4.0) * 0.40
                    else -> (0.40 + ((futureDrop60Mmol - 12.0) / 8.0) * 0.40).coerceAtMost(0.80)
                }
                (1.0 - baseReductie * fd60IobFactor * 1.5).coerceAtLeast(0.10)
            } else 1.0
            if (postBigCommitActive && postBigCommitScale < 0.999) {
                status.append(
                    "POST-COMMIT REM ×${"%.2f".format(postBigCommitScale)} " +
                        "(${minutesSinceLastBigCommit}min na ${"%.2f".format(lastBigCommitDoseSnapshot)}U, fd60=${"%.1f".format(futureDrop60Mmol)})\n"
                )
            }

            // Laag 2: hoge IOB + late fase guard
            val minutesSinceEpisode = mealEpisodeStartTime?.let {
                org.joda.time.Minutes.minutesBetween(it, now).minutes
            } ?: 0
            // Tijdsgrens 40 min: remmen zodra maaltijdcurve begint af te vlakken
            val isLatePhase = minutesSinceEpisode > 40
            // Drempel 0.70: ook bij iob_ratio 0.70-0.79 al licht remmen
            val highIobLateWaveScale: Double = if (isLatePhase && ctx.iobRatio > 0.70) {
                val excess = (ctx.iobRatio - 0.70) / 0.30   // 0..1 over bereik 0.70-1.00
                1.0 - (excess * 0.60).coerceIn(0.0, 0.60)   // max 60% reductie
            } else 1.0

            // Laag 2b: late tweede golf — onafhankelijk van actuele iobRatio
            //
            // Achtergrond (12/06-episode): na een eerste piek+daling kan de
            // IOB tussentijds uitwerken (iob_ratio terug naar ~0.10) terwijl
            // de BG vervolgens een TWEEDE keer stijgt door dezelfde
            // (vetrijke) maaltijd. Laag 2 grijpt dan niet in omdat
            // iobRatio > 0.70 niet gehaald wordt — het systeem "ziet" een
            // frisse episode en geeft een volle frontload-dosis, die samen
            // met de net-uitgewerkte IOB-rest overshoot en een late hypo
            // veroorzaakt.
            //
            // Laag 2b kijkt daarom alleen naar minutesSinceEpisode, los van
            // iobRatio: hoe langer de episode al loopt, hoe meer een nieuwe
            // forse dosis wordt teruggeschaald. Tussen 90-180 min loopt de
            // reductie lineair op tot max 60%; binnen 90 min (normale
            // episodes) is deze laag volledig inactief.
            val lateSecondWaveScale: Double = if (minutesSinceEpisode > 90) {
                val frac = ((minutesSinceEpisode - 90).toDouble() / 90.0).coerceAtMost(1.0)
                1.0 - frac * 0.60
            } else 1.0

            val afterloadScale = futureDrop60Scale * highIobLateWaveScale * lateSecondWaveScale * postBigCommitScale
            logRow.afterloadFutureDrop60Scale = futureDrop60Scale
            logRow.afterloadHighIobLateScale  = highIobLateWaveScale
            // lateSecondWaveScale en postBigCommitScale zijn bewust niet
            // toegevoegd aan LogRow/Entity: dat zou een Room schema-bump
            // vereisen die (met de huidige fallbackToDestructiveMigration)
            // de 7-dagen cyclus-log wist. Beide waarden zijn zichtbaar via
            // de status-regels (hieronder resp. hierboven).

            if (afterloadScale < 1.0 - 1e-9) {
                val beforeAfterload = commandedDose
                commandedDose *= afterloadScale
                status.append(
                    "AFTERLOAD GUARD: fd60=${"%.2f".format(futureDrop60Mmol)}mmol " +
                        "fdScale=${"%.2f".format(futureDrop60Scale)} " +
                        "iobR=${"%.2f".format(ctx.iobRatio)} " +
                        "late=${isLatePhase}(${minutesSinceEpisode}min) " +
                        "hiScale=${"%.2f".format(highIobLateWaveScale)} " +
                        "lateWaveScale=${"%.2f".format(lateSecondWaveScale)} " +
                        "→ dose ${"%.2f".format(beforeAfterload)}→${"%.2f".format(commandedDose)}U\n"
                )
            }
        }


        // ─────────────────────────────────────────────
        // 🧯 DOWN-TREND FINAL DOSE GATE (last line of defense)
        if (downGate.locked && mealSignal.state == MealState.NONE) {
            status.append("DOWNTREND LOCKED (no-meal): commandedDose forced to 0\n")
            commandedDose = 0.0
            earlyConfirmDone = false
        }

        // ─────────────────────────────────────────────



        // ─────────────────────────────────────────────
        // 🔟 Execution: SMB / hybride bolus + basaal
        // ─────────────────────────────────────────────
        val execution = executeDelivery(
            dose = commandedDose,
            hybridPercentage = config.hybridPercentage,
            cycleMinutes = config.deliveryCycleMinutes,
            maxTempBasalRate = config.maxTempBasalRate,
            smallDoseThreshold = config.smallDoseThresholdU
        )
        val deliveredNow = execution.deliveredTotal
        val minDeliveryU = config.minDeliverDose

        // ✅ NEW: als het onder de zichtbare/werkelijke delivery drempel is, behandel als 0
        val effectiveDeliveredNow =
            if (deliveredNow >= minDeliveryU) deliveredNow else 0.0
        if (commandedDose > 0.0 && deliveredNow > 0.0 && effectiveDeliveredNow == 0.0) {
            logRow.guardMinDeliverClipped = true
        }


        val effectiveBolus =
            if (deliveredNow >= minDeliveryU) execution.bolus else 0.0

        val effectiveBasalRate =
            if (deliveredNow >= minDeliveryU) execution.basalRate else 0.0

        // ✅ Alleen echte, zichtbare afleveringen loggen
        if (effectiveDeliveredNow >= minDeliveryU) {
            deliveryHistory.addFirst(Triple(now, effectiveDeliveredNow, true))
            while (deliveryHistory.size > MAX_DELIVERY_HISTORY) {
                deliveryHistory.removeLast()
            }
        }




        status.append(
            "DELIVERY: dose=${"%.2f".format(commandedDose)}U " +
                "basal=${"%.2f".format(effectiveBasalRate)}U/h " +
                "bolus=${"%.2f".format(effectiveBolus)}U " +
                "(${config.deliveryCycleMinutes}m)\n"
        )


        val shouldDeliver =
            if (persistentOverrideActive) {
                persistResult.fired && effectiveDeliveredNow >= minDeliveryU
            } else {
                effectiveDeliveredNow >= minDeliveryU
            }

        val rescueSignal = updateRescueDetection(
            ctx = ctx,
            now = now,
            config = config,
            deliveredThisCycle = effectiveDeliveredNow,
            pred60 = pred60
        )


        status.append("RESCUE: state=${rescueSignal.state} conf=${"%.2f".format(rescueSignal.confidence)} pred60=${"%.2f".format(rescueSignal.pred60)}\n")
        if (rescueSignal.armed || rescueSignal.confirmed) {
            status.append("RESCUE: ${rescueSignal.reason}\n")
        }



        val minutesSinceCommit =
            if (lastCommitAt != null)
                org.joda.time.Minutes.minutesBetween(lastCommitAt, now).minutes
            else
                -1


        // ─────────────────────────────────────────────
        // Parameter snapshot logging (laagfrequent)
        // ─────────────────────────────────────────────

        profileParamLogger.maybeLog()


        // ─────────────────────────────────────────────
        // CSV logging (analyse / tuning)
        // ─────────────────────────────────────────────

        // ─────────────────────────────────────────────
        // Meal episode CSV fields
        // ─────────────────────────────────────────────

        if (activeMealEpisodeId != -1L && mealEpisodeStartTime != null && mealEpisodeStartBg != null) {

            val minutesSinceStart =
                org.joda.time.Minutes.minutesBetween(mealEpisodeStartTime, now).minutes

            val riseSinceStart =
                ctx.input.bgNow - mealEpisodeStartBg!!

            logRow.mealEpisodeId = activeMealEpisodeId
            logRow.minutesSinceMealStart = minutesSinceStart
            logRow.riseSinceMealStart = riseSinceStart

        } else {

            logRow.mealEpisodeId = -1
            logRow.minutesSinceMealStart = -1
            logRow.riseSinceMealStart = 0.0
        }

        logRow.effectiveISF = input.effectiveISF
        logRow.gain = config.gain
        logRow.energyBase = energy
        logRow.energyTotal = energyTotal



        logRow.stagnationActive = stagnationBoost > 0.0
        logRow.stagnationBoost = stagnationBoost


        logRow.rawDose = rawDose
        logRow.iobFactor = iobFactor
        logRow.normalDose = finalDose

        logRow.earlyStage = earlyDose.stage
        logRow.earlyConfidence = earlyDose.lastConfidence
        logRow.earlyTargetU = early.targetU
        // earlyBoost velden — waren altijd default (1.0/false/0) omdat ze niet werden gezet
        logRow.earlyBoostActive  = early.boostActive
        logRow.earlyBoostFactor  = early.effectiveBoostFactor
        logRow.earlyBoostCount   = early.boostCommitNr

        // Basisvelden die ook ontbraken
        logRow.isNight  = input.isNight
        logRow.bg       = ctx.input.bgNow
        logRow.target   = ctx.input.targetBG

        logRow.mealState = mealSignal.state.name
        logRow.commitFraction = commitFraction

        logRow.minutesSinceCommit = minutesSinceCommit

        logRow.peakState = peak.state.name
        logRow.predictedPeak = predictedPeak
        logRow.peakIobBoost = peakIobBoost
        logRow.effectiveIobRatio = boostedIobRatio

        logRow.peakMaxSlope = peak.maxSlope
        logRow.peakMomentum = peak.momentum
        logRow.peakRiseSinceStart = peak.riseSinceStart
        logRow.peakEpisodeActive = peakEstimator.active

        logRow.lateDecayMul = lateDecayMul
        // BUGFIX (11/07/2026, Ecko): stond hier als episodeCommitCount (zonder
        // +1), terwijl de beslissing zelf (cappedFinalDose, decayFloor) intern
        // commitNr = episodeCommitCount + 1 gebruikt. Het gelogde getal liep
        // dus structureel 1 achter op wat er echt gebeurde — verwarrend bij
        // het terugkijken in de CSV (bijv. "commit_nr=4" terwijl er intern al
        // met commit 5 werd gerekend). Puur een logging-correctie, geen
        // gedragswijziging: de dosering zelf was hier nooit fout, alleen het
        // getal dat ervan werd getoond.
        logRow.episodeCommitNr = episodeCommitCount + 1
        logRow.bgStijgtNogFors = lastBgStijgtNogFors
        logRow.commitNrUsed = lastCommitNrUsed
        logRow.suppressForPeak = suppressForPeak
        logRow.absorptionActive = isInAbsorptionWindow(now, config)
        logRow.peakIobBrakeActive = postPeak.peakBrake.reason != "NONE"
        logRow.reentrySignal = reentry
        logRow.decisionReason = decision.reason

        // ── V5: suppress/lockout redenen ──
        logRow.suppressReason = postPeak.suppressReason
        logRow.lockoutReason = postPeak.lockoutReason
        logRow.commitBlockReason = postPeak.commitBlockReason

        // ── V5: marges tot drempels ──
        val dynamicIobThresholdForLog = (0.30 + 0.07 * ctx.deltaToTarget).coerceIn(0.30, 0.70)
        logRow.iobMarginToBrake = ctx.iobRatio - 0.62
        logRow.iobMarginToLockout = ctx.iobRatio - dynamicIobThresholdForLog
        logRow.predMarginToWatching = predictedPeak - 9.0
        logRow.predMarginToTarget = predictedPeak - 10.0
        logRow.slopeMarginToBrake = ctx.slope - 0.50

        // ── V5: peak internals ──
        logRow.predictedPeakBallistic = peak.predictedPeakBallistic
        logRow.futureDrop60 = peak.futureDrop60
        logRow.peakFloorActive = peak.peakFloorActive
        logRow.peakFloorValue = peak.peakFloorValue
        logRow.hEff = peak.hEff
        logRow.iobScaleUsed = peak.iobScaleUsed
        logRow.vUsed = peak.vUsed

        // ── V5: doseerruimte ──
        logRow.iobHeadroom = (input.maxIOB - input.currentIOB).coerceAtLeast(0.0)
        logRow.doseSuppressedU = if (suppressForPeak && finalDose > commandedDose)
            finalDose - commandedDose else 0.0
        logRow.peakApproachActive = logRow.peakApproachFactor < 1.0 - 1e-9
        logRow.earlyResetThisCycle = earlyResetThisCycle
        logRow.downtrendLocked = downGate.locked
        logRow.sensorBlipActive = postPeak.sensorBlip

        logRow.doseAccess = accessLevel.name


        logRow.pred60 = rescueSignal.pred60
        logRow.rescueState = rescueSignal.state.name
        logRow.rescueConfidence = rescueSignal.confidence
        logRow.rescueReason = rescueSignal.reason


        logRow.finalDose = finalDose
        logRow.commandedDose = commandedDose
        logRow.deliveredTotal = effectiveDeliveredNow
        logRow.externalBolusU = input.externalBolusU
        // Update prevFclDose voor externe bolus detectie volgende cyclus
        prevFclDoseForExternalDetect = effectiveDeliveredNow
        logRow.bolus = effectiveBolus
        logRow.basalRate = effectiveBasalRate

        logRow.reserveU = reservedInsulinU
        logRow.reserveAction = reserveActionThisCycle
        logRow.reserveDeltaU = reserveDeltaThisCycle
        logRow.reserveAgeMin =
            if (reserveAddedAt != null)
                org.joda.time.Minutes.minutesBetween(reserveAddedAt, now).minutes
            else -1

        logRow.shouldDeliver = shouldDeliver


        cycleLogRepository.insert(logRow.toEntity())


        // ─────────────────────────────────────────────
        // RETURN
        // ─────────────────────────────────────────────
        return FCLvNextAdvice(
            bolusAmount = effectiveBolus,
            basalRate = effectiveBasalRate,
            shouldDeliver = shouldDeliver,
            effectiveISF = input.effectiveISF,
            targetAdjustment = 0.0,

            predictedPeak = predictedPeak,
            peakBand = peak.peakBand,
            peakState = peak.state.name,
            secondDerivative = ctx.acceleration,

            statusText = status.toString()
        )
    }
}