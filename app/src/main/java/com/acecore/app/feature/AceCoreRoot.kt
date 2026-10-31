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
import androidx.compose.material3.Button
import com.acecore.app.runtime.LaunchDiagnosis.Fix
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
import com.acecore.app.feature.launcher.LaunchOptions
import com.acecore.app.feature.launcher.LaunchPreparation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import com.acecore.app.feature.library.HomeScreen
import com.acecore.app.feature.settings.ConfigScopeScreen
import com.acecore.app.feature.settings.SettingsHomeScreen
import com.acecore.app.feature.settings.SettingsSectionScreen
import kotlinx.coroutines.launch

/** Progress of a launch that needs preparation (AceCore's private copy of an RGSS game). */
class StagingState(val title: String, val firstCopy: Boolean) {
    var progress by mutableStateOf<com.acecore.app.core.install.InstallProgress?>(null)
    @Volatile var cancelled = false
    var job: kotlinx.coroutines.Job? = null
}

/** Starts a game (validated) from anywhere in the UI. */
fun interface GameLauncher {
    fun launch(game: GameEntity, options: LaunchOptions)
    fun launch(game: GameEntity) = launch(game, LaunchOptions())
}

val LocalGameLauncher = staticCompositionLocalOf<GameLauncher> { GameLauncher { _, _ -> } }

object Routes {
    const val HOME = "home"
    const val IMPORT = "import?uri={uri}&category={category}"
    const val GAME = "game/{id}"
    const val GAME_SETTINGS = "game/{id}/settings"
    const val GAME_CHEATS = "game/{id}/cheats"
    const val GAME_TROUBLESHOOT = "game/{id}/troubleshoot"
    const val CATEGORIES = "categories"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "settings/{section}"
    const val ENGINE_SETTINGS = "settings/engine/{engine}"

    fun import(uri: Uri, category: Long? = null) = "import?uri=${Uri.encode(uri.toString())}&category=${category ?: -1L}"
    fun game(id: Long) = "game/$id"
    fun gameSettings(id: Long) = "game/$id/settings"
    fun cheats(id: Long) = "game/$id/cheats"
    fun troubleshoot(id: Long) = "game/$id/troubleshoot"
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
        Box(Modifier.fillMaxSize().background(Color(0xFF06051A)))
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
        LaunchedEffect(Unit) {
            // Re-evaluate stored statuses against the runtimes bundled in this build (e.g. after an update adds one).
            runCatching { container.games.refreshRuntimeStatuses() }
            // Categories with automatic scanning pick up new games in their folders.
            runCatching { container.categories.autoScan() }
        }
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
    var needsAccess by remember { mutableStateOf<Pair<GameEntity, LaunchPreparation.NeedsFileAccess>?>(null) }
    var chooseFileFor by remember { mutableStateOf<GameEntity?>(null) }
    var launchFailure by remember { mutableStateOf<com.acecore.app.runtime.LaunchFailure?>(null) }
    /** Game (id) to start automatically once the user returns from granting file access. Survives recreation. */
    var resumeAfterGrantId by rememberSaveable { mutableStateOf(-1L) }
    val launcherRef = remember { java.util.concurrent.atomic.AtomicReference<GameLauncher?>(null) }

