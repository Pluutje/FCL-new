package app.aaps.plugins.aps.openAPSFCL.vnext


import android.annotation.SuppressLint
import app.aaps.core.interfaces.meal.MealIntentRepository
import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCsvLogger
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextParameterLogger
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextProfileParameterSnapshot
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCsvLogRow
import app.aaps.plugins.aps.openAPSFCL.vnext.meal.PreBolusController
import app.aaps.core.interfaces.meal.MealIntentType
import app.aaps.plugins.aps.openAPSFCL.vnext.model.computeIobOvershootFactor
import app.aaps.plugins.aps.openAPSFCL.vnext.model.clampDoseByIob
import app.aaps.plugins.aps.openAPSFCL.vnext.meal.MealIntentOverlay
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileFunction


import kotlin.math.roundToInt

data class FCLvNextInput(
    val bgNow: Double,                          // mmol/L
    val bgHistory: List<Pair<DateTime, Double>>, // mmol/L
    val currentIOB: Double,
    val maxIOB: Double,
    val effectiveISF: Double,                   // mmol/L per U
    val targetBG: Double,                       // mmol/L
    val isNight: Boolean
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
            // Gebruik recent slope voor snelle detectie.
            // Factor 0.3 → 0.5: IN_RANGE gebruikt nu dezelfde gewichting als MID,
            // zodat een agressieve fast-lane stijging ook bij lage BG doorkomt.
            // Accel-drempel 0.10 → 0.06: voorkomt dat een net-begonnende maaltijdstijging
            // geblokkeerd blijft doordat versnelling nog iets achterloper op de sensordata.
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
    var lastConfidence: Double = 0.0
)

private var earlyDose = EarlyDoseContext()

// ── PRE-PEAK IMPULSE STATE ──
private var prePeakImpulseDone: Boolean = false
private var lastSegmentAt: DateTime? = null

private var lastSmallCorrectionAt: DateTime? = null

private var earlyConfirmDone: Boolean = false


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

private const val MAX_DELIVERY_HISTORY = 6

