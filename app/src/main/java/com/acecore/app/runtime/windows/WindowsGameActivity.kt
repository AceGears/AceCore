package com.acecore.app.runtime.windows

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
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
import com.acecore.app.runtime.input.ControllerInput
import com.acecore.app.runtime.web.overlay.GamepadLayoutStore
import com.acecore.app.runtime.web.overlay.PanelBg
import com.acecore.app.runtime.web.overlay.VirtualGamepad
import org.json.JSONObject
import java.io.File

/** What the library passes to [WindowsGameActivity]. */
data class WindowsLaunchSpec(
    val gameId: Long,
    val title: String,
    /** The Windows program, as a path on the device. */
    val exe: String,
    val helperDir: String,
    val buttonMap: String,
    val accentArgb: Long,
    val gamepadMode: String = "auto",
    val opacity: Int = 55,
    val scale: Int = 100,
    val haptics: Boolean = true,
) {
    fun toJson(): String = JSONObject().put("gameId", gameId).put("title", title).put("exe", exe).put("helperDir", helperDir)
        .put("buttonMap", buttonMap).put("accent", accentArgb).put("gamepadMode", gamepadMode).put("opacity", opacity)
        .put("scale", scale).put("haptics", haptics).toString()

    companion object {
        const val EXTRA = "com.acecore.app.extra.WINDOWS_SPEC"
        fun fromJson(s: String): WindowsLaunchSpec = JSONObject(s).let {
            WindowsLaunchSpec(
                it.getLong("gameId"), it.getString("title"), it.getString("exe"), it.getString("helperDir"),
                it.optString("buttonMap"), it.optLong("accent", 0xFFFF2A2AL), it.optString("gamepadMode", "auto"),
                it.optInt("opacity", 55), it.optInt("scale", 100), it.optBoolean("haptics", true),
            )
        }

        fun intent(context: Context, spec: WindowsLaunchSpec): Intent =
            Intent(context, WindowsGameActivity::class.java).putExtra(EXTRA, spec.toJson())
    }
}

/**
 * Runs a Windows program with the Windows runtime (see [WindowsRuntime]) in its own process (":windows"). The
 * program's screen comes from the runtime's VNC display and is drawn here, scaled to fit; touches become mouse
 * clicks, the on-screen controller and real controllers/keyboards become keyboard keys, and sound is streamed in.
 */
class WindowsGameActivity : ComponentActivity() {
    private lateinit var spec: WindowsLaunchSpec
    private lateinit var helperDir: File
    private var buttonMap: Map<PadButton, String> = ButtonMap.WINDOWS_DEFAULT
    private lateinit var screen: ScreenView
    private var process: Process? = null
    private var rfb: RfbClient? = null
    private val audio = PcmAudio()
    @Volatile private var finishing = false
    @Volatile private var frames = 0L
    /** When run.sh started the game's program (0 = not yet). */
    @Volatile private var gameStartedAt = 0L

    private var stage by mutableStateOf("Starting…")
    private var detail by mutableStateOf<String?>(null)
    private var installProgress by mutableStateOf<Float?>(null)
    private var failure by mutableStateOf<String?>(null)
    private var menuOpen by mutableStateOf(false)
    private var padVisible by mutableStateOf(true)
    private var showing by mutableStateOf(false)
    private var logView by mutableStateOf<String?>(null)

    private val controller = ControllerInput(onButton = ::sendPad, onMenu = { menuOpen = !menuOpen })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = intent?.getStringExtra(WindowsLaunchSpec.EXTRA)?.let { runCatching { WindowsLaunchSpec.fromJson(it) }.getOrNull() }
        if (s == null) { finish(); return }
        spec = s
        helperDir = File(s.helperDir).apply { mkdirs() }
        LaunchTimeline.mark(helperDir, "Game process started (Windows runtime)")
        buttonMap = ButtonMap.decode(s.buttonMap.ifBlank { null }, ButtonMap.WINDOWS_DEFAULT)
        padVisible = when (s.gamepadMode) {
            "never" -> false
            "always" -> true
            else -> !ControllerInput.controllerConnected()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        screen = ScreenView(this)
        val root = FrameLayout(this).apply { setBackgroundColor(AColor.BLACK) }
        root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ComposeView(this).apply {
            isFocusable = false
            setContent { Overlay() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Back opens AceArcade's menu (Exit is there), so a stray swipe doesn't end the game.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { menuOpen = !menuOpen }
        })
        Thread(::startRuntime, "windows-start").start()
    }

