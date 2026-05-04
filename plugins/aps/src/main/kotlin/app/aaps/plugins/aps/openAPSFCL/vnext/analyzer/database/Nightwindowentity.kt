package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "night_windows",
    indices = [
        Index(value = ["localDate"]),
        Index(value = ["startTs"])
    ]
)
data class NightWindowEntity(
    @PrimaryKey
    val id: String,

    val localDate: String,
    val startTs: String,
    val endTs: String,

    val slotLabel: String,

    val classification: String,
    val suitabilityWeight: Double,

    val rowCount: Int,

    val avgBg: Double,
    val avgTarget: Double,
    val minBg: Double,
    val maxBg: Double,
    val bgDelta: Double,
    val bgSlopePerHour: Double,

    val avgIob: Double,
    val iobDelta: Double,

    val mealCarryoverScore: Double,
    val overlappingEpisodeCount: Int,

    val driftSignal: String,
    val driftStrength: Double,
    val driftReason: String,

    val shiftedBlockLabel: String,

    val effectHour: Int,
    val effectHourLabel: String,

    val activeProfileSignature: String,
    val activeProfileKnown: Boolean,
    val activeProfileAssumedBeforeHistory: Boolean,
    val activeProfileSourceTsUtc: String,
    val activeProfileBasalUph: Double,

    // S/T/V — vervangt activeProfileName/activeMealDetectSpeed/
    //          activeCorrectionStyle/activeMealHandlingStyle/activeHypoProtectionStyle
    val activeSterktePct: Int,
    val activeTimingPct: Int,
    val activeVolhoudendheidPct: Int,
    val activeDoseDistributionStyle: String,

    val activeNightResponseStyle: String,
    val activeSettingsSignature: String,

    val nightMechanism: String,
    val riseDespiteIobScore: Double,
    val lateOvershootRiskScore: Double,
    val guardFrictionScore: Double,

    // Directe TAT/TBT per venster (toegevoegd v16)
    val tatPct: Double = 0.0,   // % cycli boven target + 0.7 mmol/L
    val tbtPct: Double = 0.0    // % cycli onder target − 0.7 mmol/L
)