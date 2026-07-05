package app.aaps.plugins.aps.openAPSFCL.vnext

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * FclMealTimeAnticipation — geleerde, gewoontematige maaltijdtijden gebruiken
 * om VOORAFGAAND aan een verwachte maaltijd de target kortstondig te verlagen.
 *
 * (05/07/2026, Ecko — geïnspireerd op openAPSBoost's MealTimeLearner, maar
 * met drie bewuste aanpassingen op verzoek:)
 *
 * 1. ALLEEN de target wordt verlaagd (geen los ingrijpen op de dosering zelf) —
 *    de bestaande FCLvNext-doseerlogica (energie-model, WatchingFrontload,
 *    aggressie, alle veiligheidslagen) reageert vervolgens vanzelf op de
 *    kortstondig lagere target, zoals die dat ook doet bij een normale
 *    tijdelijke streefwaarde. Zie de toelichting in FCLvNext.kt bij de
 *    aanroep van [preMealWindow] voor een concrete analyse van welke
 *    doseeronderdelen hierdoor worden geraakt.
 * 2. KORT venster (relatief t.o.v. Boost's origineel van 45-60 min): opent
 *    [PRE_MEAL_LEAD_OPEN_MIN] vóór de geleerde tijd, sluit alweer
 *    [PRE_MEAL_LEAD_CLOSE_MIN] vóór de geleerde tijd — dus ruim voordat de
 *    "echte" maaltijd normaliter zou beginnen, draagt de normale reactieve
 *    detectie het stokje al over.
 * 3. WEEKEND-BEWUST: leert twee onafhankelijke setjes "modes" — één voor
 *    doordeweekse dagen, één voor weekenddagen (conform de bestaande
 *    WeekendDagen-instelling, zie FCLvNextDayNightHelper). Geen aparte,
 *    strengere weekend-drempels nodig: als de weekend-timing structureel
 *    onregelmatiger is (zoals Ecko aangeeft), voldoet die data vanzelf
 *    niet aan MIN_SESSIONS/MIN_DISTINCT_DAYS binnen CLUSTER_HALF_WIDTH_MIN,
 *    en vormt zich simpelweg geen mode — de anticipatie blijft dan vanzelf
 *    uit in het weekend, zonder specialcasing.
 *
 * Bron van de events: FCLvNext.kt's EIGEN CONFIRMED meal-state (zie de
 * "MEAL EPISODE TRACKING"-sectie) — geen tweede detector, conform de regel
 * in Fcl vnext doel.txt tegen dubbele/redundante mechanismen. Er wordt per
 * episode precies éénmaal een event vastgelegd, op het moment dat de
 * episode voor het eerst CONFIRMED wordt (dedupe via lastRecordedMealTimeEpisodeId
 * in FCLvNext.kt).
 *
 * VEILIGHEIDSKANTTEKENING (bewust, zie gesprek met Ecko 05/07/2026): dit
 * mechanisme kijkt uitsluitend naar klok-tijd + geleerd patroon — het
 * controleert niet of er die specifieke dag ook daadwerkelijk een begin van
 * een stijging te zien is. Een eenmalig wegvallende maaltijd op een verder
 * consistente dag geeft dus gewoon een korte, kortstondige target-verlaging
 * zonder dat er iets gebeurt. Het venster is kort en de resterende
 * veiligheidslagen (hypo-bescherming, IOB-caps) blijven volledig actief
 * bovenop de verlaagde target — het effect is begrensd, niet nul.
 */
object FclMealTimeAnticipation {

    // ── Gedeelde opslag (05/07/2026, Ecko) ───────────────────────────────
    // FCLvNext.kt (schrijft — legt CONFIRMED-episodes vast) en DetermineBasalFCL.kt
    // (leest — past de korte target-verlaging toe, exact zoals FCLActivityModule
    // dat al doet voor activiteit) delen dezelfde SharedPreferences-opslag. Door
    // de naam/sleutel en de (de)serialisatie hier op één plek te houden, kunnen
    // de twee bestanden niet uit de pas gaan lopen.
    private const val PREFS = "fcl_mealtime_anticipation"
    private const val KEY = "history_json"

