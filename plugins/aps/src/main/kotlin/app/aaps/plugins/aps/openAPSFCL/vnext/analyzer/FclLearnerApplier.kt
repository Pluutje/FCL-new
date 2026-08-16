package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context

/**
 * FclLearnerApplier (10/07/2026) — accepteren/afwijzen van het
 * openstaande MANUAL-voorstel. Zelfde rol als FclAiAdvisorApplier, maar voor
 * de Learner: geen los-per-parameter-goedkeuren (de Learner stelt altijd een
 * complete, samenhangende D/F/vExtra/ref-set voor — dat is nu eenmaal hoe
 * DFMapping werkt, zie de kdoc daar over "structureel onmogelijk om
 * earlyBoost te verhogen zonder dat lateDecay meegaat").
 */
object FclLearnerApplier {

    /**
     * Bouwt ParamOverrides/stvMap vers op uit het opgeslagen voorstel — exact
     * dezelfde DFMapping-aanroepen die AUTO-modus ook gebruikt (zie
     * FCLCycleLogRepository.kt) — en schrijft ze naar ConfigOverrideWriter.
     * Retourneert false als er niets openstond of het schrijven mislukte.
     */
    fun approve(context: Context): Boolean {
        val p = FclLearnerPendingProposal.load(context) ?: return false

        val po = DFMapping.toParamOverrides(
            d = p.d, f = p.f,
            refWmd = p.refWmd, refWff = p.refWff, refEb = p.refEb,
            refPeakBias = p.refPeakBias, refLcd = p.refLcd,
            vExtra = p.vExtra, aggLevel = p.agg
        )
        val stvMap = DFMapping.toStvMap(p.d, p.f, 85.0, p.vExtra, aggLevel = p.agg)

        val ok = ConfigOverrideWriter.writeWithStvAndParams(
            stvMap         = stvMap,
            paramOverrides = po,
            reason         = "manual-learner (geaccepteerd): ${p.reason}",
            context        = context,
            episodeCount   = p.episodeCount
        )
        if (ok) {
            // 27/07/2026 — snapshot bewaren VOORDAT de pending-rij
            // gewist wordt, zodat FCLCycleLogRepository.kt (Stap 7) een
            // volgende, bijna-identieke berekening kan herkennen als "niets
            // nieuws" en niet opnieuw hoeft te melden. Zie
            // FclLearnerPendingProposal.isMeaningfullyDifferent().
            FclLearnerPendingProposal.saveLastApplied(context, p)
            FclLearnerPendingProposal.clear(context)
            FclLearnerNotificationHelper.dismissAdvice(context)
        }
        return ok
    }

    /**
     * Wijst het voorstel af. De Learner's eigen interne D/F/ref-stand (waar
     * evaluate() al naartoe was bewogen, ongeacht MANUAL/AUTO — zie
     * DFLearner.isEvaluationEnabled) blijft ongewijzigd staan; er is geen
     * "terugdraaien" nodig omdat er nooit iets live is geschreven. De
     * eerstvolgende episode-evaluatie rekent gewoon verder vanaf die stand.
     */
    fun reject(context: Context) {
        FclLearnerPendingProposal.clear(context)
        FclLearnerNotificationHelper.dismissAdvice(context)
    }
}
