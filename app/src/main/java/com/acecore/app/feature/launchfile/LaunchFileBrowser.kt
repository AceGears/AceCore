package com.acecore.app.feature.launchfile

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.acecore.app.container
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.detection.LaunchEvaluation
import com.acecore.app.core.detection.LaunchFileRules
import com.acecore.app.core.filesystem.SafNode
import com.acecore.app.core.filesystem.SafTree
import com.acecore.app.runtime.RuntimeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Mode { AUTO, MANUAL }

/**
 * Launch-file selection: Automatic (AceCore's detected entry) or Manual (browse the game folder and pick any file).
 * Every file can be selected; files that can't start a game get a clear explanation instead of a Save button.
 * The choice is stored in the library and reused on every launch.
 */
@Composable
fun LaunchFileBrowser(game: GameEntity, onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    val tree = remember(game.treeUri) { SafTree(ctx.contentResolver, Uri.parse(game.treeUri)) }
    val detection = remember(game.detectionJson) { repo.decodeDetection(game) }
    val engine = game.effectiveEngine
    var mode by remember { mutableStateOf(if (game.isManualLaunch) Mode.MANUAL else Mode.AUTO) }

    // Folder stack: (docId, relativePath, name). The game root is the top; you can't leave the game's folder.
    val stack = remember { mutableStateListOf(Triple(game.rootDocId, "", game.folderLabel)) }
    var listing by remember { mutableStateOf<List<SafNode>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(if (game.isManualLaunch) game.launchFile else null) }
    var saving by remember { mutableStateOf(false) }

    // Open the folder of the current manual selection.
    LaunchedEffect(Unit) {
        val sel = game.launchFile
        if (game.isManualLaunch && sel != null && sel.contains('/')) {
            val parts = sel.substringBeforeLast('/').split('/')
            var parentId = game.rootDocId
            var parentPath = ""
            for (p in parts) {
                val node = withContext(Dispatchers.IO) { runCatching { tree.children(parentId, parentPath) }.getOrNull() }
                    ?.firstOrNull { it.isDirectory && it.name.equals(p, true) } ?: break
                stack.add(Triple(node.docId, node.path, node.name))
                parentId = node.docId; parentPath = node.path
            }
        }
    }
    val (dirId, dirPath, _) = stack.last()
    LaunchedEffect(dirId) {
        listing = null; loadError = null
        withContext(Dispatchers.IO) {
            runCatching { tree.children(dirId, dirPath) }
        }.onSuccess { list ->
            listing = list.sortedWith(compareByDescending<SafNode> { it.isDirectory }.thenByDescending { LaunchFileRules.isCandidateName(it.name) }.thenBy { it.name.lowercase() })
        }.onFailure { loadError = if (it is SecurityException) "AceArcade no longer has access to this folder." else (it.message ?: it.toString()) }
    }
    val siblings = listing.orEmpty().map { it.name }
    val evaluation = selected?.let { sel ->
        val inThisDir = sel.substringBeforeLast('/', "") == dirPath
        LaunchFileRules.evaluate(engine, sel, if (inThisDir) siblings else emptyList()) { RuntimeRegistry.canLaunch(it.representative()) }
    }
    val autoCandidate = detection?.launchCandidates?.firstOrNull { it.playable }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text("Launch file", style = MaterialTheme.typography.titleLarge)
                        Text(game.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    SegmentedButton(mode == Mode.AUTO, { mode = Mode.AUTO }, SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) }) { Text("Automatic") }
                    SegmentedButton(mode == Mode.MANUAL, { mode = Mode.MANUAL }, SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Rounded.TouchApp, null, Modifier.size(18.dp)) }) { Text("Manual") }
                }
                AnimatedContent(mode, label = "mode", modifier = Modifier.weight(1f)) { m ->
                    if (m == Mode.AUTO) {
                        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("AceArcade analyses the folder and picks the entry the runtime needs.", style = MaterialTheme.typography.bodyMedium)
                            if (autoCandidate != null || detection?.entry != null) {
                                ResultCard(
                                    ok = true,
                                    title = "Detected: ${autoCandidate?.path ?: detection?.entry}",
                                    lines = buildList {
                                        add("Engine" to engine.displayName)
                                        add("Runtime" to RuntimeRegistry.info(engine).id.displayName)
                                        detection?.entry?.let { e -> if (e != autoCandidate?.path) add("Runtime opens" to e) }
                                    },
                                    body = autoCandidate?.note,
                                )
                            } else {
                                ResultCard(false, "Nothing to launch was detected", emptyList(), "Switch to Manual and choose the file that starts the game.")
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Breadcrumbs(stack.map { it.third }) { idx -> while (stack.size > idx + 1) stack.removeAt(stack.lastIndex) }
                            Box(Modifier.weight(1f)) {
                                when {
                                    loadError != null -> Text(loadError!!, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                                    listing == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                                    // LazyColumn builds its rows later (during layout), when `listing` may already be null again
                                    // (a folder was just opened) and the folder stack may have shrunk (Up was tapped): use snapshots.
                                    else -> {
                                     val rows = listing.orEmpty().distinctBy { it.docId }
                                     val parentName = stack.getOrNull(stack.size - 2)?.third
                                     LazyColumn(Modifier.fillMaxSize()) {
                                        if (parentName != null) item(key = "..") {
                                            FileRow(Icons.AutoMirrored.Rounded.ArrowBack, "Up to $parentName", null, highlighted = false, selected = false) {
                                                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                                            }
                                        }
                                        if (rows.isEmpty()) item { Text("This folder is empty.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        items(rows, key = { "f:" + it.docId }) { node ->
                                            if (node.isDirectory) {
                                                FileRow(Icons.Rounded.Folder, node.name, null, highlighted = false, selected = false, chevron = true) {
                                                    stack.add(Triple(node.docId, node.path, node.name))
                                                }
                                            } else {
                                                val ev = LaunchFileRules.evaluate(engine, node.path, siblings) { RuntimeRegistry.canLaunch(it.representative()) }
                                                val detected = detection?.launchCandidates?.any { it.path.equals(node.path, true) } == true
                                                FileRow(
                                                    iconFor(node.name), node.name,
                                                    subtitle = buildString {
                                                        if (node.size >= 0) append(formatSize(node.size))
                                                        if (detected) append(if (isEmpty()) "Detected" else " · Detected")
                                                        if (ev is LaunchEvaluation.Supported) append(if (isEmpty()) "Can start the game" else " · Can start the game")
                                                    }.ifEmpty { null },
                                                    highlighted = ev is LaunchEvaluation.Supported,
                                                    selected = selected.equals(node.path, true),
                                                ) { selected = node.path }
                                            }
                                        }
                                    }
                                    }
                                }
                            }
                            selected?.let { sel ->
                                when (val ev = evaluation) {
                                    is LaunchEvaluation.Supported -> ResultCard(
                                        ok = true, title = "Selected file: ${sel.substringAfterLast('/')}",
                                        lines = buildList {
                                            add("Runtime" to runtimeLabel(ev))
                                            if (ev.entry != sel) add("Runtime opens" to ev.entry)
                                            ev.configFile?.let { add("Configuration" to it) }
                                        },
                                        body = ev.explanation, modifier = Modifier.padding(12.dp),
                                    )
                                    is LaunchEvaluation.Unsupported -> ResultCard(false, "Can't start the game with ${sel.substringAfterLast('/')}", emptyList(), ev.reason, Modifier.padding(12.dp))
                                    null -> Unit
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    val canSave = !saving && (mode == Mode.AUTO && (autoCandidate != null || detection?.entry != null) || (mode == Mode.MANUAL && evaluation is LaunchEvaluation.Supported))
                    Button(enabled = canSave, onClick = {
                        saving = true
                        scope.launch {
                            runCatching {
                                if (mode == Mode.AUTO) repo.useAutomaticLaunch(game)
                                else repo.useManualLaunch(game, selected!!, evaluation as LaunchEvaluation.Supported)
                            }.onSuccess { onSaved(); onDismiss() }
                                .onFailure { Toast.makeText(ctx, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
                            saving = false
                        }
                    }) { Text(if (mode == Mode.AUTO) "Use automatic detection" else "Use this file") }
                }
            }
        }
    }
}

private fun com.acecore.app.core.model.EngineFamily.representative(): com.acecore.app.core.model.Engine = when (this) {
    com.acecore.app.core.model.EngineFamily.WEB -> com.acecore.app.core.model.Engine.HTML5
    com.acecore.app.core.model.EngineFamily.RGSS -> com.acecore.app.core.model.Engine.RPG_MAKER_XP
    com.acecore.app.core.model.EngineFamily.FLASH -> com.acecore.app.core.model.Engine.FLASH
    com.acecore.app.core.model.EngineFamily.EASYRPG -> com.acecore.app.core.model.Engine.RPG_MAKER_2K
    com.acecore.app.core.model.EngineFamily.RENPY -> com.acecore.app.core.model.Engine.RENPY
    com.acecore.app.core.model.EngineFamily.GODOT -> com.acecore.app.core.model.Engine.GODOT
    com.acecore.app.core.model.EngineFamily.UNSUPPORTED_NATIVE -> com.acecore.app.core.model.Engine.UNKNOWN
}

private fun runtimeLabel(ev: LaunchEvaluation.Supported): String = when (ev.family) {
    com.acecore.app.core.model.EngineFamily.RGSS -> "RGSS-compatible Android runtime (mkxp-z)"
    com.acecore.app.core.model.EngineFamily.FLASH -> "Ruffle (Flash) in the web runtime"
    com.acecore.app.core.model.EngineFamily.WEB -> "AceArcade web runtime"
    else -> ev.family.name
}

private fun iconFor(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.endsWith(".exe") -> Icons.Rounded.SportsEsports
        n.endsWith(".html") || n.endsWith(".htm") -> Icons.Rounded.Code
        n.endsWith(".swf") -> Icons.Rounded.Movie
        n.endsWith(".ini") || n.endsWith(".json") -> Icons.Rounded.Settings
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

private fun formatSize(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
private fun Breadcrumbs(names: List<String>, onClick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            if (i > 0) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                n, maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                color = if (i == names.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick(i) }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FileRow(
    icon: ImageVector, name: String, subtitle: String?, highlighted: Boolean, selected: Boolean, chevron: Boolean = false, onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) primary.copy(alpha = 0.14f) else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) primary else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (highlighted || chevron) primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = primary)
    }
}

@Composable
private fun ResultCard(ok: Boolean, title: String, lines: List<Pair<String, String>>, body: String?, modifier: Modifier = Modifier) {
    val color = if (ok) Color(0xFF1ED69B) else MaterialTheme.colorScheme.error
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Block, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        lines.forEach { (k, v) ->
            Row {
                Text("$k: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
        body?.let { Spacer(Modifier.height(2.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
