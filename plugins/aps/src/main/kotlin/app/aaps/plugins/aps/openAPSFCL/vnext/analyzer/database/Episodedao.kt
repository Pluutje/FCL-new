package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EpisodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes ORDER BY startTs DESC")
    suspend fun getAllEpisodes(): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE startTs < :cutoff")
    suspend fun deleteOlderThan(cutoff: String)

    @Query("UPDATE episodes SET adviceStatus = :status, advisorWeight = :weight WHERE startTs = :startTs")
    suspend fun updateAdviceState(startTs: String, status: String, weight: Double)

    @Query("SELECT * FROM episodes WHERE startTs = :startTs LIMIT 1")
    suspend fun getEpisodeByStartTs(startTs: String): EpisodeEntity?

    @Query("UPDATE episodes SET adviceStatus = :state WHERE startTs IN (:startTsList)")
    suspend fun updateAdviceStateForStarts(startTsList: List<String>, state: String)

    @Query("UPDATE episodes SET adviceStatus = :newState WHERE adviceStatus = :oldState")
    suspend fun replaceAdviceState(oldState: String, newState: String)

    @Query("""
UPDATE episodes
SET adviceStatus = :newState
WHERE adviceStatus != :consumedState
  AND sterktePct BETWEEN :sterkte - 2 AND :sterkte + 2
  AND timingPct BETWEEN :timing - 2 AND :timing + 2
  AND volhoudendheidPct BETWEEN :volhoudendheid - 2 AND :volhoudendheid + 2
""")
    suspend fun consumeProfileEpisodes(
        sterkte: Int,
        timing: Int,
        volhoudendheid: Int,
        newState: String,
        consumedState: String
    )
}