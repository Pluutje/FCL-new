package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night

import android.content.Context
import org.json.JSONObject

/**
 * ============================================================================
 * FCL Nacht-basaal — automatisch bijstellen — Store
 * ============================================================================
 *
 * 24/07/2026 (Ecko). Eigen SharedPreferences-bestand, zelfde patroon als
 * FclNightAiAdvisorStore — losstaand van alle andere opslag. Bewaart:
 *  - de modus, standaard UIT
 *  - het basisprofiel (per-uur basaalwaarden) waar de ±25%-drift-cap in
 *    FclNightBasalAutoAdjuster tegen wordt afgemeten — vastgelegd bij de
 *    EERSTE toepassing (voorstel of automatisch) en daarna alleen nog
 *    gewijzigd via een expliciete, handmatige "opnieuw vastleggen"-actie
 *    (zie resetBaseline hieronder) — NOOIT automatisch verschoven, precies
 *    zoals met Ecko besproken (24/07/2026): "mocht hij ooit tegen de max
 *    van de 25% aanlopen dan zouden we het oorspronkelijke ook handmatig
 *    aan moeten kunnen passen".
 *  - per uur, hoeveel opeenvolgende dagen de drift-cap dat uur heeft geraakt
 *    (voor de "dit uur zit al N dagen tegen de grens aan"-indicatie in de
 *    UI) — gaat naar 0 zodra een uur een dag niet raakt, en helemaal
 *    opnieuw bij een baseline-reset.
 *
 * DAG/NACHT-HERSTRUCTURERING (26/07/2026, Ecko): de eigen
 * "enum class Mode { OFF, DRY_RUN, AUTO }" is vervangen door de gedeelde
 * FclSystemMode (OFF/AUTO/MANUAL) — zelfde patroon als DFLearner en de
 * dag-AI-adviseur, zodat de nacht-adjuster nu ook een echte MANUAL-modus
 * heeft (voorstel + Accepteren/Afwijzen) i.p.v. alleen passief loggen.
 * ALLEEN_LOGGEN/DRY_RUN wordt functioneel MANUAL: in beide gevallen wordt
 * er berekend en gelogd maar niet automatisch toegepast — het verschil is
 * dat MANUAL er nu ook een expliciete accepteer/afwijs-actie bovenop krijgt
 * (zie FclNightBasalAutoAdjuster.applyPending()/rejectPending()).
 */
object FclNightBasalAutoAdjustStore {

    private const val PREFS_NAME = "fcl_night_basal_auto_adjust_prefs"

    private const val KEY_MODE = "mode"
    private const val KEY_BASELINE_JSON = "baseline_json"     // {"0": 0.96, "1": 1.02, ...}
    private const val KEY_BASELINE_SET_AT = "baseline_set_at"
    private const val KEY_BASELINE_SOURCE = "baseline_source" // "initial" | "manual-reset"
    private const val KEY_CAP_HIT_JSON = "cap_hit_json"       // {"0": 3, "1": 0, ...}

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Modus ────────────────────────────────────────────────────────────────

    /**
     * Backward-compat (26/07/2026, Ecko): bestaande installaties hebben
     * mogelijk nog de oude waarden "OFF"/"DRY_RUN"/"AUTO" opgeslagen staan.
     * "DRY_RUN" wordt EXPLICIET naar MANUAL gemapt — bewust NIET via
     * FclSystemMode.fromStored() (die valt voor een onherkende waarde terug
     * op AUTO, wat hier een bestaande dry-run/test-gebruiker zonder waarschuwing
     * naar "daadwerkelijk toepassen op de pomp" zou zetten — een serieuze
     * veiligheidsregressie). Nooit-ingesteld (null) blijft OFF, net als
     * voorheen — geen zelfstandige start zonder expliciete keuze.
     */
    fun getMode(context: Context): app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode {
        val stored = prefs(context).getString(KEY_MODE, null)
        return when (stored) {
            null -> app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF
            "DRY_RUN" -> app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL
            else -> app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.fromStored(stored)
        }
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

    /**
     * Legt een nieuw basisprofiel vast en zet de cap-hit-tellers terug op 0.
     * [source] is puur informatief ("initial" bij de allereerste keer,
     * "manual-reset" bij een bewuste herziening door Ecko via de UI).
     */
    fun setBaseline(context: Context, hourlyValues: Map<Int, Double>, source: String, nowMs: Long) {
        val obj = JSONObject()
        hourlyValues.forEach { (hour, value) -> obj.put(hour.toString(), value) }
        prefs(context).edit()
            .putString(KEY_BASELINE_JSON, obj.toString())
            .putLong(KEY_BASELINE_SET_AT, nowMs)
            .putString(KEY_BASELINE_SOURCE, source)
            .putString(KEY_CAP_HIT_JSON, JSONObject().toString())
            .apply()
    }

    // ── Cap-hit-tellers (per uur, opeenvolgende dagen tegen de drift-cap aan) ──

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

    /**
     * [hitCapHours]: uren die deze run daadwerkelijk tegen de drift-cap aanliepen (teller +1).
     * [touchedHours]: alle uren die deze run een AI-suggestie hadden, cap geraakt of niet — een
     * uur dat wél is aangeraakt maar de cap NIET raakte, gaat terug naar 0 (de streak is
     * onderbroken). Een uur dat deze run helemaal geen suggestie had, blijft ongewijzigd staan.
     */
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
