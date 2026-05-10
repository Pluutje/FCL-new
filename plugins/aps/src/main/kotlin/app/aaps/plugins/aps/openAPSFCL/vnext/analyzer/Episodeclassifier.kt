package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Duration
import java.time.Instant
import kotlin.math.max

/**
 * EpisodeClassifier (robust, edge-case arm)
 *
 * Kernprincipes:
 * 1) Facts first: HYPER / HYPO_EARLY / HYPO_LATE worden puur uit de data afgeleid.
 * 2) Axis directions zijn deterministisch en symmetrisch:
 *    - Early axis (E):  HYPO_EARLY -> -1 else if HYPER -> +1 else 0
 *    - Late  axis (L):  HYPO_LATE  -> -1 else if HYPER -> +1 else 0
 * 3) Magnitude bepaalt nooit de richting; magnitude is alleen "hoe sterk".
 * 4) Classificatie is een vaste 3x3 matrix op (E,L).
 *
 * Interpretatie:
 * - +1 = meer insuline in die fase (underpowered)
 * - -1 = minder insuline in die fase (overpowered)
 */
object EpisodeClassifier {

    data class Config(
        // doelen
        val tirGoalPercent: Double = 90.0,
        val peakGoal: Double = 10.5,
        val hypoThreshold: Double = 3.9,

        // TIR range (mmol/L)
        val tirLow: Double = 3.9,
        val tirHigh: Double = 10.0,

        // windows (minuten vanaf liabilityStart)
        val earlyWindowMinutes: Long = 60,
        val lateWindowEndMinutes: Long = 180, // voor pressure/deficit AUC (late) binnen eval window

        // dt handling
        val maxStepClampMinutes: Long = 10
    )

    enum class EpisodeClass {
        PERFECT,

        UNDERPOWERED_EARLY,
        UNDERPOWERED_LATE,
        UNDERPOWERED_MIXED,

        OVERPOWERED_EARLY,
        OVERPOWERED_LATE,
        OVERPOWERED_MIXED,
        OVERPOWERED_RESCUE,   // te veel/te laat — alleen stabiel door rescue carbs

        IMBALANCED_EARLY_PLUS_LATE_OVER,
        IMBALANCED_EARLY_OVER_PLUS_LATE_UNDER
    }

    data class EpisodeClassification(
        val episodeId: Int,

        // hard goal metrics
        val tirPercent: Double,
        val peakBg: Double,
        val minutesAbove105: Long,
        val hypoDetected: Boolean,
        val minutesBelow39: Long,

        // fact flags (helpful for debugging/UI)
        val hyper: Boolean,
        val hypoEarly: Boolean,
        val hypoLate: Boolean,

        // evidence (AUCs)
        val earlyPressureAuc: Double,
        val latePressureAuc: Double,
        val earlyDeficitAuc: Double,
        val lateDeficitAuc: Double,
        val postDeficitAuc: Double,

        // net signals (druk minus deficit) – puur informatief
        val earlySignal: Double,
        val lateSignal: Double,

        // axes
        val earlyAxisDir: Int,     // -1/0/+1
        val earlyAxisMag: Double,  // 0..1
        val lateAxisDir: Int,      // -1/0/+1
        val lateAxisMag: Double,   // 0..1

        val meetsGoal: Boolean,
        val classification: EpisodeClass
    )

