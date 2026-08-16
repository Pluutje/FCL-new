package app.aaps.plugins.aps.openAPSFCL.vnext

import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory brug die de actuele FCLvNextConfig + S/T/V/N + handmatige referentiewaarden
 * beschikbaar stelt voor de geïntegreerde Analyzer zonder bestandslezing.
 *
 * FCLvNextActiveParamsWriter.writeIfChanged() roept snapshot() aan na elke
 * cyclus (alleen als de config veranderd is). De Analyzer leest via get().
 *
 * manualMaxBolus = prefs.get(DoubleKey.max_bolus_day) — de handmatige instelling
 * die als referentie dient voor de dynamische grenzen van MaxSmbLearner.
 */
object FclActiveConfigBridge {

    data class Snapshot(
        val config:         FCLvNextConfig,
        val sterkte:        Int,
        val timing:         Int,
        val volhoudendheid: Int,
        val nfLevel:        Double,
        val manualMaxBolus: Double,  // prefs max_bolus_day — handmatige MaxSMB instelling
        // 12/07/2026 — analoog aan manualMaxBolus, nodig om iobRatio
        // (die intern altijd relatief aan max_iob is, zie DetermineBasalFCL.kt)
        // terug te kunnen rekenen naar absolute eenheden voor de learner (zie
        // Dflearner.kt's "laagMetNogAanwezigeIob"-check). manualMaxBolus alleen
        // was hiervoor niet bruikbaar: dat is een heel andere, veel kleinere
        // referentiewaarde (per-cyclus maximum, geen dag-plafond).
        val manualMaxIob: Double = 10.0,
        // 12/07/2026 — actuele ISF (mmol/L per U), voor de Learner's
        // "laagMetNogAanwezigeIob"-check (zie Dflearner.kt controlevraag de gebruiker
        // 12/07/2026): de theoretische-verdere-daling-drempel schaalt hiermee
        // mee per persoon, i.p.v. één vaste eenheid voor iedereen. "Actueel op
        // evaluatiemoment", net als manualMaxBolus/manualMaxIob — niet per se
        // exact de ISF die gold op het moment van de lage BG zelf (kan bij een
        // dag/nacht-ISF-wissel rond de episode net iets afwijken).
        val effectiveIsfMmol: Double = 4.0
    )

    private val current = AtomicReference<Snapshot?>(null)

    /** Aanroepen vanuit FCLvNextActiveParamsWriter.writeIfChanged() */
    fun snapshot(
        config:          FCLvNextConfig,
        sterkte:         Int,
        timing:          Int,
        volhoudendheid:  Int,
        nfLevel:         Double,
        manualMaxBolus: Double,
        manualMaxIob: Double = 10.0,
        effectiveIsfMmol: Double = 4.0
    ) {
        current.set(Snapshot(config, sterkte, timing, volhoudendheid, nfLevel, manualMaxBolus, manualMaxIob, effectiveIsfMmol))
    }

    /** Aanroepen vanuit de Analyzer i.p.v. readActiveParams() */
    fun get(): Snapshot? = current.get()

    /** True als er al een snapshot beschikbaar is (na de eerste FCLvNext cyclus) */
    fun isAvailable(): Boolean = current.get() != null
}