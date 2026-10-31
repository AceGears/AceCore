package com.acecore.app.feature.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.datastore.SettingsRepository
import com.acecore.app.core.designsystem.accentArgbFor
import com.acecore.app.core.filesystem.SafTree
import com.acecore.app.core.install.GameInstaller
import com.acecore.app.core.install.GameLocations
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.install.InstallProgress
import com.acecore.app.core.install.SafInstallSource
import com.acecore.app.core.model.CompatStatus
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.core.storage.DirectStorage
import com.acecore.app.feature.cheats.CheatStore
import com.acecore.app.runtime.RuntimeId
import com.acecore.app.runtime.RuntimeRegistry
import com.acecore.app.runtime.SessionRecord
import com.acecore.app.runtime.rgss.RgssActivity
import com.acecore.app.runtime.rgss.RgssLaunchSpec
import com.acecore.app.runtime.rgss.RgssRuntime
import com.acecore.app.runtime.web.GameActivity
import com.acecore.app.runtime.web.GameLaunchSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LaunchPreparation {
    val gameId: Long

    /** The runtime can start right away. */
    data class Ready(val intent: Intent, override val gameId: Long, val title: String) : LaunchPreparation

    /** Needs a real preparation step first: making AceCore's private copy (copy mode only). */
    data class Staged(
        override val gameId: Long,
        val title: String,
        val firstCopy: Boolean,
        val prepareIntent: suspend (onProgress: (InstallProgress) -> Unit, isCancelled: () -> Boolean) -> Intent,
    ) : LaunchPreparation

    /**
     * Direct mode is selected, but AceCore can't read the folder by path yet. The user chooses: allow file access
     * (recommended, nothing is copied) or play from a private copy.
     */
    data class NeedsFileAccess(
        override val gameId: Long,
        val title: String,
        /** False when the folder's storage provider has no real path (e.g. a cloud provider): only a copy works. */
        val directPossible: Boolean,
    ) : LaunchPreparation

    data class Blocked(
        override val gameId: Long,
        val title: String,
        val message: String,
        val canRescan: Boolean = false,
        val fix: Fix = Fix.NONE,
    ) : LaunchPreparation

    enum class Fix { NONE, CHOOSE_LAUNCH_FILE }
}

/** How the user asked to launch (normally just a tap; the options come from the file-access dialog or details). */
data class LaunchOptions(
    val forceCopy: Boolean = false,
    val forceResync: Boolean = false,
    /** Diagnostic retry: start the game without AceCore's runtime helper (no stage tracking or cheats). */
    val disableHelper: Boolean = false,
)

/**
 * Validates a game before launch and builds the Intent for its isolated runtime process.
 *
 * Fast path: everything needed (engine, entry, settings) is already in the library database, so a launch does no
 * folder scanning. RGSS games run directly from the user's folder when "All files access" is granted; otherwise
 * from AceCore's private copy, which is only re-synchronised when the game was rescanned or the user asks.
 */
