package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Instant

data class Episode(
    val id: Int,
    val rows: List<LogRow>,

    val start: Instant,
    val end: Instant,
    val postWindowEnd: Instant,

    val coreStart: Instant,
    val firstDoseTime: Instant?,

    val startBg: Double,
    val minBgCore: Double,
    val minBgWindow: Double,

    val hypoDetected: Boolean,
    val hypoMinutes: Long,

    val tbtDetected: Boolean,
    val tbtMinutes: Long,
    val isComplete: Boolean,

    // S/T/V — vervangt heightSetting, timingSetting, persistenceSetting,
    //          mealHandlingSetting, hypoProtectionSetting
    val sterktePct: Int,
    val timingPct: Int,
    val volhoudendheidPct: Int,
    val doseDistribution: String
)