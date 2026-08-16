package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import org.json.JSONObject

/**
 * NachtLearnerPendingProposal (26/07/2026) — analoog aan
 * FclLearnerPendingProposal, maar voor de NACHT-as van de Learner (het
 * NF-schaal-voorstel van NachtLearner.maybeLearnNacht(), i.p.v. D/F).
 *
 * Zelfde ontwerp: bewaart alleen de simpele invoerwaarden (geleerde NF +
 * de context die de reden-tekst nodig heeft), niet een al-berekende
 * ParamOverrides-snapshot — die wordt bij accepteren vers opgebouwd via
 * dezelfde ConfigOverrideWriter.writeWithNfLevel-aanroep die AUTO ook
 * gebruikt (zie NachtLearnerApplier.approve()). Geen geschiedenis/queue:
 * net als bij de Dag-Learner en de AI-adviseur overschrijft elke nieuwe
 * berekening gewoon het vorige, nog-niet-beoordeelde voorstel.
 */
object NachtLearnerPendingProposal {

    private const val PREFS = "nacht_learner_pending_proposal"
    private const val KEY = "proposal_json"

    data class Proposal(
        val tsMs: Long,
        val huidigeNf: Double,
        val nieuweNf: Double,
        val effectieveNf: Double,
        val reason: String,
        val episodeCount: Int
    )

    fun save(context: Context, p: Proposal) {
        val json = JSONObject()
            .put("ts", p.tsMs)
            .put("huidigeNf", p.huidigeNf)
            .put("nieuweNf", p.nieuweNf)
            .put("effectieveNf", p.effectieveNf)
            .put("reason", p.reason)
            .put("episodeCount", p.episodeCount)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }

    fun load(context: Context): Proposal? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            Proposal(
                tsMs = o.getLong("ts"),
                huidigeNf = o.getDouble("huidigeNf"),
                nieuweNf = o.getDouble("nieuweNf"),
                effectieveNf = o.getDouble("effectieveNf"),
                reason = o.optString("reason", ""),
                episodeCount = o.optInt("episodeCount", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun hasPending(context: Context): Boolean = load(context) != null
}
