package org.easyrpg.player.player

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.config.PadButton
import com.acecore.app.core.config.WebKey
import com.acecore.app.runtime.LaunchTimeline
import com.acecore.app.runtime.web.overlay.GamepadLayoutStore
import com.acecore.app.runtime.web.overlay.PanelBg
import com.acecore.app.runtime.web.overlay.VirtualGamepad
import org.json.JSONObject
import org.libsdl3.app.SDLActivity
import java.io.File

/**
 * AceArcade's host for EasyRPG Player (RPG Maker 2000/2003, GPL-3.0), running in its own process (":easyrpg").
 *
 * The class name is fixed: EasyRPG's native code (libeasyrpg_android.so) calls back into
 * org.easyrpg.player.player.EasyRpgPlayerActivity (getAssetManager, getHandleForPath, getRtpPath) and registers its
 * native methods on it. Games are read straight from the user's folder: through its real path, or through Android's
 * document access (content:// URI, handled by [SafFile]) when there is no file path. Nothing is copied.
 */
class EasyRpgPlayerActivity : SDLActivity(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var spec: EasyRpgLaunchSpec? = null
    private var buttonMap: Map<PadButton, String> = ButtonMap.EASYRPG_DEFAULT
    private var menuOpen by mutableStateOf(false)
    private var padVisible by mutableStateOf(true)

    override fun getLibraries(): Array<String> = arrayOf("SDL3", "easyrpg_android")

    override fun getArguments(): Array<String> = spec?.arguments?.toTypedArray() ?: emptyArray()

    /** RPG Maker 2000/2003 games are 4:3 landscape; EasyRPG leaves the orientation free, which turned phones to portrait. */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) =
        super.setOrientationBis(w, h, resizable, "LandscapeLeft LandscapeRight")

    override fun onCreate(savedInstanceState: Bundle?) {
        val s = intent?.getStringExtra(EasyRpgLaunchSpec.EXTRA)?.let { runCatching { EasyRpgLaunchSpec.fromJson(it) }.getOrNull() }
        spec = s
        s?.let {
            LaunchTimeline.mark(File(it.helperDir), "Game process started (EasyRPG)")
            buttonMap = ButtonMap.decode(it.buttonMap.ifBlank { null }, ButtonMap.EASYRPG_DEFAULT)
            padVisible = when (it.gamepadMode) {
                "never" -> false
                "always" -> true
                else -> !com.acecore.app.runtime.input.ControllerInput.controllerConnected()
            }
        }
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        if (mBrokenLibraries || s == null) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        attachOverlay(s)
        watchFirstFrame(s)
    }

    override fun onStart() { super.onStart(); lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
    override fun onResume() { super.onResume(); lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME); hideSystemBars() }
    override fun onPause() { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); super.onPause() }
    override fun onStop() { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); savedStateController.performSave(outState) }
    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
        // EasyRPG/SDL can't be restarted inside a process; the next game gets a fresh one.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** EasyRPG logs "Engine configured as: 2k=…" once it has read the game's database (RPG_RT.ldb/.lmt). */
    private fun watchFirstFrame(s: EasyRpgLaunchSpec) {
        Thread {
            val log = File(s.logFile)
            val until = System.currentTimeMillis() + 300_000
            while (System.currentTimeMillis() < until && !isFinishing) {
                val text = runCatching { log.readText() }.getOrNull().orEmpty()
                if (text.contains("Engine configured as")) {
                    runCatching { File(s.helperDir, "ready").writeText(System.currentTimeMillis().toString()) }
                    LaunchTimeline.mark(File(s.helperDir), "EasyRPG game data loaded")
                    return@Thread
                }
                Thread.sleep(300)
            }
        }.start()
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

    private fun attachOverlay(s: EasyRpgLaunchSpec) {
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@EasyRpgPlayerActivity)
            setViewTreeSavedStateRegistryOwner(this@EasyRpgPlayerActivity)
            isFocusable = false
            setContent { Overlay(s) }
        }
        (mLayout as? ViewGroup)?.addView(compose, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** On-screen controller → keyboard key held in SDL until released (EasyRPG: arrows, Z/Enter = OK, X/Esc = cancel). */
    private fun sendPad(button: PadButton, down: Boolean) {
        val code = WebKey.byId(buttonMap[button])?.androidKeyCode ?: return
        android.util.Log.d("AceArcadeInput", "easyrpg ${button.name} ${if (down) "down" else "up"} -> android key $code")
        if (code == 0 || mBrokenLibraries) return
        if (down) onNativeKeyDown(code) else onNativeKeyUp(code)
    }

    private fun reportDpad(r: android.graphics.Rect) {
        val dir = spec?.helperDir ?: return
        Thread { runCatching { File(dir, "dpad").writeText("${r.left} ${r.top} ${r.right} ${r.bottom}") } }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) menuOpen = !menuOpen
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun Overlay(s: EasyRpgLaunchSpec) {
        val accent = Color(s.accentArgb.toInt())
        val store = remember { GamepadLayoutStore(this@EasyRpgPlayerActivity) }
        Box(Modifier.fillMaxSize()) {
            if (padVisible && !menuOpen) {
                VirtualGamepad(
                    store = store, opacity = s.opacity / 100f, scale = s.scale / 100f, haptics = s.haptics, accent = accent,
                    editing = false, onEditDone = {}, onButton = ::sendPad, onMenu = { menuOpen = true },
                    fourWay = true, onDpadBounds = ::reportDpad,
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
                        Text("EasyRPG Player runtime", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        MenuRow("Resume", accent) { menuOpen = false }
                        MenuRow(if (padVisible) "Hide on-screen controls" else "Show on-screen controls", accent) { padVisible = !padVisible; menuOpen = false }
                        MenuRow("EasyRPG settings (F1)", accent) { menuOpen = false; runCatching { openSettings() } }
                        MenuRow("Reset game", accent) { menuOpen = false; runCatching { resetGame() } }
                        MenuRow("Exit to library", accent) { menuOpen = false; runCatching { endGame() }.onFailure { finish() } }
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

    /** Called by EasyRPG's native code (JNI) for the RTP folder. "" = none configured (games that need RTP files show missing graphics). */
    fun getRtpPath(): String = spec?.rtpPath.orEmpty()

    companion object {
        private const val TAG = "AceArcadeEasyRPG"

        @JvmStatic external fun openSettings()
        @JvmStatic external fun endGame()
        @JvmStatic external fun resetGame()
        @JvmStatic external fun toggleFps()

        /** Called by EasyRPG's native code (JNI) for content:// game folders. */
        @JvmStatic fun getHandleForPath(path: String): SafFile? = SafFile.fromPath(SDLActivity.getContext(), path)

        /** Called by EasyRPG's native code (JNI) for its built-in assets. */
        @JvmStatic fun getAssetManager(): AssetManager = SDLActivity.getContext().assets
    }
}

/** What the library passes to [EasyRpgPlayerActivity]. */
data class EasyRpgLaunchSpec(
    val gameId: Long,
    val title: String,
    val helperDir: String,
    val logFile: String,
    val arguments: List<String>,
    val buttonMap: String,
    val accentArgb: Long,
    val rtpPath: String = "",
    val gamepadMode: String = "auto",
    val opacity: Int = 55,
    val scale: Int = 100,
    val haptics: Boolean = true,
) {
    fun toJson(): String = JSONObject().put("gameId", gameId).put("title", title).put("helperDir", helperDir).put("logFile", logFile)
        .put("arguments", org.json.JSONArray(arguments)).put("buttonMap", buttonMap).put("accent", accentArgb).put("rtp", rtpPath)
        .put("gamepadMode", gamepadMode).put("opacity", opacity).put("scale", scale).put("haptics", haptics).toString()

    companion object {
        const val EXTRA = "com.acecore.app.extra.EASYRPG_SPEC"
        fun fromJson(s: String): EasyRpgLaunchSpec = JSONObject(s).let { o ->
            val args = o.getJSONArray("arguments").let { a -> (0 until a.length()).map { a.getString(it) } }
            EasyRpgLaunchSpec(
                o.getLong("gameId"), o.getString("title"), o.getString("helperDir"), o.getString("logFile"), args,
                o.optString("buttonMap"), o.optLong("accent", 0xFFF03AD6L), o.optString("rtp"), o.optString("gamepadMode", "auto"),
                o.optInt("opacity", 55), o.optInt("scale", 100), o.optBoolean("haptics", true),
            )
        }

        /**
         * [project]: the game folder (real path or content:// document URI). Saves stay next to the game (as on PC).
         */
        fun intent(context: Context, spec: EasyRpgLaunchSpec): Intent =
            Intent(context, EasyRpgPlayerActivity::class.java).putExtra(EXTRA, spec.toJson())

        fun arguments(project: String, configDir: File, logFile: File): List<String> = listOf(
            "--project-path", project,
            "--config-path", configDir.absolutePath,
            "--log-file", logFile.absolutePath,
        )
    }
}
