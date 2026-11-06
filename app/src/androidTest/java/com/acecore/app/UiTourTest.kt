package com.acecore.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acecore.app.core.database.CategoryEntity
import com.acecore.app.core.database.GameCategoryRef
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.database.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks through the redesigned screens with a seeded library and saves real screenshots (pulled by CI). Also checks
 * that the main navigation works: category tabs, long-press menu, details, layout panel, categories, engines, cheats.
 */
@RunWith(AndroidJUnit4::class)
class UiTourTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext

    private fun seed(): Long = runBlocking {
        val db = ctx.container.database
        db.clearAllTables()
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AGames"
        fun folder(name: String) = File(Environment.getExternalStorageDirectory(), "Games/$name").apply {
            mkdirs(); File(this, "Data").mkdirs(); File(this, "Game.ini").writeText("[Game]\r\nLibrary=RGSS104E.dll\r\nTitle=$name\r\n")
        }
        val games = listOf(
            Triple("Pokemon Ancient Platinum", "POKEMON_ESSENTIALS", 0xFFE3132B.toInt()),
            Triple("Crimson Tower", "RPG_MAKER_VX_ACE", 0xFF9B5CFF.toInt()),
            Triple("Skyward Saga", "RPG_MAKER_XP", 0xFF4C8DFF.toInt()),
            Triple("Neon Runner", "HTML5", 0xFF1ED69B.toInt()),
            Triple("Moonlit Letters", "RENPY", 0xFFE8B931.toInt()),
            Triple("Retro Blaster", "FLASH", 0xFFFF7A1A.toInt()),
        )
        val ids = games.mapIndexed { i, (title, engine, color) ->
            val dir = title.replace(' ', '_')
            if (engine.startsWith("RPG") || engine.startsWith("POKEMON")) folder(dir)
            val status = if (engine == "RENPY") "RUNTIME_NOT_BUILT" else "PLAYABLE_UNTESTED"
            val launch = when (engine) { "HTML5" -> "index.html"; "FLASH" -> "game.swf"; "RENPY" -> "Moonlit.exe"; else -> "Game.exe" }
            val entry = when (engine) { "HTML5" -> "index.html"; "FLASH" -> "game.swf"; "RENPY" -> null; else -> "Game.ini" }
            db.games().insert(
                GameEntity(
                    title = title, treeUri = tree, rootDocId = "primary:Games/$dir", folderLabel = dir, launchFile = launch, entry = entry,
                    contentRoot = "", engine = engine, engineVersion = if (engine == "POKEMON_ESSENTIALS") "v20+ (estimated)" else null,
                    confidence = 95, status = status, detectionJson = "{}", customCoverPath = cover(title, color).absolutePath,
                    favorite = i == 0, addedAt = System.currentTimeMillis() - i * 86_400_000L,
                    lastPlayedAt = if (i < 3) System.currentTimeMillis() - i * 3_600_000L else null,
                    playtimeMs = if (i == 0) 5_400_000L else 0L, launchCount = if (i == 0) 4 else 0,
                    sessionCount = if (i == 0) 3 else 0, launchAttempts = if (i == 0) 4 else 0, successfulLaunches = if (i == 0) 3 else 0,
                    failedLaunches = if (i == 0) 1 else 0, longestSessionMs = if (i == 0) 2_700_000L else 0,
                    lastLaunchLatencyMs = if (i == 0) 1_900L else null, lastLaunchOk = if (i == 0) true else null,
                    firstPlayedAt = if (i == 0) System.currentTimeMillis() - 5 * 86_400_000L else null,
                ),
            )
        }
        repeat(3) { n -> db.sessions().insert(SessionEntity(gameId = ids[0], startedAt = System.currentTimeMillis() - n * 86_400_000L, durationMs = 1_800_000L, reachedGame = true, latencyMs = 1_900L, error = null)) }
        val pokemon = db.categories().insert(CategoryEntity(name = "Pokémon Fan Games", icon = "pokemon", color = 0xFFE3132B, sortOrder = 0))
        val rpg = db.categories().insert(CategoryEntity(name = "RPG Maker", icon = "rpg", color = 0xFF9B5CFF, sortOrder = 1))
        db.categories().insert(CategoryEntity(name = "Visual Novels", icon = "novel", color = 0xFFE8B931, sortOrder = 2))
        db.categories().addRefs(listOf(GameCategoryRef(ids[0], pokemon), GameCategoryRef(ids[0], rpg), GameCategoryRef(ids[1], rpg), GameCategoryRef(ids[2], rpg)))
        ctx.container.settings.updateLibrary { it.copy(selectedCategory = -1L, showAllGames = true, layout = com.acecore.app.core.datastore.LibraryLayout.GRID) }
        ids[0]
    }

    private fun cover(title: String, color: Int): File {
        val bmp = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 300f, 400f, color, 0xFF0B0B0E.toInt(), Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt(); textSize = 34f; isFakeBoldText = true }
        title.split(' ').forEachIndexed { i, w -> c.drawText(w, 22f, 300f + i * 38f - (title.split(' ').size - 1) * 38f, p) }
        val f = File(ctx.filesDir, "covers/test_${title.hashCode()}.png").apply { parentFile?.mkdirs() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        return f
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(900)
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        // Also in app-private storage: on Android 11+ CI fetches it with "run-as" (adb can't read the external folder).
        for (base in listOf(ctx.filesDir)) {
            val dir = File(base, "testresults").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // The same text can appear twice (a category tab and its banner): the first match is the one on top of the screen.
    private fun click(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).onFirst().performClick()
    /** The first node matching [m] that is actually on screen (pager pages keep off-screen copies of headings and covers). */
    private fun visible(m: androidx.compose.ui.test.SemanticsMatcher): androidx.compose.ui.test.SemanticsNodeInteraction {
        val all = compose.onAllNodes(m, useUnmergedTree = true)
        for (i in 0 until all.fetchSemanticsNodes().size) {
            val n = all[i]
            if (runCatching { n.assertIsDisplayed() }.isSuccess) return n
        }
        throw AssertionError("nothing on screen matches $m")
    }
    private fun clickDesc(desc: String) = compose.onAllNodes(hasContentDescription(desc), useUnmergedTree = true).onFirst().performClick()
    private fun waitFor(text: String, ms: Long = 10_000) =
        compose.waitUntil(ms) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun tour() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Pokemon Ancient Platinum")).fetchSemanticsNodes().isNotEmpty() }
            shot("01_home")
            click("Pokémon Fan Games")
            shot("02_category")
            // Long-press a category tab: its options sheet.
            compose.onAllNodes(hasText("RPG Maker"), useUnmergedTree = true).onFirst().performTouchInput { longClick() }
            waitFor("Move left")
            shot("02b_category_sheet")
            Espresso.pressBack()
            click("All games")
            compose.onNode(hasContentDescription("Pokemon Ancient Platinum", substring = true)).performTouchInput { longClick() }
            shot("03_game_menu")
            click("Game details")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Launch configuration", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
            shot("04_details")
            compose.onRoot().performTouchInput { swipeUp() }
            shot("05_details_stats")
            compose.onRoot().performTouchInput { swipeUp() }
            shot("06_details_more")
            click("Open")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Enable cheats")).fetchSemanticsNodes().isNotEmpty() }
            shot("07_cheats")
            Espresso.pressBack()
            Espresso.pressBack()
            clickDesc("Library layout")
            shot("08_layout")
            Espresso.pressBack()
            // The manage button sits at the end of the scrolling tab row; the tab's long-press sheet always has it.
            compose.onAllNodes(hasText("RPG Maker"), useUnmergedTree = true).onFirst().performTouchInput { longClick() }
            waitFor("All categories, folders and order")
            click("All categories, folders and order")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("New category")).fetchSemanticsNodes().isNotEmpty() }
            shot("09_categories")
            Espresso.pressBack()
            clickDesc("Settings")
            click("Game engines")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Visual novel engines", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
            shot("10_engines")
            Espresso.pressBack()
            click("Gestures")
            waitFor("Swipe to change category")
            shot("11_gestures")
            // The test area recognises a double tap with the same code the games use.
            compose.onNode(hasContentDescription("Gesture test area")).performScrollTo().performTouchInput { doubleClick() }
            waitFor("Double tap →")
            shot("12_gesture_test")
            Espresso.pressBack()
            click("Appearance")
            waitFor("Preview", 10_000)
            shot("13_appearance")
            // The premium themes, each in dark and light, on the library (Sandstone and Jade with the floating dock).
            Espresso.pressBack(); Espresso.pressBack()
            waitFor("Pokemon Ancient Platinum")
            val settings = ctx.container.settings
            fun theme(p: com.acecore.app.core.datastore.ThemePreset, mode: com.acecore.app.core.datastore.ThemeMode, dock: Boolean, name: String) {
                runBlocking {
                    settings.updateTheme { it.copy(preset = p, mode = mode) }
                    settings.updateLibrary { it.copy(categoryBar = if (dock) com.acecore.app.core.datastore.CategoryBarStyle.DOCK else com.acecore.app.core.datastore.CategoryBarStyle.TABS) }
                }
                shot(name)
            }
            val D = com.acecore.app.core.datastore.ThemeMode.DARK
            val L = com.acecore.app.core.datastore.ThemeMode.LIGHT
            theme(com.acecore.app.core.datastore.ThemePreset.AQUA_GLASS, D, false, "14_aqua_dark")
            theme(com.acecore.app.core.datastore.ThemePreset.AQUA_GLASS, L, false, "15_aqua_light")
            theme(com.acecore.app.core.datastore.ThemePreset.VIOLET_PULSE, D, false, "16_violet_dark")
            theme(com.acecore.app.core.datastore.ThemePreset.VIOLET_PULSE, L, false, "17_violet_light")
            theme(com.acecore.app.core.datastore.ThemePreset.JADE_GLASS, D, true, "18_jade_dark_dock")
            theme(com.acecore.app.core.datastore.ThemePreset.JADE_GLASS, L, true, "19_jade_light_dock")
            click("RPG Maker")
            shot("20_jade_dock_moved")
            click("All games")
            shot("20b_jade_dock_first")
            click("Visual Novels")
            shot("20c_jade_dock_last")
            // The other dock styles, with the last and first tabs selected (where shapes meet the bar's rounded ends).
            for (st in listOf(com.acecore.app.core.datastore.CategoryBarStyle.WAVE, com.acecore.app.core.datastore.CategoryBarStyle.SPOTLIGHT,
                com.acecore.app.core.datastore.CategoryBarStyle.DROP)) {
                runBlocking { settings.updateLibrary { it.copy(categoryBar = st) } }
                shot("20d_${st.name.lowercase()}")
                click("Visual Novels")
                Thread.sleep(400)
                shot("20e_${st.name.lowercase()}_last")
            }
            theme(com.acecore.app.core.datastore.ThemePreset.SANDSTONE, D, true, "21_sandstone_dark_dock")
            theme(com.acecore.app.core.datastore.ThemePreset.SANDSTONE, L, false, "22_sandstone_light")
            theme(com.acecore.app.core.datastore.ThemePreset.PLASMA, D, false, "23_plasma_dark")
            theme(com.acecore.app.core.datastore.ThemePreset.PLASMA, L, false, "24_plasma_light")
            theme(com.acecore.app.core.datastore.ThemePreset.MIDNIGHT_BLUE, L, false, "25_midnight_light")
            // Game details on a glass theme (back on "All games": the dock tests left the last category selected).
            visible(hasText("All games")).performClick()
            Thread.sleep(500)
            visible(hasContentDescription("Pokemon Ancient Platinum", substring = true)).performTouchInput { longClick() }
            click("Game details")
            waitFor("Launch configuration")
            shot("26_midnight_light_details")
        }
    }
}
