package com.acecore.app

import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.storage.DirectStorage
import com.acecore.app.runtime.godot.GodotLaunchSpec
import com.acecore.app.runtime.godot.GodotPack
import com.acecore.app.runtime.rgss.RgssRuntime
import org.godotengine.godot.GodotActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs a real Godot 4 game pack (ci/godot-test, exported by CI with the official Godot 4.7 editor) from shared
 * storage with AceArcade's Godot runtime, then holds the on-screen D-pad (real touch events) and checks that the
 * game's own `Input.is_action_pressed("ui_down")` moves its character, and that it stops on release.
 */
@RunWith(AndroidJUnit4::class)
class GodotLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext

    @Test
    fun runsGodotPackFromSharedStorage_andDpadMovesCharacter() {
        val assets = instrumentation.context.assets
        assumeTrue("No Godot test pack in this test APK", assets.list("godot").orEmpty().contains("TestGame.pck"))
        assertTrue("All files access must be granted for this test", DirectStorage.hasAccess(ctx))

        val gameDir = File(Environment.getExternalStorageDirectory(), "AceCoreTest/GodotGame").apply { deleteRecursively(); mkdirs() }
        val pckFile = File(gameDir, "TestGame.pck")
        assets.open("godot/TestGame.pck").use { i -> pckFile.outputStream().use { i.copyTo(it) } }

        val check = GodotPack.check(gameDir, "TestGame.pck")
        assertTrue("Pack check: $check", check is GodotPack.Check.Ok)
        val pack = (check as GodotPack.Check.Ok).pack
        assertEquals(4, pack.major)

        val helper = RgssRuntime.helperDir(GameStorage.dataDir(ctx, 990_002L)).apply { deleteRecursively(); mkdirs() }
        val spec = GodotLaunchSpec(990_002L, "Godot Test", helper.absolutePath, ButtonMap.GODOT_DEFAULT_ENCODED, 0xFFF03AD6L, gamepadMode = "always")
        val intent = GodotLaunchSpec.intent(ctx, spec, pack.file, File(helper, "godot.log"))
        val args = intent.getStringArrayExtra(GodotActivity.EXTRA_COMMAND_LINE_PARAMS)!! + arrayOf("--", "--out=${helper.absolutePath}")
        intent.putExtra(GodotActivity.EXTRA_COMMAND_LINE_PARAMS, args).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = System.currentTimeMillis()
        ctx.startActivity(intent)

        if (!waitFor(420_000) { File(helper, "godot_started").exists() }) fail("Godot game script didn't start within 420 s. " + diagnostics(helper))
        report("godot_first_script_ms", System.currentTimeMillis() - started)
        report("godot_engine", File(helper, "godot_started").readText())
        if (!waitFor(30_000) { File(helper, "dpad").exists() && File(helper, "godot_walk").exists() }) fail("No D-pad / walk report. " + diagnostics(helper))
        Thread.sleep(1000)
        screenshot("godot_game.png")

        val pad = File(helper, "dpad").readText().trim().split(' ').map { it.toInt() }
        val x = (pad[0] + pad[2]) / 2f
        val y = pad[1] + (pad[3] - pad[1]) * 0.83f
        val before = walk(helper)
        val downTime = SystemClock.uptimeMillis()
        touch(downTime, MotionEvent.ACTION_DOWN, x, y)
        try {
            Thread.sleep(1500)
            touch(downTime, MotionEvent.ACTION_MOVE, x, y + 1)
            screenshot("godot_walk.png")
            Thread.sleep(1500)
        } finally {
            touch(downTime, MotionEvent.ACTION_UP, x, y + 1)
        }
        val held = walk(helper)
        Thread.sleep(1500)
        val released = walk(helper)
        Thread.sleep(1000)
        val later = walk(helper)
        report("godot_walk", "before=${before.joinToString(" ")} held=${held.joinToString(" ")} later=${later.joinToString(" ")}")
        keepLog(helper)
        assertTrue("Character should walk at least 4 tiles in 3 s: before=$before held=$held", held[0] - before[0] >= 4)
        assertEquals("ui_down must be released: $released", 0, released[1])
        assertEquals("Character must stop after release", released[0], later[0])
    }

    private fun walk(helper: File): List<Int> =
        File(helper, "godot_walk").takeIf { it.exists() }?.readText()?.trim()?.split(' ')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 } ?: listOf(0, 0, 0)

    private fun touch(downTime: Long, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue("Touch injection failed", instrumentation.uiAutomation.injectInputEvent(ev, true))
        ev.recycle()
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { if (cond()) return true; Thread.sleep(250) }
        return cond()
    }

    private fun diagnostics(helper: File): String = keepLog(helper) + listOf("timeline", "godot.log", "ready").joinToString(" | ") { n ->
        "$n=" + (File(helper, n).takeIf { it.exists() }?.readText()?.takeLast(1200) ?: "-")
    }

    private fun keepLog(helper: File): String {
        val dir = File(ctx.getExternalFilesDir(null), "testresults").apply { mkdirs() }
        listOf("godot.log", "timeline").forEach { n -> File(helper, n).takeIf { it.exists() }?.copyTo(File(dir, "godot-$n.txt"), overwrite = true) }
        return ""
    }

    private fun report(key: String, value: Any) {
        val dir = File(ctx.getExternalFilesDir(null), "testresults").apply { mkdirs() }
        File(dir, "metrics.txt").appendText("$key=$value\n")
        android.util.Log.i("AceCoreTest", "$key=$value")
    }

    private fun screenshot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "testresults").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