    // ------------------------------------------------------------------ runtime lifecycle

    private fun startRuntime() {
        try {
            if (!WindowsRuntime.installed(this)) {
                stage = "Preparing the Windows runtime"
                detail = "One time only: unpacking Wine and Box64 into AceArcade's storage."
                LaunchTimeline.mark(helperDir, "Unpacking the Windows runtime")
                WindowsRuntime.install(this) { done, total -> if (total > 0) installProgress = (done.toFloat() / total).coerceIn(0f, 1f) }
                installProgress = null
                LaunchTimeline.mark(helperDir, "Windows runtime unpacked")
            }
            val exe = File(spec.exe)
            if (!exe.isFile) throw IllegalStateException("The program ${exe.absolutePath} isn't there any more.")
            WindowsRuntime.status(this).let { it.deleteRecursively(); it.mkdirs() }
            WindowsRuntime.writeEnv(this, emptyMap())
            stage = "Starting Windows"
            detail = null
            val p = WindowsRuntime.processBuilder(this, exe, DISPLAY_W, DISPLAY_H).start()
            process = p
            LaunchTimeline.mark(helperDir, "Runtime started (Wine + Box64 under PRoot)")
            Thread({ pumpLog(p) }, "windows-log").start()
            Thread({ runCatching { audio.run() } }, "windows-audio").start()
            Thread(::watchStatus, "windows-status").start()
            Thread(::runDisplay, "windows-display").start()
            val rc = p.waitFor()
            LaunchTimeline.mark(helperDir, "Runtime ended (exit code $rc)")
            if (!finishing) onRuntimeEnded(rc)
        } catch (e: Throwable) {
            Log.e(TAG, "Windows runtime failed", e)
            val m = "The Windows runtime couldn't start: ${e.message ?: e.javaClass.simpleName}"
            saveReport(m + "\n" + Log.getStackTraceString(e), -1)
            fail(m)
        }
    }

    private fun runDisplay() {
        val client = RfbClient(5900, onFrame = {
            frames++
            if (frames == 1L) {
                showing = true
                LaunchTimeline.mark(helperDir, "First frame drawn")
            }
            screen.frameArrived()
        }, onResize = { w, h -> screen.post { screen.resizeFrame(w, h) } })
        rfb = client
        screen.client = client
        try {
            client.run(timeoutMs = 120_000)
        } catch (e: Exception) {
            if (!finishing && process?.isAlive == true) Log.w(TAG, "Display connection ended: ${e.message}")
        }
    }

