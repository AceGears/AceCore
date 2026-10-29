package com.acecore.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

// AceArcade neon palette (same as the Android app's signature theme).
private val Magenta = Color(0xFFF03AD6)
private val HotPink = Color(0xFFE5199C)
private val Blue = Color(0xFF2E86FF)
private val Night = Color(0xFF06051A)
private val NightSurface = Color(0xFF110C2A)
private val Muted = Color(0xFFB9B3D6)

private val AceColors = darkColorScheme(
    primary = Magenta, onPrimary = Color.White, secondary = Blue, tertiary = HotPink,
    background = Night, onBackground = Color.White, surface = NightSurface, onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1440), onSurfaceVariant = Muted, outline = Color(0xFF3A2F6E),
)

fun main() = application {
    val store = remember { LibraryStore() }
    val launcher = remember { Launcher(store, Planner(Runtimes())) }
    Window(
        onCloseRequest = { launcher.stop(); exitApplication() },
        title = "AceArcade",
        icon = painterResource("acearcade_icon.png"),
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        MaterialTheme(colorScheme = AceColors) { App(store, launcher) }
    }
}

@Composable
fun App(store: LibraryStore, launcher: Launcher) {
    var revision by remember { mutableIntStateOf(0) }       // bumps after every library change
    val games = remember(revision) { store.games }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Long?>(null) }
    var enginesOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(text: String) = scope.launch { snackbar.showSnackbar(text) }
    fun refresh() { revision++ }

    fun addFolder(many: Boolean) {
        val dir = chooseFolder(if (many) "Choose a folder that contains your games" else "Choose a game folder") ?: return
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                val known = store.games.map { it.folder }.toSet()
                val found = if (many) Scanner.scanChildren(dir, store.nextId()) else listOfNotNull(Scanner.gameFor(dir, store.nextId()))
                found.filter { it.folder !in known }.onEach { store.upsert(it) }
            }
            refresh()
            say(when {
                added.isEmpty() && many -> "No new games found in ${dir.name}."
                added.isEmpty() -> "${dir.name} doesn't look like a game folder AceArcade recognises."
                added.size == 1 -> "Added ${added[0].title} (${added[0].engine.displayName})."
                else -> "Added ${added.size} games."
            })
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E0A2A), Night)))) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource("acearcade_logo.png"), "AceArcade", Modifier.height(54.dp))
                    Spacer(Modifier.width(20.dp))
                    OutlinedTextField(
                        query, { query = it }, singleLine = true, placeholder = { Text("Search titles, engines, folders") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = { enginesOpen = true }) { Icon(Icons.Rounded.Extension, "Game engines", tint = Muted) }
                    OutlinedButton(onClick = { addFolder(true) }) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("Add folder of games") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { addFolder(false) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add game") }
                }
                Spacer(Modifier.height(18.dp))
                val shown = games.filter {
                    query.isBlank() || it.title.contains(query, true) || it.engine.displayName.contains(query, true) || it.folder.contains(query, true)
                }.sortedByDescending { it.lastPlayedAt ?: it.addedAt }
                if (shown.isEmpty()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(if (games.isEmpty()) "Your library is empty" else "No games match \"$query\"", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Text("Add a game folder, or a folder that contains several games.", color = Muted)
                    }
                } else {
                    LazyVerticalGrid(GridCells.Adaptive(store.settings.coverWidth.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(shown, key = { it.id }) { g -> GameTile(g, selected == g.id) { selected = g.id } }
                    }
                }
            }
            games.firstOrNull { it.id == selected }?.let { g ->
                GamePanel(g, launcher.planSummary(g),
                    onPlay = {
                        val plan = launcher.launch(g) { _, error -> refresh(); if (error != null) say(error) }
                        refresh()
                        if (plan !is LaunchPlan.Unsupported) say("Starting ${g.title}…")
                    },
                    onFavorite = { store.update(g.id) { it.copy(favorite = !it.favorite) }; refresh() },
                    onCover = {
                        chooseImage()?.let { f -> store.update(g.id) { it.copy(customCover = f.absolutePath) }; refresh() }
                    },
                    onOpenFolder = { runCatching { java.awt.Desktop.getDesktop().open(g.folderFile) } },
                    onRemove = { store.remove(g.id); selected = null; refresh(); say("Removed ${g.title} from the library (its files are untouched).") },
                    onClose = { selected = null },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        if (enginesOpen) EnginesDialog(Runtimes()) { enginesOpen = false }
    }
}

private fun Launcher.planSummary(g: DesktopGame): LaunchPlan = Planner(Runtimes()).plan(g)

@Composable
private fun GameTile(g: DesktopGame, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Cover(g, Modifier.fillMaxWidth().aspectRatio(0.75f)
            .border(if (selected) 3.dp else 1.dp, Brush.linearGradient(listOf(Magenta, Blue.copy(alpha = 0.6f))), RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(8.dp))
        Text(g.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The cover image, cropped to fill the tile (no text drawn over it). */
@Composable
fun Cover(g: DesktopGame, modifier: Modifier) {
    val file = g.coverFile
    var image by remember(file?.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file?.path) {
        image = file?.let { f -> withContext(Dispatchers.IO) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }.getOrNull() } }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF1A1440), Magenta.copy(alpha = 0.45f), NightSurface))),
        contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(g.title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
            fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
    }
}

