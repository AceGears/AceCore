package com.acecore.app.runtime.handheld

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.PadButton
import com.acecore.app.core.handheld.CheatStore
import com.acecore.app.core.handheld.HandheldDb
import com.acecore.app.core.handheld.HandheldPlatform
import com.acecore.app.core.handheld.Identification
import com.acecore.app.core.handheld.RomInfo
import com.acecore.app.runtime.LaunchTimeline
import com.acecore.app.runtime.input.ControllerInput
import com.acecore.app.runtime.rgss.DisplayPrefs
import com.acecore.app.runtime.web.overlay.GamepadLayoutStore
import com.acecore.app.runtime.web.overlay.PanelBg
import com.acecore.app.runtime.web.overlay.VirtualGamepad
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a Game Boy, Game Boy Color, Game Boy Advance or Nintendo DS ROM with the bundled emulators (see
 * [HandheldCore]), in its own process (":handheld"). Battery saves live in the game's AceArcade data folder (with
 * automatic backups); the ROM file is only read.
 */
class HandheldActivity : ComponentActivity() {
    private lateinit var spec: HandheldLaunchSpec
    private lateinit var screen: HandheldScreen
    private var core: HandheldCore? = null
    private val frameQueued = AtomicBoolean(false)
    @Volatile private var finishing = false
    @Volatile private var started = false
    private var lastSaveDigest: String? = null
    private var vibrator: Vibrator? = null
    @Volatile private var rumbling = false
    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    private var micAsked = false
    private var frames = 0L

    // UI state
    private var status by mutableStateOf<String?>("Starting…")
    private var failure by mutableStateOf<String?>(null)
    private var menuOpen by mutableStateOf(false)
    private var displayOpen by mutableStateOf(false)
    private var statesOpen by mutableStateOf(false)
    internal var cheatsOpen by mutableStateOf(false)
    private var padVisible by mutableStateOf(true)
    private var fastForward by mutableStateOf(false)
    private var toast by mutableStateOf<String?>(null)
    private var toastSerial by mutableIntStateOf(0)
    private var display by mutableStateOf(DisplayPrefs())
    private var smooth by mutableStateOf(false)
    private var dsLayout by mutableStateOf(DsLayout.VERTICAL)
    private var dsGap by mutableIntStateOf(0)
    private var showFps by mutableStateOf(false)
    private var fpsText by mutableStateOf("")
    internal var cheats by mutableStateOf<CheatController?>(null)
    internal var cheatRevision by mutableIntStateOf(0)

    private var buttonsMask = 0
    private lateinit var padMap: Map<PadButton, Int>
    private val controller = ControllerInput(onButton = ::onPad, onMenu = { toggleMenu() })

