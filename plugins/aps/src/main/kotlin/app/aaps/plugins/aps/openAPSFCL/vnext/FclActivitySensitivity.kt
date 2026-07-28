package app.aaps.plugins.aps.openAPSFCL.vnext

import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * FclActivitySensitivity — Activiteits Insuline Gevoeligheids Factor (AIGF)
 * ============================================================================
 *
 * Ontwerp overeengekomen met Ecko, 14/07/2026 — HERONTWORPEN 28/07/2026 na
 * een gesprek over een structurele ochtendbias (zie hieronder).
 *
 * ── HERONTWERP 28/07/2026 (Ecko) — waarom ────────────────────────────────
 * De oorspronkelijke versie vergeleek één rollend 8-uursvenster (gemeten bij
 * elke maaltijd) tegen een 7-daagse mediaan die ALLE momenten van de dag
 * door elkaar mengde (ontbijt- én lunch- én dinermetingen samen). Bij het
 * ontbijt bestaat dat 8-uursvenster vrijwel volledig uit slaap — dat ligt
 * structureel onder de gemengde mediaan (die door de hogere lunch/diner-
 * metingen omhoog wordt getrokken), ongeacht hoe actief de gebruiker
 * werkelijk was. Gevolg, bevestigd in de logs van 27-28/07: een gladde,
 * bijna identieke dag-op-dag AIGF-curve met een dieptepunt exact rond
 * 07:00-08:00 (~90%) en een piek rond middernacht (~101%) — dat is geen
 * signaal dat reageert op een afwijkende dag, dat is een klok.
 *
 * Daarnaast bleek het oorspronkelijke ontwerp eigenlijk twee dingen wilde
 * vangen: (A) een naijleffect van een actieve VORIGE dag (tot 25%
 * gevoeliger, opbouwend "zolang het goed gaat", werkend tot in de volgende
 * ~24 uur) en (B) een kortetermijn-effect van de RECENTE uren vóór een
 * specifieke maaltijd (een actieve ochtend → minder insuline bij de lunch
 * die volgt). Eén 8-uursvenster kan geen van beide goed vangen: te kort om
 * "gisteren" te zien, en niet eerlijk vergeleken voor "recent, t.o.v. wat
 * normaal is op dit moment".
 *
 * Nu twee losse componenten, elk met hun eigen historie en eigen
 * toepassing (zie kdoc bij FCLvNext.kt's AIGF-sectie voor de toepassing):
 *
 *   COMPONENT A — vorige dag / naijling. Rollend 24-uurstotaal, vergeleken
 *   met de eigen 7-daagse mediaan van datzelfde 24-uurstotaal (dus altijd
 *   dezelfde vensterbreedte tegen dezelfde vensterbreedte — geen 8u-tegen-
 *   gemengd-probleem meer). Werkt continu door (niet gebonden aan een
 *   specifieke maaltijd), stuurt de afterload-reductie aan.
 *
 *   COMPONENT B — recente uren. Laatste 4 uur vóór het eerste écht
 *   bevestigde commit van een maaltijd-episode, vergeleken met een eigen
 *   7-daagse baseline van "hoeveel ik typisch doe in 4 wakkere uren".
 *   Cruciaal: zowel de HUIDIGE meting als de HISTORIE worden gewogen naar
 *   hoeveel van dat 4-uursvenster daadwerkelijk binnen wakkere uren viel
 *   (zie wakeOverlapFrac, aangeleverd door FclWakeDetector.kt — eerste
 *   ~150 stappen binnen 10 minuten, niet een vaste kloktijd). Bij het
 *   ontbijt is dat aandeel ~0 (bijna alle 4 uur was slaap) → component B
 *   telt dan vanzelf niet mee. Metingen met een te laag wakker-aandeel
 *   worden ook niet in de HISTORIE opgenomen (zie
 *   WAKE_OVERLAP_MIN_FOR_HISTORY) — anders zou de baseline zelf weer
 *   vervuild raken met slaapuren, hetzelfde probleem in een nieuwe vorm.
 *   Bewust GEEN maaltijd-volgnummer of dagdeel-bucket (koek-ambiguïteit:
 *   soms telt een tussendoortje als eigen episode, soms niet — dat maakt
 *   volgnummer als index onbetrouwbaar).
 *
 * BEWUST GEEN vaste referentieperiode voor beide componenten: het gemiddelde
 * waartegen vergeleken wordt schuift continu mee (rolling window,
 * WINDOW_DAYS). Reden (Ecko, 14/07/2026, nog steeds geldig): een
 * structurele verandering hoort via de bestaande Learner/AI-adviseur te
 * lopen, niet een tweede keer via deze factor.
 *
 * NORMEREN, NIET CLIPPEN: zie RATIO_REF_LOW/HIGH hieronder — ongewijzigd
 * t.o.v. de oorspronkelijke versie, geldt voor beide componenten.
 */
object FclActivitySensitivity {

    // ── Opslag — component A en B krijgen elk hun EIGEN historie, bewust
    // niet gedeeld (zie kdoc hierboven: mengen van de twee vensterbreedtes
    // was precies het oorspronkelijke probleem). ────────────────────────────
    private const val PREFS_A = "fcl_activity_sensitivity_a"
    private const val KEY_A = "cal24h_history_json"
    private const val PREFS_B = "fcl_activity_sensitivity_b"
    private const val KEY_B = "wake4h_history_json"

    private const val WINDOW_DAYS = 7L
    private const val WINDOW_MS = WINDOW_DAYS * 24L * 60L * 60L * 1000L

    /** Minstens dit veel eerdere metingen nodig voor een betrouwbare mediaan. */
    private const val MIN_HISTORY_FOR_BASELINE = 5

    // ── Opbouw-vertrouwen o.b.v. gevulde historie (16/07/2026, Ecko) ────────
    // Zie de oorspronkelijke kdoc-uitleg (ongewijzigd, geldt voor beide
    // componenten): de afwijking t.o.v. 100 wordt lineair gedempt met hoeveel
    // dagen de OUDSTE bruikbare meting in het venster al terug ligt.
    private const val DAYS_FOR_FULL_CONFIDENCE = 5.0
    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    // Vaste referentie voor ruwe ratio → interne score -1..+1 (14/07/2026,
    // Ecko: bewust NIET instelbaar).
    private const val RATIO_REF_LOW = 0.4
    private const val RATIO_REF_HIGH = 2.5

    // ── Component B: wakker-weging (28/07/2026, Ecko) ──────────────────────
    // Een meting wordt alleen in de HISTORIE van component B opgenomen als
    // minstens dit aandeel van het 4-uursvenster binnen wakkere uren viel —
    // anders raakt de "typisch wakker"-baseline zelf vervuild met slaapuren.
    // De HUIDIGE meting van een specifieke maaltijd wordt altijd berekend,
    // maar de resulterende afwijking t.o.v. 100 wordt vermenigvuldigd met
    // het eigen wakeOverlapFrac (dus bij het ontbijt ~0 effect, ook al is
    // de baseline zelf schoon).
    const val WAKE_OVERLAP_MIN_FOR_HISTORY = 0.75

    data class Sample(val tsMs: Long, val value: Double)

    /** Rolling geschiedenis van metingen, één per opgeslagen moment. */
    data class History(var samples: MutableList<Sample> = mutableListOf()) {
        fun serialize(): String {
            val arr = JSONArray()
            for (s in samples) arr.put(JSONObject().put("ts", s.tsMs).put("val", s.value))
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
                        // "val" i.p.v. het oude "cal" — nieuwe historie-vorm
                        // (28/07/2026); oude bestanden onder de oude sleutel
                        // (cal8h_history_json) worden simpelweg niet meer
                        // gelezen, geen migratie nodig (zelfde patroon als
                        // eerdere nieuwe-SharedPreferences-bestand-fixes).
                        list.add(Sample(o.getLong("ts"), o.getDouble("val")))
                    }
                    History(list)
                } catch (e: Exception) {
                    History()
                }
            }
        }
    }

    fun loadHistoryA(context: android.content.Context): History =
        History.deserialize(
            context.getSharedPreferences(PREFS_A, android.content.Context.MODE_PRIVATE)
                .getString(KEY_A, "") ?: ""
        )

    fun saveHistoryA(context: android.content.Context, h: History) {
        context.getSharedPreferences(PREFS_A, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_A, h.serialize()).apply()
    }

    fun loadHistoryB(context: android.content.Context): History =
        History.deserialize(
            context.getSharedPreferences(PREFS_B, android.content.Context.MODE_PRIVATE)
                .getString(KEY_B, "") ?: ""
        )

    fun saveHistoryB(context: android.content.Context, h: History) {
        context.getSharedPreferences(PREFS_B, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_B, h.serialize()).apply()
    }

    /**
     * Voeg een meting toe en trim tot het glijdende venster. Caller
     * persisteert het resultaat via saveHistoryA()/saveHistoryB().
     * Foutwaarden (value < 0) worden niet opgeslagen.
     */
    fun record(h: History, tsMs: Long, value: Double): History {
        if (value < 0.0) return h
        val newSamples = h.samples.toMutableList()
        newSamples.add(Sample(tsMs, value))
        val cutoff = tsMs - WINDOW_MS
        newSamples.removeAll { it.tsMs < cutoff }
        return History(newSamples)
    }

    data class AigfResult(
        /** false = te weinig historie of ongeldige huidige meting; component dan neutraal (100). */
        val active: Boolean,
        val aigf: Double,
        // Puur diagnostisch, voor de status-formatter/logging:
        val rawRatio: Double,
        val baselineMedian: Double,
        val sampleCount: Int,
        val reasonNl: String = "",
        val daysOfHistory: Double = 0.0
    )

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    // ── Gedeelde kernberekening (14/07/2026, herzien 28/07/2026) ────────────
    // Ratio → vaste interne score (-1..+1) → proportionele herschaling naar
    // minPct..maxPct → opbouw-vertrouwensdemping. Identiek voor component A
    // en B; het enige verschil tussen de twee zit in WAT ze meegeven als
    // `currentValue`/`history` en (alleen bij B) de wake-weging die de
    // AANROEPER (computeComponentB hieronder) er nog overheen legt.
    private fun computeCore(
        history: History,
        currentValue: Double,
        minPct: Double,
        maxPct: Double,
        nowMs: Long,
        geenHistorieReden: String,
        geenMetingReden: String
    ): AigfResult {
        val usableSamples = history.samples.filter { it.value >= 0.0 }
        val usable = usableSamples.map { it.value }
        if (usable.size < MIN_HISTORY_FOR_BASELINE) {
            return AigfResult(
                active = false, aigf = 100.0, rawRatio = 1.0, baselineMedian = 0.0, sampleCount = usable.size,
                reasonNl = "$geenHistorieReden (${usable.size}/$MIN_HISTORY_FOR_BASELINE)"
            )
        }
        if (currentValue < 0.0) {
            return AigfResult(
                active = false, aigf = 100.0, rawRatio = 1.0, baselineMedian = 0.0, sampleCount = usable.size,
                reasonNl = geenMetingReden
            )
        }
        val baselineMedian = median(usable)
        if (baselineMedian <= 0.001) {
            return AigfResult(
                active = false, aigf = 100.0, rawRatio = 1.0, baselineMedian = baselineMedian, sampleCount = usable.size,
                reasonNl = "baseline te laag om te normeren"
            )
        }

        val rawRatio = currentValue / baselineMedian

        val internalScore = when {
            rawRatio <= RATIO_REF_LOW -> -1.0
            rawRatio >= RATIO_REF_HIGH -> 1.0
            rawRatio < 1.0 -> (rawRatio - 1.0) / (1.0 - RATIO_REF_LOW)
            rawRatio > 1.0 -> (rawRatio - 1.0) / (RATIO_REF_HIGH - 1.0)
            else -> 0.0
        }.coerceIn(-1.0, 1.0)

        val rawAigf = if (internalScore >= 0.0) {
            100.0 + internalScore * (maxPct - 100.0)
        } else {
            100.0 + internalScore * (100.0 - minPct)
        }

        val oldestUsableTsMs = usableSamples.minOf { it.tsMs }
        val daysOfHistory = ((nowMs - oldestUsableTsMs).coerceAtLeast(0L) / MS_PER_DAY.toDouble())
        val confidence = (daysOfHistory / DAYS_FOR_FULL_CONFIDENCE).coerceIn(0.0, 1.0)
        val aigf = 100.0 + confidence * (rawAigf - 100.0)

        return AigfResult(
            active = true, aigf = aigf, rawRatio = rawRatio, baselineMedian = baselineMedian,
            sampleCount = usable.size, daysOfHistory = daysOfHistory
        )
    }

    /**
     * Component A — vorige dag/naijling. `currentCal24h` is het rollende
     * 24-uurstotaal NU; `history` is de 7-daagse reeks van datzelfde
     * 24-uurstotaal, gemeten op eerdere momenten (zie FCLvNext.kt: bij elk
     * eerste écht bevestigde commit van een maaltijd-episode).
     */
    fun computeComponentA(
        history: History,
        currentCal24h: Double,
        minPct: Double,
        maxPct: Double,
        nowMs: Long
    ): AigfResult = computeCore(
        history, currentCal24h, minPct, maxPct, nowMs,
        geenHistorieReden = "nog te weinig meetpunten (24u-historie)",
        geenMetingReden = "geen geldige 24u-calorie-meting deze cyclus"
    )

    /**
     * Component B — recente uren, wakker-gewogen. `currentCal4h` is het
     * 4-uurstotaal vlak vóór het eerste échte commit; `wakeOverlapFrac`
     * (0..1) geeft aan hoeveel van dat venster binnen wakkere uren viel
     * (zie FclWakeDetector.kt). De resulterende afwijking t.o.v. 100 wordt
     * MET dat aandeel vermenigvuldigd — bij een grotendeels-slaap-venster
     * (zoals vóór het ontbijt) blijft het resultaat dus dicht bij 100,
     * ongeacht hoe laag currentCal4h op zichzelf is.
     */
    fun computeComponentB(
        history: History,
        currentCal4h: Double,
        wakeOverlapFrac: Double,
        minPct: Double,
        maxPct: Double,
        nowMs: Long
    ): AigfResult {
        val core = computeCore(
            history, currentCal4h, minPct, maxPct, nowMs,
            geenHistorieReden = "nog te weinig meetpunten (wakkere-uren-historie)",
            geenMetingReden = "geen geldige 4u-calorie-meting bij dit commit"
        )
        if (!core.active) return core
        val w = wakeOverlapFrac.coerceIn(0.0, 1.0)
        val weightedAigf = 100.0 + (core.aigf - 100.0) * w
        return core.copy(aigf = weightedAigf)
    }
}
