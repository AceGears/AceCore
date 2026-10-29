package com.acecore.app

import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.storage.DirectStorage
import com.acecore.app.runtime.rgss.RgssRuntime
import org.easyrpg.player.player.EasyRpgLaunchSpec
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs EasyRPG's own RPG Maker 2000 test suite (EasyRPG TestGame, GPL-3.0; added to the test APK by CI) straight
 * from shared storage with AceArcade's EasyRPG runtime: waits until the game's database is loaded, starts a new game
 * from the title screen, then holds the on-screen D-pad (real touch events) and checks that the picture changes
 * (the player walks / the map scrolls).
 */
@RunWith(AndroidJUnit4::class)
class EasyRpgLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext

    @Test
    fun runsRm2000GameFromSharedStorage_titleToMap_dpadWalks() {
        val loader = ctx.classLoader as dalvik.system.BaseDexClassLoader
        assumeTrue("This build has no EasyRPG natives", loader.findLibrary("easyrpg_android") != null)
        val assets = instrumentation.context.assets
        assumeTrue("No EasyRPG test game in this test APK", assets.list("easyrpg").orEmpty().contains("TestGame-2000"))
        assertTrue("All files access must be granted for this test", DirectStorage.hasAccess(ctx))

        val gameDir = File(Environment.getExternalStorageDirectory(), "AceCoreTest/EasyRPG/TestGame-2000")
        gameDir.parentFile!!.deleteRecursively()
        copyAsset("easyrpg/TestGame-2000", gameDir)
        assertTrue(File(gameDir, "RPG_RT.ldb").isFile)

        val dataDir = GameStorage.dataDir(ctx, 990_003L).apply { deleteRecursively() }
        val helper = RgssRuntime.helperDir(dataDir).apply { mkdirs() }
        val log = File(helper, "easyrpg.log")
        val spec = EasyRpgLaunchSpec(
            990_003L, "EasyRPG TestGame 2000", helper.absolutePath, log.absolutePath,
            EasyRpgLaunchSpec.arguments(gameDir.absolutePath, File(dataDir, "easyrpg").apply { mkdirs() }, log),
            ButtonMap.EASYRPG_DEFAULT_ENCODED, 0xFFF03AD6L, gamepadMode = "always",
        )
        val started = System.currentTimeMillis()
        ctx.startActivity(EasyRpgLaunchSpec.intent(ctx, spec).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))

        if (!waitFor(180_000) { File(helper, "ready").exists() }) fail("EasyRPG didn't load the game within 180 s. " + diagnostics(helper))
        report("easyrpg_game_loaded_ms", System.currentTimeMillis() - started)
        Thread.sleep(4000)
        screenshot("easyrpg_title.png")

        // Title screen: "New Game" is selected → confirm.
        key(KeyEvent.KEYCODE_ENTER)
        Thread.sleep(5000)
        if (!waitFor(30_000) { File(helper, "dpad").exists() }) fail("No on-screen controls. " + diagnostics(helper))
        val pad = File(helper, "dpad").readText().trim().split(' ').map { it.toInt() }
        val before = instrumentation.uiAutomation.takeScreenshot()
        save(before, "easyrpg_map_before.png")
        val x = (pad[0] + pad[2]) / 2f
        val y = pad[1] + (pad[3] - pad[1]) * 0.83f
        val downTime = SystemClock.uptimeMillis()
        touch(downTime, MotionEvent.ACTION_DOWN, x, y)
        try { Thread.sleep(1200) } finally { touch(downTime, MotionEvent.ACTION_UP, x, y) }
        Thread.sleep(800)
        val after = instrumentation.uiAutomation.takeScreenshot()
        save(after, "easyrpg_map_after.png")
        val changed = changedShare(before, after, pad)
        report("easyrpg_walk_changed_pixels", "%.4f".format(changed))
        keepLog(helper)
        val text = log.takeIf { it.exists() }?.readText().orEmpty()
        assertTrue("EasyRPG reported an error: ${text.lines().filter { it.contains("Error") }.take(5)}", !text.contains("Error:"))
        assertTrue("Holding Down on the D-pad should change the picture (walk/scroll); changed share = $changed", changed > 0.002)
    }

    /** Share of pixels (outside the D-pad) that differ between two screenshots. */
    private fun changedShare(a: Bitmap?, b: Bitmap?, pad: List<Int>): Double {
        if (a == null || b == null || a.width != b.width || a.height != b.height) return 0.0
        var diff = 0; var total = 0
        for (yy in 0 until a.height step 4) for (xx in 0 until a.width step 4) {
            if (xx in pad[0]..pad[2] && yy in pad[1]..pad[3]) continue
            total++
            if (a.getPixel(xx, yy) != b.getPixel(xx, yy)) diff++
        }
        return if (total == 0) 0.0 else diff.toDouble() / total
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val ev = KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            instrumentation.uiAutomation.injectInputEvent(ev, true)
            Thread.sleep(80)
        }
    }

    private fun touch(downTime: Long, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue("Touch injection failed", instrumentation.uiAutomation.injectInputEvent(ev, true))
        ev.recycle()
    }

    private fun copyAsset(path: String, target: File) {
        val am = instrumentation.context.assets
        val children = am.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            am.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAsset("$path/$it", File(target, it)) }
        }
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { if (cond()) return true; Thread.sleep(250) }
        return cond()
    }

    private fun keepLog(helper: File) {
        val dir = File(ctx.getExternalFilesDir(null), "testresults").apply { mkdirs() }
        listOf("easyrpg.log", "timeline").forEach { n -> File(helper, n).takeIf { it.exists() }?.copyTo(File(dir, "easyrpg-$n.txt"), overwrite = true) }
    }

    private fun diagnostics(helper: File): String {
        keepLog(helper)
        return listOf("timeline", "easyrpg.log").joinToString(" | ") { n -> "$n=" + (File(helper, n).takeIf { it.exists() }?.readText()?.takeLast(1500) ?: "-") }
    }

    private fun report(key: String, value: Any) {
        val dir = File(ctx.getExternalFilesDir(null), "testresults").apply { mkdirs() }
        File(dir, "metrics.txt").appendText("$key=$value\n")
        android.util.Log.i("AceCoreTest", "$key=$value")
    }

    private fun save(bmp: Bitmap?, name: String) {
        bmp ?: return
        val dir = File(ctx.getExternalFilesDir(null), "testresults").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screenshot(name: String) = save(instrumentation.uiAutomation.takeScreenshot(), name)
}
