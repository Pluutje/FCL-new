package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Dao voor isf_auto_adjust_log — 1-op-1 gespiegeld aan ProfileAutoAdjustLogDao. */
@Dao
interface IsfAutoAdjustLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: IsfAutoAdjustLogEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM isf_auto_adjust_log WHERE localDate = :localDate)")
    suspend fun existsForDate(localDate: String): Boolean

    @Query("SELECT * FROM isf_auto_adjust_log ORDER BY timestampMs DESC")
    suspend fun getAll(): List<IsfAutoAdjustLogEntity>

    @Query("SELECT * FROM isf_auto_adjust_log WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    suspend fun getSince(sinceMs: Long): List<IsfAutoAdjustLogEntity>

    @Query("SELECT * FROM isf_auto_adjust_log ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): IsfAutoAdjustLogEntity?

    /** Meest recente daadwerkelijk toegepaste rij (applied=1) — voor de
     *  wachtperiode-berekening "hoeveel dagen sinds de laatste échte
     *  wijziging", zelfde rol als ProfileAutoAdjustLogDao.getLatestApplied(). */
    @Query("SELECT * FROM isf_auto_adjust_log WHERE applied = 1 ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatestApplied(): IsfAutoAdjustLogEntity?

    @Query("SELECT COUNT(DISTINCT localDate) FROM isf_auto_adjust_log WHERE mode = :mode AND timestampMs >= :sinceMs")
    suspend fun countDistinctDates(mode: String, sinceMs: Long): Int

    /** Alle rijen NA [sinceDate] (exclusief), nieuwste eerst — voor het
     *  gewogen gemiddelde-voorstel over meerdere dagen sinds een wijziging,
     *  zelfde patroon als ProfileAutoAdjustLogDao.getSinceDateDesc(). */
    @Query("SELECT * FROM isf_auto_adjust_log WHERE localDate > :sinceDate ORDER BY timestampMs DESC")
    suspend fun getSinceDateDesc(sinceDate: String): List<IsfAutoAdjustLogEntity>

    @Query("DELETE FROM isf_auto_adjust_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