@Composable
private fun GamePanel(
    g: DesktopGame, plan: LaunchPlan, onPlay: () -> Unit, onFavorite: () -> Unit, onCover: () -> Unit,
    onOpenFolder: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit,
) {
    Column(
        Modifier.width(380.dp).fillMaxHeight().background(NightSurface).border(1.dp, Color(0xFF2A2156))
            .verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row { Spacer(Modifier.weight(1f)); IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") } }
        Cover(g, Modifier.width(200.dp).aspectRatio(0.75f).align(Alignment.CenterHorizontally))
        Text(g.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text(g.engine.displayName + (g.engineVersion?.let { " · $it" } ?: ""), color = Muted)
        val can = plan !is LaunchPlan.Unsupported
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlay, enabled = can, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Magenta)) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (can) "Play" else "Can't run here", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onFavorite) { Icon(if (g.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = Magenta) }
        }
        Section("How it runs on ${Platform.current.label}", plan.summary)
        g.lastError?.let { Section("Last launch", it) }
        Section("Statistics", buildString {
            append("Play time: ${formatDuration(g.playtimeMs)}\n")
            append("Launches: ${g.launches}\n")
            append("Last played: ${g.lastPlayedAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "never"}")
        })
        Section("Folder", g.folder)
        HorizontalDivider()
        TextButton(onClick = onCover) { Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(6.dp)); Text("Choose cover image") }
        TextButton(onClick = onOpenFolder) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Open game folder") }
        TextButton(onClick = onRemove) { Text("Remove from library", color = HotPink) }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Magenta)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EnginesDialog(runtimes: Runtimes, onClose: () -> Unit) {
    val p = Platform.current
    fun bundled(f: File?) = if (f != null) "Bundled engine included" else "Engine not bundled in this build"
    val rows = listOf(
        "RPG Maker XP / VX / VX Ace, Pokémon Essentials" to if (p == Platform.WINDOWS) "Runs the game's own Game.exe" else "Needs mkxp-z for ${p.label} (not bundled yet)",
        "RPG Maker 2000 / 2003" to "EasyRPG Player · " + bundled(runtimes.easyRpg()),
        "RPG Maker MV / MZ, HTML5, Construct, TyranoScript" to (if (p == Platform.WINDOWS) "Game's own program when present, otherwise " else "") + "your web browser (served locally)",
        "Godot" to if (p == Platform.WINDOWS) "Runs the game's own program" else "Godot 4 packs · " + bundled(runtimes.godot()),
        "Ren'Py" to "Runs the game's own launcher for ${p.label}",
        "Flash (SWF)" to "Ruffle · " + bundled(runtimes.ruffle()),
        "Unity, Unreal, GameMaker, WOLF RPG" to if (p == Platform.WINDOWS) "Runs the game's own program" else "Windows-only builds can't run on ${p.label}",
    )
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Game engines on ${p.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (k, v) -> Column { Text(k, fontWeight = FontWeight.SemiBold); Text(v, color = Muted) } }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private fun formatDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

/** Native folder picker (Finder on macOS, Explorer-style on Windows). */
private fun chooseFolder(title: String): File? = if (Platform.current == Platform.MAC) {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD).apply { isVisible = true }
    System.setProperty("apple.awt.fileDialogForDirectories", "false")
    d.file?.let { File(d.directory, it) }
} else {
    JFileChooser().apply { dialogTitle = title; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        .let { if (it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) it.selectedFile else null }
}

private fun chooseImage(): File? {
    val d = FileDialog(null as Frame?, "Choose a cover image", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.lowercase().substringAfterLast('.') in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif") }
        isVisible = true
    }
    return d.file?.let { File(d.directory, it) }
}

@Suppress("unused")
private fun Engine.familyLabel() = if (family == EngineFamily.UNSUPPORTED_NATIVE) "PC" else displayName
