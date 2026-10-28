package com.acecore.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history is exported to app/schemas. Any schema change MUST bump [version] and add a Migration
 * (never fallbackToDestructiveMigration) so users never lose their library on update.
 */
@Database(
    entities = [GameEntity::class, CategoryEntity::class, CategoryDirEntity::class, GameCategoryRef::class, SessionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AceDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun categories(): CategoryDao
    abstract fun sessions(): SessionDao

    companion object {
        const val NAME = "acecore.db"

        fun create(context: Context): AceDatabase =
            Room.databaseBuilder(context.applicationContext, AceDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Default colour for categories created from v1's free-text category names (AceCore crimson). */
        private const val LEGACY_CATEGORY_COLOR = 0xFFE3132BL

        /**
         * v1 → v2: statistics columns, launch-file mode, categories (many-to-many), category folders and sessions.
         * v1's single free-text `category` per game becomes a real category with the game as a member.
         * Existing playtime and launch counts are kept as they are; no sessions are invented for the past.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN launchMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE games ADD COLUMN firstPlayedAt INTEGER")
                db.execSQL("ALTER TABLE games ADD COLUMN sessionCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE games ADD COLUMN launchAttempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE games ADD COLUMN successfulLaunches INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE games ADD COLUMN failedLaunches INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE games ADD COLUMN longestSessionMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE games ADD COLUMN lastLaunchLatencyMs INTEGER")
                db.execSQL("ALTER TABLE games ADD COLUMN lastLaunchOk INTEGER")
                db.execSQL("ALTER TABLE games ADD COLUMN folderSizeBytes INTEGER")
                // A manually chosen launch file from v1 (one that differs from the detected entry) stays manual.
                db.execSQL("UPDATE games SET launchMode = 1 WHERE launchFile IS NOT NULL AND entry IS NOT NULL AND launchFile != entry AND launchFile NOT LIKE '%.exe'")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`icon` TEXT NOT NULL, `color` INTEGER NOT NULL, `coverPath` TEXT, `hidden` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                        "`autoScan` INTEGER NOT NULL, `lastScanAt` INTEGER, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_sortOrder` ON `categories` (`sortOrder`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `category_dirs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `categoryId` INTEGER NOT NULL, " +
                        "`treeUri` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_dirs_categoryId` ON `category_dirs` (`categoryId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_category_dirs_categoryId_treeUri` ON `category_dirs` (`categoryId`, `treeUri`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_categories` (`gameId` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`gameId`, `categoryId`), " +
                        "FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_categories_categoryId` ON `game_categories` (`categoryId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `gameId` INTEGER NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `reachedGame` INTEGER NOT NULL, `latencyMs` INTEGER, `error` TEXT, " +
                        "FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_gameId` ON `sessions` (`gameId`)")

                // v1 free-text categories → real categories + membership.
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT INTO categories (name, icon, color, coverPath, hidden, sortOrder, autoScan, lastScanAt, createdAt) " +
                        "SELECT TRIM(category), 'folder', $LEGACY_CATEGORY_COLOR, NULL, 0, 0, 0, NULL, $now FROM games " +
                        "WHERE category IS NOT NULL AND TRIM(category) != '' GROUP BY TRIM(category) COLLATE NOCASE ORDER BY TRIM(category) COLLATE NOCASE",
                )
                db.execSQL("UPDATE categories SET sortOrder = id")
                db.execSQL(
                    "INSERT OR IGNORE INTO game_categories (gameId, categoryId, addedAt) " +
                        "SELECT g.id, c.id, $now FROM games g JOIN categories c ON c.name = TRIM(g.category) COLLATE NOCASE",
                )
            }
        }
    }
}