    /** Status written by the runtime's run.sh (stage) and its window watcher (game window position). */
    private fun watchStatus() {
        val dir = WindowsRuntime.status(this)
        var lastStage = ""
        while (!finishing && process?.isAlive == true) {
            val st = runCatching { File(dir, "stage").readText().trim() }.getOrDefault("")
            if (st != lastStage) {
                lastStage = st
                when {
                    st == "display" -> stage = "Starting Windows"
                    st == "prefix" -> {
                        stage = "Setting up Windows"
                        detail = "First launch only: Wine creates its Windows folder. This takes a few minutes on a phone."
                        LaunchTimeline.mark(helperDir, "Creating the Windows folder (first launch)")
                    }
                    st == "game" -> {
                        stage = "Starting ${spec.title}"
                        detail = "Translating the game's PC code for this phone; the first start of a game is the slowest."
                        gameStartedAt = System.currentTimeMillis()
                        LaunchTimeline.mark(helperDir, "Windows program started")
                    }
                    st.startsWith("failed") -> st.removePrefix("failed:").trim().let { m -> saveReport(m, -1); fail(m) }
                }
            }
            val win = runCatching { File(dir, "window").readText().trim().split(' ').map { it.toInt() } }.getOrNull()
            if (win != null && win.size == 4 && win[2] > 32 && win[3] > 32) {
                screen.setCrop(Rect(win[0], win[1], win[0] + win[2], win[1] + win[3]))
                if (gameStartedAt > 0 && !File(helperDir, "ready").exists()) {
                    runCatching { File(helperDir, "ready").writeText(System.currentTimeMillis().toString()) }
                    LaunchTimeline.mark(helperDir, "Game window shown")
                }
            }
            Thread.sleep(500)
        }
    }

