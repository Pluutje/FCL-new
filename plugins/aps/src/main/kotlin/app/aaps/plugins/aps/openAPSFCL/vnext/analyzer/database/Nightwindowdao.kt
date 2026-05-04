package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NightWindowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNightWindows(windows: List<NightWindowEntity>)

    @Query("SELECT * FROM night_windows ORDER BY startTs DESC")
    suspend fun getAllNightWindows(): List<NightWindowEntity>

    @Query("DELETE FROM night_windows WHERE startTs < :cutoffUtc")
    suspend fun deleteOlderThan(cutoffUtc: String)
}