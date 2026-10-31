package com.acecore.app.feature.details

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.container
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.database.SessionTotals
import com.acecore.app.core.designsystem.AceCard
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.designsystem.SectionHeader
import com.acecore.app.core.designsystem.aceGlow
import com.acecore.app.core.designsystem.pressScale
import com.acecore.app.core.install.GameLocations
import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.feature.LocalGameLauncher
import com.acecore.app.feature.categories.CategoryBadge
import com.acecore.app.feature.library.CoverImage
import com.acecore.app.feature.library.GameDialog
import com.acecore.app.feature.library.GameDialogs
import com.acecore.app.feature.library.GameStateChip
import com.acecore.app.feature.library.gameState
import com.acecore.app.feature.library.openFolder
import com.acecore.app.feature.library.rememberCoverPicker
import com.acecore.app.runtime.RuntimeRegistry
import com.acecore.app.runtime.web.GameActivity
import kotlinx.coroutines.launch

/**
 * Game page: header with the cover, Play/Favorite/Settings, how the game starts, real play statistics, cheats,
 * categories, management actions and game information. Technical diagnostics live on the Troubleshooting screen.
 */
@Composable
fun GameDetailsScreen(
    gameId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCheats: () -> Unit,
    onOpenTroubleshooting: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val game by repo.game(gameId).collectAsStateWithLifecycle(initialValue = null)
    val g = game ?: run { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)); return }
    var dialog by remember { mutableStateOf(GameDialog.NONE) }
    var engineDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 720.dp
        Box(Modifier.fillMaxWidth().height(if (wide) 220.dp else 300.dp)) {
            CoverImage(g, Modifier.fillMaxSize().blur(28.dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), MaterialTheme.colorScheme.background))))
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(340.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                    TopBar(g, onBack, onRename = { dialog = GameDialog.RENAME }, onEngine = { engineDialog = true }, onTroubleshooting = onOpenTroubleshooting)
                    Hero(g, coverWidth = 180.dp, vertical = true)
                    PrimaryActions(g, onOpenSettings)
                    Notices(g)
                }
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars)) {
                    Sections(g, onOpenCheats, onDialog = { dialog = it })
                    Spacer(Modifier.navigationBarsPadding().height(32.dp))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TopBar(g, onBack, onRename = { dialog = GameDialog.RENAME }, onEngine = { engineDialog = true }, onTroubleshooting = onOpenTroubleshooting)
                Hero(g, coverWidth = 116.dp, vertical = false)
                PrimaryActions(g, onOpenSettings)
                Notices(g)
                Sections(g, onOpenCheats, onDialog = { dialog = it })
                Spacer(Modifier.navigationBarsPadding().height(32.dp))
            }
        }
    }

    GameDialogs(g, dialog, onClose = { dialog = GameDialog.NONE }, onRemoved = onBack)
    if (engineDialog) EngineOverrideDialog(g) { engineDialog = false }
}

@Composable
private fun TopBar(g: GameEntity, onBack: () -> Unit, onRename: () -> Unit, onEngine: () -> Unit, onTroubleshooting: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp).fillMaxWidth()) {
        IconButton(onClick = onBack, modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)) {
                Icon(Icons.Rounded.MoreVert, "More", tint = Color.White)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Override detected engine") }, onClick = { menu = false; onEngine() })
                if (g.customCoverPath != null) DropdownMenuItem(text = { Text("Restore detected cover") }, onClick = { menu = false; scope.launch { repo.clearCustomCover(g) } })
                DropdownMenuItem(text = { Text(if (g.hidden) "Unhide game" else "Hide game") }, onClick = { menu = false; scope.launch { repo.setHidden(g.id, !g.hidden) } })
                DropdownMenuItem(text = { Text("Troubleshooting") }, leadingIcon = { Icon(Icons.Rounded.BugReport, null) }, onClick = { menu = false; onTroubleshooting() })
            }
        }
    }
}

