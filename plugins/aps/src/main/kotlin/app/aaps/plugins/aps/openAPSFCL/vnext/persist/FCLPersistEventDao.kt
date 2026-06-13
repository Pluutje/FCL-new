package app.aaps.plugins.aps.openAPSFCL.vnext.persist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FCLPersistEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FCLPersistEventEntity)

    @Query("SELECT * FROM fcl_persist_event WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun getSince(fromMs: Long): List<FCLPersistEventEntity>

    @Query("DELETE FROM fcl_persist_event WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM fcl_persist_event")
    suspend fun count(): Int
}
