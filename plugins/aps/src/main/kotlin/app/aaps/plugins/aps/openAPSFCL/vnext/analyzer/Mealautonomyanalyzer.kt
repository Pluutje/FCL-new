package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Duration
import java.time.Instant
import kotlin.math.max

object MealAutonomyAnalyzer {

    fun analyze(episode: Episode): MealAutonomyMetrics {
        val rows = episode.rows
            .filter { it.timestamp >= episode.start && it.timestamp <= episode.end }
            .sortedBy { it.timestamp }

        val first60mRows = rows.filter {
            Duration.between(episode.start, it.timestamp).toMinutes() in 0..60
        }

        val firstDetectionTs = first60mRows
            .firstOrNull { hasMeaningfulMealSignal(it) }
            ?.timestamp

        val firstMeaningfulDoseTs = first60mRows
            .firstOrNull {
                max(max(it.finalDose, it.commitDoseFinal), it.deliveredTotal) >= 0.05
            }
            ?.timestamp

        // prebolus niet meer aanwezig in FCLvNext v6
        val mealIntentAssistSeen = false

        val frontloadSeen = rows.any { it.watchingFrontloadTriggered }

        val guardBlockingSeen = first60mRows.any {
            !it.effectiveCommitAllowed || it.topGuardActive || it.trajectoryHardBlock
        }

        val hypoProtectionSeen = first60mRows.any { it.hypoActive }

        val maxEarlyConfidence = first60mRows.maxOfOrNull { it.earlyConfidence } ?: 0.0
        val maxCommitDoseFirst60m = first60mRows.maxOfOrNull {
            max(it.commitDoseFinal, it.finalDose)
        } ?: 0.0

        val startToDetectionMinutes = minutesBetweenOrNull(episode.start, firstDetectionTs)
        val detectionToDoseMinutes = minutesBetweenOrNull(firstDetectionTs, firstMeaningfulDoseTs)
        val startToDoseMinutes = minutesBetweenOrNull(episode.start, firstMeaningfulDoseTs)

        val hypoBlockedFirstDose = run {
            if (!hypoProtectionSeen) return@run false
            if (firstMeaningfulDoseTs == null) return@run true
            val rowsBeforeFirstDose = first60mRows.filter { it.timestamp < firstMeaningfulDoseTs }
            rowsBeforeFirstDose.any { it.hypoActive }
        }

        val autonomyClass: MealAutonomyClass
        val autonomyReason: String

        when {
            hypoBlockedFirstDose && firstMeaningfulDoseTs == null -> {
                autonomyClass = MealAutonomyClass.SAFETY_LIMITED
                autonomyReason = "Hypo-protectie blokkeerde alle dosering in het eerste uur — autonoom vroeg starten was hier niet mogelijk zonder veiligheidsrisico."
            }
            hypoBlockedFirstDose && startToDoseMinutes != null && startToDoseMinutes > 20 -> {
                autonomyClass = MealAutonomyClass.SAFETY_LIMITED
                autonomyReason = "Hypo-protectie was actief vóór de eerste dosis en vertraagde het doseren tot ${startToDoseMinutes} minuten na episode-start."
            }
            guardBlockingSeen && firstMeaningfulDoseTs == null -> {
                autonomyClass = MealAutonomyClass.SAFETY_LIMITED
                autonomyReason = "Er waren remmende guards of commit-blokkades actief en er kwam geen betekenisvolle dosis vrij in het eerste uur."
            }
            startToDoseMinutes != null && startToDoseMinutes <= 10 && !mealIntentAssistSeen -> {
                autonomyClass = MealAutonomyClass.EARLY_AUTONOMOUS_RESPONSE
                autonomyReason = "Het systeem kwam binnen 10 minuten zelfstandig tot een betekenisvolle dosis zonder zichtbare meal-intent assist."
            }
            startToDoseMinutes != null && startToDoseMinutes <= 18 && !mealIntentAssistSeen -> {
                autonomyClass = MealAutonomyClass.BORDERLINE_AUTONOMOUS_RESPONSE
                autonomyReason = "Het systeem reageerde autonoom, maar nog niet echt vroeg. Dit zit dichter bij knoploos gedrag, maar is nog grensgeval."
            }
            mealIntentAssistSeen -> {
                autonomyClass = MealAutonomyClass.ASSIST_USED_OR_NEEDED
                autonomyReason = "Tijdens deze episode was meal-intent/prebolus assist zichtbaar. Daarmee is dit geen zuivere autonome maaltijdrespons."
            }
            firstDetectionTs == null && firstMeaningfulDoseTs == null -> {
                autonomyClass = MealAutonomyClass.UNCLEAR
                autonomyReason = "Er is in de episode geen duidelijk detectie- of doseermoment gevonden met de huidige analyzer-regels."
            }
            else -> {
                autonomyClass = MealAutonomyClass.UNCLEAR
                autonomyReason = "De episode bevat wel signalen, maar nog niet genoeg om veilig te zeggen of autonoom starten hier echt vroeg genoeg was."
            }
        }

        return MealAutonomyMetrics(
            episodeId = episode.id,
            firstDetectionTs = firstDetectionTs,
            firstMeaningfulDoseTs = firstMeaningfulDoseTs,
            startToDetectionMinutes = startToDetectionMinutes,
            detectionToDoseMinutes = detectionToDoseMinutes,
            startToDoseMinutes = startToDoseMinutes,
            mealIntentAssistSeen = mealIntentAssistSeen,
            frontloadSeen = frontloadSeen,
            guardBlockingSeen = guardBlockingSeen,
            hypoProtectionSeen = hypoProtectionSeen,
            maxEarlyConfidence = maxEarlyConfidence,
            maxCommitDoseFirst60m = maxCommitDoseFirst60m,
            autonomyClass = autonomyClass,
            autonomyReason = autonomyReason
        )
    }

    private fun hasMeaningfulMealSignal(row: LogRow): Boolean {
        val mealState = row.mealState.trim().uppercase()
        val mealStateSuggestsActivity = when (mealState) {
            "", "NONE", "IDLE", "OFF" -> false
            else -> true
        }
        return mealStateSuggestsActivity ||
            row.earlyConfidence >= 0.20 ||
            row.earlyTargetU >= 0.05 ||
            row.watchingFrontloadTriggered
    }

    private fun minutesBetweenOrNull(from: Instant?, to: Instant?): Long? {
        if (from == null || to == null) return null
        return Duration.between(from, to).toMinutes()
    }
}