    fun loadFrom(context: android.content.Context): History =
        History.deserialize(
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY, "") ?: ""
        )

    fun saveTo(context: android.content.Context, h: History) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, h.serialize()).apply()
    }

    private const val WINDOW_DAYS = 60L
    private const val WINDOW_MS = WINDOW_DAYS * 24L * 60L * 60L * 1000L
    private const val MINUTES_PER_DAY = 1440

    /** Een cluster moet minstens dit veel events bevatten om een mode te vormen. */
    const val MIN_SESSIONS = 6

    /** …verspreid over minstens dit veel verschillende dagen (voorkomt een eenmalige uitschieter). */
    const val MIN_DISTINCT_DAYS = 4

    /** Circulaire halve-breedte (min) om events tot één mode te groeperen. */
    const val CLUSTER_HALF_WIDTH_MIN = 45

    // ── Kort venster (bewust anders dan Boost's 45-60 min, op verzoek Ecko) ──
    /** Venster opent dit veel minuten vóór de geleerde tijd. */
    const val PRE_MEAL_LEAD_OPEN_MIN = 20

    /** Venster sluit alweer dit veel minuten vóór de geleerde tijd — geeft de
     *  normale reactieve detectie ruim de tijd om het over te nemen. */
    const val PRE_MEAL_LEAD_CLOSE_MIN = 5

    /** Hoeveel de target verlaagd wordt zolang het venster actief is. */
    const val TARGET_LOWER_MMOL = 0.5

    /** Eén event: tijdstip (UTC ms) + of die dag een weekenddag was. */
    data class Event(val tsMs: Long, val isWeekend: Boolean)

    /** Rolling geschiedenis van CONFIRMED meal-episode-starttijden. */
    data class History(var events: MutableList<Event> = mutableListOf()) {
        fun serialize(): String {
            val arr = JSONArray()
            for (e in events) {
                arr.put(JSONObject().put("ts", e.tsMs).put("wk", e.isWeekend))
            }
            return JSONObject().put("events", arr).toString()
        }

        companion object {
            fun deserialize(raw: String): History {
                if (raw.isBlank()) return History()
                return try {
                    val arr = JSONObject(raw).optJSONArray("events") ?: JSONArray()
                    val list = mutableListOf<Event>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(Event(o.getLong("ts"), o.optBoolean("wk", false)))
                    }
                    History(list)
                } catch (e: Exception) {
                    History()
                }
            }
        }
    }

    /** Een geleerde, gewoontematige maaltijdtijd. */
    data class MealMode(
        val centreMin: Int,      // circulair-gemiddelde klok-minuut-van-de-dag [0..1439]
        val eventCount: Int,
        val distinctDays: Int,
    )

    data class PreMealHit(
        val mode: MealMode,
        val minutesBeforeMeal: Int,
    )

    /** Voeg een event toe en trim tot het rollende venster. Caller persisteert het resultaat. */
    fun record(h: History, tsMs: Long, isWeekend: Boolean): History {
        val newEvents = h.events.toMutableList()
        newEvents.add(Event(tsMs, isWeekend))
        val cutoff = tsMs - WINDOW_MS
        newEvents.removeAll { it.tsMs < cutoff }
        return History(newEvents)
    }

    private fun msToMinOfDay(ms: Long, localOffsetMs: Long): Int {
        val localMs = ms + localOffsetMs
        val minOfDay = ((localMs / 60_000L) % MINUTES_PER_DAY).toInt()
        return if (minOfDay < 0) minOfDay + MINUTES_PER_DAY else minOfDay
    }

    /** Circulair gemiddelde van een lijst klok-minuten-van-de-dag, of null bij een lege lijst. */
    private fun circularMean(minutes: List<Int>): Int? {
        if (minutes.isEmpty()) return null
        var sumSin = 0.0
        var sumCos = 0.0
        for (m in minutes) {
            val angle = 2.0 * Math.PI * m / MINUTES_PER_DAY
            sumSin += sin(angle)
            sumCos += cos(angle)
        }
        val meanAngle = atan2(sumSin, sumCos)
        val meanMin = (meanAngle / (2.0 * Math.PI) * MINUTES_PER_DAY).toInt()
        return ((meanMin % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    }

    /** Kleinste van de klokwijzer- / tegen-de-klok-in-afstand tussen twee klok-minuten. */
    private fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, MINUTES_PER_DAY - d)
    }

    /**
     * Clustert de geschiedenis van het gevraagde dagtype (weekend of doordeweeks)
     * tot vertrouwde meal-modes. O(n²), maar n is klein (≤ ~3-4 maaltijden/dag
     * × 60 dagen, en dan ook nog eens gehalveerd door de weekend/doordeweeks-split).
     */
    fun modes(h: History, localOffsetMs: Long, isWeekend: Boolean): List<MealMode> {
        val relevant = h.events.filter { it.isWeekend == isWeekend }
        if (relevant.size < MIN_SESSIONS) return emptyList()

        val pts = relevant.map { e ->
            msToMinOfDay(e.tsMs, localOffsetMs) to ((e.tsMs + localOffsetMs) / (24L * 60L * 60L * 1000L))
        }.toMutableList()

        val result = mutableListOf<MealMode>()
        while (pts.size >= MIN_SESSIONS) {
            val best = pts.maxByOrNull { c -> pts.count { circularDistance(it.first, c.first) <= CLUSTER_HALF_WIDTH_MIN } }
                ?: break
            val cluster = pts.filter { circularDistance(it.first, best.first) <= CLUSTER_HALF_WIDTH_MIN }
            val distinctDays = cluster.map { it.second }.distinct().size
            if (cluster.size >= MIN_SESSIONS && distinctDays >= MIN_DISTINCT_DAYS) {
                val centre = circularMean(cluster.map { it.first })
                if (centre != null) result.add(MealMode(centre, cluster.size, distinctDays))
                pts.removeAll(cluster.toSet())
            } else {
                // de dichtste resterende cluster is niet betrouwbaar → verder ook niet
                break
            }
        }
        return result
    }

    /**
     * Is [nowMin] (lokale klok-minuut-van-de-dag) binnen het korte anticipatie-
     * venster van een geleerde mode voor het gevraagde dagtype?
     *
     * Venster: `[centre - PRE_MEAL_LEAD_OPEN_MIN, centre - PRE_MEAL_LEAD_CLOSE_MIN]`.
     */
    fun preMealWindow(
        h: History,
        nowMin: Int,
        localOffsetMs: Long,
        isWeekend: Boolean,
    ): PreMealHit? {
        for (mode in modes(h, localOffsetMs, isWeekend)) {
            val ahead = ((mode.centreMin - nowMin) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
            if (ahead in PRE_MEAL_LEAD_CLOSE_MIN..PRE_MEAL_LEAD_OPEN_MIN) {
                return PreMealHit(mode, ahead)
            }
        }
        return null
    }
}
