package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride
import app.aaps.plugins.aps.openAPSFCL.vnext.FclOverrideBridge
import java.time.Instant

/**
 * ============================================================================
 * FCL AI-Advisor — Applier
 * ============================================================================
 *
 * Bewust GEEN afhankelijkheid van ConfigOverrideWriter (analyzer-package) —
 * de AI-advisor is zelfstandig en praat rechtstreeks met dezelfde
 * FclOverrideBridge die de Analyzer ook gebruikt. Dankzij de merge-fix in
 * FclOverrideBridge.post() (30/06/2026) kunnen meerdere kaarten ná elkaar
 * goedgekeurd worden zonder dat eerdere goedkeuringen verloren gaan, en
 * zonder dat dit een race vormt met een eventuele gelijktijdige
 * Analyzer-override.
 *
 * Toepassing is ALTIJD het resultaat van een expliciete handmatige
 * goedkeuring per kaart — er is bewust geen pad dat een suggestie automatisch
 * toepast.
 */
object FclAiAdvisorApplier {

    sealed class ApplyResult {
        data class Applied(val param: String, val value: Double) : ApplyResult()
        data class Rejected(val reasonNl: String) : ApplyResult()
    }

    /**
     * @param suggestion de goedgekeurde suggestie (suggestion.rejected moet false zijn —
     *                   een al-afgewezen suggestie kan hier niet doorheen, zie check hieronder)
     */
    fun approve(suggestion: AiParamSuggestion): ApplyResult {
        if (suggestion.rejected) {
            return ApplyResult.Rejected(
                "Suggestie was al afgewezen door validatie (${suggestion.rejectionReasonNl}) — kan niet alsnog goedgekeurd worden"
            )
        }
        val spec = FclAiAdvisorRanges.byKey[suggestion.param]
            ?: return ApplyResult.Rejected("Onbekende parameter '${suggestion.param}'")

        // Laatste-moment her-validatie (defense in depth — de waarde kan in theorie
        // tussen parse-tijd en goedkeur-tijd niet meer wijzigen, maar dit is goedkoop
        // en voorkomt dat een toekomstige UI-bug een ongevalideerde waarde doorlaat).
        if (!FclAiAdvisorRanges.isInRange(spec, suggestion.proposedValue)) {
            FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.REJECTED)
            return ApplyResult.Rejected("Waarde buiten bereik bij toepassen — niet toegepast")
        }

        val paramOverrides = FclAiAdvisorRanges.singleFieldOverride(suggestion.param, suggestion.proposedValue)
        val override = FCLvNextConfigOverride.Override(
            writtenAt = Instant.now().toString(),
            reason = "AI-advisor: ${suggestion.reasonNl}",
            paramOverrides = paramOverrides
        )
        FclOverrideBridge.post(override)
        FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.APPROVED)

        return ApplyResult.Applied(suggestion.param, suggestion.proposedValue)
    }

    /** Aanroepen als de gebruiker een kaart afwijst — alleen voor het audit-spoor/cooldown. */
    fun reject(suggestion: AiParamSuggestion) {
        FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.REJECTED)
    }
}