@Composable
private fun Hero(g: GameEntity, coverWidth: Dp, vertical: Boolean) {
    val t = AceTheme.tokens
    val engine = g.effectiveEngine
    val cover = @Composable {
        CoverImage(g, Modifier.width(coverWidth).aspectRatio(0.75f).aceGlow(t.glow, t.glowIntensity, 16.dp).clip(RoundedCornerShape(16.dp))
            .border(1.dp, Brush.linearGradient(listOf(t.glow.copy(alpha = 0.8f), t.glowAlt.copy(alpha = 0.6f))), RoundedCornerShape(16.dp)))
    }
    val text = @Composable { align: Alignment.Horizontal ->
        Column(horizontalAlignment = align) {
            // Explicit colour (the header isn't inside a Surface, so the default content colour would be black) and a
            // soft shadow so the title stays readable over any blurred cover, in dark and light themes.
            val onBg = MaterialTheme.colorScheme.onBackground
            val shadow = androidx.compose.ui.graphics.Shadow(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), blurRadius = 12f)
            val titleStyle = (if (g.title.length > 28) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                .copy(color = onBg, fontWeight = FontWeight.ExtraBold, shadow = shadow)
            Text(g.title, style = titleStyle, maxLines = 4, overflow = TextOverflow.Ellipsis,
                textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start)
            Spacer(Modifier.height(4.dp))
            Text(
                engine.displayName + (g.engineVersion?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow), color = onBg.copy(alpha = 0.78f),
            )
            Spacer(Modifier.height(8.dp))
            GameStateChip(g)
        }
    }
    if (vertical) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            cover(); Spacer(Modifier.height(16.dp)); text(Alignment.CenterHorizontally)
        }
    } else {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 60.dp), verticalAlignment = Alignment.Bottom) {
            cover(); Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f).padding(bottom = 4.dp)) { text(Alignment.Start) }
        }
    }
}

@Composable
private fun PrimaryActions(g: GameEntity, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    val launcher = LocalGameLauncher.current
    val playable = GameRepository.isPlayable(g)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var launching by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(launching) { if (launching) { kotlinx.coroutines.delay(1800); launching = false } }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { launching = true; launcher.launch(g) }, enabled = playable && !launching,
            interactionSource = interaction,
            modifier = Modifier.weight(1f).height(56.dp).pressScale(interaction),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (launching) 0.7f else 0.3f)),
        ) {
            if (launching) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("Starting…", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(26.dp)); Spacer(Modifier.width(6.dp))
                Text(if (playable) "Play" else "Not playable yet", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
        OutlinedIconButton(onClick = { scope.launch { repo.setFavorite(g.id, !g.favorite) } }, modifier = Modifier.size(54.dp)) {
            Icon(if (g.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (g.favorite) "Remove from favorites" else "Add to favorites",
                tint = if (g.favorite) AceTheme.tokens.glow else MaterialTheme.colorScheme.onSurface)
        }
        OutlinedIconButton(onClick = onOpenSettings, modifier = Modifier.size(54.dp)) { Icon(Icons.Rounded.Tune, "Game settings") }
    }
}

@Composable
private fun Notices(g: GameEntity) {
    val ctx = LocalContext.current
    val access = remember(g.treeUri) { ctx.container.games.hasAccess(g.treeUri) }
    if (!access && g.effectiveEngine.family != EngineFamily.RGSS) Warning("AceArcade no longer has permission to read this folder. Remove the game and add its folder again.")
    // Checked when the page opens, so a moved or renamed folder is noticed before Play is pressed.
    val folderExists by androidx.compose.runtime.produceState<Boolean?>(null, g.treeUri, g.rootDocId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!access) null else com.acecore.app.core.filesystem.SafTree(ctx.contentResolver, android.net.Uri.parse(g.treeUri)).exists(g.rootDocId)
        }
    }
    val folderMissing = folderExists == false || (g.lastLaunchOk == false && g.lastError?.startsWith("Game folder not found") == true)
    if (folderMissing) FolderUnavailableCard(g, g.lastError ?: "Saved location: ${g.folderLabel}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    else if (g.lastLaunchOk == false && g.lastError != null) Warning("Last launch: ${g.lastError}")
}

@Composable
private fun Sections(g: GameEntity, onOpenCheats: () -> Unit, onDialog: (GameDialog) -> Unit) {
    LaunchCard(g, onChange = { onDialog(GameDialog.LAUNCH_FILE) })
    if (g.effectiveEngine.family == EngineFamily.RGSS) GameFilesCard(g)
    StatisticsCard(g)
    if (g.effectiveEngine.family == EngineFamily.RGSS) CheatsCard(g, onOpenCheats)
    CategoriesCard(g) { onDialog(GameDialog.CATEGORIES) }
    ManagementCard(g, onDialog)
    InformationCard(g)
}

// ---------------------------------------------------------------------------------------------- launch

