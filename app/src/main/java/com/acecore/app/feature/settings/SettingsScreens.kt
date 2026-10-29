package com.acecore.app.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.InputDevice
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.BuildConfig
import com.acecore.app.container
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.ConfigSource
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.datastore.AppSettings
import com.acecore.app.core.datastore.LibraryLayout
import com.acecore.app.core.datastore.ThemePreset
import com.acecore.app.core.datastore.ThemeSettings
import com.acecore.app.core.designsystem.accentArgbFor
import com.acecore.app.core.designsystem.toColor
import com.acecore.app.core.model.Engine
import com.acecore.app.runtime.RuntimeRegistry
import kotlinx.coroutines.launch

private data class SectionDef(val id: String, val title: String, val subtitle: String, val icon: ImageVector, val keywords: List<String>)

private val SECTIONS = listOf(
    SectionDef("appearance", "Appearance", "Themes, colours, glow, corners, text size, animation", Icons.Rounded.Palette,
        listOf("theme", "dark", "light", "amoled", "color", "colour", "accent", "glow", "gradient", "corner", "radius", "border", "text", "font", "animation", "motion", "material you", "dynamic")),
    SectionDef("library", "Library", "Layout, covers, sorting, game folders", Icons.Rounded.Collections,
        listOf("grid", "list", "carousel", "cover", "spacing", "title", "sort", "hidden", "folder", "directory", "rescan")),
    SectionDef("controls", "Controllers & input", "On-screen controls, controller mapping, dead zone", Icons.Rounded.Gamepad,
        listOf("controller", "gamepad", "mapping", "button", "virtual", "touch", "haptic", "vibration", "deadzone", "opacity", "keyboard")),
    SectionDef("display", "Display & graphics", "Orientation, fullscreen, scaling filter, FPS overlay", Icons.Rounded.DisplaySettings,
        listOf("orientation", "landscape", "portrait", "immersive", "fullscreen", "filter", "sharp", "smooth", "pixel", "fps", "performance", "screen")),
    SectionDef("audio", "Audio", "Volume and background behaviour", Icons.Rounded.Audiotrack, listOf("volume", "sound", "music", "audio", "background")),
    SectionDef("engines", "Game engines", "Runtime status and per-engine defaults", Icons.Rounded.Memory,
        listOf("rpg maker", "mv", "mz", "xp", "vx", "ace", "essentials", "pokemon", "renpy", "godot", "flash", "construct", "tyrano", "html5", "runtime")),
    SectionDef("storage", "Storage & data", "File cache, library backup and restore", Icons.Rounded.Storage,
        listOf("cache", "backup", "restore", "export", "import", "storage", "clear")),
    SectionDef("advanced", "Advanced", "Network access, debugging, diagnostics", Icons.Rounded.Build,
        listOf("network", "internet", "debug", "diagnostic", "log", "developer")),
    SectionDef("about", "About AceArcade", "Version, licenses, privacy", Icons.Rounded.Info, listOf("version", "license", "privacy", "about", "open source")),
)

