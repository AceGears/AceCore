package com.acecore.app.feature.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.datastore.SettingsRepository
import com.acecore.app.core.designsystem.accentArgbFor
import com.acecore.app.core.install.GameInstaller
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.install.InstallProgress
import com.acecore.app.core.install.SafInstallSource
import com.acecore.app.core.model.CompatStatus
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.runtime.rgss.RgssActivity
import com.acecore.app.runtime.rgss.RgssLaunchSpec
import com.acecore.app.runtime.rgss.RgssRuntime
import com.acecore.app.runtime.RuntimeRegistry
import com.acecore.app.runtime.web.GameActivity
import com.acecore.app.runtime.web.GameLaunchSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface LaunchPreparation {
    data class Ready(val intent: Intent, val gameId: Long) : LaunchPreparation

    /** Needs a (possibly long) preparation step first, e.g. syncing the game's private working copy. */
    data class Staged(
        val gameId: Long,
        val title: String,
        val prepareIntent: suspend (onProgress: (InstallProgress) -> Unit, isCancelled: () -> Boolean) -> Intent,
    ) : LaunchPreparation
    data class Blocked(val gameId: Long, val title: String, val message: String, val canRescan: Boolean = false) : LaunchPreparation
}

/**
 * Validates a game before launch (folder permission, runtime availability, entry point) and builds the
 * Intent for the isolated runtime process with the fully resolved layered configuration.
 */
class LaunchCoordinator(
    private val context: Context,
    private val games: GameRepository,
    private val settings: SettingsRepository,
) {
    suspend fun prepare(game: GameEntity, forceReindex: Boolean = false): LaunchPreparation {
        val engine = game.effectiveEngine
        if (!games.hasAccess(game.treeUri)) return LaunchPreparation.Blocked(
            game.id, "Folder access lost",
            "Android no longer lets AceCore read this game's folder (it may have been moved, deleted, or the permission was revoked). Remove the game and add its folder again.",
        )
        val runtime = RuntimeRegistry.info(engine)
        if (!runtime.bundled) return LaunchPreparation.Blocked(
            game.id, "${engine.displayName} isn't playable yet",
            buildString {
                append(runtime.status)
                runtime.blocker?.let { append("\n\n").append(it) }
            },
        )
        if (game.compatStatus == CompatStatus.PACKED) return LaunchPreparation.Blocked(
            game.id, "Game files are packed", "This build keeps its game inside an archive. See Compatibility details for how to extract it, then rescan.", canRescan = true,
        )
        val entry = game.entry ?: return LaunchPreparation.Blocked(
            game.id, "No launch file", "AceCore couldn't find a file to start this game with. Choose a launch file or rescan the game.", canRescan = true,
        )
        val s = settings.current()
        val config = LayeredConfig(s.globalConfig, s.engineConfig[engine].orEmpty(), games.overrides(game), engine).snapshot()
        if (engine.family == EngineFamily.RGSS) return prepareRgss(game, config, accentArgbFor(s.theme))
        val spec = GameLaunchSpec(
            gameId = game.id, title = game.title, engine = engine.name, treeUri = game.treeUri, rootDocId = game.rootDocId,
            contentRoot = game.contentRoot, entry = entry, config = config, webDebugging = s.webDebugging,
            accentArgb = accentArgbFor(s.theme), forceReindex = forceReindex,
        )
        // Clear any stale session file from a previous crash before starting a new session.
        withContext(Dispatchers.IO) { GameActivity.sessionFile(context, game.id).delete() }
        games.markLaunched(game.id)
        return LaunchPreparation.Ready(Intent(context, GameActivity::class.java).putExtra(GameLaunchSpec.EXTRA, spec.toJson()), game.id)
    }

    private suspend fun prepareRgss(game: GameEntity, config: Map<String, String>, accent: Long): LaunchPreparation {
        withContext(Dispatchers.IO) { GameActivity.sessionFile(context, game.id).delete() }
        return LaunchPreparation.Staged(game.id, game.title) { onProgress, isCancelled ->
            val intent = withContext(Dispatchers.IO) {
                // Ruby can only start once per process: make sure no previous RGSS game process is still alive.
                RgssRuntime.killStaleProcess(context)
                val installDir = GameStorage.installDir(context, game.id)
                val dataDir = GameStorage.dataDir(context, game.id).apply { mkdirs() }
                GameInstaller(installDir).install(
                    SafInstallSource(context, android.net.Uri.parse(game.treeUri), game.rootDocId), isCancelled, onProgress,
                )
                // Player settings go in the per-game pref path; mkxp-z merges it over the game's own mkxp.json.
                java.io.File(dataDir, "mkxp.json").writeText(RgssRuntime.overrideConfigJson(config))
                val spec = RgssLaunchSpec(
                    gameId = game.id, title = game.title, engine = game.effectiveEngine.name,
                    gamePath = installDir.absolutePath, dataPath = dataDir.absolutePath, config = config, accentArgb = accent,
                )
                Intent(context, RgssActivity::class.java).putExtra(RgssLaunchSpec.EXTRA, spec.toJson())
            }
            games.markLaunched(game.id) // only once the game is really about to start
            intent
        }
    }

    /** Collects playtime (also after a game-process crash) and records any launch error. */
    suspend fun onResult(gameId: Long, resultCode: Int, data: Intent?) {
        val file = GameActivity.sessionFile(context, gameId)
        val fromFile = withContext(Dispatchers.IO) {
            runCatching { file.readText().trim().toLong() }.getOrNull().also { file.delete() }
        }
        val fromResult = data?.getLongExtra(GameActivity.EXTRA_SESSION_MS, -1L)?.takeIf { it >= 0 }
        games.addPlaytime(gameId, maxOf(fromFile ?: 0L, fromResult ?: 0L))
        when {
            resultCode == Activity.RESULT_FIRST_USER -> games.setError(gameId, data?.getStringExtra(GameLaunchSpec.RESULT_ERROR))
            resultCode == Activity.RESULT_CANCELED && fromResult == null && fromFile == null ->
                games.setError(gameId, "The game closed unexpectedly before it finished loading.")
            else -> Unit
        }
    }
}
