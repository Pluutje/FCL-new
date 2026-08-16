package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.isf

import android.content.Context
import org.json.JSONObject

/**
 * ============================================================================
 * FCL ISF — automatisch bijstellen — Store
 * ============================================================================
 *
 * 16/08/2026. Eigen SharedPreferences-bestand, 1-op-1 gespiegeld aan
 * FclNightBasalAutoAdjustStore (zelfde rol, nu voor de ISF-tegenhanger).
 * Bewaart:
 *  - de modus, standaard UIT (FclSystemMode, gedeeld met alle andere
 *    Learner/adviseur-systemen)
 *  - het basisprofiel (per-uur ISF in mg/dl, zelfde eenheid als
 *    profile.getIsfMgdl()) waar de drift-cap in FclIsfAutoAdjuster tegen
 *    wordt afgemeten — vastgelegd bij de EERSTE toepassing en daarna
 *    alleen nog gewijzigd via een expliciete, handmatige "opnieuw
 *    vastleggen"-actie, exact zoals bij de nacht-basaal-variant
 *  - per uur, hoeveel opeenvolgende toepassingen de drift-cap dat uur
 *    hebben geraakt
 *
 * Bewust een VOLLEDIG LOSSTAANDE store (eigen prefs-bestand, eigen basis-
 * profiel, eigen mode) van FclNightBasalAutoAdjustStore — ISF en basaal
 * worden onafhankelijk van elkaar aan/uit gezet en hebben elk hun eigen
 * drift-anker, ook al is de mechaniek identiek.
 */
object FclIsfAutoAdjustStore {

    private const val PREFS_NAME = "fcl_isf_auto_adjust_prefs"

    private const val KEY_MODE = "mode"
    private const val KEY_BASELINE_JSON = "baseline_json"     // {"0": 68.4, "1": 68.4, ...} — mg/dl per U
    private const val KEY_BASELINE_SET_AT = "baseline_set_at"
    private const val KEY_BASELINE_SOURCE = "baseline_source" // "initial" | "manual-reset"
    private const val KEY_CAP_HIT_JSON = "cap_hit_json"       // {"0": 3, "1": 0, ...}

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Modus ────────────────────────────────────────────────────────────────

    /** Nooit-ingesteld (null) blijft OFF — geen zelfstandige start zonder
     *  expliciete keuze, zelfde uitgangspunt als bij de nacht-basaal. */
    fun getMode(context: Context): app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode {
        val stored = prefs(context).getString(KEY_MODE, null) ?: return app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF
        return app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.fromStored(stored)
    }

    fun setMode(context: Context, mode: app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    // ── Basisprofiel (ankerpunt voor de drift-cap) ─────────────────────────────

    /** Null zolang er nog nooit een basisprofiel is vastgelegd. */
    fun getBaseline(context: Context): Map<Int, Double>? {
        val json = prefs(context).getString(KEY_BASELINE_JSON, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val map = LinkedHashMap<Int, Double>()
            obj.keys().forEach { key -> map[key.toInt()] = obj.getDouble(key) }
            map
        } catch (_: Exception) {
            null
        }
    }

    fun getBaselineSetAt(context: Context): Long =
        prefs(context).getLong(KEY_BASELINE_SET_AT, 0L)

    fun getBaselineSource(context: Context): String =
        prefs(context).getString(KEY_BASELINE_SOURCE, "") ?: ""

    fun setBaseline(context: Context, hourlyMgdlValues: Map<Int, Double>, source: String, nowMs: Long) {
        val obj = JSONObject()
        hourlyMgdlValues.forEach { (hour, value) -> obj.put(hour.toString(), value) }
        prefs(context).edit()
            .putString(KEY_BASELINE_JSON, obj.toString())
            .putLong(KEY_BASELINE_SET_AT, nowMs)
            .putString(KEY_BASELINE_SOURCE, source)
            .putString(KEY_CAP_HIT_JSON, JSONObject().toString())
            .apply()
    }

    // ── Cap-hit-tellers (per uur, opeenvolgende toepassingen tegen de drift-cap aan) ──

    fun getCapHitCounters(context: Context): Map<Int, Int> {
        val json = prefs(context).getString(KEY_CAP_HIT_JSON, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = LinkedHashMap<Int, Int>()
            obj.keys().forEach { key -> map[key.toInt()] = obj.getInt(key) }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun updateCapHitCounters(context: Context, hitCapHours: Set<Int>, touchedHours: Set<Int>) {
        val current = getCapHitCounters(context).toMutableMap()
        touchedHours.forEach { hour ->
            current[hour] = if (hour in hitCapHours) (current[hour] ?: 0) + 1 else 0
        }
        val obj = JSONObject()
        current.forEach { (hour, count) -> obj.put(hour.toString(), count) }
        prefs(context).edit().putString(KEY_CAP_HIT_JSON, obj.toString()).apply()
    }
}
