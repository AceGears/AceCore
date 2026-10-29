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
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import com.acecore.app.core.config.RgssKeys
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.foundation.horizontalScroll
import com.acecore.app.runtime.web.overlay.GamepadTools
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.FastForward
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
import kotlin.math.roundToInt
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
        /** While playing: no new frame for this long → "not producing frames" banner. */
        private const val STALL_FRAMES_MS = 20_000L

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
    /** On-screen pad → keyboard key; editable in ☰ › Controls › Button mapping. */
    private var buttonMap by mutableStateOf(ButtonMap.RGSS_DEFAULT)
    /** The game's own names for its buttons (mkxp.json "bindingNames"), shown in the mapping and test panels. */
    private var gameButtonNames: Map<String, String> = emptyMap()
    private var rgssVersion = 1
    private var mappingOpen by mutableStateOf(false)
    private var testOpen by mutableStateOf(false)
    /** Last on-screen button press (for the controls test), e.g. "A → Enter". */
    private var lastPad by mutableStateOf<String?>(null)
    private var gamepadMode = "auto"

    // ---- Fast-forward and quick save (acecore_compat.rb › Tools)
    private var ffOn by mutableStateOf(false)
    private var ffSpeed by mutableStateOf(2f)
    private var ffHold by mutableStateOf(false)
    private var ffQuiet by mutableStateOf(true)
    private var padFf by mutableStateOf(true)
    private var padQs by mutableStateOf(true)
    private var padQl by mutableStateOf(true)
    private var toolsScale by mutableStateOf(1f)
    private var toolsOpacity by mutableStateOf(0.7f)
    private var toolsOpen by mutableStateOf(false)
    /** Short confirmation shown at the top ("Quick save created"…). */
    private var notice by mutableStateOf<String?>(null)
    private var noticeSeq = 0
    private var toolRequest = 0

    private var menuOpen by mutableStateOf(false)
    private var cheatsOpen by mutableStateOf(false)
    private var displayOpen by mutableStateOf(false)
    /** Screen fit and game size; changed live from ☰ › Display and saved for this game. */
    private var display by mutableStateOf(DisplayPrefs())
    private var appliedSurfaceSize: Pair<Int, Int>? = null
    /** The game's own resolution (Graphics.width × height), reported by acecore_compat.rb; null until known. */
    private var gameRes by mutableStateOf<Pair<Int, Int>?>(null)
    /** Screen area and game surface currently in use, shown in the Display panel. */
    private var layoutInfo by mutableStateOf("")
    /** Latest heartbeat from the running game (acecore_compat.rb), for the status line and stall detection. */
    private var heartbeat by mutableStateOf<GameDiagnostics.Heartbeat?>(null)
    /** Shown when the game stops producing frames for a while (frozen, or a long load). */
    private var stalled by mutableStateOf(false)
    private var stallDismissedAtFrame = -1L
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
            com.acecore.app.runtime.LaunchTimeline.mark(RgssRuntime.helperDir(File(s.dataPath)), "Game process started")
            buttonMap = ButtonMap.decode(s.config[ConfigKey.BUTTON_MAP.id], ButtonMap.RGSS_DEFAULT)
            rgssVersion = RgssRuntime.rgssVersion(com.acecore.app.core.model.Engine.fromName(s.engine))
            gameButtonNames = RgssKeys.gameNames(runCatching { File(s.gamePath, "mkxp.json").takeIf { it.length() < 256_000 }?.readText() }.getOrNull())
            gamepadMode = s.config[ConfigKey.GAMEPAD_MODE.id] ?: "auto"
            ffSpeed = (s.config[ConfigKey.FF_SPEED.id] ?: "2").toFloatOrNull()?.coerceIn(1.25f, 8f) ?: 2f
            ffHold = s.config[ConfigKey.FF_MODE.id] == "hold"
            ffQuiet = (s.config[ConfigKey.FF_AUDIO.id] ?: "quiet") == "quiet"
            padFf = ConfigKey.PAD_FF.bool(s.config[ConfigKey.PAD_FF.id] ?: "true")
            padQs = ConfigKey.PAD_QS.bool(s.config[ConfigKey.PAD_QS.id] ?: "true")
            padQl = ConfigKey.PAD_QL.bool(s.config[ConfigKey.PAD_QL.id] ?: "true")
            toolsScale = ConfigKey.TOOLS_SCALE.int(s.config[ConfigKey.TOOLS_SCALE.id] ?: "100") / 100f
            toolsOpacity = ConfigKey.TOOLS_OPACITY.int(s.config[ConfigKey.TOOLS_OPACITY.id] ?: "70") / 100f
            display = DisplayPrefs.from(s.config)
        } else {
            buttonMap = ButtonMap.RGSS_DEFAULT
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
        watchSurfaceSize()
        watchStartup(s)
    }

    // ------------------------------------------------------------------ display size

    /** Keeps the game surface at the chosen size whenever the screen area changes (rotation, multi-window…). */
    private fun watchSurfaceSize() {
        val layout = SDLActivity.mLayout ?: return
        layout.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) layout.post { applySurfaceSize() }
        }
        layout.post { applySurfaceSize() }
        // Heartbeat: running (frames advancing, even if the picture is black) vs. not producing frames.
        dataDir?.let { RgssRuntime.helperDir(it) }?.let { helper ->
            lifecycleScope.launch {
                var lastFrame = -1L
                var sameSince = 0L
                while (true) {
                    val hb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GameDiagnostics.heartbeat(helper) }
                    heartbeat = hb
                    val now = android.os.SystemClock.elapsedRealtime()
                    val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (hb == null || hb.frame != lastFrame || !resumed || menuOpen) { lastFrame = hb?.frame ?: -1; sameSince = now; if (stalled && hb?.frame != stallDismissedAtFrame) stalled = false }
                    else if (now - sameSince > STALL_FRAMES_MS && hb.frame != stallDismissedAtFrame && failure == null) stalled = true
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
        // The game reports its resolution once Ruby runs (and again if it changes, e.g. Graphics.resize_screen).
        val screenFile = dataDir?.let { File(RgssRuntime.helperDir(it), "screen") } ?: return
        lifecycleScope.launch {
            while (true) {
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { screenFile.readText().trim().split(' ').map { it.toInt() } }.getOrNull()
                }?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let { it[0] to it[1] }
                if (res != null && res != gameRes) { gameRes = res; applySurfaceSize() }
                kotlinx.coroutines.delay(if (gameRes == null) 250 else 1000)
            }
        }
    }

    /** Updates the Display panel's size readout (the engine does the actual layout inside the full-screen surface). */
    private fun applySurfaceSize() {
        val layout = SDLActivity.mLayout ?: return
        val surface = SDLActivity.mSurface ?: return
        val w = layout.width; val h = layout.height
        if (w <= 0 || h <= 0) return
        val (gw, gh) = gameRes ?: (0 to 0)
        val (sw, sh) = display.surfaceSize(w, h, gw, gh)
        layoutInfo = (if (gameRes != null) "Game ${gw}×${gh}" else "Game resolution not reported yet") +
            " · screen ${w}×${h} · game area ${sw}×${sh}"
        // The surface itself always stays full-screen: the engine fits/stretches/scales the game inside it and applies
        // the game-size setting (Graphics.acearcade_area, set by acecore_compat.rb). Resizing the Android surface
        // while the game runs left the engine drawing for the old size (picture offset and clipped).
        if (appliedSurfaceSize == null && surface.layoutParams?.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            surface.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        appliedSurfaceSize = sw to sh
    }

    /** Applies a Display-panel change now and records it for this game (imported into its settings by the library). */
    private fun updateDisplay(next: DisplayPrefs) {
        val n = next.normalized()
        if (n == display) return
        display = n
        applySurfaceSize()
        dataDir?.let { dir -> Thread { DisplayPrefs.write(dir, n) }.start() }
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
        if (down && testOpen) lastPad = "${button.label} → ${key.label}"
        Log.d("AceArcadeInput", "rgss ${button.name} ${if (down) "down" else "up"} -> ${key.id} (android key ${key.androidKeyCode})")
        sendKey(key, down)
    }

    /** Saves an in-game change so the library stores it as this game's setting. */
    private fun savePending(values: Map<String, String>) {
        val dir = dataDir ?: return
        Thread { DisplayPrefs.writePending(dir, values) }.start()
    }

    private fun setInputTest(on: Boolean) {
        val dir = dataDir?.let { RgssRuntime.helperDir(it) } ?: return
        Thread {
            val flag = File(dir, "input_test")
            if (on) runCatching { dir.mkdirs(); flag.writeText("1") } else { flag.delete(); File(dir, "input_state").delete() }
        }.start()
    }

    // ------------------------------------------------------------------ fast-forward / quick save

    private fun speedLabel(v: Float) = (if (v % 1f == 0f) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')) + "×"

    /** Tells the game script the current speed ("speed" file; it checks it every few frames). */
    private fun writeSpeed() {
        val dir = dataDir?.let { RgssRuntime.helperDir(it) } ?: return
        val text = "speed=${if (ffOn) ffSpeed else 1f}\naudio=${if (ffQuiet) "quiet" else "normal"}\n"
        Thread {
            runCatching {
                val tmp = File(dir, "speed.tmp"); tmp.writeText(text); tmp.renameTo(File(dir, "speed"))
            }
        }.start()
    }

    private fun setFastForward(on: Boolean) {
        if (ffOn == on) return
        ffOn = on
        writeSpeed()
    }

    /** FF button: toggle mode flips on press; hold mode is on while held. */
    private fun onFastForwardButton(down: Boolean) {
        if (ffHold) setFastForward(down) else if (down) setFastForward(!ffOn)
    }

    private fun showNotice(text: String) {
        val seq = ++noticeSeq
        notice = text
        lifecycleScope.launch { kotlinx.coroutines.delay(2600); if (noticeSeq == seq) notice = null }
    }

    /** Quick save / quick load through the game script ("tools_cmd" → "tools_result"). */
    private fun quickAction(action: String) {
        val dir = dataDir?.let { RgssRuntime.helperDir(it) } ?: return
        val id = (++toolRequest).toString() + "-" + System.currentTimeMillis()
        showNotice(if (action == "quicksave") "Saving…" else "Loading…")
        lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { File(dir, "tools_cmd.tmp").apply { writeText("$id\t$action") }.renameTo(File(dir, "tools_cmd")) }
                val res = File(dir, "tools_result")
                val until = System.currentTimeMillis() + 10_000
                var parts: List<String>? = null
                while (System.currentTimeMillis() < until) {
                    parts = runCatching { res.readText().split('\t', limit = 3) }.getOrNull()?.takeIf { it.firstOrNull() == id }
                    if (parts != null) break
                    Thread.sleep(80)
                }
                parts
            }
            showNotice(when {
                result == null -> "The game didn't respond. Quick save and load work once the game is on its map."
                else -> result.getOrElse(2) { "" }.ifBlank { if (result[1] == "ok") "Done" else "Didn't work" }
            })
        }
    }

    private fun saveTools() = savePending(mapOf(
        ConfigKey.FF_SPEED.id to ffSpeed.toString().removeSuffix(".0"), ConfigKey.FF_MODE.id to if (ffHold) "hold" else "toggle",
        ConfigKey.FF_AUDIO.id to if (ffQuiet) "quiet" else "normal", ConfigKey.PAD_FF.id to padFf.toString(),
        ConfigKey.PAD_QS.id to padQs.toString(), ConfigKey.PAD_QL.id to padQl.toString(),
        ConfigKey.TOOLS_SCALE.id to (toolsScale * 100).toInt().toString(), ConfigKey.TOOLS_OPACITY.id to (toolsOpacity * 100).toInt().toString(),
    ))

    private var lastDpadBounds: android.graphics.Rect? = null

    /** Writes the D-pad's on-screen rectangle to the helper folder ("dpad": left top right bottom) for diagnostics/tests. */
    private fun reportDpadBounds(r: android.graphics.Rect) {
        if (r == lastDpadBounds) return
        lastDpadBounds = r
        val dir = dataDir?.let { RgssRuntime.helperDir(it) } ?: return
        Thread { runCatching { File(dir, "dpad").writeText("${r.left} ${r.top} ${r.right} ${r.bottom}") } }.start()
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
            displayOpen -> displayOpen = false
            mappingOpen -> mappingOpen = false
            testOpen -> { testOpen = false; setInputTest(false) }
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
            if (!engineStartRequested) {
                Log.i(TAG, "Starting the SDL/mkxp-z thread")
                com.acecore.app.runtime.LaunchTimeline.mark(RgssRuntime.helperDir(File(spec!!.dataPath)), "Engine starting (SDL, OpenGL, audio, file index)")
            }
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
                    onDpadBounds = ::reportDpadBounds,
                    onMenu = { menuOpen = true },
                    fourWay = true,
                    tools = GamepadTools(
                        showFastForward = padFf, showQuickSave = padQs, showQuickLoad = padQl,
                        fastForwardActive = ffOn, speedLabel = speedLabel(ffSpeed), scale = toolsScale, opacity = toolsOpacity,
                        onFastForward = ::onFastForwardButton,
                        onQuickSave = { quickAction("quicksave") }, onQuickLoad = { quickAction("quickload") },
                    ),
                )
            }
            if (keyboardOpen) VirtualKeyboard(accent, haptics, ::sendKey) { keyboardOpen = false }
            if (menuOpen) RgssMenu(s, accent)
            if (cheatsOpen) RgssCheatPanel(s, applicationContext, accent) { cheatsOpen = false }
            if (displayOpen) DisplayPanel(accent)
            if (mappingOpen) MappingPanel(accent)
            if (testOpen) ControlsTestPanel(accent, Modifier.align(Alignment.TopCenter))
            if (toolsOpen) ToolsPanel(accent)
            notice?.let { n ->
                Text(n, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp).widthIn(max = 420.dp)
                        .background(PanelBg, RoundedCornerShape(50)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (stalled && !menuOpen && failure == null) StallBanner(s, accent, Modifier.align(Alignment.TopCenter))
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
                Text("Files: " + if (s.direct) "direct from ${s.gamePath}" else "AceArcade's private copy", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Text(f.reason, color = Color.White, fontSize = 14.sp)
                if (details) Text(logTail(s, 60), color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(10.dp)).padding(8.dp))
                Spacer(Modifier.height(6.dp))
                MenuItem(Icons.Rounded.RestartAlt, "Retry", accent, highlight = true) { finishForRetry(f) }
                MenuItem(Icons.Rounded.Edit, if (details) "Hide details" else "View error details", accent,
                    subtitle = "Engine log. Copy it to share with support.") { details = !details }
                if (details) MenuItem(Icons.Rounded.Edit, "Copy details", accent) {
                    val text = "AceArcade start-up failure\nGame: ${s.title}\nStage: ${f.stage}\nReason: ${f.reason}\nPath: ${s.gamePath}\n\n${logTail(s, 300)}"
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("AceArcade log", text))
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

    /**
     * ☰ › Display: screen fit and game size, applied live. The panel sits at the top of the screen so the game stays
     * visible behind it while adjusting.
     */
    @Composable
    private fun DisplayPanel(accent: Color) {
        Box(
            Modifier.fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { displayOpen = false },
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.padding(top = 12.dp).widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp)
                    .background(PanelBg, RoundedCornerShape(20.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Display", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { updateDisplay(DisplayPrefs()) }) { Text("Reset", color = accent) }
                    androidx.compose.material3.TextButton(onClick = { displayOpen = false }) { Text("Done", color = accent, fontWeight = FontWeight.Bold) }
                }
                Text("Screen fit", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DisplayPrefs.FIT to "Fit", DisplayPrefs.FILL to "Stretch", DisplayPrefs.INTEGER to "Pixel-perfect").forEach { (mode, label) ->
                        val sel = display.fit == mode
                        Box(
                            Modifier.weight(1f).height(40.dp)
                                .background(if (sel) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                .border(1.dp, if (sel) accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                .clickable { updateDisplay(display.copy(fit = mode)) },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = Color.White, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp) }
                    }
                }
                Text(
                    when (display.fit) {
                        DisplayPrefs.FILL -> "Fills the whole area; the picture is stretched to the screen's shape."
                        DisplayPrefs.INTEGER -> "Largest whole-number multiple of the game's resolution: crisp pixels, may leave borders."
                        else -> "Largest size that keeps the game's proportions."
                    },
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Game size", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${display.sizePercent}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                androidx.compose.material3.Slider(
                    value = display.sizePercent.toFloat(),
                    onValueChange = { updateDisplay(display.copy(sizePercent = (it / 5f).roundToInt() * 5)) },
                    valueRange = DisplayPrefs.MIN_SIZE.toFloat()..100f, steps = (100 - DisplayPrefs.MIN_SIZE) / 5 - 1,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                )
                Text(layoutInfo, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                Text("Saved for this game. Also in the game's settings in the library.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun RgssMenu(s: RgssLaunchSpec, accent: Color) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.74f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = false },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(16.dp)
                    .background(PanelBg, RoundedCornerShape(24.dp)).border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            ) {
                val wide = maxWidth > 560.dp
                Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                    // Header: game and engine
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${com.acecore.app.core.model.Engine.fromName(s.engine).displayName} · paused menu, the game keeps running",
                                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)).clickable { menuOpen = false },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, "Close menu", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.height(14.dp))
                    val game: @Composable () -> Unit = {
                        MenuSection("Gameplay") {
                            MenuItem(Icons.Rounded.PlayArrow, "Resume", accent, highlight = true) { menuOpen = false }
                            FastForwardRow(accent)
                            MenuItem(Icons.Rounded.Save, "Quick save", accent, subtitle = "One slot for this game, kept by AceArcade (your game saves are untouched)") {
                                menuOpen = false; quickAction("quicksave")
                            }
                            MenuItem(Icons.Rounded.Restore, "Quick load", accent, subtitle = "Back to this game's quick save") {
                                menuOpen = false; quickAction("quickload")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        MenuSection("System") {
                            MenuItem(Icons.Rounded.RestartAlt, "Restart game", accent, subtitle = "Back to the title screen (F12). Unsaved progress is lost.") {
                                menuOpen = false; tapKey(KeyEvent.KEYCODE_F12)
                            }
                            if (s.helper) MenuItem(Icons.Rounded.AutoFixHigh, "Cheats", accent,
                                subtitle = if (s.cheatsEnabled) "Money, items, Pokémon, movement…" else "Off for this game") { cheatsOpen = true; menuOpen = false }
                            MenuItem(Icons.Rounded.MonitorHeart, "Game status", accent, subtitle = statusLine() + " · tap to save a diagnostic report") {
                                saveDiagnosticReport(s, "Diagnostic report (from the in-game menu)")
                            }
                            MenuItem(Icons.AutoMirrored.Rounded.ExitToApp, "Exit to library", accent, subtitle = "Unsaved progress is lost.") { exitGame() }
                        }
                    }
                    val view: @Composable () -> Unit = {
                        MenuSection("Display") {
                            MenuItem(Icons.Rounded.AspectRatio, "Screen fit & size", accent,
                                subtitle = "${fitLabel(display.fit)} · ${display.sizePercent}%") { displayOpen = true; menuOpen = false }
                        }
                        Spacer(Modifier.height(12.dp))
                        MenuSection("Controls") {
                            MenuItem(Icons.Rounded.Gamepad, if (gamepadVisible) "Hide on-screen controls" else "Show on-screen controls", accent) {
                                gamepadMode = if (gamepadVisible) "never" else "always"; refreshGamepadVisibility()
                                savePending(mapOf(ConfigKey.GAMEPAD_MODE.id to gamepadMode)); menuOpen = false
                            }
                            MenuItem(Icons.Rounded.SettingsInputComponent, "Button mapping", accent, subtitle = "Choose what each on-screen button presses") {
                                mappingOpen = true; menuOpen = false
                            }
                            MenuItem(Icons.Rounded.TouchApp, "Test controls", accent, subtitle = "See which buttons the game receives") {
                                testOpen = true; lastPad = null; setInputTest(true); menuOpen = false
                                if (!gamepadVisible) { gamepadMode = "always"; refreshGamepadVisibility() }
                            }
                            MenuItem(Icons.Rounded.Edit, "Move on-screen controls", accent) { editingLayout = true; menuOpen = false }
                            MenuItem(Icons.Rounded.FastForward, "Gameplay buttons (FF / QS / QL)", accent, subtitle = "Show, hide, size and fast-forward options") {
                                toolsOpen = true; menuOpen = false
                            }
                            MenuItem(Icons.Rounded.Keyboard, "Keyboard", accent, subtitle = "For name entry and keyboard shortcuts") { keyboardOpen = true; menuOpen = false }
                        }
                    }
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) { game() }
                            Column(Modifier.weight(1f)) { view() }
                        }
                    } else {
                        game(); Spacer(Modifier.height(12.dp)); view()
                    }
                }
            }
        }
    }

    /** Fast-forward switch with the speed presets, inside the menu. */
    @Composable
    private fun FastForwardRow(accent: Color) {
        Column(
            Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FastForward, null, tint = accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Fast-forward", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(if (ffOn) "On · ${speedLabel(ffSpeed)}" else "Off · ${speedLabel(ffSpeed)} when on", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(checked = ffOn, onCheckedChange = { setFastForward(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = accent))
            }
            SpeedChips(accent)
        }
    }

    @Composable
    private fun SpeedChips(accent: Color) {
        val presets = listOf(1.25f, 1.5f, 2f, 3f, 4f, 6f, 8f)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { v ->
                val sel = ffSpeed == v
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (sel) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f))
                        .border(1.dp, if (sel) accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                        .clickable { ffSpeed = v; if (ffOn) writeSpeed(); saveTools() }.padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(speedLabel(v), color = Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) }
            }
        }
        androidx.compose.material3.Slider(
            value = ffSpeed, valueRange = 1.25f..8f, steps = 26,
            onValueChange = { ffSpeed = (Math.round(it * 4) / 4f).coerceIn(1.25f, 8f) },
            onValueChangeFinished = { if (ffOn) writeSpeed(); saveTools() },
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
        )
    }

    /** ☰ › Controls › Gameplay buttons: show/hide FF, QS, QL, their size and opacity, fast-forward mode and sound. */
    @Composable
    private fun ToolsPanel(accent: Color) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { toolsOpen = false; saveTools() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp)
                    .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Gameplay buttons", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("FF = fast-forward, QS = quick save, QL = quick load. Drag them with ☰ › Move on-screen controls.",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                ToggleRow("FF button", padFf, accent) { padFf = it }
                ToggleRow("QS button", padQs, accent) { padQs = it }
                ToggleRow("QL button", padQl, accent) { padQl = it }
                Text("Size ${(toolsScale * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
                androidx.compose.material3.Slider(value = toolsScale, valueRange = 0.6f..1.6f, onValueChange = { toolsScale = it },
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
                Text("Opacity ${(toolsOpacity * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
                androidx.compose.material3.Slider(value = toolsOpacity, valueRange = 0.15f..1f, onValueChange = { toolsOpacity = it },
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
                ToggleRow("Hold to fast-forward (instead of tap on / tap off)", ffHold, accent) { ffHold = it; if (ffOn) setFastForward(false) }
                ToggleRow("Quiet while fast-forwarding (no sound effects)", ffQuiet, accent) { ffQuiet = it; if (ffOn) writeSpeed() }
                Text("Fast-forward speed", color = Color.White, fontSize = 13.sp)
                SpeedChips(accent)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.TextButton(onClick = { toolsOpen = false; saveTools() }) { Text("Done", color = accent) }
                }
            }
        }
    }

    @Composable
    private fun ToggleRow(label: String, value: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!value) }) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = value, onCheckedChange = onChange,
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = accent))
        }
    }

    private fun statusLine(): String {
        val hb = heartbeat ?: return "Starting…"
        val state = if (hb.ageMs > STALL_FRAMES_MS) "Not producing frames for ${hb.ageMs / 1000} s" else "Running"
        return "$state · ${hb.scene}" + (if (hb.map != "-") " · map ${hb.map}" else "")
    }

    /** Saves timeline, scene log, heartbeat and engine log as a local report (Settings › Advanced › Crash reports). */
    private fun saveDiagnosticReport(s: RgssLaunchSpec, title: String) {
        val dir = dataDir ?: return
        Thread {
            val helper = RgssRuntime.helperDir(dir)
            val report = com.acecore.app.runtime.CrashReports.redact(buildString {
                append(com.acecore.app.runtime.CrashReports.header(s.title, com.acecore.app.core.model.Engine.fromName(s.engine).displayName, statusLine()))
                appendLine(title)
                appendLine("Display: ${display.fit} ${display.sizePercent}% · $layoutInfo")
                appendLine("Surface: ${SDLActivity.mSurface?.width ?: 0}x${SDLActivity.mSurface?.height ?: 0} · window ${window.decorView.width}x${window.decorView.height}" +
                    " · D-pad ${lastDpadBounds?.toShortString() ?: "hidden"}")
                appendLine()
                append(GameDiagnostics.summary(helper))
                runCatching { File(helper, "runtime.log").readLines().takeLast(200).joinToString("\n") }.getOrNull()?.let {
                    appendLine(); appendLine("== Game process log (last lines) =="); appendLine(it)
                }
            })
            val saved = com.acecore.app.runtime.CrashReports.save(applicationContext, s.gameId, report) != null
            runOnUiThread {
                android.widget.Toast.makeText(this, if (saved) "Report saved: Settings › Advanced › Crash reports" else "Couldn't save the report",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    @Composable
    private fun StallBanner(s: RgssLaunchSpec, accent: Color, modifier: Modifier) {
        Column(
            modifier.padding(top = 10.dp).widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp)
                .background(PanelBg, RoundedCornerShape(16.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("The game hasn't drawn a new frame for ${(heartbeat?.ageMs ?: 0) / 1000} s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("It may be loading or frozen. Last scene: ${heartbeat?.scene ?: "unknown"}" + (heartbeat?.map?.takeIf { it != "-" }?.let { ", map $it" } ?: ""),
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.TextButton(onClick = { stallDismissedAtFrame = heartbeat?.frame ?: -1; stalled = false }) { Text("Keep waiting", color = accent) }
                androidx.compose.material3.TextButton(onClick = { saveDiagnosticReport(s, "Diagnostic report (game stopped producing frames)") }) { Text("Save report", color = accent) }
                androidx.compose.material3.TextButton(onClick = { exitGame() }) { Text("Exit to library", color = accent) }
            }
        }
    }

    @Composable
    private fun MenuSection(title: String, content: @Composable () -> Unit) {
        Text(title.uppercase(), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }

    /** ☰ › Controls › Button mapping: what each on-screen button presses, described in the game's own terms. */
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun MappingPanel(accent: Color) {
        var editing by remember { mutableStateOf<PadButton?>(null) }
        fun set(map: Map<PadButton, String>) {
            buttonMap = map
            savePending(mapOf(ConfigKey.BUTTON_MAP.id to ButtonMap.encode(map)))
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { mappingOpen = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp)
                    .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Button mapping", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { set(ButtonMap.RGSS_DEFAULT); editing = null }) { Text("Defaults", color = accent) }
                    androidx.compose.material3.TextButton(onClick = { mappingOpen = false }) { Text("Done", color = accent, fontWeight = FontWeight.Bold) }
                }
                Text("Saved for this game. Physical controllers use the engine's own controller layout.",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val order = listOf(PadButton.A, PadButton.B, PadButton.X, PadButton.Y, PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2,
                        PadButton.START, PadButton.SELECT, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT)
                    order.forEach { b ->
                        val keyId = buttonMap[b] ?: "NONE"
                        val open = editing == b
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (open) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                                .clickable { editing = if (open) null else b }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.label.substringBefore(" /"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                                Text(WebKey.byId(keyId)?.label ?: keyId, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                                Text(RgssKeys.describe(keyId, rgssVersion, gameButtonNames), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            if (open) {
                                Spacer(Modifier.height(6.dp))
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RgssKeys.CHOICES.forEach { id ->
                                        val sel = id == keyId
                                        Column(
                                            Modifier.clip(RoundedCornerShape(10.dp))
                                                .background(if (sel) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f))
                                                .border(1.dp, if (sel) accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                                .clickable { set(buttonMap + (b to id)); editing = null }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Text(WebKey.byId(id)?.label ?: id, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(RgssKeys.describe(id, rgssVersion, gameButtonNames), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * ☰ › Controls › Test controls: a small card at the top while the controls stay usable. Shows the last on-screen
     * button and the key it sent, and the RGSS buttons the engine itself reports as held (read by acecore_compat.rb).
     */
    @Composable
    private fun ControlsTestPanel(accent: Color, modifier: Modifier) {
        var engine by remember { mutableStateOf<Set<String>>(emptySet()) }
        var seen by remember { mutableStateOf<Set<String>>(emptySet()) }
        var engineReplying by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val f = dataDir?.let { File(RgssRuntime.helperDir(it), "input_state") }
            while (true) {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { f?.readText() }.getOrNull() }
                if (text != null) {
                    engineReplying = true
                    engine = text.lineSequence().first().split(' ').filter { it.isNotBlank() }.toSet()
                    seen = seen + engine
                }
                kotlinx.coroutines.delay(100)
            }
        }
        Column(
            modifier.padding(top = 10.dp).widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 16.dp)
                .background(PanelBg, RoundedCornerShape(18.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Test controls", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { testOpen = false; setInputTest(false) }) { Text("Done", color = accent, fontWeight = FontWeight.Bold) }
            }
            Text(lastPad?.let { "Last button: $it" } ?: "Press any on-screen button. The game receives these presses too.",
                color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("UP" to "↑", "DOWN" to "↓", "LEFT" to "←", "RIGHT" to "→", "C" to "C", "B" to "B", "A" to "A",
                    "X" to "X", "Y" to "Y", "Z" to "Z", "L" to "L", "R" to "R").forEach { (id, label) ->
                    val on = id in engine; val ok = id in seen
                    Box(
                        Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                            .background(when { on -> accent; ok -> accent.copy(alpha = 0.22f); else -> Color.White.copy(alpha = 0.06f) })
                            .border(1.dp, if (ok) accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
            }
            Text(
                if (engineReplying) "Lit = held now in the engine · outlined = worked during this test"
                else "Waiting for the engine… (it reports once the game is running)",
                color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
            )
        }
    }

}

private fun fitLabel(fit: String) = when (fit) {
    DisplayPrefs.FILL -> "Stretch"
    DisplayPrefs.INTEGER -> "Pixel-perfect"
    else -> "Fit"
}

/** What went wrong during start-up. [detected] = an actual error was reported (vs. a stall). */
data class StartFailure(val stage: String, val reason: String, val detected: Boolean)
