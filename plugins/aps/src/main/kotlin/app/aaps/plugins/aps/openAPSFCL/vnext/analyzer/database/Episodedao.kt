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

    // ── Rescue — detectiestatus ───────────────────────────────────────────

    // Sla automatische detectiestatus + ARM-context op
    // (geschreven door FCLvNext bij episode-afsluiting)
    @Query("""
UPDATE episodes
SET rescueAutoState        = :autoState,
    rescueAutoConfidence   = :autoConfidence,
    rescueArmedIobRatio    = :armedIobRatio,
    rescueArmedSlope       = :armedSlope,
    rescueArmedBg          = :armedBg,
    rescueArmedMinAfterPeak = :armedMinAfterPeak
WHERE startTs = :startTs
""")
    suspend fun updateRescueAuto(
        startTs: String,
        autoState: String,
        autoConfidence: Double,
        armedIobRatio: Double,
        armedSlope: Double,
        armedBg: Double,
        armedMinAfterPeak: Int
    )

    // Sla handmatige gebruikersbevestiging op (geschreven vanuit de UI)
    @Query("UPDATE episodes SET rescueUserConfirmed = :userConfirmed WHERE startTs = :startTs")
    suspend fun updateRescueUserConfirmed(startTs: String, userConfirmed: String)

    // ── Rescue — calibratie queries ───────────────────────────────────────

    // Bevestigde rescue-episodes (true positives) — voor drempel-verlaging
    @Query("""
SELECT * FROM episodes
WHERE rescueUserConfirmed = 'YES'
ORDER BY startTs DESC LIMIT 20
""")
    suspend fun getRescueConfirmedEpisodes(): List<EpisodeEntity>

    // False positives: auto CONFIRMED maar gebruiker zei NO — voor drempel-verhoging
    @Query("""
SELECT * FROM episodes
WHERE rescueAutoState = 'CONFIRMED' AND rescueUserConfirmed = 'NO'
ORDER BY startTs DESC LIMIT 20
""")
    suspend fun getRescueFalsePositiveEpisodes(): List<EpisodeEntity>

    // Gemiste gevallen: gebruiker zei YES maar auto was niet CONFIRMED
    // ARM-context beschikbaar voor analyse welke drempel te laag was
    @Query("""
SELECT * FROM episodes
WHERE rescueUserConfirmed = 'YES' AND rescueAutoState != 'CONFIRMED'
ORDER BY startTs DESC LIMIT 20
""")
    suspend fun getRescueMissedEpisodes(): List<EpisodeEntity>
}