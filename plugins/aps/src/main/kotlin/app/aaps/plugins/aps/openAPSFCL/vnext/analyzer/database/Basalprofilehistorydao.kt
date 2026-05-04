package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BasalProfileHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<BasalProfileHistoryEntity>)

    @Query("SELECT * FROM basal_profile_history ORDER BY tsUtc ASC")
    suspend fun getAll(): List<BasalProfileHistoryEntity>

    @Query("SELECT MAX(tsUtc) FROM basal_profile_history")
    suspend fun getLatestTimestamp(): String?

    @Query("DELETE FROM basal_profile_history WHERE tsUtc < :cutoffUtc")
    suspend fun deleteOlderThan(cutoffUtc: String)

    @Query("SELECT * FROM basal_profile_history ORDER BY tsUtc DESC LIMIT 1")
    suspend fun getLatest(): BasalProfileHistoryEntity?


}