    private val romBase by lazy { File(spec.romPath).nameWithoutExtension }
    private val saveDir by lazy { File(spec.saveDir).apply { mkdirs() } }
    private val batteryFile by lazy { File(saveDir, "$romBase.sav") }
    private val statesDir by lazy { File(saveDir, "states").apply { mkdirs() } }
    private val dataDir by lazy { saveDir.parentFile ?: saveDir }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = intent?.getStringExtra(HandheldLaunchSpec.EXTRA)?.let { runCatching { HandheldLaunchSpec.fromJson(it) }.getOrNull() }
        if (s == null) { finish(); return }
        spec = s
        LaunchTimeline.mark(File(s.helperDir), "Game process started (${s.platform.displayName})")
        padMap = parseButtonMap(s.buttonMap)
        display = DisplayPrefs.from(DisplayPrefs.parse(s.display))
        smooth = s.smooth; showFps = s.showFps
        dsLayout = DsLayout.byId(s.dsLayout)
        padVisible = when (s.gamepadMode) { "never" -> false; "always" -> true; else -> !ControllerInput.controllerConnected() }
        vibrator = if (s.vibration) getSystemService(Vibrator::class.java) else null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        screen = HandheldScreen(this, s.platform).apply {
            prefs = display; smooth = this@HandheldActivity.smooth; layout = dsLayout
            onTouch = { x, y, pressed -> core?.setPointer(x, y, pressed) }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(AColor.BLACK) }
        root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ComposeView(this).apply { isFocusable = false; setContent { Overlay() } },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = toggleMenu()
        })
        Thread(::start, "handheld-start").start()
    }

    private fun toggleMenu() {
        when {
            cheatsOpen -> cheatsOpen = false
            displayOpen -> displayOpen = false
            statesOpen -> statesOpen = false
            else -> menuOpen = !menuOpen
        }
        updateRunning()
    }

    private fun updateRunning() {
        core?.setRunning(started && !finishing && !menuOpen && !statesOpen && !cheatsOpen && failure == null && hasWindowFocusCompat)
    }
    private var hasWindowFocusCompat = true

    // ------------------------------------------------------------------ start / stop

    private fun start() {
        try {
            if (!HandheldCore.loadLibrary()) throw IllegalStateException("This build of AceArcade doesn't include the handheld emulators.")
            val c = HandheldCore(onFrameReady = ::onFrame, onLogLine = { level, msg -> if (level >= 2) Log.w(TAG, msg) else Log.i(TAG, msg) })
            core = c
            status = "Starting the ${spec.platform.displayName} emulator"
            c.open(this, spec.platform, File(filesDir, "handheld/system"), saveDir)?.let { throw IllegalStateException(it) }
            HandheldCore.baseOptions(spec.platform, spec.gbPalette).forEach { (k, v) -> c.setOption(k, v) }
            c.load(spec.romPath)?.let { throw IllegalStateException("The game couldn't be started: $it") }
            restoreBatterySave(c)
            c.setVolume(spec.volume / 100f)
            started = true
            status = null
            LaunchTimeline.mark(File(spec.helperDir), "Emulator running")
            runOnUiThread { updateRunning() }
            setupCheats(c)
            autosaveLoop()
        } catch (e: Throwable) {
            Log.e(TAG, "start", e)
            failure = e.message ?: e.javaClass.simpleName
            runCatching { File(spec.helperDir, "handheld-error").writeText(failure.orEmpty()) }
        }
    }

    private fun onFrame(rumble: Int) {
        frames++
        if (frames == 1L) {
            LaunchTimeline.mark(File(spec.helperDir), "First frame drawn")
            runCatching { File(spec.helperDir, "ready").writeText(System.currentTimeMillis().toString()) }
        }
        if (frameQueued.compareAndSet(false, true)) screen.post {
            frameQueued.set(false)
            core?.let { screen.showFrame(it) }
        }
        val on = rumble > 4096
        if (on != rumbling) {
            rumbling = on
            vibrator?.let { v -> if (on) v.vibrate(VibrationEffect.createOneShot(200, (rumble / 257).coerceIn(1, 255))) else v.cancel() }
        }
        if (frames % 30 == 0L) checkMicrophone()
    }

    private fun exitGame() {
        if (finishing) return
        finishing = true
        val c = core
        Thread {
            runCatching { c?.setRunning(false); c?.let { saveBattery(it) } }
            stopMicrophone()
            runCatching { c?.destroy() }
            runOnUiThread { finish(); screen.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 500) }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        core?.setRunning(false)
        core?.let { c -> if (started) Thread { saveBattery(c) }.start() }
    }

    override fun onResume() { super.onResume(); hideSystemBars(); updateRunning() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasWindowFocusCompat = hasFocus
        if (hasFocus) hideSystemBars()
        updateRunning()
    }

    override fun onDestroy() {
        if (!finishing) runCatching { core?.let { saveBattery(it) } }
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun say(text: String) { toast = text; toastSerial++ }

    // ------------------------------------------------------------------ battery saves (with backups)

    private fun digest(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun restoreBatterySave(c: HandheldCore) {
        if (!batteryFile.isFile) return
        val data = batteryFile.readBytes()
        backupBattery("session")
        val n = c.writeBatterySave(data)
        lastSaveDigest = digest(data)
        Log.i(TAG, "battery save restored: ${data.size} bytes (emulator took $n)")
    }

    /** Copies the current battery save to backups/ (keeps the newest [HandheldLaunchSpec.backups]). */
    internal fun backupBattery(reason: String) {
        if (spec.backups <= 0 || !batteryFile.isFile) return
        val dir = File(saveDir, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        batteryFile.copyTo(File(dir, "$romBase-$stamp-$reason.sav"), overwrite = true)
        dir.listFiles { f -> f.name.startsWith("$romBase-") }?.sortedByDescending { it.lastModified() }?.drop(spec.backups)?.forEach { it.delete() }
    }

    @Synchronized
    private fun saveBattery(c: HandheldCore) {
        val data = c.readBatterySave() ?: return
        if (data.isEmpty() || data.all { it == 0xFF.toByte() } && !batteryFile.isFile) return // untouched save memory
        val d = digest(data)
        if (d == lastSaveDigest) return
        val tmp = File(saveDir, "$romBase.sav.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(batteryFile)) { batteryFile.delete(); tmp.renameTo(batteryFile) }
        lastSaveDigest = d
    }

    private fun autosaveLoop() {
        Thread({
            while (!finishing) {
                Thread.sleep(10_000)
                core?.let { c -> if (started && !finishing) runCatching { saveBattery(c) } }
                if (core?.stoppedByError() == true && failure == null) failure = "The emulator stopped: ${core?.lastMessage()?.ifBlank { null } ?: "an internal error"}."
            }
        }, "handheld-autosave").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ save states

    internal fun stateFile(slot: Int) = File(statesDir, "$romBase.state$slot")
    private fun thumbFile(slot: Int) = File(statesDir, "$romBase.state$slot.png")

    internal fun saveState(slot: Int) {
        val c = core ?: return
        Thread {
            val data = c.saveState()
            if (data == null) { say("This game can't be saved right now."); return@Thread }
            stateFile(slot).writeBytes(data)
            screen.snapshot()?.let { b -> thumbFile(slot).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
            say(if (slot == 0) "Quick save done" else "Saved to slot $slot")
        }.start()
    }

    internal fun loadState(slot: Int) {
        val c = core ?: return
        val f = stateFile(slot)
        if (!f.isFile) { say("Slot ${if (slot == 0) "quick save" else slot} is empty"); return }
        Thread { say(if (c.loadState(f.readBytes())) "Loaded ${if (slot == 0) "quick save" else "slot $slot"}" else "That save state doesn't fit this game or emulator version.") }.start()
    }

    private fun screenshot() {
        val b = screen.snapshot() ?: return
        Thread {
            val name = "${spec.title.replace(Regex("[^A-Za-z0-9 _-]"), "")}-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.png"
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AceArcade")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("no media store")
                    contentResolver.openOutputStream(uri)!!.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    "Pictures/AceArcade"
                } else {
                    val dir = File(getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                    File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    dir.absolutePath
                }
            }
            say(ok.fold({ "Screenshot saved to $it" }, { "Couldn't save the screenshot: ${it.message}" }))
        }.start()
    }

    // ------------------------------------------------------------------ cheats

    private fun setupCheats(c: HandheldCore) {
        Thread {
            val db = runCatching { HandheldDb.open(this) }.getOrNull()
            var sha1 = spec.romSha1
            var ident: Identification? = null
            runCatching {
                val rom = File(spec.romPath)
                val header = rom.inputStream().use { i -> ByteArray(RomInfo.HEADER_BYTES).also { i.read(it) } }
                    .let { RomInfo.parseHeader(it, spec.platform) }
                val hashes = rom.inputStream().use { RomInfo.hash(it) }
                sha1 = hashes.sha1
                ident = db?.identify(spec.platform, hashes, header)
            }
            val store = CheatStore(CheatStore.file(dataDir))
            val ctl = CheatController(spec.platform, store, db, ident, sha1, spec.title)
            cheats = ctl
            applyCheats(showMessage = false)
            runOnUiThread { cheatRevision++ }
        }.start()
    }

    /** Sends the enabled cheats to the emulator (live; no restart). */
    internal fun applyCheats(showMessage: Boolean = true) {
        val ctl = cheats ?: return
        val c = core ?: return
        val codes = ctl.activeCodes()
        Thread { c.setCheats(codes); if (showMessage) say(if (codes.isEmpty()) "Cheats off" else "${codes.size} cheat(s) on") }.start()
        ctl.store.save()
        cheatRevision++
    }

    internal fun readMemory(address: Long, length: Int): ByteArray? = core?.readMemory(address, length)

    private val importLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val ctl = cheats ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        Thread {
            val r = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                .mapCatching { ctl.store.importFrom(it, spec.platform) }
            r.fold({ (added, skipped) ->
                ctl.store.save()
                say("Imported $added cheat(s)" + if (skipped > 0) ", skipped $skipped (invalid for ${spec.platform.displayName} or already there)" else "")
                runOnUiThread { cheatRevision++ }
            }, { say("Couldn't import: ${it.message}") })
        }.start()
    }

    private val exportLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val ctl = cheats ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        Thread {
            runCatching { contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(ctl.store.exportJson(spec.title, spec.platform)) } }
                .fold({ say("Exported ${ctl.store.custom.size} cheat(s) and ${ctl.store.presets.size} preset(s)") }, { say("Couldn't export: ${it.message}") })
        }.start()
    }

    /** Imports a RetroArch/libretro .cht file or an AceArcade export as the user's own cheats. */
    internal fun importCheats() = importLauncher.launch(arrayOf("*/*"))
    internal fun exportCheats() = exportLauncher.launch("${spec.title.replace(Regex("[^A-Za-z0-9 _-]"), "")} cheats.json")

    /** Restarts the game (keeps the battery save): needed to undo Game Genie codes, which change the ROM. */
    internal fun restartGame() {
        val c = core ?: return
        Thread {
            saveBattery(c)
            runOnUiThread { recreateGame() }
        }.start()
    }

    private fun recreateGame() {
        finishing = true
        Thread {
            runCatching { core?.destroy() }
            runOnUiThread {
                startActivity(HandheldLaunchSpec.intent(this, spec))
                finish()
                screen.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
            }
        }.start()
    }

    // ------------------------------------------------------------------ microphone (DS)

    private fun checkMicrophone() {
        val c = core ?: return
        val wanted = c.microphoneWanted() && c.microphoneRate() > 0
        if (wanted && mic == null) runOnUiThread { startMicrophone() }
    }

    private fun startMicrophone() {
        val c = core ?: return
        if (mic != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!micAsked) { micAsked = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST) }
            return
        }
        val rate = c.microphoneRate().takeIf { it in 8000..96000 } ?: 44100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096)) }
            .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return
        mic = r
        r.startRecording()
        micThread = Thread({
            val buf = ShortArray(1024)
            while (!finishing && mic === r) { val n = r.read(buf, 0, buf.size); if (n > 0) core?.pushMicrophone(buf, n) }
        }, "handheld-mic").apply { isDaemon = true; start() }
    }

    private fun stopMicrophone() { mic?.let { runCatching { it.stop(); it.release() } }; mic = null }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMicrophone()
            else say("Microphone not allowed: the game hears silence.")
        }
    }

    // ------------------------------------------------------------------ input

    /** AceArcade pad button -> console button (libretro joypad bit), from the game's button map ("A=HH_A;..."). */
    private fun parseButtonMap(s: String): Map<PadButton, Int> {
        val ids = mapOf("HH_A" to HandheldCore.A, "HH_B" to HandheldCore.B, "HH_X" to HandheldCore.X, "HH_Y" to HandheldCore.Y,
            "HH_L" to HandheldCore.L, "HH_R" to HandheldCore.R, "HH_START" to HandheldCore.START, "HH_SELECT" to HandheldCore.SELECT,
            "HH_UP" to HandheldCore.UP, "HH_DOWN" to HandheldCore.DOWN, "HH_LEFT" to HandheldCore.LEFT, "HH_RIGHT" to HandheldCore.RIGHT)
        val default = mapOf(PadButton.A to HandheldCore.A, PadButton.B to HandheldCore.B, PadButton.X to HandheldCore.X,
            PadButton.Y to HandheldCore.Y, PadButton.L1 to HandheldCore.L, PadButton.R1 to HandheldCore.R,
            PadButton.START to HandheldCore.START, PadButton.SELECT to HandheldCore.SELECT, PadButton.UP to HandheldCore.UP,
            PadButton.DOWN to HandheldCore.DOWN, PadButton.LEFT to HandheldCore.LEFT, PadButton.RIGHT to HandheldCore.RIGHT)
        val out = default.toMutableMap()
        s.split(';').forEach { pair ->
            val b = PadButton.entries.firstOrNull { it.name == pair.substringBefore('=') } ?: return@forEach
            val v = pair.substringAfter('=', "")
            if (v == "NONE") out.remove(b) else ids[v]?.let { out[b] = it }
        }
        return out
    }

    private fun onPad(b: PadButton, down: Boolean) {
        // L2 / R2 are AceArcade's: fast-forward (hold-free toggle) and quick save when not mapped to the console.
        if (b == PadButton.R2 && padMap[b] == null) { if (down) toggleFastForward(); return }
        if (b == PadButton.L2 && padMap[b] == null) { if (down) saveState(0); return }
        val bit = padMap[b] ?: return
        buttonsMask = if (down) buttonsMask or (1 shl bit) else buttonsMask and (1 shl bit).inv()
        core?.setButtons(buttonsMask)
    }

    private fun toggleFastForward() {
        fastForward = !fastForward
        core?.setSpeed(if (fastForward) spec.fastForwardSpeed else 1f)
        say(if (fastForward) "Fast-forward ${formatSpeed(spec.fastForwardSpeed)}" else "Normal speed")
    }

    private fun formatSpeed(f: Float) = if (f == f.toInt().toFloat()) "${f.toInt()}×" else "$f×"

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controller.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controller.handleMotion(event) || super.dispatchGenericMotionEvent(event)

    // ------------------------------------------------------------------ display settings (saved for the game)

    private fun saveForGame(values: Map<String, String>) = Thread { DisplayPrefs.writePending(dataDir, values) }.start()

    private fun updateDisplay(next: DisplayPrefs) {
        val n = next.normalized()
        display = n; screen.prefs = n
        saveForGame(n.values() - ConfigKey.SCREEN_FILTER.id)
    }

    private fun updateSmooth(v: Boolean) {
        smooth = v; screen.smooth = v
        saveForGame(mapOf(ConfigKey.FILTERING.id to if (v) "smooth" else "sharp"))
    }

    private fun updateLayout(l: DsLayout) {
        dsLayout = l; screen.layout = l
        saveForGame(mapOf("handheldDsLayout" to l.id))
    }

    // ------------------------------------------------------------------ overlay

    @Composable
    private fun Overlay() {
        val accent = Color(spec.accentArgb.toInt())
        val store = remember { GamepadLayoutStore(this@HandheldActivity) }
        Box(Modifier.fillMaxSize()) {
            val f = failure
            if (f != null || status != null) StatusPanel(accent, f)
            if (padVisible && !menuOpen && !displayOpen && !statesOpen && !cheatsOpen && f == null) {
                VirtualGamepad(
                    store = store, opacity = spec.opacity / 100f, scale = spec.scale / 100f, haptics = spec.haptics, accent = accent,
                    editing = false, onEditDone = {}, onButton = ::onPad, onMenu = { menuOpen = true; updateRunning() },
                )
            } else if (!menuOpen && !displayOpen && !statesOpen && !cheatsOpen && f == null) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).background(PanelBg, RoundedCornerShape(50))
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50)).clickable { menuOpen = true; updateRunning() }
                    .padding(horizontal = 16.dp, vertical = 6.dp)) { Text("☰", color = Color.White, fontSize = 16.sp) }
            }
            if (f == null && !menuOpen && !cheatsOpen) {
                // Fast-forward and (DS single-screen layouts) screen switch buttons.
                Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (spec.platform == HandheldPlatform.NDS && dsLayout in setOf(DsLayout.TOP_ONLY, DsLayout.BOTTOM_ONLY, DsLayout.PICTURE_IN_PICTURE))
                        SmallButton("⇅", accent) { screen.singleBottom = !screen.singleBottom }
                    SmallButton(if (fastForward) "▶ ${formatSpeed(spec.fastForwardSpeed)}" else "»", accent) { toggleFastForward() }
                }
                cheats?.let { ctl -> CheatShortcut(this@HandheldActivity, ctl, accent) }
            }
            if (showFps && f == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    var last = frames
                    while (true) { kotlinx.coroutines.delay(1000); val now = frames; fpsText = "${now - last} fps"; last = now }
                }
                Text(fpsText, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (displayOpen) DisplayPanel(accent)
            if (statesOpen) StatesPanel(accent)
            if (cheatsOpen) cheats?.let { CheatMenu(this@HandheldActivity, it, accent, cheatRevision) }
            if (menuOpen) MainMenu(accent)
            toast?.let { t ->
                androidx.compose.runtime.LaunchedEffect(toastSerial) { kotlinx.coroutines.delay(2200); toast = null }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).background(PanelBg, RoundedCornerShape(12.dp))
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(t, color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }

    @Composable
    private fun SmallButton(label: String, accent: Color, onClick: () -> Unit) {
        Box(Modifier.background(PanelBg.copy(alpha = 0.7f), RoundedCornerShape(50)).border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(label, color = Color.White, fontSize = 14.sp) }
    }

    @Composable
    private fun StatusPanel(accent: Color, f: String?) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (f != null) 0.85f else 0.5f))) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 520.dp).fillMaxWidth().padding(24.dp).background(PanelBg, RoundedCornerShape(22.dp))
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(spec.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(f ?: status.orEmpty(), color = if (f != null) Color(0xFFFF8A80) else Color.White, fontSize = 15.sp)
                if (f != null) MenuRow("Close", accent) { exitGame() }
            }
        }
    }

    @Composable
    internal fun MenuRow(label: String, accent: Color, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)) { Text(label, color = Color.White, fontSize = 15.sp) }
    }

    @Composable
    private fun Panel(accent: Color, onDismiss: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 460.dp).fillMaxWidth().padding(20.dp)
                .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }

    @Composable
    private fun MainMenu(accent: Color) {
        Panel(accent, { menuOpen = false; updateRunning() }) {
            Text(spec.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("${spec.platform.displayName} · ${if (spec.platform == HandheldPlatform.NDS) "melonDS DS" else "mGBA"}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            MenuRow("Resume", accent) { menuOpen = false; updateRunning() }
            MenuRow("Quick save", accent) { saveState(0); menuOpen = false; updateRunning() }
            MenuRow("Quick load", accent) { loadState(0); menuOpen = false; updateRunning() }
            MenuRow("Save states…", accent) { menuOpen = false; statesOpen = true }
            MenuRow("Cheats…", accent) { menuOpen = false; cheatsOpen = true; updateRunning() }
            MenuRow("Display…", accent) { menuOpen = false; displayOpen = true; updateRunning() }
            MenuRow(if (fastForward) "Normal speed" else "Fast-forward (${formatSpeed(spec.fastForwardSpeed)})", accent) { toggleFastForward(); menuOpen = false; updateRunning() }
            MenuRow("Screenshot", accent) { menuOpen = false; updateRunning(); screen.postDelayed({ screenshot() }, 150) }
            MenuRow(if (padVisible) "Hide on-screen controls" else "Show on-screen controls", accent) { padVisible = !padVisible; menuOpen = false; updateRunning() }
            MenuRow("Restart game", accent) { menuOpen = false; restartGame() }
            MenuRow("Exit to library", accent) { exitGame() }
        }
    }

    @Composable
    private fun StatesPanel(accent: Color) {
        val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
        Panel(accent, { statesOpen = false; updateRunning() }) {
            Text("Save states", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("States are snapshots of the whole game. Your battery save (in-game saves) is kept separately and backed up automatically.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            for (slot in 0..9) {
                val f = stateFile(slot)
                val label = (if (slot == 0) "Quick save" else "Slot $slot") + " · " + (if (f.isFile) fmt.format(Date(f.lastModified())) else "empty")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    SmallButton("Save", accent) { saveState(slot); statesOpen = false; updateRunning() }
                    if (f.isFile) SmallButton("Load", accent) { loadState(slot); statesOpen = false; updateRunning() }
                }
            }
        }
    }

    @Composable
    private fun DisplayPanel(accent: Color) {
        Panel(accent, { displayOpen = false; updateRunning() }) {
            Text("Display", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Screen fit", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            listOf(DisplayPrefs.FIT to "Fit", DisplayPrefs.INTEGER to "Pixel-perfect", DisplayPrefs.FILL to "Stretch",
                DisplayPrefs.COVER to "Fill", DisplayPrefs.ORIGINAL to "Original").chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (m, l) -> Choice(l, display.fit == m, accent, Modifier.weight(1f)) { updateDisplay(display.copy(fit = m)) } }
                }
            }
            Text("Game size ${display.sizePercent}%", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            androidx.compose.material3.Slider(value = display.sizePercent.toFloat(), onValueChange = { updateDisplay(display.copy(sizePercent = (it / 5).toInt() * 5)) },
                valueRange = 50f..100f, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
            Text("Picture position (vertical) ${display.offsetY}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            androidx.compose.material3.Slider(value = display.offsetY.toFloat(), onValueChange = { updateDisplay(display.copy(offsetY = (it / 5).toInt() * 5)) },
                valueRange = -100f..100f, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
            Text("Scaling", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Sharp pixels", !smooth, accent, Modifier.weight(1f)) { updateSmooth(false) }
                Choice("Smooth", smooth, accent, Modifier.weight(1f)) { updateSmooth(true) }
            }
            if (spec.platform == HandheldPlatform.NDS) {
                Text("DS screens", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                DsLayout.entries.forEach { l -> Choice(l.label, dsLayout == l, accent, Modifier.fillMaxWidth()) { updateLayout(l) } }
                Text("Gap between screens $dsGap", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                androidx.compose.material3.Slider(value = dsGap.toFloat(), onValueChange = { dsGap = it.toInt(); screen.gap = dsGap }, valueRange = 0f..64f,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Show fps", showFps, accent, Modifier.weight(1f)) { showFps = !showFps; saveForGame(mapOf(ConfigKey.SHOW_FPS.id to showFps.toString())) }
            }
            Text("Saved for this game. Game Boy palettes and more are in the game's settings in the library.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
    }

    @Composable
    internal fun Choice(label: String, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
        Box(modifier.height(40.dp).background(if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp, maxLines = 1)
        }
    }

    internal fun romSha1(): String? = cheats?.romSha1
    internal fun say2(text: String) = say(text)
    internal fun gameTitle() = spec.title

    companion object {
        private const val TAG = "AceArcadeHandheld"
        private const val MIC_REQUEST = 41
    }
}
