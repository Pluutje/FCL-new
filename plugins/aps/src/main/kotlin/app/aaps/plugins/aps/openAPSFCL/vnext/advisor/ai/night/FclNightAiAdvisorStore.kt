package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * FCL Nacht-AI-Adviseur — Store
 * ============================================================================
 *
 * Eigen SharedPreferences-bestand, los van FclAiAdvisorSettingsStore/
 * FclAiParamStore/FclAiAdvisorHistoryRepository (de dag-adviseur) — bewust
 * volledig onafhankelijke opslag, zoals gevraagd. Bewaart:
 *  - het laatste resultaat (zodat het Nacht-tabblad het 's ochtends kan
 *    tonen zonder een nieuwe AI-aanroep te hoeven doen, en ook na een
 *    app-herstart nog beschikbaar is),
 *  - de laatste kalenderdag (Europe/Amsterdam) waarop al gedraaid is
 *    (voorkomt dubbele runs als de nacht->dag-overgang per ongeluk
 *    meermaals wordt gedetecteerd binnen dezelfde ochtend),
 *  - of de vorige cyclus "nacht" was (nodig voor de randdetectie
 *    true->false in FclNightAiAdvisorScheduler.onCycle()).
 */
object FclNightAiAdvisorStore {

    private const val PREFS_NAME = "fcl_night_ai_advisor_prefs"

    private const val KEY_LAST_RESULT = "last_result_json"
    private const val KEY_LAST_PROCESSED_DATE = "last_processed_local_date"
    private const val KEY_WAS_NIGHT_LAST_CYCLE = "was_night_last_cycle"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Laatste resultaat ────────────────────────────────────────────────────

    fun saveResult(context: Context, result: NightAiAdvisorRunResult) {
        val json = JSONObject().apply {
            put("generatedAtUtc", result.generatedAtUtc)
            put("rawModelResponse", result.rawModelResponse)
            put("summaryNl", result.summaryNl ?: JSONObject.NULL)
            put("parseError", result.parseError ?: JSONObject.NULL)
            put("suggestions", JSONArray().apply {
                result.suggestions.forEach { s ->
                    put(JSONObject().apply {
                        put("hourLabel", s.hourLabel)
                        put("direction", s.direction)
                        put("currentBasalUph", s.currentBasalUph)
                        put("suggestedShiftPct", s.suggestedShiftPct)
                        put("confidence", s.confidence)
                        put("reasonNl", s.reasonNl)
                        put("evidenceFields", JSONArray(s.evidenceFields))
                    })
                }
            })
        }
        prefs(context).edit().putString(KEY_LAST_RESULT, json.toString()).apply()
    }

    fun loadResult(context: Context): NightAiAdvisorRunResult? {
        val raw = prefs(context).getString(KEY_LAST_RESULT, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val suggestionsArr = json.optJSONArray("suggestions") ?: JSONArray()
            val suggestions = (0 until suggestionsArr.length()).mapNotNull { i ->
                val o = suggestionsArr.optJSONObject(i) ?: return@mapNotNull null
                val evArr = o.optJSONArray("evidenceFields") ?: JSONArray()
                NightBasalSuggestion(
                    hourLabel = o.optString("hourLabel", ""),
                    direction = o.optString("direction", ""),
                    currentBasalUph = o.optDouble("currentBasalUph", 0.0),
                    suggestedShiftPct = o.optDouble("suggestedShiftPct", 0.0),
                    confidence = o.optDouble("confidence", 0.0),
                    reasonNl = o.optString("reasonNl", ""),
                    evidenceFields = (0 until evArr.length()).map { evArr.optString(it, "") }
                )
            }
            NightAiAdvisorRunResult(
                generatedAtUtc = json.optString("generatedAtUtc", ""),
                rawModelResponse = json.optString("rawModelResponse", ""),
                suggestions = suggestions,
                summaryNl = if (json.isNull("summaryNl")) null else json.optString("summaryNl"),
                parseError = if (json.isNull("parseError")) null else json.optString("parseError")
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── Dedup: 1x per kalenderdag ────────────────────────────────────────────

    fun getLastProcessedLocalDate(context: Context): String? =
        prefs(context).getString(KEY_LAST_PROCESSED_DATE, null)

    fun setLastProcessedLocalDate(context: Context, date: String) {
        prefs(context).edit().putString(KEY_LAST_PROCESSED_DATE, date).apply()
    }

    // ── Randdetectie nacht->dag ──────────────────────────────────────────────

    fun wasNightLastCycle(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAS_NIGHT_LAST_CYCLE, false)

    fun setWasNightLastCycle(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAS_NIGHT_LAST_CYCLE, value).apply()
    }
}
