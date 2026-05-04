package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.PrimaryKey

// ⚠️ DB SCHEMA GEWIJZIGD: verhoog de versie in AppDatabase.kt
// Verwijderd: heightSetting, timingSetting, persistenceSetting,
//             mealHandlingSetting, hypoProtectionSetting
// Toegevoegd: sterktePct, timingPct, volhoudendheidPct

@Entity(tableName = "episodes")
data class EpisodeEntity(

    @PrimaryKey
    val startTs: String,

    val endTs: String,
    val durationMinutes: Long,

    val peakBg: Double,
    val nadirBg: Double,

    val tirPercent: Double,

    val hyper: Boolean,
    val hypoEarly: Boolean,
    val hypoLate: Boolean,

    val earlyAxisDir: Int,
    val lateAxisDir: Int,

    val earlyConfidence: Double,
    val lateConfidence: Double,

    val meetsGoal: Boolean,

    // S/T/V — vervangt de 5 enum-stijl kolommen
    val sterktePct: Int,
    val timingPct: Int,
    val volhoudendheidPct: Int,
    val doseDistribution: String,

    val totalInsulinDelivered: Double,
    val advisorWeight: Double,
    val adviceStatus: String
)