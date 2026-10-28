package com.acecore.app.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-created game category. Membership is metadata only: games are never moved or copied. */
@Entity(tableName = "categories", indices = [Index("sortOrder")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Key of a built-in icon, see feature.categories.CategoryIcons. */
    val icon: String,
    /** ARGB colour. */
    val color: Long,
    /** Optional app-private image file used as the category cover. */
    val coverPath: String? = null,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
    /** Scan this category's directories automatically when AceCore starts. */
    val autoScan: Boolean = false,
    val lastScanAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A folder (SAF tree) associated with a category; scanning it adds the games found to the category. */
@Entity(
    tableName = "category_dirs",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("categoryId"), Index(value = ["categoryId", "treeUri"], unique = true)],
)
data class CategoryDirEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val treeUri: String,
    val label: String,
)

/** Many-to-many: a game can belong to any number of categories. */
@Entity(
    tableName = "game_categories",
    primaryKeys = ["gameId", "categoryId"],
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("categoryId")],
)
data class GameCategoryRef(
    val gameId: Long,
    val categoryId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

/** One launch of a game: what really happened, used for statistics. */
@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val startedAt: Long,
    /** Foreground play time (time in AceCore's pause menu or in the background is not counted). */
    val durationMs: Long,
    /** True when the runtime reported the game running (RGSS: first frame drawn; web: page loaded). */
    val reachedGame: Boolean,
    /** Tap-to-first-frame time, when measured. */
    val latencyMs: Long?,
    val error: String?,
)

data class CategoryWithCount(
    @Embedded val category: CategoryEntity,
    val gameCount: Int,
)
