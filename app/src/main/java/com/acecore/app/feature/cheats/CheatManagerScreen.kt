package com.acecore.app.feature.cheats

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.container
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.designsystem.AceCard
import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.feature.details.formatBytes
import com.acecore.app.feature.saves.SaveBackup
import com.acecore.app.feature.settings.SettingsGroupTitle
import com.acecore.app.feature.settings.SettingsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which cheats apply to a game's engine (the running game is checked again before anything is applied). */
fun cheatsFor(engine: Engine): List<CheatDef> = CheatCatalog.ALL.filter { c ->
    when {
        c.essentialsOnly -> engine == Engine.POKEMON_ESSENTIALS
        c.rpgOnly -> engine.family == EngineFamily.RGSS && engine != Engine.POKEMON_ESSENTIALS
        else -> engine.family == EngineFamily.RGSS
    }
}

/**
 * Cheat Manager for one game: master switch, profiles of persistent switches, favourites, search, import/export,
 * reset, and save backups. One-off cheats (money, items, Pokémon editing) are applied from the in-game menu, where
 * AceCore can check what the running game supports.
 */
@Composable
fun CheatManagerScreen(gameId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = ctx.container.cheats
    val game by ctx.container.games.game(gameId).collectAsStateWithLifecycle(initialValue = null)
    val g = game ?: return
    var config by remember { mutableStateOf(store.load(gameId)) }
    fun save(c: CheatConfig) { config = c; store.save(gameId, c) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf<CheatCategory?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var profileDialog by remember { mutableStateOf<String?>(null) } // null = closed, "" = new, name = rename
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val supported = g.effectiveEngine.family == EngineFamily.RGSS

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { store.export(ctx, gameId, uri) }
                .onSuccess { Toast.makeText(ctx, "Cheat profiles exported", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { store.import(ctx, gameId, uri) }
                .onSuccess { n -> config = store.load(gameId); Toast.makeText(ctx, "Imported $n profile(s)", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    SettingsScaffold("Cheats", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(g.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            if (!supported) {
                Text(
                    "Cheats are available for RPG Maker XP/VX/VX Ace and Pokémon Essentials games. Web and Flash games don't expose their game state to AceCore.",
                    modifier = Modifier.padding(20.dp),
                )
                return@Column
            }
            AceCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Enable cheats", style = MaterialTheme.typography.titleMedium)
                            Text("Takes effect the next time the game starts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(config.enabled, { save(config.copy(enabled = it)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Back up saves first", style = MaterialTheme.typography.bodyLarge)
                            Text("Before the first cheat of each session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(config.autoBackup, { save(config.copy(autoBackup = it)) })
                    }
                    Text(
                        "How it works: AceCore calls the game's own functions while it runs (it never edits save files directly). " +
                            "Each cheat is offered only if this game version supports it, and changes become permanent when you save in the game. " +
                            "Open the in-game menu (Back button or the menu button) › Cheats.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsGroupTitle("Profile")
            ProfileRow(
                config = config,
                onSelect = { save(config.copy(activeProfile = it)) },
                onNew = { profileDialog = "" },
                onRename = { profileDialog = config.activeProfile },
                onDelete = {
                    if (config.activeProfile != CheatConfig.DEFAULT_PROFILE) save(config.copy(profiles = config.profiles - config.activeProfile, activeProfile = CheatConfig.DEFAULT_PROFILE))
                },
            )

            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Search cheats") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Clear") } },
                shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val all = cheatsFor(g.effectiveEngine)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == null && !favoritesOnly, onClick = { tab = null; favoritesOnly = false }, label = { Text("All") })
                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly; tab = null }, label = { Text("Favourites") },
                    leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(16.dp)) })
                all.map { it.category }.distinct().forEach { c ->
                    FilterChip(selected = tab == c, onClick = { tab = c; favoritesOnly = false }, label = { Text(c.label) })
                }
            }
            val shown = all.filter { c ->
                (tab == null || c.category == tab) && (!favoritesOnly || c.id in config.favorites) &&
                    (query.isBlank() || c.title.contains(query, true) || c.description.contains(query, true) || c.category.label.contains(query, true))
            }
            if (shown.isEmpty()) Text("No cheats match.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            shown.forEach { c ->
                CheatRow(c, config, onFavorite = {
                    save(config.copy(favorites = if (c.id in config.favorites) config.favorites - c.id else config.favorites + c.id))
                }, onFlag = { v ->
                    val p = config.profile
                    save(config.withProfile(p.copy(flags = p.flags + (c.flag!! to v))))
                })
            }

            SettingsGroupTitle("Import, export & reset")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("${g.title.take(40)}-cheats.json") }) { Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
                }
                TextButton(onClick = { confirmReset = true }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            }

            SaveBackupsSection(g)
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    profileDialog?.let { original ->
        var name by remember(original) { mutableStateOf(original) }
        val taken = name.trim() != original && name.trim() in config.profiles.keys
        AlertDialog(
            onDismissRequest = { profileDialog = null },
            title = { Text(if (original.isEmpty()) "New profile" else "Rename profile") },
            text = { OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("Name") }, isError = taken, supportingText = { if (taken) Text("Already exists") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !taken, onClick = {
                    val n = name.trim()
                    save(
                        if (original.isEmpty()) config.copy(profiles = config.profiles + (n to config.profile), activeProfile = n)
                        else config.copy(profiles = (config.profiles - original) + (n to (config.profiles[original] ?: CheatProfile())), activeProfile = n),
                    )
                    profileDialog = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { profileDialog = null }) { Text("Cancel") } },
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset cheats?") },
        text = { Text("Turns cheats off and deletes this game's cheat profiles and favourites. Save backups are kept.") },
        confirmButton = { TextButton(onClick = { store.reset(gameId); config = store.load(gameId); confirmReset = false }) { Text("Reset", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileRow(config: CheatConfig, onSelect: (String) -> Unit, onNew: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(config.activeProfile, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${config.activeCount} on", style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(open, { open = false }) {
                config.profiles.keys.sorted().forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { open = false; onSelect(p) }) }
            }
        }
        IconButton(onClick = onNew) { Icon(Icons.Rounded.Add, "New profile") }
        IconButton(onClick = onRename) { Icon(Icons.Rounded.Edit, "Rename profile") }
        IconButton(onClick = onDelete, enabled = config.activeProfile != CheatConfig.DEFAULT_PROFILE) { Icon(Icons.Rounded.Delete, "Delete profile") }
    }
}

@Composable
private fun CheatRow(c: CheatDef, config: CheatConfig, onFavorite: () -> Unit, onFlag: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall)
                Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (c.id in config.favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (c.id in config.favorites) "Remove from favourites" else "Add to favourites",
                    tint = if (c.id in config.favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (c.kind == CheatKind.TOGGLE) Switch(config.flags[c.flag] == "1", { onFlag(if (it) "1" else "0") })
        }
        when (c.kind) {
            CheatKind.CHOICE -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                c.choices.forEachIndexed { i, (v, label) ->
                    SegmentedButton(selected = (config.flags[c.flag] ?: "0") == v, onClick = { onFlag(v) }, shape = SegmentedButtonDefaults.itemShape(i, c.choices.size)) {
                        Text(label, maxLines = 1)
                    }
                }
            }
            CheatKind.ACTION -> Text("Apply in game › Cheats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            CheatKind.TOGGLE -> Unit
        }
    }
}

