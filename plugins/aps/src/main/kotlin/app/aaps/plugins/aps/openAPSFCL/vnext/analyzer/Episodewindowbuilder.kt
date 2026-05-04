package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Instant

object EpisodeWindowBuilder {

    fun sliceWithContext(
        allRows: List<LogRow>,
        episodeStart: Instant,
        episodeEnd: Instant,
        minutesBefore: Long = 60,
        minutesAfter: Long = 90
    ): List<LogRow> {

        val start = episodeStart.minusSeconds(minutesBefore * 60)
        val end = episodeEnd.plusSeconds(minutesAfter * 60)

        return allRows.filter {
            it.timestamp >= start && it.timestamp <= end
        }
    }
}