@Composable
private fun LaunchCard(g: GameEntity, onChange: () -> Unit) {
    val rows = launchDescription(g)
    val file = (g.launchFile ?: g.entry ?: "None").substringAfterLast('/')
    ExpandableSection(
        title = "Launch configuration",
        summary = "$file · " + (rows.firstOrNull { it.first == "Runtime" }?.second ?: RuntimeRegistry.info(g.effectiveEngine).id.displayName),
        action = { TextButton(onClick = onChange) { Text("Change") } },
    ) {
        rows.forEach { (k, v) -> KeyValue(k, v) }
        Text(
            if (g.isManualLaunch) "Chosen by you. AceArcade uses it every time." else "Chosen automatically from the game's files.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Accurate wording: the file the user sees, the runtime, and what the runtime really reads. */
fun launchDescription(g: GameEntity): List<Pair<String, String>> {
    val engine = g.effectiveEngine
    val out = mutableListOf<Pair<String, String>>()
    out += "Selected file" to (g.launchFile ?: g.entry ?: "None")
    when (engine.family) {
        EngineFamily.RGSS -> {
            out += "Runtime" to "RGSS-compatible Android runtime (mkxp-z)"
            val sub = GameLocations.rgssSubdir(g.entry)
            val prefix = if (sub.isEmpty()) "" else "$sub/"
            out += "Configuration" to (g.entry?.takeIf { it.endsWith(".ini", true) } ?: "${prefix}Game.ini")
            out += "Game scripts" to "${prefix}Data/Scripts.${when (engine) { Engine.RPG_MAKER_VX -> "rvdata"; Engine.RPG_MAKER_VX_ACE -> "rvdata2"; else -> "rxdata" }}"
            if (g.launchFile?.endsWith(".exe", true) == true) out += "Note" to "${g.launchFile!!.substringAfterLast('/')} is the Windows launcher; it isn't run on Android."
        }
        EngineFamily.WEB -> {
            out += "Runtime" to "AceArcade web runtime"
            if (g.entry != null && g.entry != g.launchFile) out += "Opens" to g.entry
        }
        EngineFamily.FLASH -> {
            out += "Runtime" to "Ruffle (Flash) in the web runtime"
            if (g.entry != null && g.entry != g.launchFile) out += "Opens" to g.entry
        }
        else -> out += "Runtime" to RuntimeRegistry.info(engine).id.displayName
    }
    return out
}

/**
 * A section that shows a one-line summary and opens to the details (collapsed by default), so technical information
 * is there when needed without dominating the page.
 */
@Composable
fun ExpandableSection(
    title: String,
    summary: String,
    initiallyOpen: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    SectionHeader(title, action = action)
    AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(
                    if (open) androidx.compose.material.icons.Icons.Rounded.ExpandLess else androidx.compose.material.icons.Icons.Rounded.ExpandMore,
                    if (open) "Hide details" else "Show details", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- statistics

@Composable
private fun StatisticsCard(g: GameEntity) {
    val ctx = LocalContext.current
    val totals by ctx.container.games.sessionTotals(g.id).collectAsStateWithLifecycle(initialValue = SessionTotals(0, 0, 0, null))
    SectionHeader("Statistics")
    AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (g.playtimeMs <= 0 && g.launchAttempts == 0 && g.launchCount == 0) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No play sessions yet", style = MaterialTheme.typography.titleMedium)
                Text("Play time, sessions and launch results appear here after you play.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            StatsGrid(g, totals)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsGrid(g: GameEntity, totals: SessionTotals) {
    val ctx = LocalContext.current
    val dash = "—"
    val avg = if (totals.sessions > 0) totals.totalMs / totals.sessions else null
    val stats = listOf(
        "Total playtime" to if (g.playtimeMs > 0) GameActivity.formatDuration(g.playtimeMs) else "0 min",
        "Sessions" to (if (g.sessionCount > 0) "${g.sessionCount}" else dash),
        "Last played" to (g.lastPlayedAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "Never"),
        "First played" to (g.firstPlayedAt?.let { DateUtils.formatDateTime(ctx, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR) } ?: dash),
        "Average session" to (avg?.let { formatShort(it) } ?: dash),
        "Longest session" to (if (g.longestSessionMs > 0) formatShort(g.longestSessionMs) else dash),
        "Successful launches" to "${g.successfulLaunches}",
        "Failed launches" to "${g.failedLaunches}",
        "Last start-up time" to (g.lastLaunchLatencyMs?.let { "%.1f s".format(it / 1000.0) } ?: dash),
    )
    Column(Modifier.padding(12.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            stats.forEach { (label, value) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
                ) {
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
        val legacy = g.playtimeMs - totals.totalMs
        if (legacy > 60_000 && g.sessionCount < g.launchCount) Text(
            "Includes ${GameActivity.formatDuration(legacy)} played before detailed session tracking (AceArcade 0.4).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
        Text(
            "Only time with the game on screen counts. Start-up time is measured from tapping Play to the game's first frame.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
}

private fun formatShort(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

// ---------------------------------------------------------------------------------------------- cheats

@Composable
private fun CheatsCard(g: GameEntity, onOpenCheats: () -> Unit) {
    val ctx = LocalContext.current
    val config = remember(g.id) { ctx.container.cheats.load(g.id) }
    SectionHeader("Cheats")
    AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onOpenCheats)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (config.enabled) "Cheats on" else "Cheats off", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (config.enabled) "Profile “${config.activeProfile}” · ${config.activeCount} active switch(es). Apply one-off cheats from the in-game menu."
                    else "Money, items, Pokémon editing, movement speed and more, checked against what this game supports.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onOpenCheats) { Text("Open") }
        }
    }
}

// ---------------------------------------------------------------------------------------------- categories

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoriesCard(g: GameEntity, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val cats by ctx.container.categories.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val membership by ctx.container.categories.membership.collectAsStateWithLifecycle(initialValue = emptyMap())
    val mine = cats.filter { membership[g.id]?.contains(it.category.id) == true }
    SectionHeader("Categories", action = { TextButton(onClick = onChange) { Text("Change") } })
    AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (mine.isEmpty()) {
            Text("Not in any category.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            mine.forEach { cw ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryBadge(cw.category.icon, cw.category.color, size = 26)
                    Spacer(Modifier.width(6.dp))
                    Text(cw.category.name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- management

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagementCard(g: GameEntity, onDialog: (GameDialog) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    val pickCover = rememberCoverPicker(g)
    var rescanning by remember { mutableStateOf(false) }
    SectionHeader("Manage")
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
        val m = Modifier.weight(1f)
        ActionTile(Icons.Rounded.Description, "Launch file", m) { onDialog(GameDialog.LAUNCH_FILE) }
        ActionTile(Icons.Rounded.Image, "Cover", m) { pickCover() }
        ActionTile(Icons.AutoMirrored.Rounded.Label, "Category", m) { onDialog(GameDialog.CATEGORIES) }
        ActionTile(Icons.Rounded.FolderOpen, "Open folder", m) { openFolder(ctx, g) }
        ActionTile(Icons.Rounded.Refresh, if (rescanning) "Rescanning…" else "Rescan", m, enabled = !rescanning) {
            rescanning = true
            scope.launch {
                runCatching { repo.rescan(g) }
                    .onSuccess { Toast.makeText(ctx, "Rescanned: ${it.effectiveEngine.displayName}", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(ctx, "Rescan failed: ${it.message}", Toast.LENGTH_LONG).show() }
                rescanning = false
            }
        }
        ActionTile(Icons.Rounded.Delete, "Remove", m, destructive = true) { onDialog(GameDialog.REMOVE) }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------------------------------------- information

@Composable
private fun InformationCard(g: GameEntity) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    val engine = g.effectiveEngine
    val runtime = RuntimeRegistry.info(engine)
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionHeader("Game information") }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand")
    }
    AnimatedVisibility(open) {
        AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KeyValue("Engine", buildString {
                    append(engine.displayName)
                    g.engineVersion?.let { append(" · ").append(it) }
                    if (g.engineOverride != null) append(" (set by you; detected as ${Engine.fromName(g.engine).displayName})")
                })
                KeyValue("Detection confidence", "${g.confidence}%")
                KeyValue("Runtime", runtime.id.displayName + if (runtime.bundled) "" else " (not in this version)")
                KeyValue("Status", gameState(g).let { "${it.label}. ${it.detail}" })
                KeyValue("Folder", g.folderLabel)
                val real = remember(g.treeUri, g.rootDocId) { GameLocations.directRoot(ctx, g)?.absolutePath }
                real?.let { KeyValue("Location", it) }
                KeyValue("Launch entry", g.entry ?: "None")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        KeyValue("Folder size", g.folderSizeBytes?.let { formatBytes(it) } ?: "Not measured")
                    }
                    TextButton(enabled = !measuring, onClick = {
                        measuring = true
                        scope.launch { runCatching { repo.measureFolderSize(g) }; measuring = false }
                    }) { Text(if (measuring) "Measuring…" else if (g.folderSizeBytes == null) "Measure" else "Refresh") }
                }
                KeyValue("Added", DateUtils.formatDateTime(ctx, g.addedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR))
            }
        }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun KeyValue(key: String, value: String) {
    Column {
        Text(key, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Warning(text: String) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EngineOverrideDialog(g: GameEntity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf(g.engineOverride?.let { Engine.fromName(it) }) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Override engine") },
        text = {
            Column(Modifier.widthIn(max = 420.dp).height(380.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().clickable { choice = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(choice == null, { choice = null }); Text("Use detected (${Engine.fromName(g.engine).displayName})")
                }
                Engine.entries.filter { it != Engine.UNKNOWN }.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { choice = e }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choice == e, { choice = e })
                        Text(e.displayName + if (RuntimeRegistry.canLaunch(e)) "" else "  (not playable yet)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { repo.setEngineOverride(g, choice) }; onClose() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
