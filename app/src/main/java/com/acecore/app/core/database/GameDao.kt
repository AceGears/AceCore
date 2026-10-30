package com.acecore.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    fun observe(id: Long): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: Long): GameEntity?

    @Query("SELECT * FROM games")
    suspend fun getAll(): List<GameEntity>

    @Query("SELECT * FROM games WHERE treeUri = :treeUri AND rootDocId = :rootDocId LIMIT 1")
    suspend fun findByLocation(treeUri: String, rootDocId: String): GameEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(game: GameEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringDuplicates(games: List<GameEntity>): List<Long>

    @Update
    suspend fun update(game: GameEntity)

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM games")
    suspend fun deleteAll(): Int

    @Query("UPDATE games SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE games SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE games SET lastPlayedAt = :at, launchCount = launchCount + 1, lastError = NULL WHERE id = :id")
    suspend fun markLaunched(id: Long, at: Long)

    @Query("UPDATE games SET playtimeMs = playtimeMs + :delta WHERE id = :id")
    suspend fun addPlaytime(id: Long, delta: Long)

    @Query("UPDATE games SET lastError = :error WHERE id = :id")
    suspend fun setError(id: Long, error: String?)

    @Query("UPDATE games SET overridesJson = :json WHERE id = :id")
    suspend fun setOverrides(id: Long, json: String)

    @Query("SELECT * FROM games WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<GameEntity>

    @Query("UPDATE games SET launchAttempts = launchAttempts + 1 WHERE id = :id")
    suspend fun recordAttempt(id: Long)

    /** A Play request that was stopped before any runtime started (missing runtime, folder access lost…). */
    @Query("UPDATE games SET failedLaunches = failedLaunches + 1, lastLaunchOk = 0, lastError = :error WHERE id = :id")
    suspend fun recordBlocked(id: Long, error: String)

    /** Records a finished runtime session. [reached] is 1 when the game actually ran, 0 when it failed to start. */
    @Query(
        "UPDATE games SET playtimeMs = playtimeMs + :ms, sessionCount = sessionCount + :reached, " +
            "successfulLaunches = successfulLaunches + :reached, failedLaunches = failedLaunches + (1 - :reached), " +
            "longestSessionMs = MAX(longestSessionMs, :ms), " +
            "firstPlayedAt = CASE WHEN :reached = 1 THEN COALESCE(firstPlayedAt, :startedAt) ELSE firstPlayedAt END, " +
            "lastLaunchOk = :reached, lastLaunchLatencyMs = COALESCE(:latencyMs, lastLaunchLatencyMs), " +
            "lastError = :error WHERE id = :id",
    )
    suspend fun recordSession(id: Long, ms: Long, reached: Int, startedAt: Long, latencyMs: Long?, error: String?)

    @Query("UPDATE games SET folderSizeBytes = :bytes WHERE id = :id")
    suspend fun setFolderSize(id: Long, bytes: Long)
}