class LaunchCoordinator(
    private val context: Context,
    private val games: GameRepository,
    private val settings: SettingsRepository,
) {
    private val cheats = CheatStore(context)

    suspend fun prepare(game: GameEntity, options: LaunchOptions = LaunchOptions()): LaunchPreparation {
        val requestedAt = System.currentTimeMillis()
        tapElapsed = com.acecore.app.runtime.LaunchTimeline.now()
        val result = runCatching { prepareInternal(game, options, requestedAt) }.getOrElse { e ->
            LaunchPreparation.Blocked(game.id, "Couldn't start ${game.title}", e.message ?: e.toString())
        }
        // A question to the user (file access) isn't a launch attempt yet; the retry after answering is.
        if (result !is LaunchPreparation.NeedsFileAccess) games.recordAttempt(game.id)
        if (result is LaunchPreparation.Blocked) games.recordBlocked(game.id, result.title + ": " + result.message)
        return result
    }

    /** The private-copy step failed in the UI (after [prepare] returned Staged): count it as a failed launch. */
    suspend fun recordPreparationFailure(gameId: Long, message: String) = games.recordBlocked(gameId, message)

    /** Remembers "Private copy" for this game, so the file-access question isn't asked again. */
    suspend fun rememberCopyChoice(gameId: Long) = games.setOverride(gameId, ConfigKey.GAME_FILES.id, "copy")

    private suspend fun prepareInternal(game: GameEntity, options: LaunchOptions, requestedAt: Long): LaunchPreparation {
        val engine = game.effectiveEngine
        val runtime = RuntimeRegistry.info(engine)
        if (!runtime.bundled) return LaunchPreparation.Blocked(
            game.id, "${engine.displayName} isn't playable yet",
            buildString {
                append(runtime.status)
                runtime.blocker?.let { append("\n\n").append(it) }
            },
        )
        if (game.compatStatus == CompatStatus.PACKED) return LaunchPreparation.Blocked(
            game.id, "Game files are packed", "This build keeps its game inside an archive. Extract it, then rescan the game.", canRescan = true,
        )
        val entry = game.entry ?: return LaunchPreparation.Blocked(
            game.id, "No launch file", "AceArcade couldn't find a file to start this game with. Choose a launch file or rescan the game.",
            canRescan = true, fix = LaunchPreparation.Fix.CHOOSE_LAUNCH_FILE,
        )
        val s = settings.current()
        val overrides = if (engine.family == EngineFamily.RGSS) importDisplayChanges(game) else games.overrides(game)
        val config = LayeredConfig(s.globalConfig, s.engineConfig[engine].orEmpty(), overrides, engine).snapshot()
        val accent = accentArgbFor(s.theme)
        if (engine.family == EngineFamily.RGSS) {
            val cfg = if (options.disableHelper) config + (ConfigKey.RGSS_HELPER.id to "false") else config
            return prepareRgss(game, cfg, accent, options, requestedAt)
        }
        if (engine.family == EngineFamily.GODOT) return prepareGodot(game, config, accent, requestedAt)
        if (engine.family == EngineFamily.EASYRPG) return prepareEasyRpg(game, config, accent, requestedAt)

        if (!games.hasAccess(game.treeUri)) return folderAccessLost(game)
        // The selected entry must still exist (one query per path segment, not a folder scan).
        val exists = withContext(Dispatchers.IO) {
            runCatching { SafTree(context.contentResolver, Uri.parse(game.treeUri)).findPath(game.rootDocId, entry) != null }.getOrDefault(true)
        }
        if (!exists) return missingLaunchFile(game, entry)
        val spec = GameLaunchSpec(
            gameId = game.id, title = game.title, engine = engine.name, treeUri = game.treeUri, rootDocId = game.rootDocId,
            contentRoot = game.contentRoot, entry = entry, config = config, webDebugging = s.webDebugging,
            accentArgb = accent, launchRequestedAt = requestedAt,
        )
        startSession(game.id, requestedAt)
        games.markLaunched(game.id)
        return LaunchPreparation.Ready(Intent(context, GameActivity::class.java).putExtra(GameLaunchSpec.EXTRA, spec.toJson()), game.id, game.title)
    }

    private fun folderAccessLost(game: GameEntity) = LaunchPreparation.Blocked(
        game.id, "Folder access lost",
        "Android no longer lets AceArcade read this game's folder (it may have been moved, deleted, or the permission was revoked). Remove the game and add its folder again.",
    )

    private fun missingLaunchFile(game: GameEntity, path: String) = LaunchPreparation.Blocked(
        game.id, "Launch file not found",
        "“$path” is no longer in ${game.folderLabel}. It may have been renamed, moved or deleted. Choose the file to start the game with.",
        fix = LaunchPreparation.Fix.CHOOSE_LAUNCH_FILE,
    )

    private suspend fun startSession(gameId: Long, requestedAt: Long) = withContext(Dispatchers.IO) {
        // Clear a stale session file from a previous crash before starting a new session.
        SessionRecord.clear(context, gameId)
        SessionRecord.writeLaunch(context, gameId, requestedAt)
    }

    // ------------------------------------------------------------------ RGSS (mkxp-z)

    private suspend fun prepareRgss(
        game: GameEntity, config: Map<String, String>, accent: Long, options: LaunchOptions, requestedAt: Long,
    ): LaunchPreparation {
        val wantsDirect = config[ConfigKey.GAME_FILES.id] != "copy" && !options.forceCopy
        // Folders whose provider has no real path (cloud providers) can only use a copy: no question needed.
        val root = if (wantsDirect) GameLocations.directRoot(context, game) else null
        if (wantsDirect && root != null) {
            if (!DirectStorage.hasAccess(context)) return LaunchPreparation.NeedsFileAccess(game.id, game.title, directPossible = true)
            val dir = withContext(Dispatchers.IO) { GameLocations.directGameDir(context, game) }
            if (dir == null || !withContext(Dispatchers.IO) { DirectStorage.canList(dir) }) return LaunchPreparation.Blocked(
                game.id, "Game folder not found",
                "AceArcade can't open ${root.absolutePath}. If you moved or renamed the game folder, use “Locate folder” on the game's page.",
            )
            val iniName = GameLocations.rgssIniName(game.entry)
            if (withContext(Dispatchers.IO) { DirectStorage.findChild(dir, iniName) } == null) {
                return missingLaunchFile(game, "${GameLocations.rgssSubdir(game.entry).let { if (it.isEmpty()) "" else "$it/" }}$iniName")
            }
            // Android 8–10 can't write to SD cards even with the storage permission; RPG Maker games save next to
            // Game.ini, so such games use the private copy instead of failing when they save.
            val writable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R || withContext(Dispatchers.IO) { dir.canWrite() }
            if (writable) return LaunchPreparation.Ready(rgssIntent(game, dir, config, accent, direct = true, requestedAt), game.id, game.title)
        }

        // Private copy.
        if (!games.hasAccess(game.treeUri)) return folderAccessLost(game)
        val installDir = GameStorage.installDir(context, game.id)
        val installer = GameInstaller(installDir)
        val copyDir = GameLocations.copyGameDir(context, game.id, game.entry)
        val upToDate = withContext(Dispatchers.IO) { installer.isInstalled() && !installer.needsResync() }
        if (upToDate && !options.forceResync) {
            if (withContext(Dispatchers.IO) { DirectStorage.findChild(copyDir, GameLocations.rgssIniName(game.entry)) } == null) {
                return missingLaunchFile(game, GameLocations.rgssIniName(game.entry))
            }
            return LaunchPreparation.Ready(rgssIntent(game, copyDir, config, accent, direct = false, requestedAt), game.id, game.title)
        }
        val firstCopy = !withContext(Dispatchers.IO) { installer.isInstalled() }
        return LaunchPreparation.Staged(game.id, game.title, firstCopy) { onProgress, isCancelled ->
            withContext(Dispatchers.IO) {
                installer.install(SafInstallSource(context, Uri.parse(game.treeUri), game.rootDocId), isCancelled, onProgress)
            }
            rgssIntent(game, copyDir, config, accent, direct = false, requestedAt)
        }
    }

    // ------------------------------------------------------------------ Godot 4

    /** Godot games always run straight from the user's folder (the engine reads the pack with normal file access). */
    private suspend fun prepareGodot(game: GameEntity, config: Map<String, String>, accent: Long, requestedAt: Long): LaunchPreparation {
        val root = GameLocations.directRoot(context, game) ?: return LaunchPreparation.Blocked(
            game.id, "Folder can't be opened directly",
            "Godot games run straight from your folder, and this folder's storage (for example a cloud drive) has no file path " +
                "Android can open directly. Move the game to your device storage or SD card and add it again.",
        )
        if (!DirectStorage.hasAccess(context)) return LaunchPreparation.NeedsFileAccess(game.id, game.title, directPossible = true)
        val sub = game.entry?.replace('\\', '/')?.trim('/')?.substringBeforeLast('/', "").orEmpty()
        val dir = withContext(Dispatchers.IO) { if (sub.isEmpty()) root else DirectStorage.resolveRelative(root, sub) }
        if (dir == null || !withContext(Dispatchers.IO) { DirectStorage.canList(dir) }) return LaunchPreparation.Blocked(
            game.id, "Game folder not found",
            "AceArcade can't open ${root.absolutePath}. If you moved or renamed the game folder, use “Locate folder” on the game's page.",
        )
        val entryName = game.entry?.replace('\\', '/')?.substringAfterLast('/')
        return when (val check = withContext(Dispatchers.IO) { com.acecore.app.runtime.godot.GodotPack.check(dir, entryName) }) {
            is com.acecore.app.runtime.godot.GodotPack.Check.Unsupported -> LaunchPreparation.Blocked(game.id, check.title, check.message)
            is com.acecore.app.runtime.godot.GodotPack.Check.Ok -> {
                val intent = withContext(Dispatchers.IO) {
                    val dataDir = GameStorage.dataDir(context, game.id)
                    val helperDir = RgssRuntime.helperDir(dataDir).apply { mkdirs() }
                    listOf("ready", "timeline", "dpad", "godot.log").forEach { File(helperDir, it).delete() }
                    com.acecore.app.runtime.LaunchTimeline.mark(helperDir, "Play tapped", tapElapsed.takeIf { it > 0 } ?: com.acecore.app.runtime.LaunchTimeline.now())
                    com.acecore.app.runtime.LaunchTimeline.mark(helperDir,
                        "Launcher ready (Godot ${check.pack.version} pack" + (if (check.pack.embedded) " inside ${check.pack.file.name}" else "") + ")")
                    check.warnings.forEach { com.acecore.app.runtime.LaunchTimeline.mark(helperDir, "Note: $it") }
                    val spec = com.acecore.app.runtime.godot.GodotLaunchSpec(
                        gameId = game.id, title = game.title, helperDir = helperDir.absolutePath,
                        buttonMap = config[ConfigKey.BUTTON_MAP.id].orEmpty(), accentArgb = accent,
                        gamepadMode = config[ConfigKey.GAMEPAD_MODE.id] ?: "auto",
                        opacity = ConfigKey.GAMEPAD_OPACITY.int(config[ConfigKey.GAMEPAD_OPACITY.id] ?: "55"),
                        scale = ConfigKey.GAMEPAD_SCALE.int(config[ConfigKey.GAMEPAD_SCALE.id] ?: "100"),
                        haptics = ConfigKey.HAPTICS.bool(config[ConfigKey.HAPTICS.id] ?: "true"),
                    )
                    com.acecore.app.runtime.godot.GodotLaunchSpec.intent(context, spec, check.pack.file, File(helperDir, "godot.log"),
                        config[ConfigKey.GODOT_RENDERER.id] ?: "auto")
                }
                startSession(game.id, requestedAt)
                games.markLaunched(game.id)
                LaunchPreparation.Ready(intent, game.id, game.title)
            }
        }
    }

    // ------------------------------------------------------------------ EasyRPG (RPG Maker 2000/2003)

    /**
     * Runs from the user's folder: by real path when AceArcade has file access, otherwise through Android's document
     * access (EasyRPG reads content:// folders itself). Never copies the game.
     */
    private suspend fun prepareEasyRpg(game: GameEntity, config: Map<String, String>, accent: Long, requestedAt: Long): LaunchPreparation {
        val sub = game.entry?.replace('\\', '/')?.trim('/')?.substringBeforeLast('/', "").orEmpty()
        val realRoot = GameLocations.directRoot(context, game)?.takeIf { DirectStorage.hasAccess(context) }
        val project: String = withContext(Dispatchers.IO) {
            val realDir = realRoot?.let { if (sub.isEmpty()) it else DirectStorage.resolveRelative(it, sub) }?.takeIf { DirectStorage.canList(it) }
            realDir?.absolutePath ?: run {
                if (!games.hasAccess(game.treeUri)) return@run null
                val tree = SafTree(context.contentResolver, Uri.parse(game.treeUri))
                val docId = if (sub.isEmpty()) game.rootDocId else tree.findPath(game.rootDocId, sub)?.docId
                docId?.let { DirectStorage.documentUri(Uri.parse(game.treeUri), it).toString() }
            }
        } ?: return folderAccessLost(game)
        val intent = withContext(Dispatchers.IO) {
            val dataDir = GameStorage.dataDir(context, game.id)
            val helperDir = RgssRuntime.helperDir(dataDir).apply { mkdirs() }
            listOf("ready", "timeline", "dpad", "easyrpg.log").forEach { File(helperDir, it).delete() }
            com.acecore.app.runtime.LaunchTimeline.mark(helperDir, "Play tapped", tapElapsed.takeIf { it > 0 } ?: com.acecore.app.runtime.LaunchTimeline.now())
            com.acecore.app.runtime.LaunchTimeline.mark(helperDir,
                "Launcher ready (" + (if (project.startsWith("content://")) "document access" else "direct from your folder") + ")")
            val configDir = File(dataDir, "easyrpg").apply { mkdirs() }
            val log = File(helperDir, "easyrpg.log")
            val spec = org.easyrpg.player.player.EasyRpgLaunchSpec(
                gameId = game.id, title = game.title, helperDir = helperDir.absolutePath, logFile = log.absolutePath,
                arguments = org.easyrpg.player.player.EasyRpgLaunchSpec.arguments(project, configDir, log),
                buttonMap = config[ConfigKey.BUTTON_MAP.id].orEmpty(), accentArgb = accent,
                gamepadMode = config[ConfigKey.GAMEPAD_MODE.id] ?: "auto",
                opacity = ConfigKey.GAMEPAD_OPACITY.int(config[ConfigKey.GAMEPAD_OPACITY.id] ?: "55"),
                scale = ConfigKey.GAMEPAD_SCALE.int(config[ConfigKey.GAMEPAD_SCALE.id] ?: "100"),
                haptics = ConfigKey.HAPTICS.bool(config[ConfigKey.HAPTICS.id] ?: "true"),
            )
            org.easyrpg.player.player.EasyRpgLaunchSpec.intent(context, spec)
        }
        startSession(game.id, requestedAt)
        games.markLaunched(game.id)
        return LaunchPreparation.Ready(intent, game.id, game.title)
    }

    /** When Play was tapped (elapsedRealtime), for the launch timeline. */
    private var tapElapsed = 0L

    private suspend fun rgssIntent(
        game: GameEntity, gameDir: File, config: Map<String, String>, accent: Long, direct: Boolean, requestedAt: Long,
    ): Intent {
        val helper = config[ConfigKey.RGSS_HELPER.id] != "false"
        val cheatConfig = withContext(Dispatchers.IO) { cheats.load(game.id) }
        val cheatsOn = helper && cheatConfig.enabled
        val intent = withContext(Dispatchers.IO) {
            // Ruby can only start once per process: make sure no previous RGSS game process is still alive.
            RgssRuntime.killStaleProcess(context)
            val dataDir = GameStorage.dataDir(context, game.id)
            RgssRuntime.writeLaunchFiles(
                context, dataDir, game.effectiveEngine, config,
                helper = helper, cheatsEnabled = cheatsOn, cheatFlags = if (cheatsOn) cheatConfig.flags else emptyMap(),
                launchRequestedAt = requestedAt,
            )
            val helperDir = RgssRuntime.helperDir(dataDir)
            com.acecore.app.runtime.LaunchTimeline.mark(helperDir, "Play tapped", tapElapsed.takeIf { it > 0 } ?: com.acecore.app.runtime.LaunchTimeline.now())
            com.acecore.app.runtime.LaunchTimeline.mark(helperDir, if (direct) "Launcher ready (direct from your folder)" else "Launcher ready (private copy up to date)")
            val spec = RgssLaunchSpec(
                gameId = game.id, title = game.title, engine = game.effectiveEngine.name,
                gamePath = gameDir.absolutePath, dataPath = dataDir.absolutePath, config = config, accentArgb = accent,
                direct = direct, helper = helper, cheatsEnabled = cheatsOn, launchRequestedAt = requestedAt,
                execName = GameLocations.rgssExecName(game.entry),
            )
            Intent(context, RgssActivity::class.java).putExtra(RgssLaunchSpec.EXTRA, spec.toJson())
        }
        startSession(game.id, requestedAt)
        games.markLaunched(game.id) // only once the game is really about to start
        return intent
    }

    /** The game's own mkxp.json (direct folder or private copy), for crash reports; null when it has none. */
    private fun gameMkxpJson(game: GameEntity): String? = runCatching {
        val dir = GameLocations.directGameDir(context, game) ?: GameLocations.copyGameDir(context, game.id, game.entry)
        com.acecore.app.core.storage.DirectStorage.findChild(dir, "mkxp.json")?.takeIf { it.length() in 1..64_000 }?.readText()?.take(8_000)
    }.getOrNull()

    // ------------------------------------------------------------------ results

    /**
     * Collects play time (also after a game-process crash), whether the game really ran, how long it took to
     * start, and any error, and stores them as a session.
     */
    suspend fun onResult(gameId: Long, resultCode: Int, data: Intent?): com.acecore.app.runtime.LaunchFailure? {
        val game = games.get(gameId) ?: return null
        val rgss = game.effectiveEngine.family == EngineFamily.RGSS
        // Godot and EasyRPG report readiness through the same helper folder ("ready").
        val godot = game.effectiveEngine.family == EngineFamily.GODOT || game.effectiveEngine.family == EngineFamily.EASYRPG
        // Keep the game's settings page in step with what was chosen in-game (☰ › Display).
        if (rgss) importDisplayChanges(game)
        val record = withContext(Dispatchers.IO) { SessionRecord.read(SessionRecord.file(context, gameId)) }
        val requestedAt = withContext(Dispatchers.IO) { SessionRecord.readLaunch(context, gameId) }
        val helperDir = RgssRuntime.helperDir(GameStorage.dataDir(context, gameId))
        val markers = if (rgss) withContext(Dispatchers.IO) {
            Triple(File(helperDir, "boot").exists(), runCatching { File(helperDir, "stage").readText().trim() }.getOrNull(), File(helperDir, "ready").exists())
        } else null
        val (rubyReady, rubyError) = if (rgss || godot) withContext(Dispatchers.IO) {
            val ready = runCatching { File(helperDir, "ready").readText().trim().toLongOrNull() }.getOrNull()
            val err = runCatching { File(helperDir, "error").readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
            ready to err
        } else null to null
        withContext(Dispatchers.IO) { SessionRecord.clear(context, gameId) }

        val fromResult = data?.getLongExtra(GameActivity.EXTRA_SESSION_MS, -1L)?.takeIf { it >= 0 }
        val ms = maxOf(record?.ms ?: 0L, fromResult ?: 0L)
        val readyAt = if (rgss || godot) rubyReady else record?.readyAt
        val helperUsed = !rgss || File(GameStorage.dataDir(context, gameId), RgssRuntime.PRELOAD_FILE).exists()
        // Without the helper, RGSS readiness can't be observed: count a session that lasted a while as a real run.
        val reached = readyAt != null || (!helperUsed && ms >= 10_000)
        val error = when {
            resultCode == Activity.RESULT_FIRST_USER -> data?.getStringExtra(GameLaunchSpec.RESULT_ERROR) ?: "The game reported an error."
            rubyError != null -> "The game stopped with a script error: ${rubyError.lineSequence().first()}"
            !reached && godot -> "The game closed before it finished starting." + withContext(Dispatchers.IO) {
                listOf("godot.log", "easyrpg.log").firstNotNullOfOrNull { n ->
                    runCatching { File(helperDir, n).readLines().lastOrNull { it.contains("ERROR", true) || it.contains("Error:") } }.getOrNull()
                }?.let { "\n$it" }.orEmpty()
            }
            !reached && helperUsed -> "The game closed before it finished loading."
            else -> null
        }
        val latency = if (readyAt != null && requestedAt != null) (readyAt - requestedAt).takeIf { it in 0..600_000 } else null
        games.recordSession(
            gameId = gameId, startedAt = requestedAt ?: System.currentTimeMillis(), ms = ms, reached = reached,
            latencyMs = latency, error = error,
        )
        if (reached) settings.markRuntimeVerified(RuntimeRegistry.runtimeFor(game.effectiveEngine))
        if (reached || !rgss) return null
        val failure = withContext(Dispatchers.IO) { investigate(game, requestedAt ?: 0L, helperDir, helperUsed, markers, rubyError, data, resultCode) }
        // The game's own failure screen already showed this (user chose Retry or Return to library): report saved, no second dialog.
        return if (resultCode == Activity.RESULT_FIRST_USER) null else failure
    }

    /** Collects every piece of evidence about a game process that ended before reaching the game, and saves a report. */
    private fun investigate(
        game: GameEntity, since: Long, helperDir: File, helperUsed: Boolean, markers: Triple<Boolean, String?, Boolean>?,
        rubyError: String?, data: Intent?, resultCode: Int,
    ): com.acecore.app.runtime.LaunchFailure {
        val cr = com.acecore.app.runtime.CrashReports
        val stage = when {
            markers?.second != null -> "Game scripts (${markers.second})"
            markers?.first == true -> "Game scripts (Ruby running, before loading data)"
            File(helperDir, "runtime.log").let { it.exists() && it.readText().contains("SDLThread") } -> "Engine start-up (before Ruby)"
            !helperUsed -> "Unknown (game helper was off)"
            else -> "Engine start-up"
        }
        val exit = cr.lastExit(context, ":rgss", since)
        val javaCrash = runCatching { cr.javaCrashFile(helperDir).readText() }.getOrNull()
        val log = runCatching { File(helperDir, "runtime.log").readLines().takeLast(250).joinToString("\n") }.getOrNull()
        // What Ruby / mkxp-z printed (stdout+stderr), e.g. an invalid option or an exception raised before any dialog.
        val engineOutputFull = runCatching { File(helperDir, "engine_output.txt").takeIf { it.length() in 1..2_000_000 }?.readText() }.getOrNull()?.trim()?.ifEmpty { null }
        val engineCrash = engineOutputFull?.let { com.acecore.app.runtime.LaunchDiagnosis.rubyCrash(it) }
        val engineOutput = engineCrash?.second ?: engineOutputFull?.takeLast(6_000)
        val activityError = if (resultCode == Activity.RESULT_FIRST_USER) data?.getStringExtra(GameLaunchSpec.RESULT_ERROR) else null
        val (cause, identified) = when {
            rubyError != null -> "Ruby script error: ${rubyError.lineSequence().first()}" to true
            javaCrash != null -> "Java/Kotlin crash in the game process: ${javaCrash.lineSequence().first()}" to true
            exit != null && exit.reason == "Native crash" && exit.tombstone != null -> {
                val t = exit.tombstone
                val where = t.culprit?.let { f ->
                    " in " + com.acecore.app.runtime.Tombstone.libName(f.file) + (if (f.function.isNotEmpty()) " (${f.function})" else "")
                } ?: ""
                val thread = t.crashing?.name?.let { " on thread $it" } ?: ""
                val abort = t.abortMessage?.let { ": $it" } ?: ""
                "Native crash (${t.signal ?: exit.signal ?: "signal"})$where$thread$abort" to true
            }
            // Without a decodable crash record the faulting library is unknown: don't name libraries from other threads.
            exit != null && exit.reason == "Native crash" -> "Native crash (${exit.signal ?: "signal"}); crashing library not recorded" to true
            exit != null && exit.reason != "Exited normally" -> exit.reason + (exit.description?.let { ": $it" } ?: "") to true
            activityError != null -> activityError to true
            engineCrash != null -> "Ruby crashed: ${engineCrash.first.substringAfter("[BUG]").trim().take(200)}" to true
            engineOutput != null -> "The engine stopped and printed: ${engineOutput.lineSequence().filter { it.isNotBlank() }.lastOrNull()?.take(300)}" to true
            else -> "The game process ended without reporting an error. The cause hasn't been identified yet." to false
        }
        val report = cr.redact(buildString {
            append(cr.header(game.title, game.effectiveEngine.displayName, stage))
            appendLine("Cause: $cause")
            appendLine("Launch file: ${game.launchFile} -> ${game.entry}")
            appendLine("Helper: ${if (helperUsed) "on" else "off"}")
            exit?.let {
                appendLine()
                appendLine("== Android exit record ==")
                appendLine("${it.reason} · status ${it.status}${it.signal?.let { s -> " ($s)" } ?: ""}")
                it.description?.let { d -> appendLine(d) }
                it.trace?.let { t -> appendLine(); appendLine("== Crash trace ==" ); appendLine(t.take(30_000)) }
            }
            javaCrash?.let { appendLine(); appendLine("== Java/Kotlin stack =="); appendLine(it) }
            rubyError?.let { appendLine(); appendLine("== Ruby error =="); appendLine(it) }
            // AceArcade's own notes from the engine (Windows calls a game needed, scripts adapted, ...), always kept.
            engineOutputFull?.lineSequence()?.filter { it.startsWith("AceArcade:") }?.distinct()?.take(80)?.toList()
                ?.takeIf { it.isNotEmpty() }?.let { appendLine(); appendLine("== AceArcade notes =="); it.forEach(::appendLine) }
            engineOutput?.let { appendLine(); appendLine("== Engine output (stdout/stderr) =="); appendLine(it) }
            File(helperDir, "screen").takeIf { it.isFile }?.readText()?.trim()?.let { appendLine(); appendLine("Game resolution: $it") }
            // The game's own engine settings explain many start-up differences between games.
            gameMkxpJson(game)?.let { appendLine(); appendLine("== Game's mkxp.json =="); appendLine(it) }
            appendLine(); append(com.acecore.app.runtime.rgss.GameDiagnostics.summary(helperDir))
            log?.let { appendLine(); appendLine("== Game process log (last lines) =="); appendLine(it) }
        })
        val file = cr.save(context, game.id, report)
        val direct = File(GameStorage.dataDir(context, game.id), "mkxp.json").exists() &&
            runCatching { File(helperDir, "runtime.log").readText().contains("direct=true") }.getOrDefault(false)
        val diagnosis = com.acecore.app.runtime.LaunchDiagnosis.classify(
            cause, rubyError, engineCrash?.second, exit?.reason == "Native crash", helperUsed, direct)
        return com.acecore.app.runtime.LaunchFailure(game.id, game.title, stage, cause, identified, report, file, direct, helperUsed, diagnosis)
    }

    /**
     * Saves display changes made in the game's ☰ › Display panel as this game's own settings (they are written to a
     * small file while playing so they survive the game process being killed). Returns the game's overrides.
     */
    private suspend fun importDisplayChanges(game: GameEntity): Map<String, String> {
        val pending = withContext(Dispatchers.IO) {
            com.acecore.app.runtime.rgss.DisplayPrefs.takePending(GameStorage.dataDir(context, game.id))
        } ?: return games.overrides(game)
        pending.forEach { (k, v) -> games.setOverride(game.id, k, v) }
        return games.overrides(game) + pending
    }

    /** Runtime that a game needs, for UI that wants to show it. */
    fun runtimeOf(game: GameEntity): RuntimeId = RuntimeRegistry.runtimeFor(game.effectiveEngine)
}
