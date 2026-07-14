package app.aaps.plugins.aps.openAPSFCL.vnext

import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * FclActivitySensitivity — Activiteits Insuline Gevoeligheids Factor (AIGF)
 * ============================================================================
 *
 * Ontwerp overeengekomen met Ecko, 14/07/2026.
 *
 * Doel: een glijdende, per-episode berekende gevoeligheidsfactor die uitdrukt
 * hoe actief de afgelopen 8 uur waren t.o.v. het eigen, voortschrijdende
 * 7-daagse gemiddelde van diezelfde persoon. 125 betekent "25% gevoeliger
 * voor insuline" (dus minder insuline nodig); de toepassing elders
 * (FCLvNext.kt) is altijd: aangepaste_dosis = ruwe_dosis / (AIGF / 100).
 *
 * BEWUST GEEN vaste referentieperiode: het gemiddelde waartegen vergeleken
 * wordt schuift continu mee (rolling window, WINDOW_DAYS). Reden (Ecko):
 * als iemand structureel actiever wordt, hoort die verandering via de
 * bestaande Learner/AI-adviseur te lopen (die de sterkte over de weken
 * toch al bijstelt) — een vaste baseline zou dezelfde verandering een
 * TWEEDE keer verwerken, via deze factor. Met een glijdend venster reageert
 * de AIGF alleen nog op de kortetermijn-afwijking t.o.v. een intussen al
 * meebewegende, persoonlijke norm.
 *
 * DATABRON: FclActivityLogger.kt schrijft dezelfde 8-uurs-calorieschatting
 * al weg naar FCLvNext_ActivityLog_v2.csv (cal_totaal_8h) — bewust een
 * andere weg voor de HISTORIE hier: die CSV lezen tijdens dosering zou
 * bestandsdruk op het hot path van elke cyclus geven. In plaats daarvan
 * houdt dit bestand zijn eigen, lichte, JSON-in-SharedPreferences-historie
 * bij (zelfde patroon als FclMealTimeAnticipation.kt), bijgewerkt op
 * hetzelfde moment (episodestart) door DetermineBasalFCL.kt.
 *
 * NORMEREN, NIET CLIPPEN: de ruwe ratio (huidige 8u-kcal / historische
 * mediaan) wordt eerst gesquasht naar een VASTE interne score tussen -1 en
 * +1 over een vaste referentie (RATIO_REF_LOW..RATIO_REF_HIGH, bewust NIET
 * instelbaar — voorkomt dat één foutieve meting, bijv. IN_VEHICLE die als
 * beweging telt, meteen de volle bandbreedte triggert). Pas in de laatste
 * stap wordt die vaste interne score proportioneel uitgerekt naar het door
 * de gebruiker ingestelde bereik (minPct..maxPct) — nooit een harde afkap
 * op de grens: bij een kleiner ingesteld bereik krimpt de hele curve mee,
 * in dezelfde relatieve verhouding.
 */
object FclActivitySensitivity {

    // ── Opslag (zelfde patroon als FclMealTimeAnticipation.kt) ────────────
    private const val PREFS = "fcl_activity_sensitivity"
    private const val KEY = "cal8h_history_json"

    private const val WINDOW_DAYS = 7L
    private const val WINDOW_MS = WINDOW_DAYS * 24L * 60L * 60L * 1000L

    /** Minstens dit veel eerdere metingen nodig voor een betrouwbare mediaan. */
    private const val MIN_HISTORY_FOR_BASELINE = 5

    // Vaste referentie voor ruwe ratio → interne score -1..+1 (14/07/2026,
    // Ecko: bewust NIET instelbaar, zie kdoc hierboven).
    private const val RATIO_REF_LOW = 0.4
    private const val RATIO_REF_HIGH = 2.5

    data class Sample(val tsMs: Long, val cal8h: Double)

    /** Rolling geschiedenis van 8-uurs-calorieschattingen, één per episodestart. */
    data class History(var samples: MutableList<Sample> = mutableListOf()) {
        fun serialize(): String {
            val arr = JSONArray()
            for (s in samples) arr.put(JSONObject().put("ts", s.tsMs).put("cal", s.cal8h))
            return JSONObject().put("samples", arr).toString()
        }

