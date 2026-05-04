package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "basal_profile_history",
    indices = [
        Index(value = ["tsUtc"]),
        Index(value = ["profileSignature"])
    ]
)
data class BasalProfileHistoryEntity(
    @PrimaryKey
    val tsUtc: String,

    val tsLocal: String,
    val timezone: String,
    val eventType: String,
    val isNight: Boolean,
    val profileSignature: String,

    val basal00: Double,
    val basal01: Double,
    val basal02: Double,
    val basal03: Double,
    val basal04: Double,
    val basal05: Double,
    val basal06: Double,
    val basal07: Double,
    val basal08: Double,
    val basal09: Double,
    val basal10: Double,
    val basal11: Double,
    val basal12: Double,
    val basal13: Double,
    val basal14: Double,
    val basal15: Double,
    val basal16: Double,
    val basal17: Double,
    val basal18: Double,
    val basal19: Double,
    val basal20: Double,
    val basal21: Double,
    val basal22: Double,
    val basal23: Double
) {
    fun basalAtHour(hour: Int): Double = when (hour) {
        0 -> basal00
        1 -> basal01
        2 -> basal02
        3 -> basal03
        4 -> basal04
        5 -> basal05
        6 -> basal06
        7 -> basal07
        8 -> basal08
        9 -> basal09
        10 -> basal10
        11 -> basal11
        12 -> basal12
        13 -> basal13
        14 -> basal14
        15 -> basal15
        16 -> basal16
        17 -> basal17
        18 -> basal18
        19 -> basal19
        20 -> basal20
        21 -> basal21
        22 -> basal22
        23 -> basal23
        else -> basal00
    }
}