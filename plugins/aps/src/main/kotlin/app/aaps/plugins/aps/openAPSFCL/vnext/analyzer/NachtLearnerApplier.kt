package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context

/**
 * NachtLearnerApplier (26/07/2026) — accepteren/afwijzen van het
 * openstaande NACHT-Learner (NF-schaal) MANUAL-voorstel. Analoog aan
 * FclLearnerApplier, maar schrijft via ConfigOverrideWriter.writeWithNfLevel
 * i.p.v. writeWithStvAndParams — exact hetzelfde pad dat AUTO-modus al
 * gebruikt in NachtLearner.maybeLearnNacht().
 *
 * Hergebruikt bewust FclLearnerNotificationHelper (dezelfde generieke
 * "Learner: nieuw voorstel klaar"-melding als de Dag-as al gebruikt) — geen
 * los notificatiekanaal nodig voor wat feitelijk dezelfde functie (Learner)
 * is, alleen een andere as.
 */
object NachtLearnerApplier {

    /**
     * Schrijft het opgeslagen NF-voorstel naar AAPS — zelfde
     * ConfigOverrideWriter-aanroep als de AUTO-tak in NachtLearner.kt.
     * Retourneert false als er niets openstond of het schrijven mislukte.
     */
    fun approve(context: Context): Boolean {
        val p = NachtLearnerPendingProposal.load(context) ?: return false

        val active = ConfigOverrideWriter.readActiveParams()
        val currentState = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvState(
            sterkte        = active.sterkte,
            timing         = active.timing,
            volhoudendheid = active.volhoudendheid
        )
        val ok = ConfigOverrideWriter.writeWithNfLevel(
            currentState = currentState,
            newNfLevel   = p.effectieveNf,
            reason       = "manual-nachtlearner (geaccepteerd): ${p.reason}",
            episodeCount = p.episodeCount
        )
        if (ok) {
            DFLearner.setLastAppliedNfLevel(context, p.nieuweNf)
            NachtLearnerPendingProposal.clear(context)
            FclLearnerNotificationHelper.dismissAdvice(context)
        }
        return ok
    }

    /**
     * Wijst het voorstel af. NachtLearner's interne NF-stand (DFLearner.
     * setNfLevel — al bijgewerkt ongeacht AUTO/MANUAL, zie kdoc bij
     * maybeLearnNacht) blijft ongewijzigd staan; er is geen "terugdraaien"
     * nodig omdat er nooit iets live naar AAPS is geschreven.
     */
    fun reject(context: Context) {
        NachtLearnerPendingProposal.clear(context)
        FclLearnerNotificationHelper.dismissAdvice(context)
    }
}
