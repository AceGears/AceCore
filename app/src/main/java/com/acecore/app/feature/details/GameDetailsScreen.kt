package com.acecore.app.feature.details

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.container
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.designsystem.AceCard
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.designsystem.InfoRow
import com.acecore.app.core.designsystem.SectionHeader
import com.acecore.app.core.designsystem.StatusChip
import com.acecore.app.core.designsystem.aceGlow
import com.acecore.app.core.detection.Severity
import com.acecore.app.core.model.Engine
import com.acecore.app.feature.LocalGameLauncher
import com.acecore.app.feature.importer.severityMark
import com.acecore.app.feature.library.CoverImage
import com.acecore.app.feature.library.GameDialog
import com.acecore.app.feature.library.GameDialogs
import com.acecore.app.feature.library.rememberCoverPicker
import com.acecore.app.runtime.RuntimeRegistry
import com.acecore.app.runtime.web.GameActivity
import kotlinx.coroutines.launch

/** Game details + Compatibility Center: detected engine, runtime, diagnostics, evidence, launch file, actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameDetailsScreen(gameId: Long, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    val launcher = LocalGameLauncher.current
    val game by repo.game(gameId).collectAsStateWithLifecycle(initialValue = null)
    val g = game ?: run { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)); return }
    val detection = remember(g.detectionJson) { repo.decodeDetection(g) }
    val engine = g.effectiveEngine
    val runtime = RuntimeRegistry.info(engine)
    var dialog by remember { mutableStateOf(GameDialog.NONE) }
    var showEvidence by remember { mutableStateOf(false) }
    var engineDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var rescanning by remember { mutableStateOf(false) }
    val pickCover = rememberCoverPicker(g)
    val t = AceTheme.tokens
    val access = remember(g.treeUri) { repo.hasAccess(g.treeUri) }

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                CoverImage(g, Modifier.fillMaxSize().blur(24.dp))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.3f), MaterialTheme.colorScheme.background))))
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
                            DropdownMenuItem(text = { Text("Edit title & category") }, onClick = { menu = false; dialog = GameDialog.RENAME })
                            DropdownMenuItem(text = { Text("Override detected engine") }, onClick = { menu = false; engineDialog = true })
                            if (g.customCoverPath != null) DropdownMenuItem(text = { Text("Restore detected cover") }, onClick = { menu = false; scope.launch { repo.clearCustomCover(g) } })
                            DropdownMenuItem(text = { Text(if (g.hidden) "Unhide" else "Hide from library") }, onClick = { menu = false; scope.launch { repo.setHidden(g.id, !g.hidden) } })
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                    CoverImage(g, Modifier.width(112.dp).aspectRatio(0.75f).aceGlow(t.glow, t.glowIntensity, 14.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                        Text(g.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(engine.displayName + (g.engineVersion?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        StatusChip(g.compatStatus)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { launcher.launch(g) }, enabled = GameRepository.isPlayable(g) && access,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                }
                OutlinedButton(onClick = { scope.launch { repo.setFavorite(g.id, !g.favorite) } }, modifier = Modifier.height(52.dp)) {
                    Icon(if (g.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (g.favorite) "Unfavorite" else "Favorite")
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.height(52.dp)) { Icon(Icons.Rounded.Tune, "Game settings") }
            }
        }
        if (!access) item {
            Warning("AceCore no longer has permission to read this folder. Remove the game and add its folder again.")
        }
        g.lastError?.let { err -> item { Warning("Last launch problem: $err") } }

        item {
            AceCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow(Icons.Rounded.Timer, "Total playtime", if (g.playtimeMs > 0) GameActivity.formatDuration(g.playtimeMs) else "Not played yet")
                    InfoRow(Icons.Rounded.CalendarMonth, "Last played", g.lastPlayedAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "Never")
                    InfoRow(Icons.Rounded.Folder, "Folder", g.folderLabel + (if (g.contentRoot.isNotEmpty()) "  (web root: ${g.contentRoot})" else ""))
                    InfoRow(Icons.Rounded.Description, "Launch file", buildString {
                        append(g.launchFile ?: "—")
                        if (g.entry != null && g.entry != g.launchFile) append("  →  runs ").append(g.entry)
                    })
                    if (g.category != null) InfoRow(Icons.Rounded.Folder, "Category", g.category)
                }
            }
        }
        item {
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = GameDialog.LAUNCH_FILE }) { Icon(Icons.Rounded.Description, null); Spacer(Modifier.width(6.dp)); Text("Change launch file") }
                OutlinedButton(onClick = pickCover) { Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(6.dp)); Text("Change cover") }
                OutlinedButton(enabled = !rescanning && access, onClick = {
                    rescanning = true
                    scope.launch {
                        runCatching { repo.rescan(g) }
                            .onSuccess { Toast.makeText(ctx, "Rescanned: ${it.effectiveEngine.displayName}", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(ctx, "Rescan failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        rescanning = false
                    }
                }) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text(if (rescanning) "Rescanning…" else "Rescan") }
                OutlinedButton(onClick = { dialog = GameDialog.REMOVE }) { Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(6.dp)); Text("Remove") }
            }
        }

        item { SectionHeader("Compatibility center") }
        item {
            AceCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(Icons.Rounded.Memory, "Detected engine", buildString {
                        append(Engine.fromName(g.engine).displayName)
                        g.engineVersion?.let { append(" ").append(it) }
                        append(" · confidence ").append(g.confidence).append("%")
                        if (g.engineOverride != null) append("\nOverridden to ").append(engine.displayName)
                    })
                    InfoRow(Icons.Rounded.Memory, "Runtime", runtime.id.displayName + if (runtime.bundled) " (bundled)" else " (not in this version)")
                    Text(runtime.status, style = MaterialTheme.typography.bodyMedium)
                    runtime.blocker?.let { Text("What's needed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    runtime.license?.let { Text("License: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (detection != null && detection.diagnostics.isNotEmpty()) {
            item { SectionHeader("Diagnostics") }
            item {
                AceCard(Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        detection.diagnostics.forEach { d ->
                            Row {
                                Text(severityMark(d.severity), color = when (d.severity) {
                                    Severity.ERROR -> MaterialTheme.colorScheme.error; Severity.WARNING -> Color(0xFFE8B931); Severity.INFO -> MaterialTheme.colorScheme.primary
                                })
                                Spacer(Modifier.width(10.dp))
                                Text(d.message, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        if (detection != null) {
            item {
                Row(Modifier.fillMaxWidth().clickable { showEvidence = !showEvidence }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Detection evidence", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (showEvidence) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            item {
                AnimatedVisibility(showEvidence) {
                    AceCard(Modifier.padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            detection.scores.forEach { s ->
                                Text("${s.engine.displayName}: ${s.score} points", style = MaterialTheme.typography.titleSmall)
                                Text(s.evidence.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            if (detection.tags.isNotEmpty()) Text("Tags: ${detection.tags.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }

    GameDialogs(g, dialog, onClose = { dialog = GameDialog.NONE }, onRemoved = onBack)

    if (engineDialog) {
        var choice by remember { mutableStateOf(g.engineOverride?.let { Engine.fromName(it) }) }
        AlertDialog(
            onDismissRequest = { engineDialog = false },
            title = { Text("Override engine") },
            text = {
                LazyColumn(Modifier.height(380.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().clickable { choice = null }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(choice == null, { choice = null }); Text("Use detected (${Engine.fromName(g.engine).displayName})")
                        }
                    }
                    Engine.entries.filter { it != Engine.UNKNOWN }.forEach { e ->
                        item {
                            Row(Modifier.fillMaxWidth().clickable { choice = e }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(choice == e, { choice = e })
                                Text(e.displayName + if (RuntimeRegistry.canLaunch(e)) "" else "  (not playable yet)")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { repo.setEngineOverride(g, choice) }; engineDialog = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { engineDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Warning(text: String) {
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
