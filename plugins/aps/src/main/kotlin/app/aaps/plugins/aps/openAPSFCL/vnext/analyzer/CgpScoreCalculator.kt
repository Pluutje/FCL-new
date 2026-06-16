package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogEntity
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CgpScoreCalculator — berekent de CGP/PGR-score identiek aan de AAPS
 * Statistics-implementatie (GlucosePentagonCompose.kt, Vigersky et al. 2018).
 *
 * Methode: radar/spider-model met 5 assen.
 *   - Kleinere waarde = beter (patiënt dichter bij referentie = gezond persoon)
 *   - PGR = oppervlak patiënt-pentagon / oppervlak referentie-pentagon
 *   - Oppervlak via shoelace-formule: 0.5 * sin(72°) * Σ(r_i * r_{i+1})
 *   - Elke as genormaliseerd: 0.18 (best) → 1.0 (worst)
 *
 * De vijf assen (zelfde als AAPS):
 *   1. TOR   — Time out of Range = 100% - TIR%       (max=100%, ref=0%)
 *   2. CV    — Coefficient of Variation SD/mean       (max=60%,  ref=17%)
 *   3. Hypo  — % tijd < 3.9 mmol/L                   (max=20%,  ref=0%)
 *   4. Hyper — % tijd > 10.0 mmol/L                  (max=80%,  ref=0%)
 *   5. Mean  — gemiddelde BG in mg/dL                 (max=300,  ref=90)
 *
 * PGR-drempelwaarden (Vigersky 2018):
 *   ≤ 2.0: very low risk  ≤ 3.0: low risk
 *   ≤ 4.0: moderate       ≤ 4.5: high
 *   > 4.5: extremely high
 */
data class CgpScore(
    val tsUtc: String,
    val torPct: Double,
    val cvPct: Double,
    val hypoPct: Double,
    val hyperPct: Double,
    val meanMgdl: Double,
    val pgr: Double,
    val weakestDimension: String   // "TOR"|"CV"|"HYPO"|"HYPER"|"MEAN"
) {
    val pgrLabel: String get() = when {
        pgr <= 2.0 -> "Very low risk"
        pgr <= 3.0 -> "Low risk"
        pgr <= 4.0 -> "Moderate risk"
        pgr <= 4.5 -> "High risk"
        else       -> "Extremely high risk"
    }

    val meanMmol: Double get() = meanMgdl / 18.0

    companion object {
        val EMPTY = CgpScore(
            tsUtc = "", torPct = 0.0, cvPct = 0.0, hypoPct = 0.0,
            hyperPct = 0.0, meanMgdl = 0.0, pgr = 0.0, weakestDimension = ""
        )
    }
}

object CgpScoreCalculator {

    // Exact dezelfde constanten als AAPS GlucosePentagonCompose.kt
    private const val BASELINE_OFFSET = 0.18
    private const val ANGLE_STEP_DEG  = 72.0

    private const val TOR_MAX   = 100.0;  private const val TOR_REF   =  0.0
    private const val CV_MAX    =  60.0;  private const val CV_REF    = 17.0
    private const val HYPO_MAX  =  20.0;  private const val HYPO_REF  =  0.0
    private const val HYPER_MAX =  80.0;  private const val HYPER_REF =  0.0
    private const val MEAN_MAX  = 300.0;  private const val MEAN_REF  = 90.0   // mg/dL

    // TIR-grenzen
    private const val TIR_LOW  = 3.9   // mmol/L
    private const val TIR_HIGH = 10.0  // mmol/L