@Composable
fun SettingsHomeScreen(onBack: () -> Unit, onSection: (String) -> Unit, onEngine: (Engine) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    SettingsScaffold("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Clear") } },
                shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val q = query.trim().lowercase()
            val keyHits = if (q.isEmpty()) emptyList() else ConfigKey.entries.filter { it.title.lowercase().contains(q) || it.description.lowercase().contains(q) }
            SECTIONS.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q) || it.keywords.any { k -> k.contains(q) } }
                .forEach { NavRow(it.icon, it.title, it.subtitle) { onSection(it.id) } }
            if (keyHits.isNotEmpty()) {
                SettingsGroupTitle("Matching options")
                keyHits.forEach { k ->
                    val section = when (k.section) { "Controls", "Gameplay" -> "controls"; "Display" -> "display"; "Audio" -> "audio"; else -> "advanced" }
                    NavRow(null, k.title, k.description) { onSection(section) }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
fun SettingsSectionScreen(section: String, onBack: () -> Unit, onEngine: (Engine) -> Unit, onCategories: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = ctx.container.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    val def = SECTIONS.firstOrNull { it.id == section }
    SettingsScaffold(def?.title ?: "Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (section) {
                "appearance" -> AppearanceSection(s)
                "library" -> LibrarySection(s, onCategories)
                "controls" -> { ConnectedControllers(); GlobalConfig(s, "Controls") }
                "display" -> GlobalConfig(s, "Display")
                "audio" -> GlobalConfig(s, "Audio")
                "engines" -> EnginesSection(s, onEngine)
                "storage" -> StorageSection()
                "advanced" -> AdvancedSection(s)
                "about" -> AboutSection()
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun GlobalConfig(s: AppSettings, section: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys = ConfigKey.entries.filter { it.section == section }
    Text(
        "These are global defaults. Each engine and each game can override them (Game engines › engine, or a game's Game settings).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    ConfigEditor(keys, LayeredConfig(s.globalConfig, emptyMap(), emptyMap()), ConfigSource.GLOBAL) { key, value ->
        scope.launch { ctx.container.settings.setGlobalConfig(key, value) }
    }
}

// ------------------------------------------------------------------ appearance

@Composable
private fun AppearanceSection(s: AppSettings) {
    val ctx = LocalContext.current
    val repo = ctx.container.settings
    val scope = rememberCoroutineScope()
    val t = s.theme
    fun update(f: (ThemeSettings) -> ThemeSettings) { scope.launch { repo.updateTheme(f) } }
    var confirmReset by remember { mutableStateOf(false) }

    SettingsGroupTitle("Theme")
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ThemePreset.entries.filter { it != ThemePreset.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }) { p ->
            ThemePreview(p, t, selected = t.preset == p) { update { it.copy(preset = p) } }
        }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        Text("Dynamic Material You colours need Android 12 or newer.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    }
    SwitchRow("Follow system dark mode", "Switch between Signature Dark and Light automatically.", t.followSystem,
        enabled = t.preset == ThemePreset.SIGNATURE_DARK || t.preset == ThemePreset.SIGNATURE_LIGHT) { v -> update { it.copy(followSystem = v) } }

    SettingsGroupTitle("Custom colours")
    Text(
        if (t.preset == ThemePreset.CUSTOM) "Editing your custom theme." else "Changing a colour switches to the Custom theme, starting from these values.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    fun custom(f: (ThemeSettings) -> ThemeSettings) = update { f(it).copy(preset = ThemePreset.CUSTOM) }
    ColorRow("Primary", t.primary) { c -> custom { it.copy(primary = c) } }
    ColorRow("Secondary", t.secondary) { c -> custom { it.copy(secondary = c) } }
    ColorRow("Accent", t.accent) { c -> custom { it.copy(accent = c) } }
    ColorRow("Background", t.background) { c -> custom { it.copy(background = c) } }
    ColorRow("Cards & surfaces", t.surface) { c -> custom { it.copy(surface = c) } }
    ColorRow("Glow colour", t.glowColor) { c -> custom { it.copy(glowColor = c) } }

    SettingsGroupTitle("Effects & shape")
    SliderRow("Glow intensity", null, t.glowIntensity, 0..100, "%") { v -> update { it.copy(glowIntensity = v) } }
    SliderRow("Gradient intensity", null, t.gradientIntensity, 0..100, "%") { v -> update { it.copy(gradientIntensity = v) } }
    SliderRow("Surface opacity", "Lower values let the background show through cards.", t.surfaceOpacity, 40..100, "%") { v -> update { it.copy(surfaceOpacity = v) } }
    SliderRow("Border thickness", null, t.borderThickness, 0..4, " dp", steps = 3) { v -> update { it.copy(borderThickness = v) } }
    SliderRow("Corner radius", null, t.cornerRadius, 0..32, " dp") { v -> update { it.copy(cornerRadius = v) } }

    SettingsGroupTitle("Text & motion")
    SliderRow("Text size", "In addition to Android's font size setting.", t.textScale, 80..150, "%") { v -> update { it.copy(textScale = v) } }
    SliderRow("Animation speed", "Higher is faster.", t.animationSpeed, 25..300, "%") { v -> update { it.copy(animationSpeed = v) } }
    SwitchRow("Reduce motion", "Turn off non-essential animations. Also follows Android's 'Remove animations'.", s.reduceMotion) { v ->
        scope.launch { repo.setReduceMotion(v) }
    }
    OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.padding(20.dp)) { Text("Reset appearance to defaults") }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset appearance?") },
        text = { Text("Theme, colours and effects return to the AceArcade Neon defaults.") },
        confirmButton = { TextButton(onClick = { scope.launch { repo.resetTheme() }; confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ThemePreview(preset: ThemePreset, current: ThemeSettings, selected: Boolean, onClick: () -> Unit) {
    val previewTheme = current.copy(preset = preset)
    val accent = accentArgbFor(previewTheme).toColor()
    val bg = when (preset) {
        ThemePreset.SIGNATURE_LIGHT -> Color(0xFFF7F5FC)
        ThemePreset.AMOLED -> Color.Black
        ThemePreset.MIDNIGHT_BLUE -> Color(0xFF070B18)
        ThemePreset.CRIMSON_RED -> Color(0xFF140509)
        ThemePreset.ROYAL_PURPLE -> Color(0xFF0D0816)
        ThemePreset.EMERALD_GREEN -> Color(0xFF04120D)
        ThemePreset.GOLD_EDITION -> Color(0xFF0E0B05)
        ThemePreset.CUSTOM -> current.background.toColor()
        ThemePreset.DYNAMIC -> MaterialTheme.colorScheme.background
        else -> Color(0xFF06051A)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(96.dp, 120.dp).clip(RoundedCornerShape(16.dp)).background(bg)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            Box(Modifier.padding(10.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.2f)))))
            Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) { Box(Modifier.size(30.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.35f)).border(1.dp, accent, RoundedCornerShape(6.dp))) }
            }
            if (selected) Icon(Icons.Rounded.Check, "Selected", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(26.dp).background(accent, CircleShape).padding(4.dp))
        }
        Text(preset.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

// ------------------------------------------------------------------ library

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySection(s: AppSettings, onCategories: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.settings
    val games = ctx.container.games
    val scope = rememberCoroutineScope()
    val lib = s.library
    fun update(f: (com.acecore.app.core.datastore.LibrarySettings) -> com.acecore.app.core.datastore.LibrarySettings) { scope.launch { repo.updateLibrary(f) } }
    var folderVersion by remember { mutableIntStateOf(0) }
    val folders = remember(folderVersion) { games.grantedFolders() }
    val allGames by games.games.collectAsStateWithLifecycle(initialValue = emptyList())
    var rescanning by remember { mutableStateOf(false) }

    SettingsGroupTitle("Layout")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val layouts = listOf(LibraryLayout.GRID, LibraryLayout.COMPACT, LibraryLayout.LARGE, LibraryLayout.LIST)
        layouts.forEachIndexed { i, l ->
            SegmentedButton(
                selected = lib.layout == l, onClick = { update { it.copy(layout = l) } },
                shape = SegmentedButtonDefaults.itemShape(i, layouts.size),
            ) { Text(l.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
        }
    }
    SliderRow("Cover size", "Used when columns are set to Auto.", lib.coverWidth, 80..200, " dp") { v -> update { it.copy(coverWidth = v) } }
    ChoiceRow("Columns", null, listOf("0" to "Auto") + (2..6).map { "$it" to "$it" }, lib.columns.toString()) { v -> update { it.copy(columns = v.toInt()) } }
    ChoiceRow("Cover shape", null, com.acecore.app.core.datastore.CoverAspect.entries.map { it.name to it.label }, lib.aspect.name) { v ->
        update { it.copy(aspect = com.acecore.app.core.datastore.CoverAspect.valueOf(v)) }
    }
    SliderRow("Spacing", null, lib.spacing, 4..28, " dp") { v -> update { it.copy(spacing = v) } }
    SliderRow("Cover corner radius", null, lib.coverRadius, 0..28, " dp") { v -> update { it.copy(coverRadius = v) } }
    SwitchRow("Show game titles", null, lib.showTitles) { v -> update { it.copy(showTitles = v) } }
    SwitchRow("Cover borders", null, lib.coverBorder) { v -> update { it.copy(coverBorder = v) } }
    SwitchRow("Cover glow", "Uses the border colour (or the theme's glow colour) and the theme's glow intensity.", lib.coverGlow) { v -> update { it.copy(coverGlow = v) } }
    ColorRow("Border & glow colour", if (lib.borderColor == 0L) s.theme.glowColor else lib.borderColor) { c -> update { it.copy(borderColor = c) } }
    if (lib.borderColor != 0L) TextButton(onClick = { update { it.copy(borderColor = 0L) } }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Follow theme colour") }
    SwitchRow("Show hidden games", null, lib.showHidden) { v -> update { it.copy(showHidden = v) } }

    SettingsGroupTitle("Categories")
    ChoiceRow("Category bar", null, com.acecore.app.core.datastore.CategoryBarStyle.entries.map { it.name to it.label }, lib.categoryBar.name) { v ->
        update { it.copy(categoryBar = com.acecore.app.core.datastore.CategoryBarStyle.valueOf(v)) }
    }
    SwitchRow("Show game counts", null, lib.showCategoryCounts) { v -> update { it.copy(showCategoryCounts = v) } }
    SwitchRow("Show hidden categories", "Hidden categories stay out of the library's category bar unless this is on.", lib.showHiddenCategories) { v ->
        update { it.copy(showHiddenCategories = v) }
    }
    NavRow(null, "Manage categories", "Create, reorder, hide, delete, link folders") { onCategories() }

    SettingsGroupTitle("Game folders")
    Text(
        "Folders you picked for AceCore. Web and Flash games are read through these grants. RPG Maker XP/VX/VX Ace games " +
            "also need \"All files access\" (see Storage & data) to run in place without a copy.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    if (folders.isEmpty()) Text("No folders yet. Use “Add game” in the library.", modifier = Modifier.padding(20.dp))
    folders.forEach { uri ->
        val used = allGames.count { it.treeUri == uri.toString() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Uri.decode(uri.lastPathSegment ?: uri.toString()), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$used game(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (used == 0) TextButton(onClick = { scope.launch { games.revokeFolder(uri); folderVersion++ } }) { Text("Forget") }
        }
    }
    OutlinedButton(
        enabled = !rescanning && allGames.isNotEmpty(),
        onClick = {
            rescanning = true
            scope.launch {
                var ok = 0
                allGames.forEach { g -> if (games.hasAccess(g.treeUri) && runCatching { games.rescan(g) }.isSuccess) ok++ }
                rescanning = false
                Toast.makeText(ctx, "Rescanned $ok of ${allGames.size} games", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.padding(20.dp),
    ) { Text(if (rescanning) "Rescanning…" else "Rescan all games") }
}

// ------------------------------------------------------------------ controllers

@Composable
private fun ConnectedControllers() {
    val pads = remember {
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { d -> !d.isVirtual && ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) }
            .map { it.name }.distinct()
    }
    SettingsGroupTitle("Connected controllers")
    if (pads.isEmpty()) Text("No controller detected. Connect one over Bluetooth or USB; it is mapped automatically.",
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
    pads.forEach { Text("🎮  $it", modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
}

// ------------------------------------------------------------------ storage

@Composable
private fun StorageSection() {
    val ctx = LocalContext.current
    val games = ctx.container.games
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(games.cacheStats()) }
    var restoreMsg by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { games.exportLibrary(uri) }
                .onSuccess { Toast.makeText(ctx, "Library backup saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Backup failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            restoreMsg = runCatching { games.importLibrary(uri) }.fold(
                { (restored, needs) -> "Restored $restored game(s)." + if (needs > 0) " $needs need their folder re-added on this device (Android doesn't transfer folder permissions)." else "" },
                { "Restore failed: ${it.message}" },
            )
        }
    }

    SettingsGroupTitle("Asset path cache")
    Text(
        "AceArcade indexes each game's files so they load without repeated folder searches, and fixes upper/lower-case mismatches from Windows games. The cache rebuilds automatically after a rescan.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    Text("${stats.first} game index(es) · ${formatBytes(stats.second)}", modifier = Modifier.padding(20.dp))
    OutlinedButton(onClick = { games.clearPathCaches(); stats = games.cacheStats() }, modifier = Modifier.padding(horizontal = 20.dp)) { Text("Clear file caches") }

    SettingsGroupTitle("Direct game access")
    run {
        var access by remember { mutableStateOf(com.acecore.app.core.storage.DirectStorage.hasAccess(ctx)) }
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(owner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) access = com.acecore.app.core.storage.DirectStorage.hasAccess(ctx)
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            access = com.acecore.app.core.storage.DirectStorage.hasAccess(ctx)
        }
        Text(
            "RPG Maker XP/VX/VX Ace and Pokémon Essentials engines read games as ordinary files. With Android's \"All files access\" " +
                "AceArcade runs them straight from your folders: no copying, instant launches. Without it, each game is copied into AceArcade's storage once.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(if (access) "Allowed: games run in place." else "Not allowed: games use a private copy.", modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
        OutlinedButton(onClick = {
            if (com.acecore.app.core.storage.DirectStorage.usesSettingsScreen) {
                runCatching { ctx.startActivity(com.acecore.app.core.storage.DirectStorage.settingsIntent(ctx)) }
                    .recoverCatching { ctx.startActivity(com.acecore.app.core.storage.DirectStorage.genericSettingsIntent()) }
            } else legacy.launch(com.acecore.app.core.storage.DirectStorage.LEGACY_PERMISSIONS)
        }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Text(if (access) "Manage in Android settings" else "Allow all files access") }
    }

    SettingsGroupTitle("Private game copies")
    var usage by remember { mutableStateOf<com.acecore.app.core.data.GameRepository.StorageUsage?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { usage = games.storageUsage() }
    Text(
        "Copies exist only for games that use “Private copy” (or were played before direct access was allowed). " +
            "Saves made while playing from a copy are stored in it; a game's page can copy them to your folder and delete the copy.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    usage?.let { u ->
        Text("${u.installedGames} game(s) · ${formatBytes(u.bytes)}", modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
        if (u.orphanGames > 0) {
            Text(
                "${u.orphanGames} leftover copy(ies) from removed games · ${formatBytes(u.orphanBytes)}. These may contain saves.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.padding(horizontal = 20.dp)) { Text("Delete leftover copies") }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Delete leftover copies?") },
                text = { Text("This permanently deletes AceArcade's copies of games you removed from the library, including any saves inside them. Your original game folders are not affected.") },
                confirmButton = { TextButton(onClick = { confirm = false; scope.launch { games.deleteOrphanData(); usage = games.storageUsage() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            )
        }
    }

    SettingsGroupTitle("Library backup")
    Text(
        "Exports library metadata (titles, covers chosen from game folders, categories, playtime, per-game settings) and your AceArcade settings. It does NOT contain game files or game saves.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { exportLauncher.launch("acecore-library-backup.json") }) { Text("Export") }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Restore") }
    }
    restoreMsg?.let { Text(it, modifier = Modifier.padding(horizontal = 20.dp)) }

    SettingsGroupTitle("Game saves")
    Text(
        "Web-runtime games (RPG Maker MV/MZ, HTML5, Construct, Tyrano, Flash) store saves in AceArcade's private browser storage, separately for each game. " +
            "RPG Maker XP/VX/VX Ace games save where the game decides: in direct mode usually inside your game folder, in copy mode inside AceArcade's copy. " +
            "Pokémon Essentials v19+ saves in a private per-game data folder. Backups and restores are in each game's Cheats page (Save backups). " +
            "AceArcade's own data is removed if you uninstall it or clear its data; files in your game folders are not.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
}

private fun formatBytes(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

// ------------------------------------------------------------------ advanced

@Composable
private fun AdvancedSection(s: AppSettings) {
    val ctx = LocalContext.current
    val repo = ctx.container.settings
    val games by ctx.container.games.games.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    GlobalConfig(s, "Advanced")
    SettingsGroupTitle("Developer")
    SwitchRow("WebView debugging", "Allow inspecting running web games from Chrome on a PC (chrome://inspect). Turn off when not needed.", s.webDebugging) { v ->
        scope.launch { repo.setWebDebugging(v) }
    }
    CrashReportsSection()
    SettingsGroupTitle("Diagnostics")
    Text(
        "Copies a plain-text report (device, Android & WebView version, and each game's engine and status) to the clipboard. It contains no file contents or personal data beyond game titles.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    OutlinedButton(onClick = {
        val report = buildDiagnostics(ctx, games)
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AceArcade diagnostics", report))
        Toast.makeText(ctx, "Diagnostic report copied", Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.padding(20.dp)) { Text("Copy diagnostic report") }
}

private fun buildDiagnostics(@Suppress("UNUSED_PARAMETER") ctx: Context, games: List<com.acecore.app.core.database.GameEntity>): String = buildString {
    appendLine("AceArcade ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
    val webView = runCatching { if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } else null }.getOrNull()
    appendLine("WebView: ${webView ?: "unknown"}")
    appendLine("Runtimes: " + RuntimeRegistry.all().joinToString { "${it.id.name}=${if (it.bundled) "bundled" else "absent"}" })
    appendLine("Games (${games.size}):")
    games.forEach { g ->
        appendLine("- ${g.title} | ${g.effectiveEngine.name} ${g.engineVersion ?: ""} | ${g.status} | conf ${g.confidence}% | entry ${g.entry ?: "-"}${g.lastError?.let { " | lastError: $it" } ?: ""}")
    }
}

// ------------------------------------------------------------------ about

@Composable
private fun AboutSection() {
    var licenses by remember { mutableStateOf(false) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AceArcade ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
        Text("Build ${BuildConfig.VERSION_CODE} · ${if (BuildConfig.DEBUG) "debug" else "release"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text(
            "AceArcade works offline. It has no accounts, analytics, ads or tracking, and sends no data anywhere. Your library and settings stay on this device. " +
                "Games cannot reach the internet unless you allow it in Advanced settings.",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Licence", style = MaterialTheme.typography.titleMedium)
        Text(
            "AceArcade is free software, licensed under the GNU General Public License v3.0 or later. You may use, study, share and modify it under those terms. " +
                "If you received this app, you are entitled to its complete source code, including the build scripts and exact versions of the native runtimes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Open-source components", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { licenses = !licenses }) { Text(if (licenses) "Hide notices" else "Show notices") }
        if (licenses) Text(OPEN_SOURCE_NOTICES, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("AceArcade is an independent project and is not affiliated with JoiPlay, Kirin, KADOKAWA (RPG Maker), Ren'Py, Godot, Scirra (Construct) or The Pokémon Company. Only play games you legally own.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val OPEN_SOURCE_NOTICES = """
AndroidX (Compose, Material 3, Room, DataStore, Navigation, Lifecycle, Activity, Core, WebKit, DocumentFile, SplashScreen)
  Apache License 2.0 — The Android Open Source Project

Kotlin standard library, kotlinx.coroutines, kotlinx.serialization
  Apache License 2.0 — JetBrains s.r.o. and contributors

Coil
  Apache License 2.0 — Coil Contributors

Material Symbols / Material Icons
  Apache License 2.0 — Google LLC

Ruffle 0.7.1 — Flash Player emulator (bundled, unmodified self-hosted web build)
  MIT OR Apache-2.0 — Ruffle LLC and contributors — https://ruffle.rs
  Full licence texts: assets/ruffle/LICENSE_MIT, assets/ruffle/LICENSE_APACHE

mkxp-z (RGSS runtime) — GPL-2.0-or-later — via mkxp-z-android-reworked (when included in this build)
Ruby (mkxp-z 3.1 fork) — Ruby licence / BSD-2-Clause
SDL2, SDL2_image, SDL2_ttf, SDL_sound, PhysicsFS — zlib licence
OpenAL Soft — LGPL-2.0-or-later;  GNU libiconv — LGPL-2.1-or-later
libogg / libvorbis / libtheora — BSD-3-Clause;  pixman — MIT;  uchardet — MPL-1.1/GPL/LGPL
OpenSSL 1.1.1 — OpenSSL/SSLeay licence;  Liberation Sans — OFL-1.1;  WenQuanYi Micro Hei — Apache-2.0/GPL-3.0+FE

Android System WebView (used at runtime, not bundled)
  BSD-3-Clause and others — The Chromium Authors
""".trimIndent()


/** Settings › Advanced › Troubleshooting: local crash reports of game processes (never uploaded). */
@Composable
private fun CrashReportsSection() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val reports = remember(version) { com.acecore.app.runtime.CrashReports.list(ctx) }
    var open by remember { mutableStateOf<java.io.File?>(null) }
    SettingsGroupTitle("Troubleshooting · crash reports")
    Text(
        "When a game stops before it starts, AceArcade saves what it could find out (Android's crash record, the game's log, the launch stage) on this device only. " +
            "Personal storage paths are removed. Nothing is uploaded; share a report yourself if you want help.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    if (reports.isEmpty()) Text("No crash reports.", modifier = Modifier.padding(20.dp))
    reports.take(15).forEach { f ->
        NavRow(null, f.name.removeSuffix(".txt"), runCatching { f.readLines().firstOrNull { it.startsWith("Cause:") }?.removePrefix("Cause: ") }.getOrNull()) { open = f }
    }
    if (reports.isNotEmpty()) TextButton(onClick = { com.acecore.app.runtime.CrashReports.clear(ctx); version++ }, modifier = Modifier.padding(horizontal = 12.dp)) {
        Text("Delete all reports")
    }
    open?.let { f ->
        val text = remember(f) { runCatching { f.readText() }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("Crash report") },
            text = { Text(text.take(8000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
                    runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share crash report")) }
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AceArcade crash report", text))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
        )
    }
}
