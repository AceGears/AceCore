package com.acecore.app.runtime.rgss

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.PadButton
import com.acecore.app.core.config.WebKey
import com.acecore.app.runtime.input.ControllerInput
import com.acecore.app.runtime.web.GameActivity
import com.acecore.app.runtime.web.overlay.GamepadLayoutStore
import com.acecore.app.runtime.web.overlay.MenuItem
import com.acecore.app.runtime.web.overlay.PanelBg
import com.acecore.app.runtime.web.overlay.VirtualGamepad
import com.acecore.app.runtime.web.overlay.VirtualKeyboard
import org.libsdl.app.SDLActivity
import java.io.File
import java.util.Locale

/**
 * Hosts mkxp-z (RPG Maker XP/VX/VX Ace, Pokémon Essentials) in the isolated ":rgss" process.
 *
 * The native engine (mkxp-z-android-reworked) calls back into its host activity class through JNI:
 *  - static String field `GAME_PATH`               → folder to run
 *  - static `getSystemLanguage()`, `hasVibrator()`, `vibrate(int)`, `vibrateStop()`, `inMultiWindow(Activity)`
 * Those exact names/signatures are provided by the companion object below (@JvmField/@JvmStatic make them real
 * Java statics on this class). Do not rename them.
 *
 * Ruby's VM cannot be re-initialised inside one process, so the process exits when the activity is destroyed.
 */
