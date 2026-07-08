package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * FclActivityTypeCache (06/07/2026, Ecko) — lichte, kale opslag (geen
 * Room/PersistenceLayer) voor de door ActivityTypeListener.kt gedetecteerde
 * activiteitstypes (ON_BICYCLE/WALKING/RUNNING/STILL/IN_VEHICLE/TILTING/
 * ON_FOOT/UNKNOWN), inclusief betrouwbaarheid.
 *
 * BEWUST GEEN Room-entity/DAO/PersistenceLayer-uitbreiding — op uitdrukkelijk
 * verzoek van Ecko: "van de AAPS-database afblijven, een los bestand
 * toevoegen is geen probleem, maar geen wijzigingen die bij een volgende
 * dev-update grote impact kunnen hebben." Kale SharedPreferences, zelfde
 * lichte patroon als FclMealTimeAnticipation.loadFrom/saveTo.
 *
 * HERZIEN 06/07/2026 (Ecko): eerste versie bewaarde alleen de LAATSTE waarde
 * en was toegespitst op fietsen (isRecentlyCycling). Op verzoek uitgebreid
 * naar een rollend venster met ALLE gedetecteerde typen, zodat:
 *  (a) EstimatedCaloriesCalculator.kt élk type een passende MET-waarde kan
 *      geven, niet alleen fietsen, en
 *  (b) FclActivityLogger.kt per teruggekeken uur kan laten zien WELK type
 *      daadwerkelijk is gebruikt — nodig om de correlatie tussen activiteit
 *      en insulinebehoefte straks goed te kunnen beoordelen, in plaats van
 *      alleen het resulterende kcal-getal te zien.
 */
object FclActivityTypeCache {

    private const val PREFS = "fcl_activity_type_cache"
    private const val KEY = "history_json"

    // Rollend venster: ruim genoeg voor de 8-uurs-terugkijk in FclActivityLogger.kt
    // plus wat marge, zonder de opslag onbeperkt te laten groeien.
    private const val WINDOW_MS = 24L * 60L * 60L * 1000L

    data class Entry(val tsMs: Long, val activityType: String, val confidencePct: Int)

    private fun load(context: Context): MutableList<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return try {
            val arr = JSONObject(raw).optJSONArray("events") ?: JSONArray()
            val list = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Entry(o.getLong("ts"), o.getString("type"), o.optInt("conf", 0)))
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("ts", e.tsMs).put("type", e.activityType).put("conf", e.confidencePct))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, JSONObject().put("events", arr).toString()).apply()
    }

    /** Aanroepen vanuit DataHandlerMobile.kt zodra een ActionActivityType-batch binnenkomt. */
    fun record(context: Context, tsMs: Long, activityType: String, confidencePct: Int) {
        val list = load(context)
        list.add(Entry(tsMs, activityType, confidencePct))
        val cutoff = tsMs - WINDOW_MS
        list.removeAll { it.tsMs < cutoff }
        save(context, list)
    }

    /**
     * Dominante activiteit in het venster startMs tot endMs (exclusief endMs)
     * — het type met de hoogste opgetelde betrouwbaarheid in dat venster, met
     * de gemiddelde betrouwbaarheid van dat type. Null als er niets in het
     * venster valt. Gebruikt door EstimatedCaloriesCalculator.kt (voor de
     * MET-keuze) én FclActivityLogger.kt (voor het gelogde type) — dezelfde
     * bron, dus het kcal-getal en het gelogde type kunnen nooit uit de pas lopen.
     */
    fun dominantInWindow(context: Context, startMs: Long, endMs: Long): Entry? {
        val inWindow = load(context).filter { it.tsMs >= startMs && it.tsMs < endMs }
        if (inWindow.isEmpty()) return null
        val bestType = inWindow.groupBy { it.activityType }
            .maxByOrNull { (_, v) -> v.sumOf { e -> e.confidencePct } }
            ?.key ?: return null
        val matching = inWindow.filter { it.activityType == bestType }
        return Entry(
            matching.maxOf { it.tsMs },
            bestType,
            matching.map { it.confidencePct }.average().toInt()
        )
    }

    /** Meest recente entry, ongeacht venster — voor de status-formatter ("nu"). */
    fun latest(context: Context): Entry? = load(context).maxByOrNull { it.tsMs }
}
