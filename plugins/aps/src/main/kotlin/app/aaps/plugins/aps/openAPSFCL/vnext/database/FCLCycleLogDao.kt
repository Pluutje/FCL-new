package app.aaps.plugins.aps.openAPSFCL.vnext.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FCLCycleLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FCLCycleLogEntity)

    @Query("SELECT * FROM fcl_cycle_log ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<FCLCycleLogEntity>

    @Query("SELECT * FROM fcl_cycle_log WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun getSince(fromMs: Long): List<FCLCycleLogEntity>

    @Query("SELECT COUNT(*) FROM fcl_cycle_log")
    suspend fun count(): Int

    @Query("DELETE FROM fcl_cycle_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT * FROM fcl_cycle_log ORDER BY timestampMs ASC")
    suspend fun getAll(): List<FCLCycleLogEntity>
}