val deliveryHistory: ArrayDeque<Pair<DateTime, Double>> =
    ArrayDeque()

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
    val state = when {
        rising && accelerating && aboveTarget && confidence >= config.mealConfirmConfidence ->
            MealState.CONFIRMED

        (rising || accelerating) && aboveTarget && confidence >= config.mealUncertainConfidence ->
            MealState.UNCERTAIN

        else -> MealState.NONE
    }

    val reason = "MealSignal=$state conf=${"%.2f".format(confidence)}"
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
        if (minutesSinceEpisodeStart in 0..15 && ctx.iobRatio < 0.65) {
            effectiveCooldown.coerceAtMost(5)   // maximaal 5 minuten wachten
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

private fun commitFractionZoneFactor(bgZone: BgZone): Double {
    return when (bgZone) {
        BgZone.LOW -> 0.0
        BgZone.IN_RANGE -> 0.55
        BgZone.MID -> 0.75
        BgZone.HIGH -> 1.00
        BgZone.EXTREME -> 1.10
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

        // Worst-case downtrend:
        val vEff = minOf(vMacro, vFast * 0.9)

        val a = ctx.acceleration.coerceIn(-1.2, 1.2)

        return ctx.input.bgNow +
            vEff * tHr +
            0.5 * a * tHr * tHr
    }

    // Conservatieve “worst-case” insulin impact fracties
    // (veiligheids-gate: liever te streng dan te los).
    fun insulinActionFrac(min: Int): Double = when {
        min <= 30 -> config.hypoInsulinFrac30
        min <= 60 -> config.hypoInsulinFrac60
        else      -> config.hypoInsulinFrac90
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
    val fastLaneRising =
        mealSignal != null &&
            mealSignal.state == MealState.CONFIRMED &&
            ctx.recentSlope >= 5.0 &&
            ctx.recentDelta5m >= 0.30 &&
            ctx.iobRatio < 0.25 &&
            ctx.input.bgNow >= 5.5 &&
            projectedMinNoInsulin >= 4.8

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
    val clearlyRisingMealContext = mealSignal != null &&
        mealSignal.state == MealState.CONFIRMED &&
        ctx.slope >= 0.6 &&
        ctx.recentDelta5m >= 0.0 &&
        ctx.iobRatio < 0.50 &&
        projectedMinNoInsulin >= 4.8 &&
        projectedMinWithInsulin >= 4.0   // voorkomt bypass als dosis zelf gevaarlijk is

    if (clearlyRisingMealContext) {
        return HypoProtection(
            active = false,
            projectedMin = projectedMin,
            projectedMinNoInsulin = projectedMinNoInsulin,
            projectedMinWithPlannedInsulin = projectedMinWithInsulin,
            reason = "HYPO PROTECT BYPASSED (confirmed meal, rising, safe no-insulin projection)"
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
        for ((t, u) in deliveryHistory) {
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
            ctx.iobRatio <= MICRO_IOB_MAX &&
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
    val hasIob = ctx.iobRatio >= 0.30

    val episodeLike =
        mealSignal.state != MealState.NONE || peakEstimator.active || peak.state != PeakPredictionState.IDLE

    val shouldGuard = reliable && episodeLike && hasIob && fastPlateau && (nearPeak || braking) && ctx.deltaToTarget >= 0.8

    if (!shouldGuard) return TopGuard(false, 1.0, "TOPGUARD off")

    // CapFactor: hoe harder als we dichter bij top zijn / meer IOB hebben
    val iobSeverity = smooth01((ctx.iobRatio - 0.30) / 0.40)           // 0..1
    val peakCloseness = 1.0 - smooth01(((peak.predictedPeak - ctx.input.bgNow) - 0.2) / (0.8 - 0.2))
    val severity = (0.55 * iobSeverity + 0.45 * peakCloseness).coerceIn(0.0, 1.0)

    val cap = (1.0 - 0.70 * severity).coerceIn(0.20, 0.65)  // hard knijpen: 20–65% over

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
    preBolusActive: Boolean
): LateBolusBlock {

    // Prebolus chunks niet tegenwerken (user-intent)
    if (preBolusActive) return LateBolusBlock(false, false, "LATEBOLUS off (prebolus)")

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
        ctx.iobRatio >= 0.35

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
    for ((t, u) in deliveryHistory) {
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
    config: FCLvNextConfig
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

    // Peak pressure (optioneel, mild)
    val peakPressure = smooth01((peak.predictedPeak - 11.0) / (17.0 - 11.0)) * 0.10

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
        reason = "AGGR a=${"%.2f".format(a)} (v5=${"%.2f".format(v5)} accel=${"%.2f".format(ctx.acceleration)} delta=${"%.2f".format(ctx.deltaToTarget)} cons=${"%.2f".format(ctx.consistency)})"
    )
}

private data class EarlyDoseDecision(
    val active: Boolean,
    val stageToFire: Int,          // 0=none, 1=probe, 2=boost
    val confidence: Double,        // 0..1
    val targetU: Double,           // floor target for commandedDose
    val reason: String
)

private fun computeEarlyDoseDecision(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    trend: TrendDecision,
    bgZone : BgZone,
    now: DateTime,
    config: FCLvNextConfig
): EarlyDoseDecision {

    if (ctx.consistency < config.episodeMinConsistency) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY: low consistency")
    }

    // Fastlane veto: CGM laat al dip zien → geen early push
    if (ctx.recentDelta5m <= -0.06 || ctx.recentSlope <= -0.20) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY blocked: fastlane dip")
    }

    if ((bgZone == BgZone.LOW || bgZone == BgZone.IN_RANGE) && ctx.iobRatio >= 0.55) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY blocked: low BG zone")
    }


    if (peak.state == PeakPredictionState.CONFIRMED) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY: peak confirmed")
    }

    val slopeScore = smooth01((ctx.slope - 0.20) / (1.20 - 0.20))
    val accelScore = smooth01((ctx.acceleration - (-0.02)) / (0.15 - (-0.02)))
    val deltaScore = smooth01((ctx.deltaToTarget - 0.0) / 1.6)
    val consistScore = smooth01((ctx.consistency - 0.45) / 0.35)
    val iobRoom = 1.0 - smooth01((ctx.iobRatio - 0.20) / 0.50)

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

    var conf =
        0.32 * slopeScore +
            0.30 * accelScore +
            0.18 * deltaScore +
            0.10 * consistScore +
            0.10 * iobRoom +
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
// ✅ Stage2 eerder als de voorspelde piek groot is (timing fix, niet meer totaal)
    val stage2Min =
        if (peak.predictedPeak >= 16.0) 0.48 else 0.55

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
    val stageToFire = when {
        earlyDose.stage == 0 && conf >= dynamicStage1Min -> 1
        earlyDose.stage == 1 && conf >= stage2Min &&
            minutesSinceLastFire >= 5 && allowLarge -> 2
        else -> 0
    }
    if (earlyDose.stage == 1 && conf >= stage2Min && minutesSinceLastFire >= 5 && !allowLarge) {
        return EarlyDoseDecision(false, 0, conf, 0.0, "EARLY: stage2 blocked (trend=${trend.state})")
    }

    if (stageToFire == 0) {
        return EarlyDoseDecision(false, 0, conf, 0.0, "EARLY: no fire")
    }

    val (minF, maxF) =
        if (stageToFire == 1) 0.40 to 0.70 else 0.55 to 0.90

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


    val targetU =
        maxOf(
            config.maxSMB * factor * config.doseStrengthMul,
            minEarlyU
        )


    return EarlyDoseDecision(
        active = true,
        stageToFire = stageToFire,
        confidence = conf,
        targetU = targetU,
        reason = "EARLY: stage=$stageToFire conf=${"%.2f".format(conf)}"
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
    val commitBlockReason: String
)

private fun evaluatePostPeak(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    now: DateTime,
    config: FCLvNextConfig,
    mealIntentOrPrebolusActive: Boolean,
    minutesSinceEpisodeStart: Int = 999
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
    // 3. deltaToTarget >= 3.0 (voldoende marge voor volledige IOB-staart bij vals signaal)
    // 4. recentDelta5m >= 0.30 (meetbare snelle stijging, geen CGM-drift)
    val newMealOverride =
        mealSignal.state == MealState.CONFIRMED &&
            minutesSinceEpisodeStart >= 5 &&
            ctx.deltaToTarget >= 3.0 &&
            ctx.recentDelta5m >= 0.30

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
            ctx.iobRatio >= 0.45 &&
            ctx.deltaToTarget >= 1.0 &&
            ctx.acceleration <= 0.06 &&      // jouw 13:47 accel=0.03 moet dit raken
            fastPlateau

    // ✅ NIEUW: sensor-blip / trend conflict guard
// Slow-lane (macro) daalt, maar fast-lane (laatste 5-10m) lijkt ineens te stijgen.
// Dit is typisch CGM-spike gedrag → niet corrigeren.
    val slowFalling = ctx.slope <= -0.30
    val fastRising = (ctx.recentSlope >= 1.50) || (ctx.recentDelta5m >= 0.20)

    val sensorBlip =
        !mealIntentOrPrebolusActive &&      // ✅ CRUCIAAL: niet blokkeren als user “meal context” gaf
            episodeLike && reliable &&
            ctx.deltaToTarget >= 1.0 &&
            ctx.iobRatio >= 0.25 &&
            slowFalling && fastRising


    val risingAgainTail =
        ctx.recentSlope >= 0.35 ||
            ctx.recentDelta5m >= 0.08

    val tailSuppress =
        episodeLike && reliable &&
            peak.riseSinceStart >= 1.0 &&
            ctx.deltaToTarget >= 1.0 &&
            ctx.iobRatio >= 0.30 &&
            fastPlateau &&
            !risingAgainTail

    // ✅ NIEUW: Predictieve IOB-rem — vlak voor de piek, stijging vlakt af, IOB al hoog
    // Doel: stop doseren voordat suppress_for_peak formeel actief wordt.
    // Bewust GESCHEIDEN van iobOvershoot (die geeft juist ruimte bij echte stijging).
    val peakIobBrake =
        episodeLike &&
            reliable &&
            peak.state != PeakPredictionState.IDLE &&
            ctx.iobRatio >= 0.62 &&
            ctx.slope <= 0.50 &&
            ctx.acceleration <= 0.08 &&
            ctx.deltaToTarget >= 0.8

    // Basale post-peak kenmerken
    val flattening = ctx.acceleration <= 0.05
    val notRising = ctx.slope < 0.60
    val highIob = ctx.iobRatio >= 0.55

    // SUPPRESS: mild, alleen tijdens absorption
    val suppress =
        reliable && (
            // bestaande post-commit suppress — niet bij bewezen nieuwe maaltijdstijging
            (inAbsorption && !newMealOverride &&
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
            (inAbsorption && !newMealOverride &&
                ((ctx.slope <= config.peakSlopeThreshold) || (ctx.acceleration <= config.peakAccelThreshold)) &&
                (ctx.iobRatio >= dynamicIobThreshold)
                )
                || (preCommitTop && ctx.iobRatio >= 0.55)
                // ✅ NIEUW: bij sensorBlip liever hard stoppen met pushen
                || sensorBlip
                // ✅ NIEUW: harde stop als IOB echt hoog is vlak voor piek
                || (peakIobBrake && ctx.iobRatio >= 0.70)
            )


    // COMMIT BLOCK: voorkomen van “commit na omkeer”
    val commitBlocked =
        reliable && (
            (ctx.acceleration < -0.05 && ctx.iobRatio >= 0.45)
                || (preCommitTop && ctx.iobRatio >= 0.45)
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
        commitBlockReason = commitBlockReason
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
        ctx.acceleration <= -0.10 && ctx.iobRatio >= 0.35 && ctx.consistency >= 0.45
    ) return true
    // early bescherming alleen zolang we nog niet afremmen / piek hebben
    if (isEarlyProtectionActive(earlyStage, ctx, peak)) return false

    // nooit hard block bij meal
    if (mealSignal.state != MealState.NONE) return false

    if (earlyStage > 0) return false

    val highIob = ctx.iobRatio >= 0.70
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


private fun heightEscalationFactor(
    ctx: FCLvNextContext,
    peak: PeakEstimate,
    mealSignal: MealSignal,
    config: FCLvNextConfig
): Double {

    // Alleen vóór de piek, anders nooit harder
    if (peak.state == PeakPredictionState.CONFIRMED) return 1.0

    // Geen meal-achtig gedrag → niets doen
    if (mealSignal.state == MealState.NONE && peak.predictedPeak < 11.0) return 1.0

    // ✅ IOB-ceiling: als IOB al significant is, geen escalatie toevoegen.
    // heightEscalationFactor is bedoeld voor situaties met lage IOB en hoge piek.
    // Bij iobRatio > 0.35 bouwt de IOB snel op — extra escalatie verergert
    // het post-piek hypo-risico en wordt hier uitgeschakeld.
    if (ctx.iobRatio > 0.35) return 1.0

    // ── Normalisaties (alles 0..1) ──
    val slopeScore =
        smooth01((ctx.slope - 0.35) / (1.4 - 0.35))

    val accelScore =
        smooth01((ctx.acceleration - 0.05) / (0.35 - 0.05))

    val deltaScore =
        smooth01((ctx.deltaToTarget - 0.8) / (3.5 - 0.8))

    val consistencyScore =
        smooth01((ctx.consistency - 0.45) / (0.80 - 0.45))

    val peakPressure =
        smooth01((peak.predictedPeak - 11.0) / (17.0 - 11.0))

    // ── Combineer tot “meal momentum” ──
    val momentum =
        0.30 * slopeScore +
            0.30 * accelScore +
            0.20 * deltaScore +
            0.20 * consistencyScore

    // ── Escalatie: basis + extra druk van verwachte piek ──
    val baseBoost = lerp(1.0, 1.35, momentum)
    val peakBoost = lerp(1.0, 1.30, peakPressure)

    val factor = baseBoost * peakBoost

    // Veilig clampen
    return factor.coerceIn(1.0, 1.70)
}



class FCLvNext(
    private val preferences: Preferences,
    private val preBolusController: PreBolusController,
    private val mealIntentOverlay: MealIntentOverlay,
    private val iobCobCalculator: IobCobCalculator,      // ✅ NIEUW
    private val profileFunction: ProfileFunction         // ✅ NIEUW
) {

    private var mealEpisodeCounter: Long = 0
    private var activeMealEpisodeId: Long = -1
    private var mealEpisodeStartTime: DateTime? = null
    private var mealEpisodeStartBg: Double? = null


    private fun computeFutureInsulinDrop60m(
        now: DateTime,
        effectiveISF: Double
    ): Double {

        val profile = profileFunction.getProfile(now.millis) ?: return 0.0

        val nowIob = iobCobCalculator
            .calculateFromTreatmentsAndTemps(now.millis, profile)

        val futureTime = now.plusMinutes(60)

        val futureIob = iobCobCalculator
            .calculateFromTreatmentsAndTemps(futureTime.millis, profile)

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

        // ── episode init/reset ──
        if (!peakEstimator.active && episodeShouldBeActive) {
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
            earlyDose = EarlyDoseContext()
            earlyConfirmDone = false
        }

        // ── episode exit (niet te snel!) ──
        if (peakEstimator.active && !episodeShouldBeActive) {
            // exit pas als we echt “uit de meal dynamiek” zijn:
            val fallingClearly = ctx.slope <= -0.6 && ctx.consistency >= config.episodeMinConsistency
            val lowDelta = ctx.deltaToTarget < 0.2 && ctx.acceleration <= 0.0
            if (fallingClearly || lowDelta) {
                peakEstimator.active = false
                peakEstimator.state = PeakPredictionState.IDLE
                peakEstimator.confirmCounter = 0
                earlyDose = EarlyDoseContext()
                earlyConfirmDone = false
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

        return FCLvNextContext(
            input = input,
            slope = trends.firstDerivative,
            acceleration = trends.secondDerivative,
            consistency = trends.consistency,
            recentSlope = trends.recentSlope,
            recentDelta5m = trends.recentDelta5m,
            iobRatio = iobRatio,
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


    // ================================================    Get Advice ++++++++++++++++++++++++++++++++++++++
    @SuppressLint("SuspiciousIndentation") fun getAdvice(input: FCLvNextInput): FCLvNextAdvice {
        // reset reserve logging per cycle
        reserveActionThisCycle = "NONE"
        reserveDeltaThisCycle = 0.0

        val now = DateTime.now()
        val logRow = FCLvNextCsvLogRow(
            ts = now,
            isNight = input.isNight,
            bg = input.bgNow,
            target = input.targetBG
        )
        val status = StringBuilder()


        // ─────────────────────────────────────────────
        // 1️⃣ Config & context (trends, IOB, delta)
        var config = loadFCLvNextConfig(preferences, input.isNight)

        // 🍽️ MealIntent overlay (timing only, TTL-based)


        preBolusController.applyDecay(now)

        val mealIntent = MealIntentRepository.get()

        var preBolusUForAssist = 0.0

        if (mealIntent != null) {
            preBolusUForAssist =
                when (mealIntent.type) {
                    MealIntentType.SMALL  -> preferences.get(DoubleKey.prebolus_small)
                    MealIntentType.NORMAL -> preferences.get(DoubleKey.prebolus_normal)
                    MealIntentType.LARGE  -> preferences.get(DoubleKey.prebolus_large)
                    MealIntentType.SNACK  -> mealIntent.preBolusU ?: 0.0
                }
        }

        // decay wordt bijgehouden door PreBolusController
        val pbAfterArm = preBolusController.snapshot(now)
        val decay = pbAfterArm?.decayFactor ?: 1.0

        val mealIntentEffect = mealIntentOverlay.computeEffect(
            now = now,
            maxSmb = config.maxSMB,
            preBolusU = preBolusUForAssist,
            decayFactor = decay
        )

        config = mealIntentOverlay.applyToConfig(
            base = config,
            effect = mealIntentEffect
        )

        // ─────────────────────────────────────────────
        // 🍽️ PRE-BOLUS ARM (via MealIntentRepository)
        // ─────────────────────────────────────────────

        val pbUi = preBolusController.uiSnapshot(now)

        if (
            mealIntent != null &&
            (pbUi == null || pbUi.validUntil != mealIntent.validUntil)
        ) {
            val type = mealIntent.type

            val preBolusU = when (type) {
                MealIntentType.SMALL  -> preferences.get(DoubleKey.prebolus_small)
                MealIntentType.NORMAL -> preferences.get(DoubleKey.prebolus_normal)
                MealIntentType.LARGE  -> preferences.get(DoubleKey.prebolus_large)
                MealIntentType.SNACK  -> mealIntent.preBolusU ?: 0.0
            }

            preBolusController.arm(
                type = type,
                totalU = preBolusU,
                validUntil = DateTime(mealIntent.validUntil),
                now = now,
                bgAtArm = input.bgNow  // ✅ NIEUW: Geef huidige BG door voor grace berekening
            )


            status.append(
                "PREBOLUS ARMED: ${type} ${"%.2f".format(preBolusU)}U " +
                    "until=${DateTime(mealIntent.validUntil).toString("HH:mm")}\n"
            )
        }    // ic_meal

        val ctx = buildContext(input, config)
        var pred60 = predictBg60(ctx)

        val zoneEnum = computeBgZone(ctx)

        logRow.guardIobLimited = false
        logRow.guardPeakLimited = false
        logRow.guardMaxSmbLimited = false
        logRow.guardMinDeliverClipped = false
        logRow.guardZoneLimited = false


        logRow.profielNaam = config.profielNaam
        logRow.mealDetectSpeed = config.mealDetectSpeed
        logRow.correctionStyle = config.correctionStyle
        logRow.mealhandlingStyle = config.mealHandlingStyle
        logRow.hypoProtectionStyle = config.hypoProtectionStyle
        logRow.doseDistributionStyle = config.doseDistributionStyle
        logRow.nightResponseStyle = config.nightResponseStyle

        logRow.bgZone = zoneEnum.name
        logRow.iob = input.currentIOB
        logRow.iobRatio = ctx.iobRatio

        logRow.slope = ctx.slope
        logRow.accel = ctx.acceleration
        logRow.recentSlope = ctx.recentSlope
        logRow.recentDelta5m = ctx.recentDelta5m
        logRow.consistency = ctx.consistency

        logRow.watchingFrontloadTriggered = false
        logRow.watchingFrontloadTargetU = 0.0
        logRow.watchingSlopeOk = false
        logRow.watchingDeltaOk = false
        logRow.watchingPeakRiseOk = false
        logRow.watchingIobOk = false


        status.append("PROFILE=${config.profielNaam}\n")
        status.append("MEAL_SPEED=${config.mealDetectSpeed}\n")
        status.append("CORRECTION=${config.correctionStyle}\n")
        status.append("NIGHT_RESPONSE=${config.nightResponseStyle}\n")


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
            mealEpisodeStartTime = now
            mealEpisodeStartBg = ctx.input.bgNow

            status.append("MEAL EPISODE START id=$activeMealEpisodeId\n")
        }

// EINDE episode
        if (!episodeTrigger && activeMealEpisodeId != -1L) {

            status.append("MEAL EPISODE END id=$activeMealEpisodeId\n")

            activeMealEpisodeId = -1
            mealEpisodeStartTime = null
            mealEpisodeStartBg = null
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
        // ook als short-term ruis het maskeert
        // ─────────────────────────────────────────────

        val watchingOrConfirmed =
            (peak.state == PeakPredictionState.WATCHING || peak.state == PeakPredictionState.CONFIRMED)

        val allowDespiteLongSlope =
            zoneEnum == BgZone.EXTREME && watchingOrConfirmed && ctx.recentSlope > 0.0

        val hardNoDelivery =
            downGate.pauseThisCycle ||
                (
                    !allowDespiteLongSlope &&
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
                        "delta=${"%.2f".format(ctx.deltaToTarget)}"
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
            FCLvNextCsvLogger.log(logRow)

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

        val iobPower = if (input.isNight) 2.3 else 2.1
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
// 🚀 EARLY DOSE CONTROLLER (move earlier in pipeline)
// ─────────────────────────────────────────────

        val early = computeEarlyDoseDecision(
            ctx = ctx,
            mealSignal = mealSignal,
            peak = peak,
            trend = trend,
            bgZone = zoneEnum,
            now = now,
            config = config
        )
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
        val preBolusActive = preBolusController.isActive(now)
        val mealContextActive = (mealIntent != null) || preBolusActive


        val episodeMinutesForPostPeak =
            mealEpisodeStartTime?.let {
                org.joda.time.Minutes.minutesBetween(it, now).minutes
            } ?: 999

        val postPeak = evaluatePostPeak(ctx, mealSignal, peak, now, config, mealContextActive, episodeMinutesForPostPeak)
        status.append(postPeak.reason + "\n")

        // ✅ NIEUW: early reset zodra afremmen/omkeer start
        val earlyResetThisCycle = maybeResetEarlyOnDecel(ctx, peak, now, status)

        val suppressForPeak = postPeak.suppress

        // ✅ NIEUW: Sensor-blip guard → GEEN insulin pushen op “valse opleving”
        if (postPeak.sensorBlip) {
            status.append(
                "SENSOR-BLIP GUARD: slowFalling(slope=${"%.2f".format(ctx.slope)}) " +
                    "but fastRising(recentSlope=${"%.2f".format(ctx.recentSlope)} recentΔ5m=${"%.2f".format(ctx.recentDelta5m)}) → finalDose=0\n"
            )
            finalDose = 0.0
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

// Apply early floor AFTER dampers (maar vóór cap/commit)
// ✅ NIET toepassen als we al aan het afremmen zijn (accel < 0)
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
        val heightFactor =
            heightEscalationFactor(ctx, peak, mealSignal, config)

        if (heightFactor > 1.0) {
            val before = finalDose
            finalDose *= heightFactor
            status.append(
                "HEIGHT ESCALATION: ×${"%.2f".format(heightFactor)} " +
                    "${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        }


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


        if (persistResult.active && !preBolusActive) {
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
        } else if (persistResult.active && preBolusActive) {
            status.append("PERSIST SUPPRESSED (PRE-BOLUS ACTIVE)\n")
        }


        // 🔒 markeer dat persistent de leiding heeft
        val persistentOverrideActive = persistResult.active && !preBolusActive

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
        if (finalDose > config.maxSMB) {
            status.append(
                "Cap maxSMB ${"%.2f".format(finalDose)} → ${"%.2f".format(config.maxSMB)}U\n"
            )
            finalDose = config.maxSMB
            logRow.guardMaxSmbLimited = true
        }

        // ─────────────────────────────────────────────
        // 🍽️ WATCHING FRONTLOAD (mealHandlingStyle)
        // ─────────────────────────────────────────────


// 1) Splits de voorwaarden in losse booleans (zodat we ze kunnen loggen)
        val watchingSlopeOk = (ctx.slope >= config.watchingMinSlope)
        val watchingDeltaOk = (ctx.deltaToTarget >= config.watchingMinDeltaToTarget)
        val watchingPeakRiseOk = (peak.riseSinceStart >= config.watchingMinPeakRise)
        val watchingIobOk = (ctx.iobRatio <= config.watchingMaxIobRatio)

        val watchingContextOk =
            (peak.state == PeakPredictionState.WATCHING) &&
                !suppressForPeak &&
                !postPeak.lockout &&
                !postPeak.sensorBlip &&
                (zoneEnum != BgZone.LOW)

// 2) Bepaal target (ook als het niet triggert -> handig voor analyse)
        val watchingFrontloadTargetU =
            (config.maxSMB * config.watchingFrontloadFrac)
                .coerceAtMost(config.maxSMB)

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
        logRow.watchingFrontloadTargetU = watchingFrontloadTargetU
        logRow.watchingFrontloadTriggered = watchingFrontloadTriggered

// 5) Pas dosing toe als triggered
        if (watchingFrontloadTriggered) {

            if (finalDose < watchingFrontloadTargetU) {
                status.append(
                    "WATCHING FRONTLOAD (${config.mealHandlingStyle}): " +
                        "${"%.2f".format(finalDose)}→${"%.2f".format(watchingFrontloadTargetU)}U\n"
                )
                finalDose = watchingFrontloadTargetU
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

        val aggr = computeMealAggression(ctx, peak, mealSignal, config)
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
            prePeakImpulseDone = false
            lastSegmentAt = now
            earlyDose = EarlyDoseContext()
            earlyConfirmDone = false

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


                val zoneFactor = commitFractionZoneFactor(zoneEnum)
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

                val commitAggressionMul = lerp(0.90, 1.20, aggr.a)
                logRow.commitAggressionMul = commitAggressionMul

                logRow.commitPostPeakFactor = postPeak.commitFactor
                val commitDose =
                    if (allowCommitBoost && commitAccessOk)
                        (config.maxSMB * fraction * commitIobFactor * prePeakMul * postPeak.commitFactor * rawPlateauPenalty * commitAggressionMul)
                            .coerceAtMost(config.maxSMB)
                    else 0.0
                logRow.commitDoseRaw = commitDose

                val committedDose =
                    if (peakCategory >= PeakCategory.HIGH)
                        maxOf(finalDose, commitDose * 1.15)
                    else
                        maxOf(finalDose, commitDose)
                logRow.commitDoseFinal = committedDose


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

                    lastCommitAt = now
                    lastCommitDose = committedDose
                    lastCommitReason = "${mealSignal.state} frac=${"%.2f".format(fraction)}"

                    didCommitThisCycle = true

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

        val reserveReleaseBlocked = hypoProj.active

        if (hypoProj.active) {
            status.append("${hypoProj.reason} → commandedDose=0\n")
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
            preBolusActive = preBolusActive
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

        // 10-min burst cap (exclude prebolus-active because that's user-intent chunking)
        if (!preBolusActive && commandedDose > 0.0) {

            val delivered10m = deliveredInLastMinutes(now, 10)
            logRow.burstDelivered10m = delivered10m

            // basis cap: max ~1.1×maxSMB per 10m
            // maar iets ruimer als BG echt hoog is (zone HIGH/EXTREME) én we nog niet plateau zijn
            val fastPlateau =
                ctx.recentSlope <= 0.30 && kotlin.math.abs(ctx.recentDelta5m) <= 0.04

            val highRoom = if ((zoneEnum == BgZone.HIGH || zoneEnum == BgZone.EXTREME) && !fastPlateau) 1.45 else 1.10
            val cap10m = highRoom * config.maxSMB
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
        // 🛡️ IOB HARD CAP + DYNAMIC OVERSHOOT (EXCL. PREBOLUS)
        // ─────────────────────────────────────────────



        if (!preBolusActive && commandedDose > 0.0) {

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

// ✅ NIEUW: piek-nadering IOB taper — gradueel minder ruimte als stijging afvlakt
            // Compleet los van iobOvershoot: die geeft ruimte bij stijging,
            // dit neemt ruimte weg bij afvlakking richting piek.
            if (commandedDose > 0.0) {
                val peakApproachFactor: Double =
                    if (
                        peak.state != PeakPredictionState.IDLE &&
                        ctx.slope in -0.10..0.50 &&
                        ctx.acceleration <= 0.10 &&
                        ctx.iobRatio >= 0.62
                    ) {
                        val severity = ((ctx.iobRatio - 0.62) / (0.90 - 0.62)).coerceIn(0.0, 1.0)
                        1.0 - severity * 0.20
                    } else {
                        1.0
                    }

                logRow.peakApproachFactor = peakApproachFactor
                if (peakApproachFactor < 1.0 - 1e-9) {
                    val headroom = (input.maxIOB - input.currentIOB).coerceAtLeast(0.0)
                    val reducedHeadroom = headroom * peakApproachFactor
                    val beforeTaper = commandedDose
                    commandedDose = minOf(commandedDose, reducedHeadroom)
                    if (commandedDose < beforeTaper - 1e-9) {
                        status.append(
                            "PEAK-APPROACH TAPER: iobR=${"%.2f".format(ctx.iobRatio)} " +
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
        // 🧯 DOWN-TREND FINAL DOSE GATE (last line of defense)
        if (downGate.locked && mealSignal.state == MealState.NONE) {
            status.append("DOWNTREND LOCKED (no-meal): commandedDose forced to 0\n")
            commandedDose = 0.0
            earlyConfirmDone = false
        }

        // ─────────────────────────────────────────────
        // 🍽️ PRE-BOLUS OVERRIDE (chunked, vóór delivery)
        // ─────────────────────────────────────────────
        var effectiveHybridPercentage = config.hybridPercentage

        val pbState = preBolusController.snapshot(now)

        val trancheIndex =
            if (pbState == null || pbState.totalU <= 0.0 || config.maxSMB <= 0.0) {
                0
            } else {
                val chunkSize = config.maxSMB

                val totalTranches =
                    kotlin.math.ceil(pbState.totalU / chunkSize).toInt().coerceAtLeast(1)

                val deliveredChunks =
                    kotlin.math.floor((pbState.deliveredU + 1e-9) / chunkSize).toInt()

                (deliveredChunks + 1).coerceIn(1, totalTranches)
            }

        val trancheTier =
            when (trancheIndex) {
                1 -> 1          // eerste chunk
                2 -> 2          // tweede chunk
                else -> 3       // derde en verder
            }
        val trancheDeltaThreshold =
            when (trancheTier) {
                1 -> 0.06      // eerste tranche: snel reageren
                2 -> 0.08      // tweede tranche: duidelijker stijging nodig
                else -> 0.10   // derde+: nog iets meer bevestiging
            }

        val trancheSlopeThreshold =
            when (trancheTier) {
                1 -> 0.20
                2 -> 0.30
                else -> 0.34
            }


        val preBolusFireAllowed = preBolusActive &&
            mealIntent != null &&
            zoneEnum != BgZone.LOW &&
            ctx.input.bgNow >= 4.4 &&
            (
                ctx.recentDelta5m >= trancheDeltaThreshold ||
                    ctx.recentSlope >= trancheSlopeThreshold
                )

        if (preBolusFireAllowed) {

            val chunk = preBolusController.computeChunk(now, config.maxSMB)

            if (chunk > 0.0) {
                val before = commandedDose
                val after = maxOf(commandedDose, chunk)

                if (after > before + 1e-9) {
                    commandedDose = after
                    preBolusController.consumePlannedChunk(chunk)

                    effectiveHybridPercentage =
                        (config.hybridPercentage - 20).coerceAtLeast(0)

                    val pb = preBolusController.snapshot(now)
                    status.append(
                        "PREBOLUS APPLY (tranche=$trancheIndex tier=$trancheTier): " +
                            "${"%.2f".format(before)}→" +
                            "${"%.2f".format(commandedDose)}U " +
                            "Δthr=${"%.2f".format(trancheDeltaThreshold)} " +
                            "Sthr=${"%.2f".format(trancheSlopeThreshold)} " +
                            "hybrid=${effectiveHybridPercentage}% " +
                            (pb?.let {
                                "remaining=${"%.2f".format(it.remainingU)}U validFor=${it.minutesRemaining}m"
                            } ?: "") +
                            "\n"
                    )


                } else {
                    status.append("PREBOLUS SKIP: commandedDose already >= chunk\n")
                }
            }

        } else if (preBolusController.isActive(now)) {

            // Alleen loggen als hij actief is maar NIET mag vuren
            status.append(
                "PREBOLUS HOLD: bg=${"%.2f".format(ctx.input.bgNow)} " +
                    "zone=$zoneEnum pred60=${"%.2f".format(pred60)} " +
                    "Δ5m=${"%.2f".format(ctx.recentDelta5m)} " +
                    "slope=${"%.2f".format(ctx.recentSlope)}\n"
            )
        }



        // ─────────────────────────────────────────────
        // 🔟 Execution: SMB / hybride bolus + basaal
        // ─────────────────────────────────────────────
        val execution = executeDelivery(
            dose = commandedDose,
            hybridPercentage = effectiveHybridPercentage,
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
            deliveryHistory.addFirst(now to effectiveDeliveredNow)
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

        logRow.suppressForPeak = suppressForPeak
        logRow.absorptionActive = isInAbsorptionWindow(now, config)
        logRow.peakIobBrakeActive = postPeak.suppress && peak.state != PeakPredictionState.IDLE && ctx.iobRatio >= 0.62
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

        // ─────────────────────────────────────────────
        // Pre-bolus logging fields
        // ─────────────────────────────────────────────
        val pbEnd = preBolusController.snapshot(now)

        logRow.preBolusActive = preBolusActive   // of: (pbEnd != null) als snapshot null betekent “inactief”
        logRow.preBolusType = pbEnd?.mealType?.name ?: "NONE"
        logRow.preBolusTotalU = pbEnd?.totalU ?: 0.0
        logRow.preBolusDeliveredU = pbEnd?.deliveredU ?: 0.0
        logRow.preBolusRemainingU = pbEnd?.remainingU ?: 0.0
        logRow.preBolusMinutesSinceArmed = pbEnd?.minutesSinceArmed ?: -1
        logRow.preBolusMinutesRemaining = pbEnd?.minutesRemaining ?: -1


        logRow.finalDose = finalDose
        logRow.commandedDose = commandedDose
        logRow.deliveredTotal = effectiveDeliveredNow
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

        FCLvNextCsvLogger.log(logRow)


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


