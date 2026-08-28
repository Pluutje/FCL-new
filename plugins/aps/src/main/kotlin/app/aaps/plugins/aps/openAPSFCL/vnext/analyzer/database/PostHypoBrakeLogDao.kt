package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Dao voor post_hypo_brake_log — zelfde eenvoudige patroon als
 * IsfAutoAdjustLogDao/ProfileAutoAdjustLogDao. Zie kdoc bij
 * PostHypoBrakeLogEntity voor de aanleiding (26/08/2026).
 */
@Dao
interface PostHypoBrakeLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: PostHypoBrakeLogEntity): Long

    @Query("SELECT * FROM post_hypo_brake_log WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    suspend fun getSince(sinceMs: Long): List<PostHypoBrakeLogEntity>

    @Query("SELECT * FROM post_hypo_brake_log ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): PostHypoBrakeLogEntity?

    @Query("DELETE FROM post_hypo_brake_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
