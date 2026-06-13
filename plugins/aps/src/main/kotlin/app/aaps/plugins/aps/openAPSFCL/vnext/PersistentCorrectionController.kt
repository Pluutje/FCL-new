package app.aaps.plugins.aps.openAPSFCL.vnext

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stateful persistent correction loop:
 * - Detect persistent-high + stable OR slowly-drifting plateau
 * - Fire micro-shot (<= 30% maxBolus, opgeschaald bij langdurig hoog)
 * - Start cooldown voor N cycli (1-2) na elke dosis
 *
 * AANPASSING: stableSlopeAbs verruimd zodat ook langzaam dalende BG
 * die persistent hoog blijft wordt herkend. Plus: naarmate BG langer
 * boven target blijft (persistentCounter groeit), wordt de dosis
 * 20-30% groter — maar nooit meer dan maxBolusFraction * maxBolusU.
 */
class PersistentCorrectionController(
    private val cooldownCycles: Int = 2,
    private val maxBolusFraction: Double = 0.30,
) {
    private var cooldownLeft: Int = 0
    private var lastFireTs: Long = 0L
    private var persistentCounter: Int = 0

    data class Result(
        val active: Boolean,
        val fired: Boolean,
        val doseU: Double,
        val cooldownLeft: Int,
        val reason: String,
        val persistentCounter: Int = 0,
        val escalationFactor: Double = 1.0
    )

    fun tickAndMaybeFire(
        tsMillis: Long,
        bgMmol: Double,
        targetMmol: Double,
        deltaToTarget: Double,
        slope: Double,
        accel: Double,
        consistency: Double,
        iob: Double,
        iobRatio: Double,
        maxBolusU: Double,

        minDeltaToTarget: Double = 1.6,
        stableSlopeAbs: Double = 0.25,
        stableAccelAbs: Double = 0.06,
        minConsistency: Double = 0.45,
        confirmCycles: Int = 2,

        minDoseU: Double = 0.05,
        iobRatioHardStop: Double = 0.55
    ): Result {

        // Cooldown countdown
        if (cooldownLeft > 0) {
            cooldownLeft -= 1
            return Result(
                active = true,
                fired = false,
                doseU = 0.0,
                cooldownLeft = cooldownLeft,
                reason = "PERSIST: cooldown ($cooldownLeft left)",
                persistentCounter = persistentCounter
            )
        }

        // Persistent hoog definitie:
        // - BG voldoende boven target
        // - slope stabiel OF langzaam dalend (tot -0.60 mmol/5min)
        //   Reden: bij persistente hyperglykemie daalt BG wel heel traag
        //   maar is hij structureel te hoog. -0.60 = ~12 mmol/uur daling,
        //   wat bij BG=12 nog steeds uren duurt voor target bereikt wordt.
        // - geen sterke versnelling (accel stabiel)
        val slopeOk = slope <= stableSlopeAbs && slope >= -0.60
        val persistentCandidate =
            deltaToTarget >= minDeltaToTarget &&
                slopeOk &&
                abs(accel) <= stableAccelAbs &&
                consistency >= minConsistency

        if (persistentCandidate) {
            persistentCounter++
        } else {
            persistentCounter = 0
        }

        val persistentConfirmed = persistentCounter >= confirmCycles

        if (!persistentConfirmed) {
            return Result(
                active = false,
                fired = false,
                doseU = 0.0,
                cooldownLeft = 0,
                reason = "PERSIST: building (${persistentCounter}/${confirmCycles})"
            )
        }

        // Dosis model:
        // Base: afhankelijk van deltaToTarget en iobRatio
        // Opschaling: naarmate counter groeit (BG al langer hoog),
        //   dosis 20-30% groter. Na 4 bevestigde cycli (= ~20 min) max opschaling.
        val deltaFactor = (deltaToTarget / 3.0).coerceIn(0.0, 1.0)
        val iobFactor = (1.0 - (iobRatio / iobRatioHardStop)).coerceIn(0.0, 1.0)
        val baseRaw = minDoseU + (maxBolusU * maxBolusFraction - minDoseU) *
            (0.65 * deltaFactor + 0.35 * iobFactor)

        // Opschaling: elke 2 extra bevestigde cycli na confirmCycles +10%, max +30%
        // persistentCounter=2 (net bevestigd) → factor 1.0
        // persistentCounter=4 → factor 1.10
        // persistentCounter=6 → factor 1.20
        // persistentCounter=8+ → factor 1.30 (max)
        val extraCycles = (persistentCounter - confirmCycles).coerceAtLeast(0)
        val escalationFactor = (1.0 + (extraCycles / 2) * 0.10).coerceAtMost(1.30)

        val dose = (baseRaw * escalationFactor)
            .coerceAtLeast(0.0)
            .coerceAtMost(maxBolusU * maxBolusFraction)

        if (dose < minDoseU) {
            return Result(true, false, 0.0, 0, "PERSIST: computed too small")
        }

        lastFireTs = tsMillis
        cooldownLeft = cooldownCycles

        return Result(
            active = true,
            fired = true,
            doseU = dose,
            cooldownLeft = cooldownLeft,
            reason = "PERSIST: fire dose=${"%.2f".format(dose)}U delta=${"%.2f".format(deltaToTarget)} " +
                "iobR=${"%.2f".format(iobRatio)} esc=${"%+.0f".format((escalationFactor - 1.0) * 100)}%",
            persistentCounter = persistentCounter,
            escalationFactor = escalationFactor
        )
    }
}
