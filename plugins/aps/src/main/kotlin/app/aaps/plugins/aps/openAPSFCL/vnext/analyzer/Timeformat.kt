package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFormat {

    private val amsterdamZone: ZoneId = ZoneId.of("Europe/Amsterdam")

    // Voorbeeld: 03-03-2026 14:27
    private val fmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.getDefault())

    fun formatLocalAmsterdam(ts: Instant?): String {
        if (ts == null) return "–"
        return fmt.withZone(amsterdamZone).format(ts)
    }
}