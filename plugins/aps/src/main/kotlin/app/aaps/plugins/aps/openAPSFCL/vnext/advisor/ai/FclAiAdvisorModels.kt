package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

/**
 * ============================================================================
 * FCL AI-Advisor — datamodellen
 * ============================================================================
 */

/** Eén voorgesteld parametervoorstel, vóór gebruikersgoedkeuring. */
data class AiParamSuggestion(
    val param: String,
    val currentValue: Double,
    val proposedValue: Double,
    val confidence: Double,        // 0.0–1.0, door het model zelf opgegeven
    val reasonNl: String,          // onderbouwing, citeert evidenceFields
    val evidenceFields: List<String>,
    /** Gezet door de validator; reden van afwijzing als rejected=true. */
    val rejected: Boolean = false,
    val rejectionReasonNl: String? = null
)

/** Status van een kaart in de UI — wat de gebruiker er (nog) mee gedaan heeft. */
enum class AiSuggestionStatus { PENDING, APPROVED, REJECTED }

data class AiAdvisorRunResult(
    val generatedAtUtc: String,
    val rawModelResponse: String,
    val suggestions: List<AiParamSuggestion>,
    val parseError: String? = null   // null = parsing/validatie geslaagd (suggesties kunnen alsnog individueel rejected zijn)
)

/** Compact dagrapport dat als input-payload naar het model gaat — geen ruwe CSV. */
data class FclDailyReportPayload(
    val dateUtc: String,
    val periodHours: Int,
    val timeInRangePct: Double,
    val hypoCount: Int,
    val hypoMinutesTotal: Int,
    val avgTimeToPeakMin: Double?,
    val avgOvershootAfterPeakMmol: Double?,
    /** Gemiddelde |predFout0_20|+|predFout20_40| uit EpisodeMetrics — directe
     *  evidence voor peakPredictionThreshold/peakPredictionHorizonH/
     *  earlyPeakBiasMmol. Positief = systematische overschatting van de piek,
     *  negatief = onderschatting. null = geen episodes met predictiedata. */
    val avgPredictionErrorMmol: Double?,
    /** key = parameternaam, value = active/default/delta zoals in FCLvNext_active_params.json */
    val activeParams: Map<String, ActiveParamSnapshot>,
    /** Korte samenvattingsregels uit FCLvNext_LearnerLog_v1.csv van vandaag (geen ruwe rijen). */
    val learnerEventsSummary: List<String>,
    /** Korte, genummerde episode-incidenten (zoals het 14:15U-incident) — max ~5/dag om prompt compact te houden. */
    val notableEpisodes: List<String>,
    /** Aantal episodes deze periode met >= 3 verschillende commit-nummers
     *  (>= 3 losse doseermomenten binnen één maaltijdepisode). Noemer voor
     *  flatTaperEpisodeCount hieronder. (08/07/2026, Ecko) */
    val multiCommitEpisodeCount: Int,
    /** Van die multi-commit-episodes: hoeveel hadden een "vlakke afbouw" —
     *  de LAATSTE commit was nog >= 80% van de hoogste eerdere commit, dus
     *  geen merkbare afname ondanks meerdere commits. Hoog t.o.v.
     *  multiCommitEpisodeCount = de afbouw werkte niet, ook al lijkt de
     *  vroege trigger (earlyBoostFactor/earlyBoostMinConfidence) misschien
     *  "te agressief" op basis van hypoCount alleen. (08/07/2026, Ecko) */
    val flatTaperEpisodeCount: Int
)

data class ActiveParamSnapshot(
    val active: Double,
    val default: Double,
    val src: String   // "modified" | "default" | "override" — zoals in active_params.json
)
