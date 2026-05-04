package app.aaps.plugins.aps.openAPSFCL.vnext

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stateful persistent correction loop:
 * - Detect persistent-high + stable plateau
 * - Fire micro-shot (<= 30% maxBolus)
 * - Start cooldown for N cycles (2-3)
 * - During cooldown: shouldDeliver=false (cooperative with AAPS), but we keep control.
 */
class PersistentCorrectionController(
    private val cooldownCycles: Int = 2,              // jij: 2 of 3
    private val maxBolusFraction: Double = 0.30,     // jij: ~30%

) {
    private var cooldownLeft: Int = 0
    private var lastFireTs: Long = 0L
    private var persistentCounter: Int = 0

    data class Result(
        val active: Boolean,
        val fired: Boolean,
        val doseU: Double,
        val cooldownLeft: Int,
        val reason: String
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

        // persistent-definitie
        minDeltaToTarget: Double = 1.6,
        stableSlopeAbs: Double = 0.25,
        stableAccelAbs: Double = 0.06,
        minConsistency: Double = 0.45,
        confirmCycles: Int = 2,

        // dosing
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
                reason = "PERSIST: cooldown ($cooldownLeft left)"
            )
        }

        val persistentCandidate =
            deltaToTarget >= minDeltaToTarget &&
                abs(slope) <= stableSlopeAbs &&
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


        // Dose model: afhankelijk van iobRatio en deltaToTarget
        // - hoger delta => meer (tot maxBolus*0.30)
        // - hoger iobRatio => minder
        val deltaFactor = (deltaToTarget / 3.0).coerceIn(0.0, 1.0)     // 0..1 bij 0..3 mmol boven target (tune)
        val iobFactor = (1.0 - (iobRatio / iobRatioHardStop)).coerceIn(0.0, 1.0)  // 1..0
        val raw = minDoseU + (maxBolusU * maxBolusFraction - minDoseU) * (0.65 * deltaFactor + 0.35 * iobFactor)

        val dose = raw
            .coerceAtLeast(0.0)
            .coerceAtMost(maxBolusU * maxBolusFraction)

        // Fire only if meaningful
        if (dose < minDoseU) {
            return Result(true, false, 0.0, 0, "PERSIST: computed too small")
        }

        // Fire + start cooldown
        lastFireTs = tsMillis
        cooldownLeft = cooldownCycles

        return Result(
            active = true,
            fired = true,
            doseU = dose,
            cooldownLeft = cooldownLeft,
            reason = "PERSIST: fire dose=${"%.2f".format(dose)}U delta=${"%.2f".format(deltaToTarget)} iobR=${"%.2f".format(iobRatio)}"
        )
    }
}
