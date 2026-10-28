package com.acecore.app.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrading from AceCore 0.3 (schema v1) must keep every game, its playtime, favourites and settings, and turn
 * v1's free-text category into real categories. Room validates the migrated schema against the v2 entities, so a
 * migration that doesn't produce exactly the expected tables fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before fun clean() { context.deleteDatabase(name) }
    @After fun cleanUp() { context.deleteDatabase(name) }

    /** Exactly what Room 2.7 generated for AceCore 0.3's GameEntity (schema version 1). */
    private fun createV1() {
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `games` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `treeUri` TEXT NOT NULL, " +
                    "`rootDocId` TEXT NOT NULL, `folderLabel` TEXT NOT NULL, `launchFile` TEXT, `entry` TEXT, `contentRoot` TEXT NOT NULL, `engine` TEXT NOT NULL, " +
                    "`engineOverride` TEXT, `engineVersion` TEXT, `confidence` INTEGER NOT NULL, `status` TEXT NOT NULL, `detectionJson` TEXT NOT NULL, " +
                    "`coverDocId` TEXT, `customCoverPath` TEXT, `favorite` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `category` TEXT, `addedAt` INTEGER NOT NULL, " +
                    "`lastPlayedAt` INTEGER, `playtimeMs` INTEGER NOT NULL, `launchCount` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                    "`overridesJson` TEXT NOT NULL DEFAULT '{}', `lastError` TEXT)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_treeUri_rootDocId` ON `games` (`treeUri`, `rootDocId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_lastPlayedAt` ON `games` (`lastPlayedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_favorite` ON `games` (`favorite`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'v1-test')")
            fun game(id: Int, title: String, category: String?, fav: Int, playtime: Long, launchFile: String?, entry: String?) = db.execSQL(
                "INSERT INTO games (id, title, treeUri, rootDocId, folderLabel, launchFile, entry, contentRoot, engine, engineOverride, engineVersion, confidence, " +
                    "status, detectionJson, coverDocId, customCoverPath, favorite, hidden, category, addedAt, lastPlayedAt, playtimeMs, launchCount, sortOrder, overridesJson, lastError) " +
                    "VALUES (?, ?, 'content://tree/x', ?, ?, ?, ?, '', 'POKEMON_ESSENTIALS', NULL, 'v20+', 100, 'PLAYABLE_UNTESTED', '{}', NULL, NULL, ?, 0, ?, 1000, 5000, ?, 3, 0, '{\"filtering\":\"sharp\"}', NULL)",
                arrayOf<Any?>(id, title, "doc$id", title, launchFile, entry, fav, category, playtime),
            )
            game(1, "Pokemon Ancient Platinum", "Pokémon", 1, 3_600_000, "Game.exe", "Game.ini")
            game(2, "Another Fangame", " pokémon ", 0, 0, "Game.exe", "Game.ini")
            game(3, "Web Game", null, 0, 120_000, "index2.html", "www/index2.html")
            game(4, "Visual Novel", "Visual Novels", 0, 0, null, null)
            db.version = 1
        }
    }

    @Test
    fun migrate1To2_keepsLibraryAndCreatesCategories() = runBlocking {
        createV1()
        val db = Room.databaseBuilder(context, AceDatabase::class.java, name)
            .addMigrations(AceDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val games = db.games().getAll().associateBy { it.id }
            assertEquals(4, games.size)
            val apt = games.getValue(1)
            assertEquals("Pokemon Ancient Platinum", apt.title)
            assertEquals(3_600_000L, apt.playtimeMs)
            assertTrue(apt.favorite)
            assertEquals(3, apt.launchCount)
            assertEquals("{\"filtering\":\"sharp\"}", apt.overridesJson)
            assertEquals(GameEntity.LAUNCH_AUTO, apt.launchMode)
            assertEquals(0, apt.sessionCount)
            assertNull(apt.lastLaunchOk)
            // A hand-picked non-.exe entry from 0.3 stays a manual choice.
            assertEquals(GameEntity.LAUNCH_MANUAL, games.getValue(3).launchMode)

            val cats = db.categories().getAll()
            // "Pokémon" and " pokémon " become one category (trimmed, case-insensitive).
            assertEquals(listOf("pokémon", "visual novels"), cats.map { it.name.lowercase() })
            val refs = db.categories().allRefs().groupBy({ it.categoryId }, { it.gameId })
            val pokemon = cats.first { it.name.lowercase() == "pokémon" }.id
            assertEquals(setOf(1L, 2L), refs.getValue(pokemon).toSet())
            assertEquals(listOf(4L), refs.getValue(cats.first { it.name == "Visual Novels" }.id))
            assertTrue(db.categories().observeWithCounts().first().all { it.gameCount >= 1 })
        } finally {
            db.close()
        }
    }

    @Test
    fun deletingCategoryKeepsGames_andDeletingGameRemovesMembership() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AceDatabase::class.java).allowMainThreadQueries().build()
        try {
            val g1 = db.games().insert(sampleGame("a"))
            val g2 = db.games().insert(sampleGame("b"))
            val c1 = db.categories().insert(CategoryEntity(name = "RPG", icon = "rpg", color = 1))
            val c2 = db.categories().insert(CategoryEntity(name = "Done", icon = "completed", color = 2))
            db.categories().addRefs(listOf(GameCategoryRef(g1, c1), GameCategoryRef(g2, c1), GameCategoryRef(g1, c2)))
            db.categories().insertDir(CategoryDirEntity(categoryId = c1, treeUri = "content://t", label = "Games"))

            db.categories().moveTo(listOf(g1), c2)
            assertEquals(setOf(c2), db.categories().categoryIdsOf(g1).toSet())

            db.categories().delete(c1)
            assertEquals(2, db.games().getAll().size)
            assertTrue(db.categories().allDirs().isEmpty())
            assertTrue(db.categories().categoryIdsOf(g2).isEmpty())

            db.games().delete(g1)
            assertTrue(db.categories().allRefs().none { it.gameId == g1 })

            db.categories().reorder(listOf(c2))
            assertNotNull(db.categories().get(c2))
        } finally {
            db.close()
        }
    }

    @Test
    fun sessionsUpdateStatistics() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AceDatabase::class.java).allowMainThreadQueries().build()
        try {
            val id = db.games().insert(sampleGame("s"))
            db.games().recordAttempt(id)
            db.games().recordSession(id, ms = 600_000, reached = 1, startedAt = 1_000, latencyMs = 1_800, error = null)
            db.games().recordAttempt(id)
            db.games().recordSession(id, ms = 0, reached = 0, startedAt = 2_000, latencyMs = null, error = "boom")
            db.games().recordAttempt(id)
            db.games().recordBlocked(id, "no access")
            val g = db.games().get(id)!!
            assertEquals(3, g.launchAttempts)
            assertEquals(1, g.successfulLaunches)
            assertEquals(2, g.failedLaunches)
            assertEquals(1, g.sessionCount)
            assertEquals(600_000L, g.playtimeMs)
            assertEquals(600_000L, g.longestSessionMs)
            assertEquals(1_000L, g.firstPlayedAt)
            assertEquals(1_800L, g.lastLaunchLatencyMs)
            assertEquals(false, g.lastLaunchOk)
            assertEquals("no access", g.lastError)
        } finally {
            db.close()
        }
    }

    private fun sampleGame(key: String) = GameEntity(
        title = key, treeUri = "content://t", rootDocId = key, folderLabel = key, launchFile = null, entry = "Game.ini", contentRoot = "",
        engine = "RPG_MAKER_XP", engineVersion = null, confidence = 90, status = "PLAYABLE_UNTESTED", detectionJson = "{}",
    )
}