    /**
     * Berekent de CGP/PGR-score over de opgegeven cyclusregels.
     * Verwacht: alle regels van de afgelopen 14 dagen gesorteerd op timestamp.
     * Retourneert null als er minder dan 288 datapunten zijn (< 1 dag).
     */
    /** Primaire aanroepmethode — neemt lijst van mmol-waarden.
     *  Minimaal 24 punten (= 2 uur bij 5-min cycli) voor een dagpunt. */
    fun calculateFromBg(
        bgMmolValues: List<Double>,
        tsUtc: String = java.time.Instant.now().toString()
    ): CgpScore? {
        val filtered = bgMmolValues.filter { it > 0.0 }
        if (filtered.size < 24) return null   // < 2 uur data
        return calculateInternal(filtered, tsUtc)
    }

    /** Backward compatibility — neemt FCLCycleLogEntity-rijen */
    fun calculate(
        rows: List<FCLCycleLogEntity>,
        tsUtc: String = java.time.Instant.now().toString()
    ): CgpScore? {
        val filtered = rows.map { it.bg }.filter { it > 0.0 }
        if (filtered.size < 288) return null
        return calculateInternal(filtered, tsUtc)
    }

    private fun calculateInternal(bgMmol: List<Double>, tsUtc: String): CgpScore? {
        val n = bgMmol.size.toDouble()

        // ── Vijf CGP-metrieken ────────────────────────────────────────────
        val inRange  = bgMmol.count { it in TIR_LOW..TIR_HIGH }
        val tirPct   = inRange / n * 100.0
        val torPct   = 100.0 - tirPct

        val hypoPct  = bgMmol.count { it < TIR_LOW }  / n * 100.0
        val hyperPct = bgMmol.count { it > TIR_HIGH } / n * 100.0

        val meanMmol = bgMmol.average()
        val meanMgdl = meanMmol * 18.0   // intern in mg/dL voor AAPS-formule

        val sd  = sqrt(bgMmol.sumOf { (it - meanMmol) * (it - meanMmol) } / n)
        val cvPct = if (meanMmol > 0.0) sd / meanMmol * 100.0 else 0.0

        // ── Normaliseer naar [0.18, 1.0] (AAPS formule) ──────────────────
        fun norm(value: Double, max: Double): Double =
            BASELINE_OFFSET + (1.0 - BASELINE_OFFSET) *
                (value / max).coerceIn(0.0, 1.0)

        val patientValues = listOf(
            norm(torPct,   TOR_MAX),
            norm(cvPct,    CV_MAX),
            norm(hypoPct,  HYPO_MAX),
            norm(hyperPct, HYPER_MAX),
            norm(meanMgdl, MEAN_MAX)
        )
        val referenceValues = listOf(
            norm(TOR_REF,  TOR_MAX),
            norm(CV_REF,   CV_MAX),
            norm(HYPO_REF, HYPO_MAX),
            norm(HYPER_REF,HYPER_MAX),
            norm(MEAN_REF, MEAN_MAX)
        )

        // ── PGR via shoelace pentagon-oppervlak (AAPS formule) ────────────
        val pgr = pentagonArea(patientValues) / pentagonArea(referenceValues)

        // ── Zwakste dimensie: grootste normaliseerde waarde = slechtste ───
        val dimNames = listOf("TOR","CV","HYPO","HYPER","MEAN")
        val weakest  = dimNames[patientValues.indexOf(patientValues.max())]

        return CgpScore(
            tsUtc             = tsUtc,
            torPct            = torPct,
            cvPct             = cvPct,
            hypoPct           = hypoPct,
            hyperPct          = hyperPct,
            meanMgdl          = meanMgdl,
            pgr               = pgr,
            weakestDimension  = weakest
        )
    }

    /** Shoelace pentagon-oppervlak — identiek aan AAPS pentagonArea() */
    private fun pentagonArea(radii: List<Double>): Double {
        val sinAngle = sin(ANGLE_STEP_DEG * PI / 180.0)
        var sum = 0.0
        for (i in radii.indices) sum += radii[i] * radii[(i + 1) % radii.size]
        return 0.5 * sinAngle * sum
    }