class RgssActivity : SDLActivity(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "AceCoreRgss"

        /** Read by native main() via JNI. */
        @JvmField var GAME_PATH: String = ""

        private var appContext: Context? = null
        private var processUsed = false

        @JvmStatic fun getSystemLanguage(): String = Locale.getDefault().toString()

        @JvmStatic fun hasVibrator(): Boolean = vibrator()?.hasVibrator() == true

        @JvmStatic fun vibrate(duration: Int) {
            val v = vibrator() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(duration.toLong().coerceAtLeast(1), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(duration.toLong())
            }
        }

        @JvmStatic fun vibrateStop() { vibrator()?.cancel() }

        @JvmStatic fun inMultiWindow(activity: Activity): Boolean = activity.isInMultiWindowMode

        @Suppress("DEPRECATION")
        private fun vibrator(): Vibrator? = appContext?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // ---- Lifecycle plumbing so Compose can render on top of SDL's plain Activity ----
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var spec: RgssLaunchSpec? = null
    private var dataDir: File? = null
    private lateinit var buttonMap: Map<PadButton, String>
    private var gamepadMode = "auto"

    private var menuOpen by mutableStateOf(false)
    private var keyboardOpen by mutableStateOf(false)
    private var editingLayout by mutableStateOf(false)
    private var gamepadVisible by mutableStateOf(true)

    private var resumedAt = 0L
    private var sessionMs = 0L
    private var exiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        appContext = applicationContext
        if (processUsed) {
            // A game already ran in this process; Ruby/SDL cannot be re-initialised. Ask the library to relaunch.
            super.onCreate(savedInstanceState)
            setResult(RESULT_FIRST_USER, Intent().putExtra(com.acecore.app.runtime.web.GameLaunchSpec.RESULT_ERROR,
                "The previous game was still closing. Please tap Play again."))
            finish()
            return
        }
        processUsed = true
        val s = intent?.getStringExtra(RgssLaunchSpec.EXTRA)?.let { runCatching { RgssLaunchSpec.fromJson(it) }.getOrNull() }
        spec = s
        if (s != null) {
            GAME_PATH = s.gamePath
            dataDir = File(s.dataPath).apply { mkdirs() }
            buttonMap = ButtonMap.decode(s.config[ConfigKey.BUTTON_MAP.id])
            gamepadMode = s.config[ConfigKey.GAMEPAD_MODE.id] ?: "auto"
        } else {
            buttonMap = ButtonMap.DEFAULT
        }
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        if (s == null) { failAndFinish("Missing launch information."); return }
        if (SDLActivity.mBrokenLibraries) {
            // SDLActivity has already shown its own error dialog; report back to the library as well.
            setResult(RESULT_FIRST_USER, Intent().putExtra(com.acecore.app.runtime.web.GameLaunchSpec.RESULT_ERROR,
                "The RGSS runtime's native libraries could not be loaded on this device."))
            return
        }
        if (ConfigKey.KEEP_SCREEN_ON.bool(s.config[ConfigKey.KEEP_SCREEN_ON.id] ?: "true")) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        refreshGamepadVisibility()
        attachOverlay(s)
    }

    /**
     * SDL derives its "pref path" (where mkxp-z reads the user mkxp.json and Essentials-style games save) from
     * Context.getFilesDir(), ignoring the game name. Returning a per-game folder keeps saves and settings separate.
     */
    override fun getFilesDir(): File = dataDir ?: super.getFilesDir()

    override fun getLibraries(): Array<String> = RgssRuntime.LIBRARIES.copyOf()

    override fun getArguments(): Array<String> = arrayOf()

    private fun attachOverlay(s: RgssLaunchSpec) {
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@RgssActivity)
            setViewTreeSavedStateRegistryOwner(this@RgssActivity)
            setContent { Overlay(s) }
        }
        val layout: ViewGroup? = SDLActivity.mLayout
        layout?.addView(compose, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ input from AceCore overlays

    private fun sendPad(button: PadButton, down: Boolean) {
        val key = WebKey.byId(buttonMap[button]) ?: return
        sendKey(key, down)
    }

    private fun sendKey(key: WebKey, down: Boolean) {
        val code = key.androidKeyCode
        if (code == 0 || SDLActivity.mBrokenLibraries) return
        if (down) SDLActivity.onNativeKeyDown(code) else SDLActivity.onNativeKeyUp(code)
    }

    private fun tapKey(code: Int) {
        if (SDLActivity.mBrokenLibraries) return
        SDLActivity.onNativeKeyDown(code); SDLActivity.onNativeKeyUp(code)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromMouse = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !fromMouse) {
            if (event.action == KeyEvent.ACTION_UP) onBackRequested()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && ControllerInput.isController(event)) {
            if (event.action == KeyEvent.ACTION_UP) menuOpen = !menuOpen
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onBackRequested() {
        when {
            editingLayout -> editingLayout = false
            keyboardOpen -> keyboardOpen = false
            else -> menuOpen = !menuOpen
        }
    }

    private fun refreshGamepadVisibility() {
        gamepadVisible = when (gamepadMode) {
            "always" -> true
            "never" -> false
            else -> !ControllerInput.controllerConnected()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        resumedAt = SystemClock.elapsedRealtime()
        refreshGamepadVisibility()
        applyImmersive()
    }

    override fun onPause() {
        accumulatePlaytime()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedStateController.performSave(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
        // Ruby cannot be re-initialised in the same process (see mkxp-z-android). The launcher lives in another
        // process, so ending this one is safe and frees all game memory.
        if (isFinishing) {
            Log.i(TAG, "Exiting RGSS process")
            System.exit(0)
        }
    }

    private fun applyImmersive() {
        val s = spec ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (ConfigKey.IMMERSIVE.bool(s.config[ConfigKey.IMMERSIVE.id] ?: "true")) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun accumulatePlaytime() {
        val s = spec ?: return
        if (resumedAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        sessionMs += now - resumedAt
        resumedAt = now
        runCatching {
            // Use the application context: this activity's getFilesDir() is redirected to the game's data folder.
            val f = GameActivity.sessionFile(applicationContext, s.gameId)
            f.parentFile?.mkdirs()
            f.writeText(sessionMs.toString())
        }
    }

    private fun exitGame() {
        if (exiting) return
        exiting = true
        accumulatePlaytime()
        setResult(RESULT_OK, Intent().putExtra(GameActivity.EXTRA_SESSION_MS, sessionMs))
        finish()
    }

    private fun failAndFinish(message: String) {
        setResult(RESULT_FIRST_USER, Intent().putExtra(com.acecore.app.runtime.web.GameLaunchSpec.RESULT_ERROR, message))
        finish()
    }

    // ------------------------------------------------------------------ overlay UI

    @Composable
    private fun Overlay(s: RgssLaunchSpec) {
        val accent = Color(s.accentArgb.toInt())
        val cfg = s.config
        val haptics = ConfigKey.HAPTICS.bool(cfg[ConfigKey.HAPTICS.id] ?: "true")
        val store = remember { GamepadLayoutStore(this@RgssActivity) }
        Box(Modifier.fillMaxSize()) {
            if ((gamepadVisible || editingLayout) && !menuOpen) {
                VirtualGamepad(
                    store = store,
                    opacity = ConfigKey.GAMEPAD_OPACITY.int(cfg[ConfigKey.GAMEPAD_OPACITY.id] ?: "55") / 100f,
                    scale = ConfigKey.GAMEPAD_SCALE.int(cfg[ConfigKey.GAMEPAD_SCALE.id] ?: "100") / 100f,
                    haptics = haptics,
                    accent = accent,
                    editing = editingLayout,
                    onEditDone = { editingLayout = false },
                    onButton = ::sendPad,
                    onMenu = { menuOpen = true },
                )
            }
            if (keyboardOpen) VirtualKeyboard(accent, haptics, ::sendKey) { keyboardOpen = false }
            if (menuOpen) RgssMenu(s, accent)
        }
    }

    @Composable
    private fun RgssMenu(s: RgssLaunchSpec, accent: Color) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(20.dp)
                    .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(s.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("The game keeps running while this menu is open.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                MenuItem(Icons.Rounded.PlayArrow, "Back to game", accent, highlight = true) { menuOpen = false }
                MenuItem(Icons.Rounded.Gamepad, if (gamepadVisible) "Hide on-screen controls" else "Show on-screen controls", accent) {
                    gamepadMode = if (gamepadVisible) "never" else "always"; refreshGamepadVisibility(); menuOpen = false
                }
                MenuItem(Icons.Rounded.Edit, "Edit controls layout", accent) { editingLayout = true; menuOpen = false }
                MenuItem(Icons.Rounded.Keyboard, "Virtual keyboard", accent) { keyboardOpen = true; menuOpen = false }
                MenuItem(Icons.Rounded.RestartAlt, "Restart game (F12)", accent, subtitle = "Returns to the title screen. Unsaved progress is lost.") {
                    menuOpen = false; tapKey(KeyEvent.KEYCODE_F12)
                }
                MenuItem(Icons.AutoMirrored.Rounded.ExitToApp, "Exit to library", accent, subtitle = "Unsaved progress is lost.") { exitGame() }
            }
        }
    }
}