        companion object {
            fun deserialize(raw: String): History {
                if (raw.isBlank()) return History()
                return try {
                    val arr = JSONObject(raw).optJSONArray("samples") ?: JSONArray()
                    val list = mutableListOf<Sample>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(Sample(o.getLong("ts"), o.getDouble("cal")))
                    }
                    History(list)
                } catch (e: Exception) {
                    History()
                }
            }
        }
    }

    fun loadFrom(context: android.content.Context): History =
        History.deserialize(
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY, "") ?: ""
        )

    fun saveTo(context: android.content.Context, h: History) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, h.serialize()).apply()
    }

    /**
     * Voeg een meting toe (bij episodestart, vanuit DetermineBasalFCL.kt) en
     * trim tot het glijdende venster. Caller (DetermineBasalFCL.kt) persisteert
     * het resultaat via saveTo(). Foutwaarden (cal8h < 0, zie
     * EstimatedCaloriesCalculator-conventie) worden niet opgeslagen — die
     * zouden de mediaan alleen maar vervuilen.
     */
    fun record(h: History, tsMs: Long, cal8h: Double): History {
        if (cal8h < 0.0) return h
        val newSamples = h.samples.toMutableList()
        newSamples.add(Sample(tsMs, cal8h))
        val cutoff = tsMs - WINDOW_MS
        newSamples.removeAll { it.tsMs < cutoff }
        return History(newSamples)
    }

    data class AigfResult(
        /** false = te weinig historie of ongeldige huidige meting; AIGF dan neutraal (100). */
        val active: Boolean,
        val aigf: Double,
        // Puur diagnostisch, voor de status-formatter/logging:
        val rawRatio: Double,
        val baselineMedian: Double,
        val sampleCount: Int
    )

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /**
     * Bereken de AIGF. De baseline is altijd puur historisch (de huidige
     * meting zelf zit niet in `history` — die wordt apart, ná episodestart,
     * toegevoegd via record()) — geen data-lek uit het huidige moment naar
     * zijn eigen referentie.
     *
     * @param minPct/maxPct  door de gebruiker ingestelde grenzen (bijv. 95/105
     *                       bij een voorzichtige start, later evt. breder).
     */
    fun compute(
        history: History,
        currentCal8h: Double,
        minPct: Double,
        maxPct: Double
    ): AigfResult {
        val usable = history.samples.filter { it.cal8h >= 0.0 }.map { it.cal8h }
        if (usable.size < MIN_HISTORY_FOR_BASELINE || currentCal8h < 0.0) {
            return AigfResult(active = false, aigf = 100.0, rawRatio = 1.0, baselineMedian = 0.0, sampleCount = usable.size)
        }
        val baselineMedian = median(usable)
        if (baselineMedian <= 0.001) {
            return AigfResult(active = false, aigf = 100.0, rawRatio = 1.0, baselineMedian = baselineMedian, sampleCount = usable.size)
        }

        val rawRatio = currentCal8h / baselineMedian

        val internalScore = when {
            rawRatio <= RATIO_REF_LOW -> -1.0
            rawRatio >= RATIO_REF_HIGH -> 1.0
            rawRatio < 1.0 -> (rawRatio - 1.0) / (1.0 - RATIO_REF_LOW)
            rawRatio > 1.0 -> (rawRatio - 1.0) / (RATIO_REF_HIGH - 1.0)
            else -> 0.0
        }.coerceIn(-1.0, 1.0)

        // Proportionele herschaling naar het ingestelde bereik (geen clip op
        // de grens — zie kdoc bovenaan). internalScore zelf is al -1..+1.
        val aigf = if (internalScore >= 0.0) {
            100.0 + internalScore * (maxPct - 100.0)
        } else {
            100.0 + internalScore * (100.0 - minPct)
        }

        return AigfResult(active = true, aigf = aigf, rawRatio = rawRatio, baselineMedian = baselineMedian, sampleCount = usable.size)
    }
}
