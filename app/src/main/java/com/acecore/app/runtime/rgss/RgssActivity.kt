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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.AutoFixHigh
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
        const val EXTRA_RETRY = "com.acecore.app.extra.RETRY"
        /** No visible progress for this long during start-up → failure screen (with "Keep waiting"). */
        private const val STALL_MS = 60_000L
        private const val ENGINE_START_MS = 20_000L

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
    private var cheatsOpen by mutableStateOf(false)
    /** True until the game draws its first frame (reported by AceCore's runtime helper). */
    private var starting by mutableStateOf(true)
    /** Current launch stage shown in the start-up indicator. */
    private var stageText by mutableStateOf("Loading the engine…")
    /** Set when start-up failed or stalled: shown as a full failure screen instead of an endless black screen. */
    private var failure by mutableStateOf<StartFailure?>(null)
    private var engineStartRequested = false
    private var logThread: Thread? = null
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
        if (s != null) installCrashHandler(File(s.dataPath))
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
        startLogCapture(s)
        Log.i(TAG, "Game path: ${s.gamePath} (direct=${s.direct}, helper=${s.helper})")
        attachOverlay(s)
        watchStartup(s)
    }

    /**
     * Start-up watchdog. Follows the real launch stages and shows them in the indicator:
     *   engine thread started (SDL) → Ruby running (helper "boot") → game scripts loading data ("stage") → first frame ("ready").
     * A Ruby exception ("error") or no progress for [STALL_MS] shows a failure screen with the stage, the reason that was
     * actually detected, and the engine log, instead of leaving a black screen.
     */
    private fun watchStartup(s: RgssLaunchSpec) {
        val dir = RgssRuntime.helperDir(File(s.dataPath))
        val ready = File(dir, "ready"); val boot = File(dir, "boot"); val stage = File(dir, "stage"); val error = File(dir, "error")
        lifecycleScope.launch {
            var lastKey = ""
            var lastProgress = SystemClock.elapsedRealtime()
            while (true) {
                if (ready.exists()) { starting = false; return@launch }
                val err = error.takeIf { it.exists() }?.let { runCatching { it.readText().trim() }.getOrNull() }
                if (!err.isNullOrEmpty()) {
                    failure = StartFailure(currentStage(boot, stage), "The game stopped with a script error:\n$err", detected = true)
                    starting = false; return@launch
                }
                val key = when {
                    stage.exists() -> runCatching { stage.readText().trim() }.getOrDefault("")
                    boot.exists() -> "boot"
                    SDLActivity.mSDLThread != null -> "thread"
                    else -> "init"
                }
                stageText = when (key) {
                    "init" -> "Loading the engine…"
                    "thread" -> if (s.helper) "Starting Ruby and reading Game.ini…" else "Engine running…"
                    "boot" -> "Running the game's scripts…"
                    else -> key.removePrefix("Loading ").let { "Loading game data: ${it.substringAfterLast('/')}" }
                }
                val now = SystemClock.elapsedRealtime()
                if (key != lastKey) { lastKey = key; lastProgress = now }
                // Without the helper only the engine thread is observable; then just make sure it started.
                if (!s.helper && key == "thread") { starting = false; return@launch }
                val limit = if (key == "init") ENGINE_START_MS else STALL_MS
                if (now - lastProgress > limit && failure == null) {
                    failure = StartFailure(
                        currentStage(boot, stage),
                        if (key == "init") "The engine thread didn't start within ${limit / 1000} s. No error was reported; the cause hasn't been identified yet (see the engine log)."
                        else "No progress for ${limit / 1000} s at this stage. No error was reported; the game may still be working on something slow, or it is stuck.",
                        detected = false,
                    )
                }
                delay(250)
            }
        }
    }

    /** Records any uncaught Java/Kotlin exception of this game process for the launcher's crash report. */
    private fun installCrashHandler(dataDir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = com.acecore.app.runtime.CrashReports.javaCrashFile(RgssRuntime.helperDir(dataDir))
                f.parentFile?.mkdirs()
                f.writeText("$e\nThread: ${t.name}\n" + e.stackTraceToString())
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun currentStage(boot: File, stage: File): String = when {
        stage.exists() -> "Game scripts (" + runCatching { stage.readText().trim() }.getOrDefault("loading") + ")"
        boot.exists() -> "Game scripts (before loading any data)"
        SDLActivity.mSDLThread != null -> "Engine start-up (Ruby / Game.ini / Data/Scripts)"
        else -> "Engine thread start"
    }

    /** Copies this process's own log (SDL, mkxp-z, Ruby messages) into the game's helper folder for diagnosis. */
    private fun startLogCapture(s: RgssLaunchSpec) {
        val out = File(RgssRuntime.helperDir(File(s.dataPath)), "runtime.log")
        logThread = Thread({
            runCatching {
                out.parentFile?.mkdirs()
                val p = ProcessBuilder("logcat", "-v", "time", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
                out.outputStream().buffered().use { o ->
                    p.inputStream.bufferedReader().useLines { lines ->
                        var bytes = 0L
                        for (l in lines) {
                            if (bytes > 2_000_000) break
                            val b = (l + "\n").toByteArray()
                            o.write(b); o.flush(); bytes += b.size
                        }
                    }
                }
            }
        }, "AceCoreLog").apply { isDaemon = true; start() }
    }

    private fun logTail(s: RgssLaunchSpec, lines: Int = 40): String =
        runCatching { File(RgssRuntime.helperDir(File(s.dataPath)), "runtime.log").readLines().takeLast(lines).joinToString("\n") }
            .getOrDefault("(no log captured)")

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
            cheatsOpen -> cheatsOpen = false
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
        // This SDL build (from mkxp-z-android-reworked) does NOT start or resume its native thread in
        // SDLActivity.onStart; the host activity must call resumeNativeThread() itself (the port's MainActivity does
        // this in runSDLThread). Without it mIsResumedCalled stays false and the engine never starts: black screen.
        if (spec != null && !SDLActivity.mBrokenLibraries && !isFinishing) {
            if (!engineStartRequested) Log.i(TAG, "Starting the SDL/mkxp-z thread")
            engineStartRequested = true
            resumeNativeThread()
        }
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
            if (cheatsOpen) RgssCheatPanel(s, applicationContext, accent) { cheatsOpen = false }
            if (starting && !menuOpen && failure == null) StartingPill(stageText, accent, Modifier.align(Alignment.TopCenter))
            failure?.let { f -> FailureScreen(s, f, accent) }
        }
    }

    @Composable
    private fun FailureScreen(s: RgssLaunchSpec, f: StartFailure, accent: Color) {
        var details by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(20.dp)
                    .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (f.detected) "Unable to start the game" else "The game isn't starting", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Text("Game: ${s.title}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Text("Engine: ${com.acecore.app.core.model.Engine.fromName(s.engine).displayName} · RGSS runtime (mkxp-z)", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Text("Stage: ${f.stage}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Text("Files: " + if (s.direct) "direct from ${s.gamePath}" else "AceCore's private copy", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Text(f.reason, color = Color.White, fontSize = 14.sp)
                if (details) Text(logTail(s, 60), color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(10.dp)).padding(8.dp))
                Spacer(Modifier.height(6.dp))
                MenuItem(Icons.Rounded.RestartAlt, "Retry", accent, highlight = true) { finishForRetry(f) }
                MenuItem(Icons.Rounded.Edit, if (details) "Hide details" else "View error details", accent,
                    subtitle = "Engine log. Copy it to share with support.") { details = !details }
                if (details) MenuItem(Icons.Rounded.Edit, "Copy details", accent) {
                    val text = "AceCore start-up failure\nGame: ${s.title}\nStage: ${f.stage}\nReason: ${f.reason}\nPath: ${s.gamePath}\n\n${logTail(s, 300)}"
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("AceCore log", text))
                    android.widget.Toast.makeText(this@RgssActivity, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (!f.detected) MenuItem(Icons.Rounded.PlayArrow, "Keep waiting", accent) { failure = null; starting = true; watchStartup(s) }
                MenuItem(Icons.AutoMirrored.Rounded.ExitToApp, "Return to library", accent) { failAndFinish("${f.stage}: ${f.reason.lineSequence().first()}") }
            }
        }
    }

    private fun finishForRetry(f: StartFailure) {
        setResult(RESULT_FIRST_USER, Intent()
            .putExtra(com.acecore.app.runtime.web.GameLaunchSpec.RESULT_ERROR, "${f.stage}: ${f.reason.lineSequence().first()}")
            .putExtra(EXTRA_RETRY, true))
        finish()
    }

    @Composable
    private fun StartingPill(title: String, accent: Color, modifier: Modifier) {
        Row(
            modifier.padding(top = 18.dp).background(PanelBg, RoundedCornerShape(50)).border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (s.helper) MenuItem(Icons.Rounded.AutoFixHigh, "Cheats", accent,
                    subtitle = if (s.cheatsEnabled) "Money, items, Pokémon, movement…" else "Off for this game") { cheatsOpen = true; menuOpen = false }
                MenuItem(Icons.Rounded.RestartAlt, "Restart game (F12)", accent, subtitle = "Returns to the title screen. Unsaved progress is lost.") {
                    menuOpen = false; tapKey(KeyEvent.KEYCODE_F12)
                }
                MenuItem(Icons.AutoMirrored.Rounded.ExitToApp, "Exit to library", accent, subtitle = "Unsaved progress is lost.") { exitGame() }
            }
        }
    }
}

/** What went wrong during start-up. [detected] = an actual error was reported (vs. a stall). */
data class StartFailure(val stage: String, val reason: String, val detected: Boolean)
