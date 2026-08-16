package app.aaps.plugins.aps.openAPSFCL.vnext

import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory brug tussen de geïntegreerde FCL Analyzer en FCLvNext.
 *
 * Vervangt de oude JSON-bestand flow:
 *   OUD: Analyzer schrijft FCLvNext_config_override.json → FCLvNextConfigOverride leest bestand
 *   NIEUW: Analyzer roept FclOverrideBridge.post() aan → FCLvNextConfigOverride.load() leest uit geheugen
 *
 * Gedrag is identiek aan consume_after_use: true — de override wordt precies één keer
 * geconsumeerd door FCLvNext en daarna automatisch gewist.
 *
 * Thread-safety: AtomicReference garandeert dat gelijktijdige toegang vanuit
 * de AAPS APS-thread (FCLvNext) en de Analyzer coroutine veilig is.
 */
object FclOverrideBridge {

    private val pending = AtomicReference<FCLvNextConfigOverride.Override?>(null)

    /**
     * Aanroepen vanuit de Analyzer (of de AI-advisor) zodra de gebruiker een
     * parameter-aanpassing bevestigt.
     *
     * ✅ GEWIJZIGD (30/06/2026): MERGE i.p.v. volledige vervanging.
     * Reden: bij meerdere losse goedkeuringen vlak na elkaar (bijv. AI-advisor
     * kaarten één voor één goedkeuren) overschreef post() voorheen de volledige
     * pending override — een net goedgekeurde parameter kon zo stilzwijgend
     * verloren gaan vóórdat FCLvNext hem had geconsumeerd. Nu worden alleen de
     * niet-null velden van de nieuwe override over de bestaande pending override
     * heen gelegd; overige velden (inclusief eerdere paramOverrides-velden)
     * blijven behouden.
     *
     * `reason` wordt samengevoegd (niet overschreven) zodat de geconsumeerde
     * override een volledig audit-spoor van alle bijdragende bronnen behoudt.
     */
    fun post(override: FCLvNextConfigOverride.Override) {
        pending.updateAndGet { existing ->
            if (existing == null) override else merge(existing, override)
        }
    }

    private fun merge(
        base: FCLvNextConfigOverride.Override,
        incoming: FCLvNextConfigOverride.Override
    ): FCLvNextConfigOverride.Override {
        val mergedParamOverrides = when {
            base.paramOverrides == null -> incoming.paramOverrides
            incoming.paramOverrides == null -> base.paramOverrides
            else -> mergeParamOverrides(base.paramOverrides, incoming.paramOverrides)
        }
        val mergedReason = listOfNotNull(base.reason, incoming.reason)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
            .ifBlank { null }

        return FCLvNextConfigOverride.Override(
            sterkte         = incoming.sterkte         ?: base.sterkte,
            timing          = incoming.timing          ?: base.timing,
            volhoudendheid  = incoming.volhoudendheid  ?: base.volhoudendheid,
            nfLevel         = incoming.nfLevel         ?: base.nfLevel,
            writtenAt       = incoming.writtenAt       ?: base.writtenAt,
            basedOnEpisodes = incoming.basedOnEpisodes ?: base.basedOnEpisodes,
            reason          = mergedReason,
            paramOverrides  = mergedParamOverrides,
            iobBrakeLearned = incoming.iobBrakeLearned ?: base.iobBrakeLearned
        )
    }

    private fun mergeParamOverrides(
        base: FCLvNextConfigOverride.ParamOverrides,
        incoming: FCLvNextConfigOverride.ParamOverrides
    ): FCLvNextConfigOverride.ParamOverrides =
        FCLvNextConfigOverride.ParamOverrides(
            peakPredictionThreshold       = incoming.peakPredictionThreshold       ?: base.peakPredictionThreshold,
            watchingFrontloadFrac         = incoming.watchingFrontloadFrac         ?: base.watchingFrontloadFrac,
            watchingMinDeltaToTarget      = incoming.watchingMinDeltaToTarget      ?: base.watchingMinDeltaToTarget,
            commitCooldownMinutes         = incoming.commitCooldownMinutes         ?: base.commitCooldownMinutes,
            peakPredictionHorizonH        = incoming.peakPredictionHorizonH        ?: base.peakPredictionHorizonH,
            iobStart                      = incoming.iobStart                      ?: base.iobStart,
            peakIobBrakeSuppressThreshold = incoming.peakIobBrakeSuppressThreshold ?: base.peakIobBrakeSuppressThreshold,
            earlyBoostFactor              = incoming.earlyBoostFactor              ?: base.earlyBoostFactor,
            earlyBoostMinConfidence       = incoming.earlyBoostMinConfidence       ?: base.earlyBoostMinConfidence,
            earlyBoostMaxCommits          = incoming.earlyBoostMaxCommits          ?: base.earlyBoostMaxCommits,
            earlyRiseFracMin              = incoming.earlyRiseFracMin              ?: base.earlyRiseFracMin,
            peakMaxSlopeWeight            = incoming.peakMaxSlopeWeight            ?: base.peakMaxSlopeWeight,
            lateCommitDecayFactor         = incoming.lateCommitDecayFactor         ?: base.lateCommitDecayFactor,
            lateCommitDecayThreshold      = incoming.lateCommitDecayThreshold      ?: base.lateCommitDecayThreshold,
            sustainedRiseSlopeMin         = incoming.sustainedRiseSlopeMin         ?: base.sustainedRiseSlopeMin,
            sustainedRiseMinTarget        = incoming.sustainedRiseMinTarget        ?: base.sustainedRiseMinTarget,
            earlyPeakBiasMmol             = incoming.earlyPeakBiasMmol             ?: base.earlyPeakBiasMmol
        )

    /**
     * Aanroepen vanuit FCLvNextConfigOverride.load().
     * Geeft de pending override terug EN wist hem atomisch — eenmalig gebruik.
     * Geeft null terug als er geen pending override is.
     */
    fun consume(): FCLvNextConfigOverride.Override? =
        pending.getAndSet(null)

    /**
     * Geeft true als er een pending override klaarstaat die nog niet geconsumeerd is.
     * Handig voor de Analyzer UI om te tonen dat een aanpassing wacht op de volgende cyclus.
     */
    fun hasPending(): Boolean = pending.get() != null

    /**
     * Wist een eventueel pending override zonder hem te consumeren.
     * Aanroepen als de gebruiker de aanpassing annuleert in de UI.
     */
    fun cancel() {
        pending.set(null)
    }
}