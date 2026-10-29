package com.acecore.app.runtime.web

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.PadButton
import com.acecore.app.core.config.WebKey
import com.acecore.app.core.filesystem.PathIndex
import com.acecore.app.core.filesystem.SafTree
import com.acecore.app.runtime.input.ControllerInput
import com.acecore.app.runtime.web.overlay.GamepadLayoutStore
import com.acecore.app.runtime.web.overlay.PauseMenu
import com.acecore.app.runtime.web.overlay.PauseState
import com.acecore.app.runtime.web.overlay.VirtualGamepad
import com.acecore.app.runtime.web.overlay.VirtualKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Hosts one web-runtime game in the isolated ":game" process. Responsibilities: build/load the asset path
 * cache, configure a locked-down WebView, translate controller/virtual input into key events, manage
 * pause/resume and audio, track playtime, and clean up on exit.
 */
class GameActivity : ComponentActivity() {

    private enum class Phase { LOADING, RUNNING, ERROR }

    private lateinit var spec: GameLaunchSpec
    private var webView by mutableStateOf<WebView?>(null)
    private var server: WebGameServer? = null
    private var index: PathIndex? = null

    private var phase by mutableStateOf(Phase.LOADING)
    private var loadingText by mutableStateOf("Preparing…")
    private var errorText by mutableStateOf("")
    private var paused by mutableStateOf(false)
    private var keyboardOpen by mutableStateOf(false)
    private var editingLayout by mutableStateOf(false)
    private var gamepadVisible by mutableStateOf(true)
    private var fpsVisible by mutableStateOf(false)
    private var lastErrors by mutableStateOf<String?>(null)

    private lateinit var buttonMap: Map<PadButton, String>
    private lateinit var controller: ControllerInput
    private lateinit var layoutStore: GamepadLayoutStore
    private var gamepadMode = "auto"
    private var pauseAudio = true

    private var resumedAt = 0L
    private var sessionMs = 0L
    private var readyAt: Long? = null
    private var exiting = false

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshGamepadVisibility()
        override fun onInputDeviceRemoved(deviceId: Int) { controller.releaseAll(); refreshGamepadVisibility() }
        override fun onInputDeviceChanged(deviceId: Int) = refreshGamepadVisibility()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Separate WebView data directory for the game process (required when WebView is used in >1 process).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { WebView.setDataDirectorySuffix("game") }
        super.onCreate(savedInstanceState)

        val raw = intent.getStringExtra(GameLaunchSpec.EXTRA)
        if (raw == null) { finishWithError("Missing launch information."); return }
        spec = runCatching { GameLaunchSpec.fromJson(raw) }.getOrElse { finishWithError("Corrupt launch information."); return }

