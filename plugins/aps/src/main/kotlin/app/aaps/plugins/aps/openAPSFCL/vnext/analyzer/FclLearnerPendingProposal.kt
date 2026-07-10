package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import org.json.JSONObject

/**
 * FclLearnerPendingProposal (10/07/2026, Ecko) — lichte, kale opslag (zelfde
 * SharedPreferences-stijl als FclMealTimeAnticipation/FclActivityTypeCache)
 * voor het OPENSTAANDE learner-voorstel bij MANUAL-modus.
 *
 * BEWUST GEEN geschiedenis/queue — net als de AI's sticky-melding toont dit
 * altijd alleen de LAATSTE, meest actuele berekening (evaluate()/
 * evaluateLateCommitDecay() draaien toch al bij elke episode, ook in MANUAL —
 * zie DFLearner.isEvaluationEnabled). Een nieuwe berekening overschrijft dus
 * gewoon het vorige, nog-niet-beoordeelde voorstel.
 *
 * Bewaart uitsluitend de simpele invoer-scalars (D, F, vExtra, de ref-assen),
 * niet de al-berekende ParamOverrides/stvMap zelf — die worden bij het
 * accepteren vers opnieuw opgebouwd via DFMapping, exact dezelfde aanroep
 * die AUTO-modus ook al gebruikt (zie FclLearnerApplier.approve()). Zo is er
 * geen risico dat een verouderde, geserialiseerde ParamOverrides-snapshot
 * ooit afwijkt van wat DFMapping nu zou berekenen.
 */
object FclLearnerPendingProposal {

    private const val PREFS = "fcl_learner_pending_proposal"
    private const val KEY = "proposal_json"

    data class Proposal(
        val tsMs: Long,
        val d: Double,
        val f: Double,
        val vExtra: Double,
        val refWmd: Double,
        val refWff: Double,
        val refEb: Double,
        val refPeakBias: Double,
        val refLcd: Double,
        val agg: Int,
        val episodeCount: Int,
        val reason: String,
        // 10/07/2026 (Ecko) — voor de leesbare uitleg (FclLearnerUitleg):
        // de diagnose-code van evaluate() en de D/F-waarden van vóór deze
        // stap, zodat de kaart kan tonen "wat verandert er" i.p.v. alleen
        // de nieuwe absolute getallen.
        val diagnose: String = "",
        val oldD: Double = d,
        val oldF: Double = f
    )

    fun save(context: Context, p: Proposal) {
        val json = JSONObject()
            .put("ts", p.tsMs)
            .put("d", p.d)
            .put("f", p.f)
            .put("vExtra", p.vExtra)
            .put("refWmd", p.refWmd)
            .put("refWff", p.refWff)
            .put("refEb", p.refEb)
            .put("refPeakBias", p.refPeakBias)
            .put("refLcd", p.refLcd)
            .put("agg", p.agg)
            .put("episodeCount", p.episodeCount)
            .put("reason", p.reason)
            .put("diagnose", p.diagnose)
            .put("oldD", p.oldD)
            .put("oldF", p.oldF)
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
                d = o.getDouble("d"),
                f = o.getDouble("f"),
                vExtra = o.getDouble("vExtra"),
                refWmd = o.getDouble("refWmd"),
                refWff = o.getDouble("refWff"),
                refEb = o.getDouble("refEb"),
                refPeakBias = o.getDouble("refPeakBias"),
                refLcd = o.getDouble("refLcd"),
                agg = o.getInt("agg"),
                episodeCount = o.getInt("episodeCount"),
                reason = o.optString("reason", ""),
                diagnose = o.optString("diagnose", ""),
                oldD = o.optDouble("oldD", o.getDouble("d")),
                oldF = o.optDouble("oldF", o.getDouble("f"))
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
