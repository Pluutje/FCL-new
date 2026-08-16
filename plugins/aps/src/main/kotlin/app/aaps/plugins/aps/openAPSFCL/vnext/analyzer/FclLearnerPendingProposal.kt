package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import org.json.JSONObject

/**
 * FclLearnerPendingProposal (10/07/2026) — lichte, kale opslag (zelfde
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
    // 27/07/2026 — laatst TOEGEPASTE snapshot (na Goedkeuren), los van
    // het openstaande voorstel hierboven. Zie isMeaningfullyDifferent()/
    // FCLCycleLogRepository.kt Stap 7 voor waarom dit nodig is.
    private const val KEY_LAST_APPLIED = "last_applied_json"

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
        // 10/07/2026 — voor de leesbare uitleg (FclLearnerUitleg):
        // de diagnose-code van evaluate() en de D/F-waarden van vóór deze
        // stap, zodat de kaart kan tonen "wat verandert er" i.p.v. alleen
        // de nieuwe absolute getallen.
        val diagnose: String = "",
        val oldD: Double = d,
        val oldF: Double = f
    )

    private fun toJson(p: Proposal): String = JSONObject()
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
        .toString()

    private fun fromJson(raw: String): Proposal? = try {
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

    fun save(context: Context, p: Proposal) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, toJson(p)).apply()
    }

    fun load(context: Context): Proposal? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isBlank()) return null
        return fromJson(raw)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun hasPending(context: Context): Boolean = load(context) != null

    /**
     * 27/07/2026 — snapshot van het laatst TOEGEPASTE voorstel
     * (aangeroepen door FclLearnerApplier.approve() bij succes). Los van de
     * pending-opslag hierboven: deze overleeft juist het wissen daarvan.
     */
    fun saveLastApplied(context: Context, p: Proposal) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_APPLIED, toJson(p)).apply()
    }

    fun loadLastApplied(context: Context): Proposal? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_APPLIED, "") ?: ""
        if (raw.isBlank()) return null
        return fromJson(raw)
    }

    /**
     * 27/07/2026 — anti-spam-check voor Stap 7 in
     * FCLCycleLogRepository.kt: "die bleef een paar keer komen met hetzelfde
     * advies ook nadat ik het had geaccepteerd". evaluate() draait bij elke
     * episode, ook in MANUAL, en Stap 7 sloeg tot nu toe zonder check een
     * NIEUW voorstel op — zelfs als D/F/etc. sinds de laatste Goedkeuring
     * nauwelijks waren veranderd. Vergelijkt de vers berekende waarden tegen
     * loadLastApplied(); is er niets goedgekeurd sinds de laatste
     * app-(her)install/reset, dan is er niets om tegen te vergelijken en
     * geldt alles automatisch als "wel meldenswaardig" (true).
     */
    fun isMeaningfullyDifferent(
        context: Context,
        d: Double, f: Double, vExtra: Double,
        refWmd: Double, refWff: Double, refEb: Double, refPeakBias: Double, refLcd: Double
    ): Boolean {
        val a = loadLastApplied(context) ?: return true
        fun beyond(x: Double, y: Double, eps: Double) = kotlin.math.abs(x - y) > eps
        return beyond(d, a.d, 0.01) ||
            beyond(f, a.f, 0.01) ||
            beyond(vExtra, a.vExtra, 0.02) ||
            beyond(refWmd, a.refWmd, 0.02) ||
            beyond(refWff, a.refWff, 0.02) ||
            beyond(refEb, a.refEb, 0.02) ||
            beyond(refPeakBias, a.refPeakBias, 0.02) ||
            beyond(refLcd, a.refLcd, 0.01)
    }
}