        val cfg = spec.config
        buttonMap = ButtonMap.decode(cfg[ConfigKey.BUTTON_MAP.id])
        gamepadMode = cfg[ConfigKey.GAMEPAD_MODE.id] ?: "auto"
        pauseAudio = ConfigKey.PAUSE_AUDIO.bool(cfg[ConfigKey.PAUSE_AUDIO.id] ?: "true")
        fpsVisible = ConfigKey.SHOW_FPS.bool(cfg[ConfigKey.SHOW_FPS.id] ?: "false")
        layoutStore = GamepadLayoutStore(this)
        controller = ControllerInput(onButton = ::sendPad, onMenu = { togglePause() }).apply {
            deadzone = ConfigKey.DEADZONE.int(cfg[ConfigKey.DEADZONE.id] ?: "30") / 100f
        }
        applyWindowConfig()
        refreshGamepadVisibility()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    editingLayout -> editingLayout = false
                    keyboardOpen -> keyboardOpen = false
                    else -> togglePause()
                }
            }
        })

        setContent { Root() }
        prepare()
    }

    // ------------------------------------------------------------------ setup

    private fun applyWindowConfig() {
        val cfg = spec.config
        requestedOrientation = when (cfg[ConfigKey.ORIENTATION.id]) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "sensor" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            "system" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (ConfigKey.KEEP_SCREEN_ON.bool(cfg[ConfigKey.KEEP_SCREEN_ON.id] ?: "true")) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyImmersive()
    }

    private fun applyImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (ConfigKey.IMMERSIVE.bool(spec.config[ConfigKey.IMMERSIVE.id] ?: "true")) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun prepare(forceReindex: Boolean = false) {
        phase = Phase.LOADING
        loadingText = "Indexing game files…"
        lifecycleScope.launch {
            val tree = SafTree(contentResolver, Uri.parse(spec.treeUri))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cacheFile = File(cacheDir, "pathindex/game_${spec.gameId}.idx")
                    WebGameServer.loadOrBuildIndex(tree, spec.copy(forceReindex = forceReindex || spec.forceReindex), cacheFile) { n ->
                        runOnUiThread { loadingText = "Indexing game files… $n" }
                    }
                }
            }
            result.onSuccess { idx ->
                if (idx.lookup(spec.entry) == null) {
                    showError("The launch file “${spec.entry}” no longer exists in the game folder. Rescan the game from its details page.")
                    return@onSuccess
                }
                index = idx
                server = WebGameServer(this@GameActivity, spec, tree, idx).also { it.exitRequested = { runOnUiThread { exitGame() } } }
                startWebView()
            }.onFailure { e ->
                Log.e(TAG, "Index failed", e)
                showError(
                    if (e is SecurityException) "AceArcade no longer has permission to read this game's folder. Remove and re-add the game from the library."
                    else "Could not read the game folder: ${e.message}",
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWebView() {
        WebView.setWebContentsDebuggingEnabled(spec.webDebugging)
        val wv = webView ?: WebView(this).also { webView = it }
        wv.setBackgroundColor(android.graphics.Color.BLACK)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            offscreenPreRaster = true
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                server?.handle(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == spec.host) {
                    if (url.path == "/__acecore/exit") { exitGame(); return true }
                    return false
                }
                return true // never navigate away from the game
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (phase == Phase.LOADING) {
                    // Loading time is not play time: start counting from now.
                    if (resumedAt != 0L) resumedAt = SystemClock.elapsedRealtime()
                    phase = Phase.RUNNING
                    if (readyAt == null) readyAt = System.currentTimeMillis()
                    accumulatePlaytime() // persist the "game is running" moment right away (launch statistics)
                }
                view.requestFocus()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError("The game page failed to load: ${error.description}")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The renderer crashed or was killed for memory. Tear down this WebView only; the launcher survives.
                val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail.didCrash() else true
                destroyWebView()
                showError(if (crashed) "The game's renderer crashed. This usually means the game uses more memory than this device allows, or hit a WebView bug." else "Android stopped the game to free memory.")
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "JS: ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny() // no camera/mic for games
        }
        phase = Phase.LOADING
        loadingText = "Starting ${spec.title}…"
        wv.loadUrl(WebGameServer.startUrl(spec))
    }

    // ------------------------------------------------------------------ input

    private fun sendPad(button: PadButton, down: Boolean) {
        val key = WebKey.byId(buttonMap[button]) ?: return
        sendKey(key, down)
    }

    private fun sendKey(key: WebKey, down: Boolean) {
        if (key.id == "NONE") return
        val js = "window.AceCore&&AceCore.key(${key.keyCode},${q(key.key)},${q(key.code)},$down)"
        webView?.evaluateJavascript(js, null)
    }

    private fun q(s: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (phase == Phase.RUNNING && !paused && controller.handleKey(event)) return true
        if (paused && ControllerInput.isController(event) && event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_START || event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE)
        ) { togglePause(); return true }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (phase == Phase.RUNNING && !paused && controller.handleMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun refreshGamepadVisibility() {
        gamepadVisible = when (gamepadMode) {
            "always" -> true
            "never" -> false
            else -> !ControllerInput.controllerConnected()
        }
    }

    // ------------------------------------------------------------------ pause / lifecycle

    private fun togglePause() {
        if (phase != Phase.RUNNING) return
        paused = !paused
        if (paused) {
            controller.releaseAll()
            webView?.evaluateJavascript("window.AceCore&&AceCore.setPaused(true)", null)
            webView?.evaluateJavascript("window.AceCore?AceCore.lastErrors():''") { v ->
                lastErrors = if (v == null || v == "null") null
                else runCatching { Json.decodeFromString(JsonPrimitive.serializer(), v).content }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            webView?.onPause()
        } else {
            webView?.onResume()
            webView?.evaluateJavascript("window.AceCore&&AceCore.setPaused(false)", null)
            webView?.requestFocus()
            applyImmersive()
        }
    }

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.elapsedRealtime()
        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, null)
        refreshGamepadVisibility()
        if (!paused) {
            webView?.onResume()
            webView?.resumeTimers()
            if (pauseAudio) webView?.evaluateJavascript("window.AceCore&&AceCore.setPaused(false)", null)
        }
        applyImmersive()
    }

    override fun onPause() {
        accumulatePlaytime()
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
        if (::controller.isInitialized) controller.releaseAll()
        if (pauseAudio) webView?.evaluateJavascript("window.AceCore&&AceCore.setPaused(true)", null)
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::spec.isInitialized) applyImmersive()
    }

    private fun accumulatePlaytime() {
        if (resumedAt == 0L || !::spec.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (phase == Phase.RUNNING) sessionMs += now - resumedAt
        resumedAt = now
        // Persist so playtime survives even if this process is killed; the launcher collects it.
        com.acecore.app.runtime.SessionRecord.write(sessionFile(this, spec.gameId), sessionMs, readyAt)
    }

    private fun exitGame() {
        if (exiting) return
        exiting = true
        accumulatePlaytime()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION_MS, sessionMs))
        finish()
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        runCatching {
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.stopLoading(); wv.loadUrl("about:blank"); wv.removeAllViews(); wv.destroy()
        }
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
        // A finished game process has nothing else to do; ending it releases all game memory immediately.
        if (isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun showError(message: String) {
        errorText = message
        phase = Phase.ERROR
    }

    private fun finishWithError(message: String) {
        setResult(RESULT_FIRST_USER, Intent().putExtra(GameLaunchSpec.RESULT_ERROR, message))
        finish()
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun Root() {
        val accent = Color(spec.accentArgb.toInt())
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (phase != Phase.ERROR) {
                val wv = webView
                if (wv != null) key(wv) { AndroidView(factory = { wv }, modifier = Modifier.fillMaxSize()) }
            }
            if (phase == Phase.RUNNING && server != null) {
                if (gamepadVisible || editingLayout) {
                    val cfg = spec.config
                    VirtualGamepad(
                        store = layoutStore,
                        opacity = ConfigKey.GAMEPAD_OPACITY.int(cfg[ConfigKey.GAMEPAD_OPACITY.id] ?: "55") / 100f,
                        scale = ConfigKey.GAMEPAD_SCALE.int(cfg[ConfigKey.GAMEPAD_SCALE.id] ?: "100") / 100f,
                        haptics = ConfigKey.HAPTICS.bool(cfg[ConfigKey.HAPTICS.id] ?: "true"),
                        accent = accent,
                        editing = editingLayout,
                        onEditDone = { editingLayout = false },
                        onButton = ::sendPad,
                        onMenu = { togglePause() },
                    )
                }
                if (keyboardOpen) {
                    VirtualKeyboard(accent, ConfigKey.HAPTICS.bool(spec.config[ConfigKey.HAPTICS.id] ?: "true"), ::sendKey) { keyboardOpen = false }
                }
                if (paused) {
                    val idx = index
                    PauseMenu(
                        state = PauseState(
                            title = spec.title,
                            sessionText = "Session: ${formatDuration(sessionMs + if (resumedAt > 0) SystemClock.elapsedRealtime() - resumedAt else 0)}",
                            gamepadVisible = gamepadVisible,
                            fpsVisible = fpsVisible,
                            cacheText = if (idx != null) "${idx.size} files cached · ${idx.caseFixups} case fixes · ${idx.misses} misses" else "",
                            lastErrors = lastErrors,
                        ),
                        accent = accent,
                        onResume = { togglePause() },
                        onToggleGamepad = { gamepadMode = if (gamepadVisible) "never" else "always"; refreshGamepadVisibility() },
                        onKeyboard = { keyboardOpen = true; togglePause() },
                        onEditLayout = { editingLayout = true; togglePause() },
                        onToggleFps = {
                            fpsVisible = !fpsVisible
                            webView?.evaluateJavascript("window.AceCore&&AceCore.setFps($fpsVisible)", null)
                        },
                        onReload = { togglePause(); webView?.reload() },
                        onRebuildCache = { paused = false; webView?.onResume(); destroyWebView(); prepare(forceReindex = true) },
                        onExit = { exitGame() },
                    )
                }
            }
            if (phase == Phase.LOADING) LoadingScreen(accent)
            if (phase == Phase.ERROR) ErrorScreen(accent)
        }
    }

    @Composable
    private fun LoadingScreen(accent: Color) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(42.dp))
                Text(spec.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(loadingText, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun ErrorScreen(accent: Color) {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Couldn't run ${spec.title}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
                Text(errorText, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                Button(onClick = {
                    setResult(RESULT_FIRST_USER, Intent().putExtra(GameLaunchSpec.RESULT_ERROR, errorText).putExtra(EXTRA_SESSION_MS, sessionMs))
                    finish()
                }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accent)) { Text("Back to library") }
            }
        }
    }

    companion object {
        private const val TAG = "AceCoreGame"
        const val EXTRA_SESSION_MS = "com.acecore.app.extra.SESSION_MS"

        fun sessionFile(context: android.content.Context, gameId: Long) = com.acecore.app.runtime.SessionRecord.file(context, gameId)

        fun formatDuration(ms: Long): String {
            val m = ms / 60000
            return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
        }
    }
}