/** Save backups: back up now, restore (current saves are backed up first), delete. */
@Composable
private fun SaveBackupsSection(g: GameEntity) {
    val ctx = LocalContext.current
    val saves = ctx.container.saves
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<List<com.acecore.app.feature.saves.SaveFile>>(emptyList()) }
    var backups by remember { mutableStateOf<List<SaveBackup>>(emptyList()) }
    var restoring by remember { mutableStateOf<SaveBackup?>(null) }
    LaunchedEffect(g.id, version) {
        val direct = ctx.container.games.usesDirectFiles(g)
        withContext(Dispatchers.IO) { current = saves.findSaves(g, direct); backups = saves.backups(g.id) }
    }
    SettingsGroupTitle("Save backups")
    Text(
        if (current.isEmpty()) "No save files found yet (they appear after you save in the game)."
        else "${current.size} save file(s): " + current.take(4).joinToString { it.relative } + if (current.size > 4) "…" else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    OutlinedButton(enabled = current.isNotEmpty(), onClick = {
        scope.launch {
            val b = withContext(Dispatchers.IO) { runCatching { saves.backup(g, ctx.container.games.usesDirectFiles(g), "Manual backup") } }
            b.onSuccess { Toast.makeText(ctx, if (it == null) "Nothing to back up" else "Backed up ${it.files} file(s)", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Backup failed: ${it.message}", Toast.LENGTH_LONG).show() }
            version++
        }
    }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Icon(Icons.Rounded.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back up now") }
    backups.forEach { b ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(DateUtils.formatDateTime(ctx, b.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), style = MaterialTheme.typography.bodyMedium)
                Text("${b.reason} · ${b.files} file(s) · ${formatBytes(b.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { restoring = b }) { Icon(Icons.Rounded.Restore, "Restore") }
            IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { saves.delete(b) }; version++ } }) { Icon(Icons.Rounded.Delete, "Delete backup") }
        }
    }
    restoring?.let { b ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore this backup?") },
            text = { Text("Your current saves are backed up first, then replaced with the ones from ${DateUtils.getRelativeTimeSpanString(b.createdAt)}. Close the game before restoring.") },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { saves.restore(g, ctx.container.games.usesDirectFiles(g), b) } }
                        r.onSuccess { res ->
                            Toast.makeText(ctx, "Restored ${res.restored} file(s)" + if (res.skipped.isNotEmpty()) "; couldn't write ${res.skipped.joinToString()} (game folder not available)" else "", Toast.LENGTH_LONG).show()
                        }.onFailure { Toast.makeText(ctx, "Restore failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        version++
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } },
        )
    }
    HorizontalDivider(Modifier.padding(top = 12.dp).height(0.dp))
}
