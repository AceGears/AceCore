package com.acecore.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM game_categories gc WHERE gc.categoryId = c.id) AS gameCount " +
            "FROM categories c ORDER BY c.sortOrder, c.id",
    )
    fun observeWithCounts(): Flow<List<CategoryWithCount>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE categories SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE categories SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { i, id -> setSortOrder(id, i) }
    }

    @Query("UPDATE categories SET lastScanAt = :at WHERE id = :id")
    suspend fun setLastScan(id: Long, at: Long)

    // ---- directories
    @Query("SELECT * FROM category_dirs ORDER BY id")
    fun observeDirs(): Flow<List<CategoryDirEntity>>

    @Query("SELECT * FROM category_dirs WHERE categoryId = :categoryId ORDER BY id")
    suspend fun dirsOf(categoryId: Long): List<CategoryDirEntity>

    @Query("SELECT * FROM category_dirs")
    suspend fun allDirs(): List<CategoryDirEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDir(dir: CategoryDirEntity): Long

    @Query("DELETE FROM category_dirs WHERE id = :id")
    suspend fun deleteDir(id: Long)

    @Query("UPDATE category_dirs SET treeUri = :treeUri, label = :label WHERE id = :id")
    suspend fun changeDir(id: Long, treeUri: String, label: String)

    // ---- membership
    @Query("SELECT * FROM game_categories")
    fun observeRefs(): Flow<List<GameCategoryRef>>

    @Query("SELECT * FROM game_categories")
    suspend fun allRefs(): List<GameCategoryRef>

    @Query("SELECT categoryId FROM game_categories WHERE gameId = :gameId")
    suspend fun categoryIdsOf(gameId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRefs(refs: List<GameCategoryRef>)

    @Query("DELETE FROM game_categories WHERE gameId IN (:gameIds) AND categoryId = :categoryId")
    suspend fun removeRefs(gameIds: List<Long>, categoryId: Long)

    @Query("DELETE FROM game_categories WHERE gameId IN (:gameIds)")
    suspend fun clearRefs(gameIds: List<Long>)

    /** "Move to category": the games end up in exactly [categoryId]. */
    @Transaction
    suspend fun moveTo(gameIds: List<Long>, categoryId: Long) {
        clearRefs(gameIds)
        addRefs(gameIds.map { GameCategoryRef(it, categoryId) })
    }
}

data class SessionTotals(
    val sessions: Int,
    val totalMs: Long,
    val longestMs: Long,
    val firstAt: Long?,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE gameId = :gameId ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(gameId: Long, limit: Int = 20): Flow<List<SessionEntity>>

    @Query(
        "SELECT COUNT(*) AS sessions, COALESCE(SUM(durationMs), 0) AS totalMs, COALESCE(MAX(durationMs), 0) AS longestMs, " +
            "MIN(startedAt) AS firstAt FROM sessions WHERE gameId = :gameId AND reachedGame = 1",
    )
    fun observeTotals(gameId: Long): Flow<SessionTotals>
}