    fun classify(episode: Episode, config: Config = Config()): EpisodeClassification {
        val liabilityStart: Instant = episode.firstDoseTime ?: episode.coreStart

// Echte maaltijdvenster voor user-facing doelmetrics: episode.start..episode.end
        val mealRows = episode.rows
            .asSequence()
            .filter { it.timestamp >= episode.start && it.timestamp <= episode.end }
            .toList()

// Liability-venster voor fase-analyse: liabilityStart..episode.end
        val liabilityRows = episode.rows
            .asSequence()
            .filter { it.timestamp >= liabilityStart && it.timestamp <= episode.end }
            .toList()

// Post window voor late hypo: (end..postWindowEnd]
        val postRows = episode.rows
            .asSequence()
            .filter { it.timestamp > episode.end && it.timestamp <= episode.postWindowEnd }
            .toList()

// Full liability window voor hypo detect: liabilityStart..postWindowEnd
        val windowRows = episode.rows
            .asSequence()
            .filter { it.timestamp >= liabilityStart && it.timestamp <= episode.postWindowEnd }
            .toList()

        fun minutesBetween(a: Instant, b: Instant): Long =
            Duration.between(a, b).toMinutes()

        fun clampedDtMinutes(a: Instant, b: Instant): Long =
            minutesBetween(a, b).coerceAtLeast(0).coerceAtMost(config.maxStepClampMinutes)

        fun tFromLiabilityMinutes(ts: Instant): Long =
            Duration.between(liabilityStart, ts).toMinutes()

        fun sumMinutesWhere(rows: List<LogRow>, predicate: (LogRow) -> Boolean): Long {
            if (rows.size < 2) return 0L
            var minutes = 0L
            for (i in 0 until rows.size - 1) {
                val a = rows[i]
                val b = rows[i + 1]
                val dt = clampedDtMinutes(a.timestamp, b.timestamp)
                if (predicate(a)) minutes += dt
            }
            return minutes
        }

        fun integrateOver(
            rows: List<LogRow>,
            inWindow: (tMinFromStart: Long) -> Boolean,
            valueAt: (LogRow) -> Double
        ): Double {
            if (rows.size < 2) return 0.0
            var sum = 0.0
            for (i in 0 until rows.size - 1) {
                val a = rows[i]
                val b = rows[i + 1]
                val tA = tFromLiabilityMinutes(a.timestamp)
                if (!inWindow(tA)) continue
                val dt = clampedDtMinutes(a.timestamp, b.timestamp).toDouble()
                sum += valueAt(a) * dt
            }
            return sum
        }

        // --- Peak / above ---
// --- Peak / above binnen echte maaltijdvenster ---
        val peakRow = mealRows.maxByOrNull { it.bg }
        val peakBg = peakRow?.bg ?: Double.NaN
        val hyper = peakBg.isFinite() && peakBg >= config.peakGoal

        val minutesAbove105 = sumMinutesWhere(mealRows) { it.bg > config.peakGoal }

        // --- Hypo / TIR ---
        val minutesBelow39 = sumMinutesWhere(windowRows) { it.bg < config.hypoThreshold }
        val hypoDetected = episode.hypoDetected || minutesBelow39 > 0

        val totalEvalMinutes = if (mealRows.size < 2) 0L else {
            var sum = 0L
            for (i in 0 until mealRows.size - 1) {
                sum += clampedDtMinutes(mealRows[i].timestamp, mealRows[i + 1].timestamp)
            }
            sum
        }

        val tirMinutes = sumMinutesWhere(mealRows) { it.bg >= config.tirLow && it.bg <= config.tirHigh }
        val tirPercent = if (totalEvalMinutes > 0)
            100.0 * tirMinutes.toDouble() / totalEvalMinutes.toDouble()
        else 0.0

        // --- AUCs (pressure/deficit) ---
        val earlyPressureAuc = integrateOver(
            rows = liabilityRows,
            inWindow = { t -> t in 0 until config.earlyWindowMinutes },
            valueAt = { r -> max(0.0, r.bg - r.target) }
        )
        val latePressureAuc = integrateOver(
            rows = liabilityRows,
            inWindow = { t -> t in config.earlyWindowMinutes until config.lateWindowEndMinutes },
            valueAt = { r -> max(0.0, r.bg - r.target) }
        )

        val earlyDeficitAuc = integrateOver(
            rows = liabilityRows,
            inWindow = { t -> t in 0 until config.earlyWindowMinutes },
            valueAt = { r -> max(0.0, r.target - r.bg) }
        )
        val lateDeficitAuc = integrateOver(
            rows = liabilityRows,
            inWindow = { t -> t in config.earlyWindowMinutes until config.lateWindowEndMinutes },
            valueAt = { r -> max(0.0, r.target - r.bg) }
        )

        val postDeficitAuc = integrateOver(
            rows = postRows,
            inWindow = { _ -> true },
            valueAt = { r -> max(0.0, r.target - r.bg) }
        )

        val earlySignal = earlyPressureAuc - earlyDeficitAuc
        val lateSignal = latePressureAuc - lateDeficitAuc - postDeficitAuc

        // --- HYPO timing facts (robust, direction-driving) ---
        val hypoEarly = windowRows.any { r ->
            r.bg < config.hypoThreshold &&
                tFromLiabilityMinutes(r.timestamp) in 0 until config.earlyWindowMinutes
        }
        val hypoLate = windowRows.any { r ->
            r.bg < config.hypoThreshold &&
                tFromLiabilityMinutes(r.timestamp) >= config.earlyWindowMinutes
        }

        // --- Axis directions: axioms (symmetrisch, edge-case arm) ---
        val E = when {
            hypoEarly -> -1
            hyper -> +1
            else -> 0
        }
        val L = when {
            hypoLate -> -1
            hyper -> +1
            else -> 0
        }

        // --- Magnitudes (0..1), never used for direction ---
        fun normPerMin(auc: Double, minutes: Long): Double {
            if (minutes <= 0) return 0.0
            val perMin = auc / minutes.toDouble()
            // 0..2 mmol/L gemiddeld verschil => 0..1
            return (perMin / 2.0).coerceIn(0.0, 1.0)
        }

        val earlyPressureMag = normPerMin(earlyPressureAuc, config.earlyWindowMinutes)
        val earlyDeficitMag = normPerMin(earlyDeficitAuc, config.earlyWindowMinutes)

        val lateMinutes = (config.lateWindowEndMinutes - config.earlyWindowMinutes).coerceAtLeast(1)
        val latePressureMag = normPerMin(latePressureAuc, lateMinutes)
        val lateDeficitMag = normPerMin(lateDeficitAuc, lateMinutes)

        val postMinutes = if (postRows.size < 2) 0L else {
            var m = 0L
            for (i in 0 until postRows.size - 1) {
                m += clampedDtMinutes(postRows[i].timestamp, postRows[i + 1].timestamp)
            }
            m
        }
        val postDeficitMag = normPerMin(postDeficitAuc, max(1L, postMinutes))

        val earlyAxisMag = when (E) {
            +1 -> earlyPressureMag
            -1 -> earlyDeficitMag
            else -> 0.0
        }
        val lateAxisMag = when (L) {
            +1 -> latePressureMag
            -1 -> max(lateDeficitMag, postDeficitMag)
            else -> 0.0
        }

        // --- meetsGoal ---
        val meetsGoal =
            tirPercent >= config.tirGoalPercent &&
                peakBg.isFinite() && peakBg < config.peakGoal &&
                !hypoDetected

        // --- Class mapping (matrix) ---
        val classification = when {
            meetsGoal && E == 0 && L == 0 -> EpisodeClass.PERFECT

            E == +1 && L == -1 -> EpisodeClass.IMBALANCED_EARLY_PLUS_LATE_OVER
            E == -1 && L == +1 -> EpisodeClass.IMBALANCED_EARLY_OVER_PLUS_LATE_UNDER

            E == +1 && L == 0 -> EpisodeClass.UNDERPOWERED_EARLY
            E == 0 && L == +1 -> EpisodeClass.UNDERPOWERED_LATE
            E == +1 && L == +1 -> EpisodeClass.UNDERPOWERED_MIXED

            E == -1 && L == 0 -> EpisodeClass.OVERPOWERED_EARLY
            E == 0 && L == -1 -> EpisodeClass.OVERPOWERED_LATE
            E == -1 && L == -1 -> EpisodeClass.OVERPOWERED_MIXED

            // (0,0) maar niet meetsGoal: zeldzaam; kies label op basis van hypo
            else -> if (hypoDetected) EpisodeClass.OVERPOWERED_MIXED else EpisodeClass.UNDERPOWERED_MIXED
        }

        return EpisodeClassification(
            episodeId = episode.id,

            tirPercent = tirPercent,
            peakBg = peakBg,
            minutesAbove105 = minutesAbove105,
            hypoDetected = hypoDetected,
            minutesBelow39 = minutesBelow39,

            hyper = hyper,
            hypoEarly = hypoEarly,
            hypoLate = hypoLate,

            earlyPressureAuc = earlyPressureAuc,
            latePressureAuc = latePressureAuc,
            earlyDeficitAuc = earlyDeficitAuc,
            lateDeficitAuc = lateDeficitAuc,
            postDeficitAuc = postDeficitAuc,

            earlySignal = earlySignal,
            lateSignal = lateSignal,

            earlyAxisDir = E,
            earlyAxisMag = earlyAxisMag,
            lateAxisDir = L,
            lateAxisMag = lateAxisMag,

            meetsGoal = meetsGoal,
            classification = classification
        )
    }

    fun classifyAll(episodes: List<Episode>, config: Config = Config()): List<EpisodeClassification> =
        episodes.map { classify(it, config) }

    /**
     * Pas classificaties aan op basis van rescue-bevestigingen uit de database.
     * Episodes waarbij gebruiker rescue bevestigde worden OVERPOWERED_RESCUE
     * tenzij ze al UNDERPOWERED waren (rescue bij hypo is ander scenario).
     */
    fun applyRescueOverrides(
        classifications: List<EpisodeClassification>,
        rescueConfirmedStartTs: Set<String>,
        episodes: List<Episode>
    ): List<EpisodeClassification> {
        if (rescueConfirmedStartTs.isEmpty()) return classifications
        return classifications.map { c ->
            val episode = episodes.getOrNull(c.episodeId - 1) ?: return@map c
            val isRescue = episode.start.toString() in rescueConfirmedStartTs
            if (isRescue && !c.classification.name.startsWith("UNDERPOWERED")) {
                c.copy(classification = EpisodeClass.OVERPOWERED_RESCUE)
            } else {
                c
            }
        }
    }
}