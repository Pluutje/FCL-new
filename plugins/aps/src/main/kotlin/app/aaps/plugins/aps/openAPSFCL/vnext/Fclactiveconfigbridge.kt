package app.aaps.plugins.aps.openAPSFCL.vnext

import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory brug die de actuele FCLvNextConfig + S/T/V/N + handmatige referentiewaarden
 * beschikbaar stelt voor de geïntegreerde Analyzer zonder bestandslezing.
 *
 * FCLvNextActiveParamsWriter.writeIfChanged() roept snapshot() aan na elke
 * cyclus (alleen als de config veranderd is). De Analyzer leest via get().
 *
 * manualMaxSmbDay = prefs.get(DoubleKey.max_bolus_day) — de handmatige instelling
 * die als referentie dient voor de dynamische grenzen van MaxSmbLearner.
 */
object FclActiveConfigBridge {

    data class Snapshot(
        val config:          FCLvNextConfig,
        val sterkte:         Int,
        val timing:          Int,
        val volhoudendheid:  Int,
        val nachtFactor:     Int,
        val manualMaxSmbDay: Double  // prefs max_bolus_day — referentie voor MaxSmbLearner
    )

    private val current = AtomicReference<Snapshot?>(null)

    /** Aanroepen vanuit FCLvNextActiveParamsWriter.writeIfChanged() */
    fun snapshot(
        config:          FCLvNextConfig,
        sterkte:         Int,
        timing:          Int,
        volhoudendheid:  Int,
        nachtFactor:     Int,
        manualMaxSmbDay: Double
    ) {
        current.set(Snapshot(config, sterkte, timing, volhoudendheid, nachtFactor, manualMaxSmbDay))
    }

    /** Aanroepen vanuit de Analyzer i.p.v. readActiveParams() */
    fun get(): Snapshot? = current.get()

    /** True als er al een snapshot beschikbaar is (na de eerste FCLvNext cyclus) */
    fun isAvailable(): Boolean = current.get() != null
}