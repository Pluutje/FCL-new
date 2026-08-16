package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night

/**
 * ============================================================================
 * FCL Nacht-AI-Adviseur — datamodellen
 * ============================================================================
 *
 * 23/07/2026 — VOLLEDIG ONAFHANKELIJK van vnext/advisor/ai (de
 * bestaande dag/maaltijd-parameteradviseur). Andere trigger (1x per nacht,
 * bij het einde van de nacht — niet elke ~8u tijdens de dag), ander domein
 * (basaalprofiel-uren, niet FCLvNext's eigen dosis-parameters), en
 * VOORLOPIG PUUR ADVIES: geen toepas-mechanisme, geen ranges/validator-
 * bestand zoals FclAiAdvisorRanges — er is niets om automatisch toe te
 * passen. Hergebruikt alleen de generieke HTTP-laag (FclAiAdvisorService)
 * en de bestaande API-sleutel/model-instellingen (FclAiAdvisorSettingsStore)
 * — geen reden om daarvoor een tweede sleutel te vragen.
 */

/** Samengevatte nachtgegevens voor één klok-uur, over de laatste N nachten. */
data class NightHourAggregate(
    val effectHour: Int,                 // 0-23
    val effectHourLabel: String,          // "02:00"
    val nightsCount: Int,                 // aantal vensters dat tot dit uur bijdroeg
    val avgBg: Double,
    val avgTarget: Double,
    val avgBgSlopePerHour: Double,
    val avgIob: Double,
    val avgIobDelta: Double,
    val currentBasalUph: Double,
    /** bv. {"NEUTRAL": 5, "BASAL_DOWN_PRECURSOR": 3, "UNCERTAIN": 2} — geeft de AI
     *  hetzelfde classificatiebeeld als de regel-gebaseerde analyzer, zonder
     *  dat de AI aan diezelfde starre regels gebonden is. */
    val classificationCounts: Map<String, Int>
)

/** Compact rapport dat als input-payload naar het model gaat — geen ruwe CSV. */
data class FclNightReportPayload(
    val generatedAtUtc: String,
    val nightsAnalyzed: Int,
    val hourlyData: List<NightHourAggregate>
)

/** Eén voorgesteld basaal-aandachtspunt — puur informatief, niet toepasbaar vanuit de app. */
data class NightBasalSuggestion(
    val hourLabel: String,          // "02:00"
    val direction: String,          // "LOWER" | "HIGHER"
    val currentBasalUph: Double,
    val suggestedShiftPct: Double,  // negatief = lager, positief = hoger
    val confidence: Double,         // 0.0-1.0, door het model zelf opgegeven
    val reasonNl: String,
    val evidenceFields: List<String>
)

data class NightAiAdvisorRunResult(
    val generatedAtUtc: String,
    val rawModelResponse: String,
    val suggestions: List<NightBasalSuggestion>,
    /** Korte samenvatting van het model (bv. "Geen duidelijk patroon deze
     *  periode" of "02:00-04:00 laat consistent lage IOB zien"). Altijd
     *  getoond, ook als suggestions leeg is — puur informatief. */
    val summaryNl: String? = null,
    val parseError: String? = null
)