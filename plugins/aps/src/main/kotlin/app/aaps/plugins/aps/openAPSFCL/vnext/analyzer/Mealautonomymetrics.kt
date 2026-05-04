package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Instant

data class MealAutonomyMetrics(
    val episodeId: Int,
    val firstDetectionTs: Instant?,
    val firstMeaningfulDoseTs: Instant?,
    val startToDetectionMinutes: Long?,
    val detectionToDoseMinutes: Long?,
    val startToDoseMinutes: Long?,
    val mealIntentAssistSeen: Boolean,
    val frontloadSeen: Boolean,
    val guardBlockingSeen: Boolean,
    val hypoProtectionSeen: Boolean,
    val maxEarlyConfidence: Double,
    val maxCommitDoseFirst60m: Double,
    val autonomyClass: MealAutonomyClass,
    val autonomyReason: String
)

enum class MealAutonomyClass {
    EARLY_AUTONOMOUS_RESPONSE,
    BORDERLINE_AUTONOMOUS_RESPONSE,
    ASSIST_USED_OR_NEEDED,
    SAFETY_LIMITED,
    UNCLEAR
}