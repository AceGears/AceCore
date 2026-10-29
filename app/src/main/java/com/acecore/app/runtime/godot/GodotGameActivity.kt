package com.acecore.app.runtime.godot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.config.PadButton
import com.acecore.app.core.config.WebKey
import com.acecore.app.runtime.LaunchTimeline
import com.acecore.app.runtime.web.overlay.GamepadLayoutStore
import com.acecore.app.runtime.web.overlay.PanelBg
import com.acecore.app.runtime.web.overlay.VirtualGamepad
import org.godotengine.godot.GodotActivity
import org.json.JSONObject
import java.io.File

/** What the library passes to [GodotGameActivity] (besides Godot's own command line). */
data class GodotLaunchSpec(
    val gameId: Long,
    val title: String,
    val helperDir: String,
    val buttonMap: String,
    val accentArgb: Long,
    val gamepadMode: String = "auto",
    val opacity: Int = 55,
    val scale: Int = 100,
    val haptics: Boolean = true,
) {
    fun toJson(): String = JSONObject().put("gameId", gameId).put("title", title).put("helperDir", helperDir)
        .put("buttonMap", buttonMap).put("accent", accentArgb).put("gamepadMode", gamepadMode).put("opacity", opacity)
        .put("scale", scale).put("haptics", haptics).toString()

    companion object {
        const val EXTRA = "com.acecore.app.extra.GODOT_SPEC"
        fun fromJson(s: String): GodotLaunchSpec = JSONObject(s).let {
            GodotLaunchSpec(
                it.getLong("gameId"), it.getString("title"), it.getString("helperDir"), it.optString("buttonMap"),
                it.optLong("accent", 0xFFF03AD6L), it.optString("gamepadMode", "auto"), it.optInt("opacity", 55),
                it.optInt("scale", 100), it.optBoolean("haptics", true),
            )
        }

        /** Godot's command line for running [pack] (non-exported activity only, see GodotActivity). */
        fun intent(context: Context, spec: GodotLaunchSpec, pack: File, logFile: File): Intent =
            Intent(context, GodotGameActivity::class.java)
                .putExtra(GodotLaunchSpec.EXTRA, spec.toJson())
                .putExtra(GodotActivity.EXTRA_COMMAND_LINE_PARAMS, arrayOf("--main-pack", pack.absolutePath, "--log-file", logFile.absolutePath))
    }
}

/**
 * Runs a Godot 4 game with the Godot Android engine (org.godotengine:godot, MIT) in its own process (":godot"),
 * straight from the game's folder (`--main-pack`). Godot handles touch and real controllers itself; AceArcade adds
 * its on-screen controller on top, which sends keyboard keys (arrows, Enter = ui_accept, Esc = ui_cancel…) because
 * games exported for PC are usually played with a keyboard.
 */
class GodotGameActivity : GodotActivity() {
    private var spec: GodotLaunchSpec? = null
    private var buttonMap: Map<PadButton, String> = ButtonMap.GODOT_DEFAULT
    private var menuOpen by mutableStateOf(false)
    private var padVisible by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        val s = intent?.getStringExtra(GodotLaunchSpec.EXTRA)?.let { runCatching { GodotLaunchSpec.fromJson(it) }.getOrNull() }
        spec = s
        s?.let {
            LaunchTimeline.mark(File(it.helperDir), "Game process started (Godot)")
            buttonMap = ButtonMap.decode(it.buttonMap.ifBlank { null }, ButtonMap.GODOT_DEFAULT)
            padVisible = when (it.gamepadMode) {
                "never" -> false
                "always" -> true
                else -> !com.acecore.app.runtime.input.ControllerInput.controllerConnected()
            }
        }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        if (s != null) attachOverlay(s)
    }

    override fun onGodotMainLoopStarted() {
        spec?.let { s ->
            LaunchTimeline.mark(File(s.helperDir), "Godot main loop started")
            runCatching { File(s.helperDir, "ready").writeText(System.currentTimeMillis().toString()) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun attachOverlay(s: GodotLaunchSpec) {
        val compose = ComposeView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            setContent { Overlay(s) }
        }
        addContentView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Sends a key to Godot as a keyboard key press/release (held until released). */
    private fun sendPad(button: PadButton, down: Boolean) {
        val code = WebKey.byId(buttonMap[button])?.androidKeyCode ?: return
        if (code == 0) return
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD)
        super.dispatchKeyEvent(ev)
    }

    private fun reportDpad(r: android.graphics.Rect) {
        val dir = spec?.helperDir ?: return
        Thread { runCatching { File(dir, "dpad").writeText("${r.left} ${r.top} ${r.right} ${r.bottom}") } }.start()
    }

    private fun exitGame() {
        Log.i(TAG, "Exit requested from the AceArcade menu")
        finish()
        // Godot cannot be restarted inside a process; end it like Godot's own quit does.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    @Composable
    private fun Overlay(s: GodotLaunchSpec) {
        val accent = Color(s.accentArgb.toInt())
        val store = remember { GamepadLayoutStore(this@GodotGameActivity) }
        Box(Modifier.fillMaxSize()) {
            if (padVisible && !menuOpen) {
                VirtualGamepad(
                    store = store, opacity = s.opacity / 100f, scale = s.scale / 100f, haptics = s.haptics, accent = accent,
                    editing = false, onEditDone = {}, onButton = ::sendPad, onMenu = { menuOpen = true },
                    onDpadBounds = ::reportDpad,
                )
            } else if (!menuOpen) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).background(PanelBg, RoundedCornerShape(50))
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50)).clickable { menuOpen = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp)) { Text("☰", color = Color.White, fontSize = 16.sp) }
            }
            if (menuOpen) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { menuOpen = false }) {
                    Column(
                        Modifier.align(Alignment.Center).widthIn(max = 420.dp).fillMaxWidth().padding(24.dp)
                            .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(s.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${GodotPack.ENGINE_LABEL} runtime", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        MenuRow("Resume", accent) { menuOpen = false }
                        MenuRow(if (padVisible) "Hide on-screen controls" else "Show on-screen controls", accent) { padVisible = !padVisible; menuOpen = false }
                        MenuRow("Exit to library", accent) { exitGame() }
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuRow(label: String, accent: Color, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)) { Text(label, color = Color.White, fontSize = 15.sp) }
    }

    companion object { private const val TAG = "AceArcadeGodot" }
}
