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

    /** 27/07/2026 (Ecko) — meest recente rij die daadwerkelijk is toegepast
     *  (applied=1), voor de wachtperiode-berekening: "hoeveel nachten zijn
     *  verstreken sinds de laatste échte wijziging" (zie
     *  FclNightBasalAutoAdjuster.nightsSinceLastChange()). Anders dan
     *  getLatest() (die ook niet-toegepaste/afgewezen/skip-rijen meetelt). */
    @Query("SELECT * FROM profile_auto_adjust_log WHERE applied = 1 ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatestApplied(): ProfileAutoAdjustLogEntity?

    /** Aantal verschillende dagen met minstens 1 rij in modus [mode] sinds [sinceMs] —
     *  voor de "N dagen dry-run-data beschikbaar"-indicatie in de UI. */
    @Query("SELECT COUNT(DISTINCT localDate) FROM profile_auto_adjust_log WHERE mode = :mode AND timestampMs >= :sinceMs")
    suspend fun countDistinctDates(mode: String, sinceMs: Long): Int

    /** 27/07/2026 (Ecko) — voor het (gewogen) gemiddelde-voorstel over de
     *  laatste N nachten sinds een wijziging: alle rijen NA [sinceDate]
     *  (exclusief), nieuwste eerst. FclNightBasalAutoAdjuster verzamelt
     *  hieruit zelf de meest recente rij per kalenderdag en filtert rijen
     *  zonder echte berekening (perHourShiftJson == "{}") eruit — zie
     *  collectRecentNightlyShifts(). */
    @Query("SELECT * FROM profile_auto_adjust_log WHERE localDate > :sinceDate ORDER BY timestampMs DESC")
    suspend fun getSinceDateDesc(sinceDate: String): List<ProfileAutoAdjustLogEntity>

    @Query("DELETE FROM profile_auto_adjust_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