    /**
     * Berekent de procentuele bijdrage van elke parameter aan het totale
     * PGR-excess (patiënt minus referentie oppervlak).
     * Methode: vervang één parameter door de referentiewaarde, meet
     * hoeveel het oppervlak daalt — dat is de bijdrage van die parameter.
     * De vijf bijdragen sommeren op tot 100%.
     */
    fun contributionPct(
        torPct: Double, cvPct: Double, hypoPct: Double,
        hyperPct: Double, meanMgdl: Double
    ): Map<String, Int> {
        fun norm(v: Double, max: Double) =
            BASELINE_OFFSET + (1.0 - BASELINE_OFFSET) * (v / max).coerceIn(0.0, 1.0)

        val pat = listOf(
            norm(torPct,   TOR_MAX),
            norm(cvPct,    CV_MAX),
            norm(hypoPct,  HYPO_MAX),
            norm(hyperPct, HYPER_MAX),
            norm(meanMgdl, MEAN_MAX)
        )
        val ref = listOf(
            norm(TOR_REF,   TOR_MAX),
            norm(CV_REF,    CV_MAX),
            norm(HYPO_REF,  HYPO_MAX),
            norm(HYPER_REF, HYPER_MAX),
            norm(MEAN_REF,  MEAN_MAX)
        )

        val patArea = pentagonArea(pat)
        val dims = listOf("TOR","CV","HYPO","HYPER","MEAN")

        val bijdragen = dims.indices.map { i ->
            val test = pat.toMutableList(); test[i] = ref[i]
            maxOf(0.0, patArea - pentagonArea(test))
        }
        val totaal = bijdragen.sum().takeIf { it > 0 } ?: 1.0
        val pcts = bijdragen.map { (it / totaal * 100).toInt() }

        // Herstel afrondingsfout zodat som exact 100 is
        val rest = 100 - pcts.sum()
        val adjusted = pcts.toMutableList()
        if (rest != 0) adjusted[pcts.indexOf(pcts.max())] += rest

        return dims.zip(adjusted).toMap()
    }

    /** Kleurcode op basis van bijdragepercentage (gemiddelde = 20%) */
    fun contributionColor(pct: Int): String = when {
        pct < 15 -> "GREEN"   // duidelijk onder gemiddeld
        pct < 25 -> "YELLOW"  // rond het gemiddelde
        pct < 40 -> "ORANGE"  // duidelijk boven gemiddeld
        else     -> "RED"     // ≥ 2× gemiddeld
    }

    fun dimensionLabel(dimension: String): String = when (dimension) {
        "TOR"   -> "Tijd buiten bereik"
        "CV"    -> "Variabiliteit (%CV)"
        "HYPO"  -> "Hypo-tijd"
        "HYPER" -> "Hyper-tijd"
        "MEAN"  -> "Gemiddelde BG"
        else    -> dimension
    }

    fun trendArrow(current: Double, previous: Double?): String = when {
        previous == null         -> ""
        current < previous - 0.1 -> " ↓"   // PGR: lager = beter
        current > previous + 0.1 -> " ↑"
        else                     -> " →"
    }

    /** Per-as tekstwaarde voor de UI */
    fun dimensionValue(score: CgpScore, dimension: String): String = when (dimension) {
        "TOR"   -> "${"%.0f".format(score.torPct)}%"
        "CV"    -> "${"%.1f".format(score.cvPct)}%"
        "HYPO"  -> "${"%.1f".format(score.hypoPct)}%"
        "HYPER" -> "${"%.1f".format(score.hyperPct)}%"
        "MEAN"  -> "${"%.1f".format(score.meanMmol)} mmol"
        else    -> ""
    }

    // Referentiewaarden voor de UI (dezelfde als het AAPS groene pentagon)
    val REFERENCE = mapOf(
        "TOR"   to "0%",
        "CV"    to "17%",
        "HYPO"  to "0%",
        "HYPER" to "0%",
        "MEAN"  to "5.0 mmol"
    )
}
