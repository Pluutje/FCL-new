package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor

// ══════════════════════════════════════════════════════════════════════════
// S/T/V model — vervangt de 5 enum-assen
//
// Drie continue parameters in percentages:
//   S — Sterkte      (80–125%, stap ±5%)
//   T — Timing       (80–120%, stap ±5%)
//   V — Volhoudendheid (70–130%, stap ±5%)
//
// N (nacht-factor) wordt NIET via de advisor geadviseerd — dat is een
// persoonlijke kalibratie die de gebruiker zelf instelt.
//
// FclAxis, FclAxisState, FclAdjustmentVector blijven als type-aliassen
// zodat de rest van de code (AdvisorScreen, PatternAdvisor) nog compileert
// terwijl we stapsgewijs migreren.
// ══════════════════════════════════════════════════════════════════════════

enum class StvAxis { STERKTE, TIMING, VOLHOUDENDHEID }

// Backwards-compat alias — wordt stap voor stap vervangen in UI
typealias FclAxis = StvAxis
val FclAxis.HEIGHT get() = StvAxis.STERKTE
val FclAxis.TIMING_COMPAT get() = StvAxis.TIMING

// Huidige S/T/V waarden (percentages, rechtstreeks uit CSV)
data class StvState(
    val sterkte: Int = 100,         // 80–125
    val timing: Int = 100,          // 80–120
    val volhoudendheid: Int = 100   // 70–130
)

// Advies: aantal procentpunten verschil (veelal ±5)
data class StvAdjustment(
    val dSterkte: Int = 0,
    val dTiming: Int = 0,
    val dVolhoudendheid: Int = 0
) {
    fun isEmpty() = dSterkte == 0 && dTiming == 0 && dVolhoudendheid == 0
}

// Backwards-compat aliassen zodat PatternAdvisor en AdvisorScreen nog compileren
typealias FclAxisState = StvState
typealias FclAdjustmentVector = StvAdjustment

// ── Patterns (ongewijzigd) ─────────────────────────────────────────────────
enum class FclPattern {
    EARLY_SPIKE,
    LATE_PEAK,
    EARLY_HYPO,
    LATE_HYPO,
    LONG_HIGH_TAIL,
    OSCILLATING_RESPONSE,
    FLAT_GOOD,
    MIXED_UNCLEAR
}

data class FclPatternScore(
    val pattern: FclPattern,
    val score: Double,
    val reason: String
)

// ── Transition: één as, van → naar ────────────────────────────────────────
data class StvTransition(
    val axis: StvAxis,
    val fromPct: Int,
    val toPct: Int,
    val deltaPct: Int,     // toPct - fromPct
    val reason: String
)

// Backwards-compat — PatternAdvisor retourneert nog FclAxisTransition
data class FclAxisTransition(
    val axis: StvAxis,
    val fromIndex: Int,    // was enum 0..4, nu percentage
    val toIndex: Int,
    val step: Int,
    val reason: String
)

// ── Evidence ───────────────────────────────────────────────────────────────
data class FclAxisEvidence(
    val axis: StvAxis,
    val direction: Int,    // -1 omlaag, 0 neutraal, +1 omhoog
    val strength: Double,  // 0..1
    val reason: String
)

// ── Stats & selection ──────────────────────────────────────────────────────
data class FclAdvisorStats(
    val usedEpisodeCount: Int,
    val avgTirPercent: Int,
    val avgPeakBg: Double,
    val avgRiseMagnitude: Double,
    val avgDurationMinutes: Int,
    val avgInsulinDelivered: Double,
    val hyperPercent: Int,
    val hypoPercent: Int,
    val meetsGoalPercent: Int
)

data class FclAdvisorSelectionInfo(
    val totalEpisodesSeen: Int = 0,
    val usedEpisodeCount: Int = 0,
    val excludedOtherSettings: Int = 0,
    val excludedLowInsulin: Int = 0,
    val excludedConsumed: Int = 0,
    val excludedIncomplete: Int = 0
) {
    val excludedTotal: Int
        get() = excludedOtherSettings + excludedLowInsulin + excludedConsumed + excludedIncomplete
}

// ── Recommendation ─────────────────────────────────────────────────────────
// ── Param-signaal: richting voor een finetuning-parameter ────────────────
// Gegenereerd door FclPatternAdvisor op basis van episodepatronen.
// Wordt in de Fijnafstelling-tab gecombineerd met de Inzichten uit ParamFijnafstellingScreen.
enum class ParamSignalDirection { VERHOGEN, VERLAGEN, GOED, ONBEKEND }

data class FclParamSignal(
    val technisch: String,          // veldnaam in ParamOverrides, bv "peakIobBrakeSuppressThreshold"
    val direction: ParamSignalDirection,
    val strength: Double,           // 0..1
    val reason: String
)

data class FclAdvisorRecommendation(
    val dominantPattern: FclPattern,
    val confidence: Double,
    val patternScores: List<FclPatternScore>,

    // Advies in S/T/V procentpunten
    val adjustment: StvAdjustment,

    // Backwards-compat — zelfde inhoud, andere naam
    val vector: FclAdjustmentVector = adjustment,
    val transitions: List<FclAxisTransition>,

    val stats: FclAdvisorStats,
    val summary: String,
    val selectionInfo: FclAdvisorSelectionInfo = FclAdvisorSelectionInfo(),
    val axisEvidence: List<FclAxisEvidence> = emptyList(),

    // Param-signalen voor finetuning-parameters
    // Worden gecombineerd met de metrische adviezen uit ParamFijnafstellingScreen
    val paramSignals: List<FclParamSignal> = emptyList()
)