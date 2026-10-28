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

    @Query("SELECT DISTINCT category FROM games WHERE category IS NOT NULL AND category != '' ORDER BY category COLLATE NOCASE")
    fun observeCategories(): Flow<List<String>>
}
