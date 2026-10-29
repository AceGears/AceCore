package com.acecore.app.feature.details

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.container
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.designsystem.AceCard
import com.acecore.app.core.designsystem.SectionHeader
import com.acecore.app.core.install.GameInstaller
import com.acecore.app.core.install.GameLocations
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.storage.DirectStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class FilesInfo(
    val directPath: File?,
    val directReadable: Boolean,
    val copyInstalled: Boolean,
    val copyOutdated: Boolean,
    val copyBytes: Long,
    val savesInCopy: List<File>,
)

/**
 * RPG Maker XP/VX/VX Ace games: where the files are read from (directly or from AceCore's private copy), with the
 * actions to switch, move saves out of the copy and free its space.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameFilesCard(g: GameEntity) {
    val ctx = LocalContext.current
    val container = ctx.container
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    val engine = g.effectiveEngine
    val mode = LayeredConfig(s.globalConfig, s.engineConfig[engine].orEmpty(), container.games.overrides(g), engine).string(ConfigKey.GAME_FILES)
    var refresh by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(DirectStorage.hasAccess(ctx)) }
    var info by remember { mutableStateOf<FilesInfo?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Re-check after returning from Android's permission page.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { access = DirectStorage.hasAccess(ctx); refresh++ } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(g.id, g.entry, refresh, access) {
        info = withContext(Dispatchers.IO) {
            val installer = GameInstaller(GameStorage.installDir(ctx, g.id))
            val installDir = GameStorage.installDir(ctx, g.id)
            val installed = installer.isInstalled()
            FilesInfo(
                directPath = GameLocations.directRoot(ctx, g),
                directReadable = access && GameLocations.directGameDir(ctx, g) != null,
                copyInstalled = installed,
                copyOutdated = installer.needsResync(),
                copyBytes = if (installDir.exists()) installDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L,
                savesInCopy = if (installed) container.saves.savesInPrivateCopy(g) else emptyList(),
            )
        }
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = DirectStorage.hasAccess(ctx); refresh++
    }
    fun requestAccess() {
        if (DirectStorage.usesSettingsScreen) {
            runCatching { ctx.startActivity(DirectStorage.settingsIntent(ctx)) }
                .recoverCatching { ctx.startActivity(DirectStorage.genericSettingsIntent()) }
        } else legacy.launch(DirectStorage.LEGACY_PERMISSIONS)
    }
    fun setMode(v: String) { scope.launch { container.games.setOverride(g.id, ConfigKey.GAME_FILES.id, v) } }

    SectionHeader("Game files")
    AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val i = info
            val direct = mode != "copy"
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = if (direct) i?.directReadable == true else i?.copyInstalled == true
                Icon(
                    if (direct) (if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Lock) else Icons.Rounded.Inventory2, null,
                    tint = if (ok) Color(0xFF1ED69B) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            direct && i?.directReadable == true -> "Played directly from your folder"
                            direct && i?.directPath == null -> "Direct play isn't possible for this folder"
                            direct && access -> "Game folder not found"
                            direct -> "Direct play: waiting for file access"
                            i?.copyInstalled == true -> "Played from AceArcade's private copy"
                            else -> "Private copy (made on first launch)"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            direct && i?.directReadable == true -> "Nothing is copied. Launches go straight to the engine."
                            direct && i?.directPath == null -> "Its storage provider has no ordinary file path, so AceArcade uses a private copy instead."
                            direct && access -> "Nothing is at ${i?.directPath?.absolutePath}. If you moved the game, remove it and add it again."
                            direct -> "Allow \"All files access\" so the engine can read the game where it is."
                            i?.copyInstalled == true -> "${formatBytes(i.copyBytes)} in AceArcade's storage" + if (i.copyOutdated) " · updates on next launch (the game was rescanned)" else " · up to date"
                            else -> "The first launch copies the game into AceArcade's storage."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (direct && i?.directReadable == true) i.directPath?.let { KeyValue("Folder", it.absolutePath) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (direct && i != null && i.directPath != null && !access) FilledTonalButton(onClick = { requestAccess() }) { Text("Allow file access") }
                if (direct) OutlinedButton(onClick = { setMode("copy") }) { Text("Use a private copy") }
                else OutlinedButton(onClick = { setMode("direct") }) { Text("Play directly instead") }
                if (!direct && i?.copyInstalled == true && !i.copyOutdated) TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { GameInstaller(GameStorage.installDir(ctx, g.id)).markOutdated() }
                        refresh++
                        Toast.makeText(ctx, "The copy will be updated on the next launch", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Sync on next launch") }
            }
            if (i != null && i.savesInCopy.isNotEmpty()) {
                Text(
                    "${i.savesInCopy.size} save file(s) exist only in AceArcade's private copy: ${i.savesInCopy.joinToString { it.name }}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (i.directReadable) FilledTonalButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { container.saves.copySavesToDirectFolder(g, i.savesInCopy) } }
                        r.onSuccess { res ->
                            Toast.makeText(
                                ctx,
                                "Copied ${res.copied.size} save(s) to your folder" + if (res.conflicts.isNotEmpty()) "; kept your folder's ${res.conflicts.joinToString()} (not overwritten)" else "",
                                Toast.LENGTH_LONG,
                            ).show()
                        }.onFailure { Toast.makeText(ctx, "Couldn't copy saves: ${it.message}", Toast.LENGTH_LONG).show() }
                        refresh++
                    }
                }) { Text("Copy these saves to your folder") }
            }
            if (direct && i != null && i.copyInstalled && i.directReadable) {
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete private copy (${formatBytes(i.copyBytes)})") }
            }
        }
    }

    if (confirmDelete) {
        val saves = info?.savesInCopy.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the private copy?") },
            text = {
                Text(
                    "This frees ${formatBytes(info?.copyBytes ?: 0)}. The game keeps running directly from your folder. " +
                        "Every file the game created in the copy (saves, settings) is backed up first (Cheats › Save backups). " +
                        "Your own game folder isn't touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch(kotlinx.coroutines.NonCancellable) {
                        withContext(Dispatchers.IO) {
                            container.saves.backupPrivateCopy(g, "Files the game created in the private copy (before deleting it)")
                            GameStorage.installDir(ctx, g.id).deleteRecursively()
                        }
                        refresh++
                    }
                }) { Text("Delete copy", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
