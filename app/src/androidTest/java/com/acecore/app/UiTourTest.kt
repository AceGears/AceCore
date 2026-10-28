package com.acecore.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Environment
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
        ctx.container.settings.updateLibrary { it.copy(selectedCategory = -1L) }
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
        val dir = File(ctx.getExternalFilesDir(null), "testresults").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun click(text: String) = compose.onNode(hasText(text), useUnmergedTree = true).performClick()
    private fun clickDesc(desc: String) = compose.onNode(hasContentDescription(desc), useUnmergedTree = true).performClick()

    @Test
    fun tour() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Pokemon Ancient Platinum")).fetchSemanticsNodes().isNotEmpty() }
            shot("01_home")
            click("Pokémon Fan Games")
            shot("02_category")
            click("All games")
            compose.onNode(hasContentDescription("Pokemon Ancient Platinum", substring = true)).performTouchInput { longClick() }
            shot("03_game_menu")
            click("Game details")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("How it starts", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
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
            clickDesc("Manage categories")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("New category")).fetchSemanticsNodes().isNotEmpty() }
            shot("09_categories")
            Espresso.pressBack()
            clickDesc("Settings")
            click("Game engines")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Visual novel engines", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
            shot("10_engines")
        }
    }
}