    val resultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = runningGameId
        runningGameId = -1L
        if (id > 0) scope.launch {
            coordinator.onResult(id, result.resultCode, result.data)?.let { launchFailure = it }
            // "Retry" on the game's start-up failure screen: start it again.
            if (result.data?.getBooleanExtra(com.acecore.app.runtime.rgss.RgssActivity.EXTRA_RETRY, false) == true) {
                ctx.container.games.get(id)?.let { launcherRef.get()?.launch(it) }
            }
        }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val id = resumeAfterGrantId
        resumeAfterGrantId = -1L
        if (id > 0 && grants.values.all { it }) scope.launch { ctx.container.games.get(id)?.let { launcherRef.get()?.launch(it) } }
    }
    val launcher = remember(coordinator) {
        GameLauncher { game, options ->
            scope.launch {
                when (val p = coordinator.prepare(game, options)) {
                    is LaunchPreparation.Ready -> {
                        runningGameId = p.gameId
                        resultLauncher.launch(p.intent)
                    }
                    is LaunchPreparation.Blocked -> blocked = p
                    is LaunchPreparation.NeedsFileAccess -> needsAccess = game to p
                    is LaunchPreparation.Staged -> {
                        val st = StagingState(p.title, p.firstCopy)
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
                                coordinator.recordPreparationFailure(p.gameId, "Couldn't prepare the private copy: ${e.message ?: e}")
                            }
                        }
                    }
                }
            }
        }
    }
    launcherRef.set(launcher)

    // Returning from Android's "All files access" page: start the game that asked for it.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val id = resumeAfterGrantId
                if (id > 0 && com.acecore.app.core.storage.DirectStorage.usesSettingsScreen) {
                    resumeAfterGrantId = -1L
                    if (com.acecore.app.core.storage.DirectStorage.hasAccess(ctx)) scope.launch { ctx.container.games.get(id)?.let { launcher.launch(it) } }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalGameLauncher provides launcher) { content() }

    staging?.let { st -> CopyProgressDialog(st) }

    needsAccess?.let { (game, p) ->
        FileAccessDialog(
            title = p.title, directPossible = p.directPossible,
            onAllow = {
                needsAccess = null
                resumeAfterGrantId = game.id
                if (com.acecore.app.core.storage.DirectStorage.usesSettingsScreen) {
                    val opened = runCatching { ctx.startActivity(com.acecore.app.core.storage.DirectStorage.settingsIntent(ctx)) }.isSuccess ||
                        runCatching { ctx.startActivity(com.acecore.app.core.storage.DirectStorage.genericSettingsIntent()) }.isSuccess
                    if (!opened) { resumeAfterGrantId = -1L; android.widget.Toast.makeText(ctx, "Couldn't open Android's file access settings.", android.widget.Toast.LENGTH_LONG).show() }
                } else {
                    legacyPermission.launch(com.acecore.app.core.storage.DirectStorage.LEGACY_PERMISSIONS)
                }
            },
            onCopy = {
                needsAccess = null
                scope.launch { coordinator.rememberCopyChoice(game.id); launcher.launch(game, LaunchOptions(forceCopy = true)) }
            },
            onDismiss = { needsAccess = null },
        )
    }

    blocked?.let { b ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text(b.title) },
            text = { Text(b.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                if (b.fix == LaunchPreparation.Fix.CHOOSE_LAUNCH_FILE) {
                    TextButton(onClick = {
                        blocked = null
                        scope.launch { chooseFileFor = ctx.container.games.get(b.gameId) }
                    }) { Text("Choose launch file") }
                } else {
                    TextButton(onClick = { blocked = null }) { Text("OK") }
                }
            },
            dismissButton = {
                TextButton(onClick = { blocked = null; nav.navigate(Routes.game(b.gameId)) }) { Text("Game details") }
            },
        )
    }

    launchFailure?.let { f ->
        LaunchFailureDialog(
            f,
            onRetry = { opts -> launchFailure = null; scope.launch { ctx.container.games.get(f.gameId)?.let { launcher.launch(it, opts) } } },
            onDismiss = { launchFailure = null },
        )
    }

    chooseFileFor?.let { g ->
        com.acecore.app.feature.launchfile.LaunchFileBrowser(g, onDismiss = { chooseFileFor = null })
    }
}

