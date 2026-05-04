package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor

import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeClassifier
import kotlin.math.sqrt

data class EpisodeAggregate(

    val episodeCount: Int,

    val medianPeak: Double,
    val medianRise: Double,
    val medianTimeToPeak: Double,

    val hyperRate: Double,
    val earlyHypoRate: Double,
    val lateHypoRate: Double,

    val avgDuration: Double
)

object EpisodeAggregateBuilder {

    fun build(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>
    ): EpisodeAggregate {

        val episodeCount = metrics.size

        val peaks = metrics.map { it.peakBg }
        val rises = metrics.map { it.riseMagnitude }
        val times = metrics.mapNotNull { it.timeToPeakMinutes?.toDouble() }

        val durations = metrics.map { it.durationMinutes.toDouble() }

        val hyperRate =
            classes.count { it.hyper }.toDouble() / classes.size

        val earlyHypoRate =
            classes.count { it.hypoEarly }.toDouble() / classes.size

        val lateHypoRate =
            classes.count { it.hypoLate }.toDouble() / classes.size

        return EpisodeAggregate(

            episodeCount = episodeCount,

            medianPeak = peaks.median(),
            medianRise = rises.median(),
            medianTimeToPeak = times.median(),

            hyperRate = hyperRate,
            earlyHypoRate = earlyHypoRate,
            lateHypoRate = lateHypoRate,

            avgDuration = durations.average()
        )
    }
}

private fun List<Double>.median(): Double {

    if (isEmpty()) return 0.0

    val s = sorted()
    val mid = s.size / 2

    return if (s.size % 2 == 0)
        (s[mid - 1] + s[mid]) / 2.0
    else
        s[mid]
}