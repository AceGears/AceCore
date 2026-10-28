package com.acecore.app.feature

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.acecore.app.container
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.designsystem.AceCoreTheme
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.model.Engine
import com.acecore.app.feature.details.GameDetailsScreen
import com.acecore.app.feature.importer.ImportScreen
import com.acecore.app.feature.launcher.LaunchPreparation
import com.acecore.app.feature.library.HomeScreen
import com.acecore.app.feature.settings.ConfigScopeScreen
import com.acecore.app.feature.settings.SettingsHomeScreen
import com.acecore.app.feature.settings.SettingsSectionScreen
import kotlinx.coroutines.launch

/** Progress of a launch that needs preparation (e.g. syncing an RGSS game's working copy). */
class StagingState(val title: String) {
    var progress by mutableStateOf<com.acecore.app.core.install.InstallProgress?>(null)
    @Volatile var cancelled = false
    var job: kotlinx.coroutines.Job? = null
}

/** Starts a game (validated) from anywhere in the UI. */
fun interface GameLauncher { fun launch(game: GameEntity) }

val LocalGameLauncher = staticCompositionLocalOf<GameLauncher> { GameLauncher { } }

object Routes {
    const val HOME = "home"
    const val IMPORT = "import?uri={uri}"
    const val GAME = "game/{id}"
    const val GAME_SETTINGS = "game/{id}/settings"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "settings/{section}"
    const val ENGINE_SETTINGS = "settings/engine/{engine}"

    fun import(uri: Uri) = "import?uri=${Uri.encode(uri.toString())}"
    fun game(id: Long) = "game/$id"
    fun gameSettings(id: Long) = "game/$id/settings"
    fun section(s: String) = "settings/$s"
    fun engine(e: Engine) = "settings/engine/${e.name}"
}

@Composable
fun AceCoreRoot(pendingLaunchId: Long?, onPendingLaunchConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E)))
        return
    }
    AceCoreTheme(s.theme, s.reduceMotion) {
        val view = LocalView.current
        val dark = AceTheme.tokens.isDark
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        // Re-evaluate stored statuses against the runtimes bundled in this build (e.g. after an update adds one).
        LaunchedEffect(Unit) { runCatching { container.games.refreshRuntimeStatuses() } }
        val nav = rememberNavController()
        LaunchHost(nav) {
            val launcher = LocalGameLauncher.current
            LaunchedEffect(pendingLaunchId) {
                val id = pendingLaunchId ?: return@LaunchedEffect
                onPendingLaunchConsumed()
                container.games.get(id)?.let { launcher.launch(it) }
            }
            AceNavHost(nav)
        }
    }
}

@Composable
private fun LaunchHost(nav: NavHostController, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val coordinator = ctx.container.launcher
    val scope = rememberCoroutineScope()
    var runningGameId by rememberSaveable { mutableStateOf(-1L) }
    var blocked by remember { mutableStateOf<LaunchPreparation.Blocked?>(null) }
    var staging by remember { mutableStateOf<StagingState?>(null) }

    val resultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = runningGameId
        runningGameId = -1L
        if (id > 0) scope.launch { coordinator.onResult(id, result.resultCode, result.data) }
    }
    val launcher = remember(coordinator) {
        GameLauncher { game ->
            scope.launch {
                when (val p = coordinator.prepare(game)) {
                    is LaunchPreparation.Ready -> {
                        runningGameId = p.gameId
                        resultLauncher.launch(p.intent)
                    }
                    is LaunchPreparation.Blocked -> blocked = p
                    is LaunchPreparation.Staged -> {
                        val st = StagingState(p.title)
                        staging = st
                        st.job = scope.launch {
                            try {
                                val intent = p.prepareIntent({ pr -> st.progress = pr }, { st.cancelled })
                                staging = null
                                runningGameId = p.gameId
                                resultLauncher.launch(intent)
                            } catch (e: com.acecore.app.core.install.InstallCancelledException) {
                                staging = null
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                staging = null
                                throw e
                            } catch (e: Exception) {
                                staging = null
                                blocked = LaunchPreparation.Blocked(p.gameId, "Couldn't prepare ${p.title}", e.message ?: e.toString())
                            }
                        }
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalGameLauncher provides launcher) { content() }

    staging?.let { st ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Preparing ${st.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pr = st.progress
                    if (pr == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { pr.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        when {
                            pr == null -> "Checking game files…"
                            pr.filesTotal == 0 -> "Up to date."
                            else -> "Copying ${pr.filesDone} of ${pr.filesTotal} files · ${pr.copiedBytes / 1_048_576} / ${pr.totalBytes / 1_048_576} MB"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "RPG Maker XP/VX/Ace games run from a private copy. Only new or changed files are copied next time; your original folder is never modified.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { st.cancelled = true }) { Text("Cancel") } },
        )
    }

    blocked?.let { b ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text(b.title) },
            text = { Text(b.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text("OK") } },
            dismissButton = {
                TextButton(onClick = { blocked = null; nav.navigate(Routes.game(b.gameId)) }) { Text("Compatibility details") }
            },
        )
    }
}

@Composable
private fun AceNavHost(nav: NavHostController) {
    val dur = (280 * AceTheme.tokens.animationScale).toInt()
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(tween(dur)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(dur), initialOffset = { it / 6 }) },
        exitTransition = { fadeOut(tween(dur / 2)) },
        popEnterTransition = { fadeIn(tween(dur)) },
        popExitTransition = { fadeOut(tween(dur / 2)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(dur), targetOffset = { it / 6 }) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenGame = { nav.navigate(Routes.game(it)) },
                onOpenGameSettings = { nav.navigate(Routes.gameSettings(it)) },
                onImport = { nav.navigate(Routes.import(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.IMPORT, arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true })) { entry ->
            val uri = entry.arguments?.getString("uri")?.let { Uri.parse(it) }
            ImportScreen(treeUri = uri, onDone = { nav.popBackStack(Routes.HOME, inclusive = false) }, onOpenGame = {
                nav.popBackStack(Routes.HOME, inclusive = false); nav.navigate(Routes.game(it))
            })
        }
        composable(Routes.GAME, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            GameDetailsScreen(gameId = id, onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(Routes.gameSettings(id)) })
        }
        composable(Routes.GAME_SETTINGS, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            ConfigScopeScreen(gameId = id, engine = null, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsHomeScreen(onBack = { nav.popBackStack() }, onSection = { nav.navigate(Routes.section(it)) }, onEngine = { nav.navigate(Routes.engine(it)) })
        }
        composable(Routes.ENGINE_SETTINGS, arguments = listOf(navArgument("engine") { type = NavType.StringType })) { entry ->
            val engine = Engine.fromName(entry.arguments?.getString("engine"))
            ConfigScopeScreen(gameId = null, engine = engine, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS_SECTION, arguments = listOf(navArgument("section") { type = NavType.StringType })) { entry ->
            SettingsSectionScreen(section = entry.arguments?.getString("section") ?: "", onBack = { nav.popBackStack() },
                onEngine = { nav.navigate(Routes.engine(it)) })
        }
    }
}