@Composable
private fun CopyProgressDialog(st: StagingState) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(if (st.firstCopy) "Copying ${st.title}" else "Updating ${st.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val pr = st.progress
                if (pr == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { pr.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    when {
                        pr == null -> "Comparing your folder with AceArcade's copy…"
                        pr.filesTotal == 0 -> "Up to date."
                        else -> "${pr.filesDone} of ${pr.filesTotal} files · ${pr.copiedBytes / 1_048_576} / ${pr.totalBytes / 1_048_576} MB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (st.firstCopy) "This game uses a private copy (Game settings › How games are loaded). This happens once; later launches start immediately."
                    else "The game was rescanned, so AceArcade is copying new or changed files. Saves made in AceArcade are kept.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { st.cancelled = true }) { Text("Cancel") } },
    )
}

/** Asks to allow direct file access (recommended) or to use a private copy instead. */
@Composable
fun FileAccessDialog(title: String, directPossible: Boolean, onAllow: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(Icons.Rounded.FolderOpen, null) },
        title = { Text(if (directPossible) "Play $title directly from your folder" else "This folder needs a private copy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (directPossible) {
                    Text(
                        "RPG Maker XP/VX/VX Ace and Pokémon Essentials games are read by their engine as ordinary files. " +
                            "To run them straight from where they are, with nothing copied, Android needs to give AceArcade \"All files access\".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "AceArcade only opens the game folders in your library. Games save where they normally save, which for many RPG Maker games is the game's own folder.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Prefer not to? A private copy works too: AceArcade copies the game into its own storage once (this takes time and space for big games).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "This folder comes from a storage provider that has no ordinary file path (for example a cloud or network provider), so the engine can't read it in place. " +
                            "AceArcade can copy the game into its own storage once and run that copy.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            if (directPossible) TextButton(onClick = onAllow) { Text("Allow access") } else TextButton(onClick = onCopy) { Text("Make a private copy") }
        },
        dismissButton = {
            Row {
                if (directPossible) TextButton(onClick = onCopy) { Text("Use a private copy") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
                onOpenCheats = { nav.navigate(Routes.cheats(it)) },
                onImport = { uri, category -> nav.navigate(Routes.import(uri, category)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onManageCategories = { nav.navigate(Routes.CATEGORIES) },
            )
        }
        composable(
            Routes.IMPORT,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType; nullable = true },
                navArgument("category") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val uri = entry.arguments?.getString("uri")?.let { Uri.parse(it) }
            val category = entry.arguments?.getLong("category")?.takeIf { it > 0 }
            ImportScreen(treeUri = uri, categoryId = category, onDone = { nav.popBackStack(Routes.HOME, inclusive = false) }, onOpenGame = {
                nav.popBackStack(Routes.HOME, inclusive = false); nav.navigate(Routes.game(it))
            })
        }
        composable(Routes.GAME, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            GameDetailsScreen(
                gameId = id, onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(Routes.gameSettings(id)) },
                onOpenCheats = { nav.navigate(Routes.cheats(id)) },
                onOpenTroubleshooting = { nav.navigate(Routes.troubleshoot(id)) },
            )
        }
        composable(Routes.GAME_CHEATS, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            com.acecore.app.feature.cheats.CheatManagerScreen(gameId = id, onBack = { nav.popBackStack() })
        }
        composable(Routes.GAME_TROUBLESHOOT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            com.acecore.app.feature.details.TroubleshootingScreen(gameId = id, onBack = { nav.popBackStack() })
        }
        composable(Routes.CATEGORIES) {
            com.acecore.app.feature.categories.CategoryManagerScreen(onBack = { nav.popBackStack() })
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
                onEngine = { nav.navigate(Routes.engine(it)) }, onCategories = { nav.navigate(Routes.CATEGORIES) })
        }
    }
}

/**
 * Shown when a game process ended before the game started: what was detected (in plain language), one evidence-based
 * recommended fix, and the technical options tucked away under Troubleshooting.
 */
@Composable
private fun LaunchFailureDialog(f: com.acecore.app.runtime.LaunchFailure, onRetry: (LaunchOptions) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var details by remember { mutableStateOf(false) }
    var troubleshooting by remember { mutableStateOf(false) }
    val d = f.diagnosis
    val recommended: LaunchOptions? = when (d.fix) {
        Fix.RETRY -> LaunchOptions()
        Fix.NO_HELPER -> if (f.helperUsed) LaunchOptions(disableHelper = true) else null
        Fix.PRIVATE_COPY -> if (f.wasDirect) LaunchOptions(forceCopy = true) else null
        Fix.NONE -> null
    }
    val copy = {
        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("AceArcade crash report", f.report))
        android.widget.Toast.makeText(ctx, "Report copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(f.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Text("Launch failed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        },
        text = {
            Column(Modifier.androidx_verticalScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.issue.ifEmpty { f.cause }, style = MaterialTheme.typography.bodyMedium)
                if (d.recommendation.isNotEmpty()) {
                    Text(d.recommendation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recommended != null) Button(onClick = { onRetry(recommended) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(when (d.fix) { Fix.NO_HELPER -> "Try without the game helper"; Fix.PRIVATE_COPY -> "Try from a private copy"; else -> "Try again" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = copy) { Text("Copy report") }
                    TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details") }
                    TextButton(onClick = { troubleshooting = !troubleshooting }) { Text("Troubleshoot") }
                }
                if (details) {
                    Text("Stage: ${f.stage}", style = MaterialTheme.typography.bodySmall)
                    Text(f.report.take(6000), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                if (troubleshooting) {
                    TextButton(onClick = { onRetry(LaunchOptions()) }) { Text("Retry") }
                    if (f.helperUsed) TextButton(onClick = { onRetry(LaunchOptions(disableHelper = true)) }) { Text("Retry without AceArcade's game helper") }
                    if (f.wasDirect) TextButton(onClick = { onRetry(LaunchOptions(forceCopy = true)) }) { Text("Retry from a private copy") }
                }
                Text("Saved in Settings › Advanced › Crash reports.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Modifier.androidx_verticalScroll(): Modifier =
    this.then(Modifier.heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()))