    private fun pumpLog(p: Process) {
        val log = File(helperDir, "windows.log")
        runCatching {
            log.writeText("")
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (log.length() < 2_000_000) log.appendText(line + "\n")
                }
            }
        }
    }

    private fun onRuntimeEnded(rc: Int) {
        val dir = WindowsRuntime.status(this)
        val exitFile = runCatching { File(dir, "exit").readText().trim().toInt() }.getOrNull()
        val gameShown = File(helperDir, "ready").exists()
        val ranFor = if (gameStartedAt > 0) (System.currentTimeMillis() - gameStartedAt) / 1000 else -1
        // A game that played for a while and ended normally (exit code 0) returns to the library. Anything else is
        // reported with the logs: a failure code, or closing by itself within a minute of starting (the first window
        // can be Wine's own "updating configuration" dialog, so a window alone doesn't mean the game ran).
        if (exitFile == 0 && (ranFor < 0 || ranFor >= 60)) {
            runOnUiThread { exitGame() }
            return
        }
        val message = when {
            !gameShown -> "The Windows program closed before its window appeared (exit code ${exitFile ?: rc})."
            exitFile == 0 -> "The Windows program closed by itself ${ranFor}s after starting (exit code 0)."
            else -> "The Windows program stopped with exit code ${exitFile ?: rc}" + (if (ranFor >= 0) " after ${ranFor}s." else ".")
        }
        // On screen: the end of each log without Box64's start-up banner (the cause is usually in the last lines).
        val shortLogs = logFiles().mapNotNull { (n, f) ->
            val lines = f.readLines().filterNot { it.startsWith("[BOX64]") || it.startsWith(" BOX64_") }.takeLast(30)
            if (lines.isEmpty()) null else "== $n ==\n" + lines.joinToString("\n")
        }
        val report = saveReport(message, exitFile ?: rc)
        fail(message + (report?.let { "\nFull logs saved in Settings › Advanced › Crash reports." } ?: ""), shortLogs.joinToString("\n\n"))
    }

    /** The runtime's logs, the most telling first. */
    private fun logFiles(): List<Pair<String, File>> {
        val dir = WindowsRuntime.status(this)
        return (listOf("wine.log", "wineboot.log", "xvnc.log", "pulse.log").map { it to File(dir, it) } +
            listOf("runtime (PRoot)" to File(helperDir, "windows.log")))
            .filter { it.second.isFile && it.second.length() > 0 }
    }

    /** A crash report with everything needed to find the cause (shown in Settings › Advanced › Crash reports). */
    private fun saveReport(message: String, code: Int): File? = runCatching {
        val cr = com.acecore.app.runtime.CrashReports
        val text = buildString {
            append(cr.header(spec.title, "Native Windows program", runCatching { File(WindowsRuntime.status(this@WindowsGameActivity), "stage").readText().trim() }.getOrDefault("?")))
            appendLine("Runtime: Windows runtime · ${WindowsRuntime.bundledVersion(this@WindowsGameActivity)?.lines()?.take(3)?.joinToString(" · ")}")
            appendLine("Program: ${spec.exe}")
            appendLine("Cause: $message (exit code $code)")
            appendLine()
            File(helperDir, "timeline").takeIf { it.isFile }?.let { appendLine("== Launch timeline =="); appendLine(it.readText()) }
            for ((n, f) in logFiles()) {
                appendLine("== $n ==")
                f.readLines().takeLast(300).forEach { appendLine(it) }
                appendLine()
            }
        }
        cr.save(this, spec.gameId, cr.redact(text))
    }.getOrNull()

    private fun fail(message: String, logs: String? = null) {
        if (finishing) return
        failure = message
        detail = logs
        runCatching { File(helperDir, "windows-error").writeText(message) }
    }

    private fun stopRuntime() {
        finishing = true
        rfb?.close()
        audio.close()
        process?.let { p ->
            runCatching { p.destroy() }
            Thread { runCatching { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } }.start()
        }
    }

    private fun exitGame() {
        stopRuntime()
        finish()
        // The runtime's processes end with PRoot (--kill-on-exit); end this process like the other game runtimes.
        screen.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 800)
    }

    override fun onPause() { super.onPause(); audio.setPaused(true) }
    override fun onResume() { super.onResume(); audio.setPaused(false); hideSystemBars() }
    override fun onDestroy() { if (!finishing) stopRuntime(); super.onDestroy() }


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

    // ------------------------------------------------------------------ input

    private fun sendPad(button: PadButton, down: Boolean) {
        val code = WebKey.byId(buttonMap[button])?.androidKeyCode ?: return
        val sym = Keysyms.fromAndroid(code)
        Log.d("AceArcadeInput", "windows ${button.name} ${if (down) "down" else "up"} -> keysym 0x${sym.toString(16)}")
        if (sym != 0) INPUT.execute { rfb?.key(sym, down) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controller.handleKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val sym = Keysyms.fromAndroid(event.keyCode)
        if (sym != 0 && event.repeatCount == 0 && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            val down = event.action == KeyEvent.ACTION_DOWN
            INPUT.execute { rfb?.key(sym, down) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controller.handleMotion(event) || super.dispatchGenericMotionEvent(event)

    // ------------------------------------------------------------------ overlay

    @Composable
    private fun Overlay() {
        val accent = Color(spec.accentArgb.toInt())
        val store = remember { GamepadLayoutStore(this@WindowsGameActivity) }
        Box(Modifier.fillMaxSize()) {
            if (!showing || failure != null) StatusPanel(accent)
            if (padVisible && !menuOpen && failure == null) {
                VirtualGamepad(
                    store = store, opacity = spec.opacity / 100f, scale = spec.scale / 100f, haptics = spec.haptics, accent = accent,
                    editing = false, onEditDone = {}, onButton = ::sendPad, onMenu = { menuOpen = true },
                )
            } else if (!menuOpen && failure == null) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).background(PanelBg, RoundedCornerShape(50))
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50)).clickable { menuOpen = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp)) { Text("☰", color = Color.White, fontSize = 16.sp) }
            }
            logView?.let { text ->
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { logView = null }.padding(24.dp)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text("Windows log (tap to close)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (menuOpen) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { menuOpen = false }) {
                    Column(
                        Modifier.align(Alignment.Center).widthIn(max = 420.dp).fillMaxWidth().padding(24.dp)
                            .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(spec.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Windows runtime (Wine + Box64) · experimental", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        MenuRow("Resume", accent) { menuOpen = false }
                        MenuRow(if (padVisible) "Hide on-screen controls" else "Show on-screen controls", accent) { padVisible = !padVisible; menuOpen = false }
                        MenuRow("Right-click mode: ${if (screen.rightClick) "on" else "off"}", accent) { screen.rightClick = !screen.rightClick; menuOpen = false }
                        MenuRow("Show Windows log", accent) {
                            menuOpen = false
                            logView = logFiles().joinToString("\n\n") { (n, f) ->
                                "== $n ==\n" + f.readLines().filterNot { it.startsWith("[BOX64]") || it.startsWith(" BOX64_") }.takeLast(40).joinToString("\n")
                            }.ifBlank { "Nothing logged yet." }
                        }
                        MenuRow("Exit to library", accent) { exitGame() }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusPanel(accent: Color) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (failure != null) 0.85f else 0.6f))) {
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)
                    .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(spec.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                val f = failure
                if (f == null) {
                    Text(stage, color = Color.White, fontSize = 15.sp)
                    installProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = accent) }
                        ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accent)
                    detail?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
                } else {
                    Text(f, color = Color(0xFFFF8A80), fontSize = 15.sp)
                    detail?.let {
                        Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    MenuRow("Close", accent) { exitGame() }
                }
                if (f == null) MenuRow("Cancel", accent) { exitGame() }
            }
        }
    }

    @Composable
    private fun MenuRow(label: String, accent: Color, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)) { Text(label, color = Color.White, fontSize = 15.sp) }
    }

    /**
     * Draws the runtime's screen: the game's window (when known) or the whole display, scaled to fit with its aspect
     * ratio kept. Touch = left mouse button at that point (right button in right-click mode).
     */
    private class ScreenView(context: Context) : View(context) {
        @Volatile var client: RfbClient? = null
        @Volatile var rightClick = false
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var bitmap: Bitmap? = null
        private val bitmapLock = Any()
        @Volatile private var crop: Rect? = null
        private val dst = RectF()
        private var fbW = 0
        private var fbH = 0

        fun resizeFrame(w: Int, h: Int) { fbW = w; fbH = h; crop = null }

        fun setCrop(r: Rect) { if (r != crop) crop = r }

        private fun area(): Rect {
            val c = client ?: return Rect(0, 0, 1, 1)
            val full = Rect(0, 0, c.width, c.height)
            val r = crop ?: return full
            val clipped = Rect(r)
            return if (clipped.intersect(full) && clipped.width() > 32 && clipped.height() > 32) clipped else full
        }

        /** Copies the visible area out of the frame buffer (reader thread) and schedules a redraw. */
        fun frameArrived() {
            val c = client ?: return
            val a = area()
            synchronized(bitmapLock) {
                var b = bitmap
                if (b == null || b.width != a.width() || b.height != a.height()) {
                    b = Bitmap.createBitmap(a.width(), a.height(), Bitmap.Config.ARGB_8888)
                    bitmap = b
                }
                synchronized(c.lock) {
                    if (a.right <= c.width && a.bottom <= c.height) b.setPixels(c.pixels, a.top * c.width + a.left, c.width, 0, 0, a.width(), a.height())
                }
            }
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            synchronized(bitmapLock) {
                val b = bitmap ?: return
                val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
                val w = b.width * s; val h = b.height * s
                dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                canvas.drawBitmap(b, null, dst, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val c = client ?: return true
            if (dst.width() <= 0f) return true
            val a = area()
            val x = a.left + ((e.x - dst.left) / dst.width() * a.width()).toInt()
            val y = a.top + ((e.y - dst.top) / dst.height() * a.height()).toInt()
            val button = if (rightClick || e.pointerCount > 1) 4 else 1
            val action = e.actionMasked
            val mask = when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> button
                else -> 0
            }
            // Move first without a button, so a tap clicks where the finger is (not where the mouse was).
            INPUT.execute { if (action == MotionEvent.ACTION_DOWN) c.pointer(x, y, 0); c.pointer(x, y, mask) }
            return true
        }
    }

    companion object {
        private const val TAG = "AceArcadeWindows"
        /** Size of the runtime's virtual display; the game's window inside it is what's shown. */
        const val DISPLAY_W = 1600
        const val DISPLAY_H = 1200
        /** Input events go to the display connection in order, off the UI thread. */
        private val INPUT = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
