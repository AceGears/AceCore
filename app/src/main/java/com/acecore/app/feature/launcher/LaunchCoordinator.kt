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
        val overrides = if (engine.family == EngineFamily.RGSS || engine.family == EngineFamily.WINDOWS || engine.family == EngineFamily.HANDHELD) importDisplayChanges(game)
            else games.overrides(game)
        val config = LayeredConfig(s.globalConfig, s.engineConfig[engine].orEmpty(), overrides, engine).snapshot()
        val accent = accentArgbFor(s.theme)
        if (engine.family == EngineFamily.RGSS) {
            val cfg = if (options.disableHelper) config + (ConfigKey.RGSS_HELPER.id to "false") else config
            return prepareRgss(game, cfg, accent, options, requestedAt)
        }
        if (engine.family == EngineFamily.GODOT) return prepareGodot(game, config, accent, requestedAt)
        if (engine.family == EngineFamily.EASYRPG) return prepareEasyRpg(game, config, accent, requestedAt)
        if (engine.family == EngineFamily.WINDOWS) return prepareWindows(game, config, accent, requestedAt)
        if (engine.family == EngineFamily.HANDHELD) return prepareHandheld(game, config, accent, requestedAt)

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

    // ------------------------------------------------------------------ Windows programs (Wine + Box64)

    /** Windows programs run straight from the user's folder: the runtime sees device storage at the same paths. */
    private suspend fun prepareWindows(game: GameEntity, config: Map<String, String>, accent: Long, requestedAt: Long): LaunchPreparation {
        val root = GameLocations.directRoot(context, game) ?: return LaunchPreparation.Blocked(
            game.id, "Folder can't be opened directly",
            "Windows programs run straight from your folder, and this folder's storage (for example a cloud drive) has no file " +
                "path Android can open directly. Move the game to your device storage or SD card and add it again.",
        )
        if (!DirectStorage.hasAccess(context)) return LaunchPreparation.NeedsFileAccess(game.id, game.title, directPossible = true)
        val rel = (game.launchFile?.takeIf { it.endsWith(".exe", true) } ?: game.entry)?.replace('\\', '/')?.trim('/')
            ?: return LaunchPreparation.Blocked(game.id, "No program to start",
                "AceArcade didn't find the game's Windows program. Choose it with “Change launch file”.", fix = LaunchPreparation.Fix.CHOOSE_LAUNCH_FILE)
        val exe = withContext(Dispatchers.IO) { DirectStorage.resolveRelative(root, rel) }
        if (exe == null || !exe.isFile) return LaunchPreparation.Blocked(
            game.id, "Program not found",
            "AceArcade can't find $rel in ${root.absolutePath}. If you moved or renamed the game folder, use “Locate folder” on the game's page.",
            canRescan = true,
        )
        val intent = withContext(Dispatchers.IO) {
            val dataDir = GameStorage.dataDir(context, game.id)
            val helperDir = RgssRuntime.helperDir(dataDir).apply { mkdirs() }
            listOf("ready", "timeline", "windows.log", "windows-error", "windows-failure.txt", "error").forEach { File(helperDir, it).delete() }
            com.acecore.app.runtime.LaunchTimeline.mark(helperDir, "Play tapped", tapElapsed.takeIf { it > 0 } ?: com.acecore.app.runtime.LaunchTimeline.now())
            com.acecore.app.runtime.LaunchTimeline.mark(helperDir, "Launcher ready (Windows program ${exe.name})")
            com.acecore.app.runtime.windows.WindowsLaunchSpec.intent(context, com.acecore.app.runtime.windows.WindowsLaunchSpec(
                gameId = game.id, title = game.title, exe = exe.absolutePath, helperDir = helperDir.absolutePath,
                dataDir = dataDir.absolutePath,
                display = com.acecore.app.runtime.rgss.DisplayPrefs.from(config).toJson(),
                smooth = (config[ConfigKey.FILTERING.id] ?: "smooth") != "sharp",
                showFps = ConfigKey.SHOW_FPS.bool(config[ConfigKey.SHOW_FPS.id] ?: "false"),
                cursor = ConfigKey.WINDOWS_CURSOR.bool(config[ConfigKey.WINDOWS_CURSOR.id] ?: "true"),
                screen = config[ConfigKey.WINDOWS_SCREEN.id] ?: ConfigKey.WINDOWS_SCREEN.default,
                buttonMap = config[ConfigKey.BUTTON_MAP.id].orEmpty(), accentArgb = accent,
                gamepadMode = config[ConfigKey.GAMEPAD_MODE.id] ?: "auto",
                opacity = ConfigKey.GAMEPAD_OPACITY.int(config[ConfigKey.GAMEPAD_OPACITY.id] ?: "55"),
                scale = ConfigKey.GAMEPAD_SCALE.int(config[ConfigKey.GAMEPAD_SCALE.id] ?: "100"),
                haptics = ConfigKey.HAPTICS.bool(config[ConfigKey.HAPTICS.id] ?: "true"),
            ))
        }
        startSession(game.id, requestedAt)
        games.markLaunched(game.id)
        return LaunchPreparation.Ready(intent, game.id, game.title)
    }

    // ------------------------------------------------------------------ Nintendo handhelds (mGBA, melonDS DS)

    /**
     * Handheld ROMs: opened by path straight from the user's storage when AceArcade has file access, otherwise copied
     * once into the game's data folder (rom/), refreshed only when the file's size or date changes. Zipped ROMs are
     * always unpacked there. Saves and save states live in the game's data folder (saves/); the ROM is never written.
     */
    private suspend fun prepareHandheld(game: GameEntity, config: Map<String, String>, accent: Long, requestedAt: Long): LaunchPreparation {
        val (platform, zipEntry) = com.acecore.app.core.data.HandheldRoms.romOf(games, game) ?: return LaunchPreparation.Blocked(
            game.id, "Not a ROM", "AceArcade can't tell which system this ROM is for. Rescan the game or add the ROM again.", canRescan = true,
        )
        val rom = games.decodeDetection(game)?.rom
        val direct = if (zipEntry == null) withContext(Dispatchers.IO) { com.acecore.app.core.data.HandheldRoms.directFile(context, game) } else null
        if (direct != null) return LaunchPreparation.Ready(handheldIntent(game, platform, direct, config, accent, requestedAt, "direct from your storage"), game.id, game.title)

        if (!games.hasAccess(game.treeUri)) return LaunchPreparation.Blocked(
            game.id, "ROM access lost",
            "Android no longer lets AceArcade read this ROM (it may have been moved, deleted, or the permission was revoked). Remove the game and add the ROM again.",
        )
        val docUri = com.acecore.app.core.data.HandheldRoms.documentUri(game)
        val info = withContext(Dispatchers.IO) { com.acecore.app.core.data.HandheldRoms.docInfo(context.contentResolver, docUri) }
            ?: return LaunchPreparation.Blocked(
                game.id, "ROM not found",
                "“${rom?.fileName ?: game.entry}” can't be opened any more. It may have been renamed, moved or deleted. Remove the game and add the ROM again.",
            )
        val romDir = File(GameStorage.dataDir(context, game.id), "rom")
        val name = handheldFileName(zipEntry?.substringAfterLast('/') ?: rom?.fileName ?: game.entry ?: "rom.${platform.extensions.first()}")
        val target = File(romDir, name)
        val marker = File(romDir, ".source")
        val stamp = "${info.size} ${info.lastModified} ${zipEntry.orEmpty()} $name"
        val upToDate = withContext(Dispatchers.IO) { target.isFile && runCatching { marker.readText() }.getOrNull() == stamp }
        if (upToDate) return LaunchPreparation.Ready(handheldIntent(game, platform, target, config, accent, requestedAt, "AceArcade's copy"), game.id, game.title)
        val firstCopy = !withContext(Dispatchers.IO) { target.isFile }
        return LaunchPreparation.Staged(game.id, game.title, firstCopy) { onProgress, isCancelled ->
            withContext(Dispatchers.IO) {
                romDir.mkdirs()
                romDir.listFiles()?.forEach { if (it.name != name) it.deleteRecursively() }
                marker.delete()
                val total = if (zipEntry == null) info.size else -1L
                val tmp = File(romDir, "$name.part")
                val input = com.acecore.app.core.data.HandheldRoms.openRom(context, game, zipEntry)
                    ?: throw java.io.FileNotFoundException("Couldn't open ${rom?.fileName ?: name}" + (zipEntry?.let { " (or $it inside it)" } ?: ""))
                input.use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var last = 0L
                        onProgress(com.acecore.app.core.install.InstallProgress(0, total, 0, 1, name))
                        while (true) {
                            if (isCancelled()) { o.close(); tmp.delete(); throw com.acecore.app.core.install.InstallCancelledException() }
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (done - last >= 4L shl 20) { last = done; onProgress(com.acecore.app.core.install.InstallProgress(done, maxOf(total, done), 0, 1, name)) }
                        }
                        onProgress(com.acecore.app.core.install.InstallProgress(done, done, 1, 1, name))
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("Couldn't write ${target.name} in AceArcade's storage")
                marker.writeText(stamp)
            }
            handheldIntent(game, platform, target, config, accent, requestedAt, if (zipEntry != null) "unpacked from ${rom?.fileName}" else "AceArcade's copy")
        }
    }

    /** A file name safe for app storage (the ROM's own name, without path separators or control characters). */
    private fun handheldFileName(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().trimStart('.').ifEmpty { "rom" }.take(180)

    private suspend fun handheldIntent(
        game: GameEntity, platform: com.acecore.app.core.handheld.HandheldPlatform, romFile: File, config: Map<String, String>,
        accent: Long, requestedAt: Long, how: String,
    ): Intent {
        val intent = withContext(Dispatchers.IO) {
            val dataDir = GameStorage.dataDir(context, game.id)
            val helperDir = RgssRuntime.helperDir(dataDir).apply { mkdirs() }
            val saveDir = File(dataDir, "saves").apply { mkdirs() }
            listOf("ready", "timeline", "error", "handheld.log").forEach { File(helperDir, it).delete() }
            com.acecore.app.runtime.LaunchTimeline.mark(helperDir, "Play tapped", tapElapsed.takeIf { it > 0 } ?: com.acecore.app.runtime.LaunchTimeline.now())
            com.acecore.app.runtime.LaunchTimeline.mark(helperDir, "Launcher ready (${platform.displayName} ROM, $how)")
            val identity = com.acecore.app.core.data.HandheldRoms.readIdentity(context, game.id)
            val spec = com.acecore.app.runtime.handheld.HandheldLaunchSpec(
                gameId = game.id, title = game.title, platform = platform, romPath = romFile.absolutePath,
                romSha1 = identity?.sha1, helperDir = helperDir.absolutePath, saveDir = saveDir.absolutePath, accentArgb = accent,
                buttonMap = com.acecore.app.core.config.ButtonMap.normalizeHandheld(config[ConfigKey.BUTTON_MAP.id]),
                gamepadMode = config[ConfigKey.GAMEPAD_MODE.id] ?: "auto",
                opacity = ConfigKey.GAMEPAD_OPACITY.int(config[ConfigKey.GAMEPAD_OPACITY.id] ?: "55"),
                scale = ConfigKey.GAMEPAD_SCALE.int(config[ConfigKey.GAMEPAD_SCALE.id] ?: "100"),
                haptics = ConfigKey.HAPTICS.bool(config[ConfigKey.HAPTICS.id] ?: "true"),
                display = com.acecore.app.runtime.rgss.DisplayPrefs.from(config).toJson(),
                smooth = (config[ConfigKey.FILTERING.id] ?: "sharp") == "smooth",
                showFps = ConfigKey.SHOW_FPS.bool(config[ConfigKey.SHOW_FPS.id] ?: "false"),
                dsLayout = config[ConfigKey.HANDHELD_DS_LAYOUT.id] ?: ConfigKey.HANDHELD_DS_LAYOUT.default,
                gbPalette = config[ConfigKey.HANDHELD_GB_PALETTE.id] ?: ConfigKey.HANDHELD_GB_PALETTE.default,
                fastForwardSpeed = (config[ConfigKey.HANDHELD_FF_SPEED.id] ?: "2").toFloatOrNull() ?: 2f,
                volume = ConfigKey.HANDHELD_VOLUME.int(config[ConfigKey.HANDHELD_VOLUME.id] ?: "100"),
                vibration = ConfigKey.HANDHELD_VIBRATION.bool(config[ConfigKey.HANDHELD_VIBRATION.id] ?: "true"),
                backups = ConfigKey.HANDHELD_BACKUPS.int(config[ConfigKey.HANDHELD_BACKUPS.id] ?: "5"),
            )
            com.acecore.app.runtime.handheld.HandheldLaunchSpec.intent(context, spec)
        }
        startSession(game.id, requestedAt)
        games.markLaunched(game.id)
        return intent
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
        val godot = game.effectiveEngine.family == EngineFamily.GODOT || game.effectiveEngine.family == EngineFamily.EASYRPG ||
            game.effectiveEngine.family == EngineFamily.WINDOWS || game.effectiveEngine.family == EngineFamily.HANDHELD
        // Keep the game's settings page in step with what was chosen in-game (☰ › Display).
        if (rgss || game.effectiveEngine.family == EngineFamily.HANDHELD) importDisplayChanges(game)
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
                runCatching { File(helperDir, "windows-error").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext "\n$it" }
                listOf("godot.log", "easyrpg.log", "handheld.log").firstNotNullOfOrNull { n ->
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
        // A game that drew its first frame and then stopped with a script error (a splash screen, then a crash) is a
        // failure too: say so instead of returning to the library silently.
        // A game that drew its first frame and then closed by itself within seconds, with no script error and not
        // through AceArcade's Exit (Pokémon Flux "exits on launch"): also investigated, so the engine's output and
        // Android's exit record are shown instead of a silent return to the library.
        val earlyExit = rgss && reached && rubyError == null && resultCode != Activity.RESULT_OK && ms in 0 until 20_000
        if ((reached && rubyError == null && !earlyExit) || !rgss) return null
        val failure = withContext(Dispatchers.IO) {
            investigate(game, requestedAt ?: 0L, helperDir, helperUsed, markers, rubyError, data, resultCode,
                closedAfterMs = if (earlyExit) ms else null)
        }
        // The game's own failure screen already showed this (user chose Retry or Return to library): report saved, no second dialog.
        return if (resultCode == Activity.RESULT_FIRST_USER) null else failure
    }

    /** Collects every piece of evidence about a game process that ended before reaching the game, and saves a report. */
    private fun investigate(
        game: GameEntity, since: Long, helperDir: File, helperUsed: Boolean, markers: Triple<Boolean, String?, Boolean>?,
        rubyError: String?, data: Intent?, resultCode: Int, closedAfterMs: Long? = null,
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
            closedAfterMs != null -> "The game closed by itself ${closedAfterMs / 1000} s after starting, without an error message." to false
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
            File(helperDir, "exit_called").takeIf { it.isFile && it.lastModified() >= since }?.readText()?.trim()?.let {
                appendLine(); appendLine("== The game called exit =="); appendLine(it)
            }
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
    /**
     * The game asked to be restarted (it started its own exe and closed, e.g. to apply a screen-size change; see
     * acecore_compat.rb › Commands). Consumes the request; true when it was made during the session that just ended.
     */
    suspend fun consumeRestartRequest(gameId: Long): Boolean = withContext(Dispatchers.IO) {
        val f = File(RgssRuntime.helperDir(GameStorage.dataDir(context, gameId)), "restart")
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < 120_000
        f.delete()
        fresh
    }

    fun runtimeOf(game: GameEntity): RuntimeId = RuntimeRegistry.runtimeFor(game.effectiveEngine)
}
