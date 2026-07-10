package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride
import app.aaps.plugins.aps.openAPSFCL.vnext.FclOverrideBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import java.time.Instant

/**
 * Past een goedgekeurde AI-suggestie toe via FclAiParamStore (persistente opslag).
 *
 * ARCHITECTUUR (01/07/2026, Ecko — definitieve versie):
 *
 *   defaults → DFLearner leert → FclAiParamStore (AI past aan) → ConfigOverrideWriter
 *   leest FclAiParamStore als prioriteit boven DFLearner → FCLvNext gebruikt dit
 *
 * FclOverrideBridge wordt NIET meer gebruikt voor AI-waarden — dat was de
 * fundamentele bug: FclOverrideBridge is een eenmalige one-shot die na één
 * cyclus verdwijnt, waarna ConfigOverrideWriter de DFLearner-waarden terugschreef.
 * FclAiParamStore is persistent totdat de gebruiker expliciet reset.
 *
 * Voor earlyBoostFactor en watchingFrontloadFrac: ook DFLearner-prefs bijwerken
 * zodat de DFLearner-evaluator bij de volgende episode niet terugvalt op een
 * verouderde waarde als startpunt.
 */
object FclAiAdvisorApplier {

    sealed class ApplyResult {
        data class Applied(val param: String, val value: Double) : ApplyResult()
        data class Rejected(val reasonNl: String) : ApplyResult()
    }

    fun approve(suggestion: AiParamSuggestion, context: Context): ApplyResult {
        if (suggestion.rejected) {
            return ApplyResult.Rejected(
                "Suggestie was al afgewezen door validatie (${suggestion.rejectionReasonNl})"
            )
        }
        val spec = FclAiAdvisorRanges.byKey[suggestion.param]
            ?: return ApplyResult.Rejected("Onbekende parameter '${suggestion.param}'")

        if (!FclAiAdvisorRanges.isInRange(spec, suggestion.proposedValue)) {
            FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.REJECTED)
            return ApplyResult.Rejected("Waarde buiten bereik bij toepassen — niet toegepast")
        }

        // ✅ Stap 1: schrijf naar FclAiParamStore (persistente AI-override laag).
        // ConfigOverrideWriter pakt deze waarde de volgende keer als prioriteit boven DFLearner.
        writeToStore(context, suggestion.param, suggestion.proposedValue)

        // ✅ Stap 2: ook DFLearner-prefs bijwerken voor learner-beheerde params,
        // zodat DFLearner bij de volgende episode-evaluatie niet terugvalt op een
        // verouderd startpunt (bijv. als de AI earlyBoostFactor verhoogt naar 2.3
        // maar DFLearner nog 2.20 heeft → DFLearner zou bij volgende episode
        // vanuit 2.20 evalueren in plaats van vanuit 2.3).
        // 10/07/2026 (Ecko) — sync voor ALLE 10 AI-aanpasbare parameters, niet
        // alleen de twee met een dedicated evaluator. Zonder deze sync zou de
        // Learner (indien aan) bij de eerstvolgende episode vanaf een
        // verouderde referentie evalueren/convergeren, los van waar de AI de
        // waarde net naartoe heeft gezet. Drie parameters hebben een eigen,
        // dedicated "ondergrond"-waarde (refEb/refWatching/refLcd) met eigen
        // evaluatielogica; de overige zeven gebruiken de generieke tracked-
        // opslag (zie DFLearner.convergeTrackedParams) — ook klaar voor
        // toekomstige AI/Learner-aanpasbare parameters zonder verdere wijziging
        // hier nodig te hebben.
        when (suggestion.param) {
            "earlyBoostFactor"      -> DFLearner.syncEarlyBoostFactor(context, suggestion.proposedValue)
            "watchingFrontloadFrac" -> DFLearner.syncWatchingFrac(context, suggestion.proposedValue)
            "lateCommitDecayFactor" -> DFLearner.syncLateCommitDecayFactor(context, suggestion.proposedValue)
            else                    -> DFLearner.syncTrackedParam(context, suggestion.param, suggestion.proposedValue)
        }

        FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.APPROVED)
        return ApplyResult.Applied(suggestion.param, suggestion.proposedValue)
    }

    fun reject(suggestion: AiParamSuggestion) {
        FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.REJECTED)
    }

    /**
     * Reset een parameter naar een opgegeven waarde.
     * Werkt ook voor parameters die uit FclAiAdvisorRanges.ALL zijn verwijderd
     * (bijv. peakIobBrakeSuppressThreshold) — die worden direct via FclOverrideBridge
     * teruggezet en verwijderd uit FclAiParamStore.
     */
    fun resetParam(param: String, value: Double, context: Context): ApplyResult {
        // Verwijder uit FclAiParamStore zodat DFLearner/defaults weer de baas zijn
        FclAiParamStore.remove(context, param)

        // Zet de waarde terug via FclOverrideBridge (eenmalig, voor deze reset)
        val paramOverrides = try {
            FclAiAdvisorRanges.singleFieldOverride(param, value)
        } catch (_: IllegalStateException) {
            buildRemovedParamOverride(param, value)
                ?: return ApplyResult.Rejected(
                    "Parameter '$param' niet resetbaar via deze route — pas handmatig aan"
                )
        }
        FclOverrideBridge.post(
            FCLvNextConfigOverride.Override(
                writtenAt = Instant.now().toString(),
                reason = "AI-advisor reset: $param → $value",
                paramOverrides = paramOverrides
            )
        )
        when (param) {
            "earlyBoostFactor"      -> DFLearner.syncEarlyBoostFactor(context, value)
            "watchingFrontloadFrac" -> DFLearner.syncWatchingFrac(context, value)
            "lateCommitDecayFactor" -> DFLearner.syncLateCommitDecayFactor(context, value)
            else                    -> DFLearner.syncTrackedParam(context, param, value)
        }
        FclAiAdvisorHistoryRepository.deleteParam(param)
        return ApplyResult.Applied(param, value)
    }

    /** Verwijder ALLE AI-overrides — na 'Reset AI-aanpassingen' in het resetscherm. */
    fun resetAll(context: Context) {
        FclAiParamStore.clear(context)
        // Geen DFLearner reset — die behoudt zijn geleerde waarden.
        // Geen history reset — die blijft als audit-trail.
    }

    // ── Intern ───────────────────────────────────────────────────────────────

    private fun writeToStore(context: Context, param: String, value: Double) {
        val isInt = FclAiAdvisorRanges.byKey[param]?.type ==
            FclAiAdvisorRanges.ValueType.INT
        if (isInt) {
            FclAiParamStore.putInt(context, param, value.toInt())
        } else {
            FclAiParamStore.put(context, param, value)
        }
    }

    private fun buildRemovedParamOverride(
        param: String, value: Double
    ): FCLvNextConfigOverride.ParamOverrides? = when (param) {
        "peakIobBrakeSuppressThreshold" ->
            FCLvNextConfigOverride.ParamOverrides(peakIobBrakeSuppressThreshold = value)
        else -> null
    }
}
