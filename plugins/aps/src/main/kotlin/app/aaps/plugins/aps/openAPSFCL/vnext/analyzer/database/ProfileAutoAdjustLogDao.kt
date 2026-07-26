package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProfileAutoAdjustLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ProfileAutoAdjustLogEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM profile_auto_adjust_log WHERE localDate = :localDate)")
    suspend fun existsForDate(localDate: String): Boolean

    @Query("SELECT * FROM profile_auto_adjust_log ORDER BY timestampMs DESC")
    suspend fun getAll(): List<ProfileAutoAdjustLogEntity>

    @Query("SELECT * FROM profile_auto_adjust_log WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    suspend fun getSince(sinceMs: Long): List<ProfileAutoAdjustLogEntity>

    @Query("SELECT * FROM profile_auto_adjust_log ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): ProfileAutoAdjustLogEntity?

    /** Aantal verschillende dagen met minstens 1 rij in modus [mode] sinds [sinceMs] —
     *  voor de "N dagen dry-run-data beschikbaar"-indicatie in de UI. */
    @Query("SELECT COUNT(DISTINCT localDate) FROM profile_auto_adjust_log WHERE mode = :mode AND timestampMs >= :sinceMs")
    suspend fun countDistinctDates(mode: String, sinceMs: Long): Int

    @Query("DELETE FROM profile_auto_adjust_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
