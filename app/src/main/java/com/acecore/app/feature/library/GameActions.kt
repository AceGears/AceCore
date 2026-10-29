package com.acecore.app.feature.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.acecore.app.MainActivity
import com.acecore.app.R
import com.acecore.app.ShortcutLaunchActivity
import com.acecore.app.container
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.designsystem.StatusChip
import com.acecore.app.core.designsystem.aceGlow
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.feature.LocalGameLauncher
import com.acecore.app.feature.categories.CategoryAssignDialog
import com.acecore.app.feature.categories.CategoryAssignMode
import com.acecore.app.feature.categories.CategoryEditDialog
import com.acecore.app.feature.launchfile.LaunchFileBrowser
import kotlinx.coroutines.launch

/** Dialogs that several screens can open for a game. */
enum class GameDialog { NONE, RENAME, LAUNCH_FILE, REMOVE, CATEGORIES }

/** Where the quick menu can send the user. */
data class GameMenuTargets(
    val onDetails: () -> Unit,
    val onSettings: () -> Unit,
    val onControls: () -> Unit,
    val onCheats: () -> Unit,
    val onStatistics: () -> Unit,
    val onSelect: (() -> Unit)? = null,
)

/**
 * Long-press menu: a centred card with the game's cover, status and every action, laid out as a grid of tiles.
 * Opens with a short spring scale/fade (respects reduce-motion).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameQuickMenu(game: GameEntity, targets: GameMenuTargets, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    val launcher = LocalGameLauncher.current
    var dialog by remember { mutableStateOf(GameDialog.NONE) }
    val pickCover = rememberCoverPicker(game) { onDismiss() }
    val t = AceTheme.tokens
    val appear = remember { Animatable(if (t.reduceMotion) 1f else 0.9f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
    val playable = GameRepository.isPlayable(game)
    val cheatsSupported = game.effectiveEngine.family == EngineFamily.RGSS

    if (dialog == GameDialog.NONE) Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
                .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = ((appear.value - 0.9f) * 10f).coerceIn(0f, 1f) }
                .aceGlow(t.glow, t.glowIntensity * 0.5f, 26.dp, 18.dp)
                .border(1.dp, Brush.linearGradient(listOf(t.glow.copy(alpha = 0.75f), t.glowAlt.copy(alpha = 0.55f))), RoundedCornerShape(26.dp)),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(game, Modifier.size(width = 60.dp, height = 80.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(game.effectiveEngine.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        GameStateChip(game)
                    }
                }
                Spacer(Modifier.height(14.dp))
                // Primary action, full width.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (playable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(enabled = playable) { onDismiss(); launcher.launch(game) }.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = if (playable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(if (playable) "Play" else "Not playable in this version", fontWeight = FontWeight.Bold,
                        color = if (playable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                    val tile = Modifier.weight(1f)
                    MenuTile(Icons.Rounded.Info, "Game details", tile) { onDismiss(); targets.onDetails() }
                    MenuTile(if (game.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (game.favorite) "Unfavorite" else "Favorite", tile) {
                        scope.launch { repo.setFavorite(game.id, !game.favorite) }; onDismiss()
                    }
                    MenuTile(Icons.AutoMirrored.Rounded.Label, "Category", tile) { dialog = GameDialog.CATEGORIES }
                    MenuTile(Icons.Rounded.Image, "Change cover", tile) { pickCover() }
                    MenuTile(Icons.Rounded.Description, "Launch file", tile) { dialog = GameDialog.LAUNCH_FILE }
                    MenuTile(Icons.Rounded.Gamepad, "Controls", tile) { onDismiss(); targets.onControls() }
                    MenuTile(Icons.Rounded.AutoFixHigh, "Cheats", tile, enabled = cheatsSupported) { onDismiss(); targets.onCheats() }
                    MenuTile(Icons.Rounded.Tune, "Game settings", tile) { onDismiss(); targets.onSettings() }
                    MenuTile(Icons.Rounded.BarChart, "Statistics", tile) { onDismiss(); targets.onStatistics() }
                    MenuTile(Icons.Rounded.Edit, "Rename", tile) { dialog = GameDialog.RENAME }
                    MenuTile(Icons.Rounded.FolderOpen, "Open folder", tile) { openFolder(ctx, game); onDismiss() }
                    MenuTile(Icons.Rounded.Home, "Shortcut", tile) { scope.launch { createShortcut(ctx, game) }; onDismiss() }
                    targets.onSelect?.let { sel -> MenuTile(Icons.Rounded.Checklist, "Select", tile) { onDismiss(); sel() } }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { dialog = GameDialog.REMOVE }.padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text("Remove from library", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    GameDialogs(game, dialog, onClose = { dialog = GameDialog.NONE; onDismiss() }, onRemoved = onDismiss)
}

@Composable
private fun MenuTile(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else fg, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

/** Returns a function that opens the system photo picker and stores the result as the game's custom cover. */
@Composable
fun rememberCoverPicker(game: GameEntity, onPicked: () -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { ctx.container.games.setCustomCover(game, uri) }
                .onFailure { Toast.makeText(ctx, "Couldn't use that image: ${it.message}", Toast.LENGTH_LONG).show() }
            onPicked()
        }
    }
    return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
fun GameDialogs(game: GameEntity, dialog: GameDialog, onClose: () -> Unit, onRemoved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    when (dialog) {
        GameDialog.NONE -> Unit
        GameDialog.RENAME -> {
            var title by remember(game.id) { mutableStateOf(game.title) }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Rename game") },
                text = { OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true) },
                confirmButton = {
                    TextButton(enabled = title.isNotBlank(), onClick = {
                        scope.launch { repo.update(game.copy(title = title.trim())) }
                        onClose()
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            )
        }
        GameDialog.LAUNCH_FILE -> LaunchFileBrowser(game, onDismiss = onClose)
        GameDialog.CATEGORIES -> GameCategoriesDialog(listOf(game.id), onClose = onClose)
        GameDialog.REMOVE -> {
            val installed by androidx.compose.runtime.produceState(initialValue = 0L, game.id) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.installedSize(game.id) }
            }
            var deleteData by remember(game.id) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Remove “${game.title}”?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("This removes the game from your AceArcade library. The game's own files on your device are not deleted, and you can add the folder again at any time.")
                        if (installed > 0) Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { deleteData = !deleteData },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = deleteData, onCheckedChange = { deleteData = it })
                            Text(
                                "Also delete AceArcade's own data for this game (${installed / 1_048_576} MB: private copy, settings and any saves stored by AceArcade).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { scope.launch(kotlinx.coroutines.NonCancellable) { repo.remove(game, deleteData) }; onClose(); onRemoved() }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            )
        }
    }
}

/** Category assignment for one or more games (Add to / Move to / Remove from). */
@Composable
fun GameCategoriesDialog(gameIds: List<Long>, initialMode: CategoryAssignMode = CategoryAssignMode.ADD, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cats = ctx.container.categories
    val scope = rememberCoroutineScope()
    val categories by cats.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val membership by cats.membership.collectAsStateWithLifecycle(initialValue = emptyMap())
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val perCategory = remember(membership, gameIds) {
        gameIds.flatMap { membership[it].orEmpty() }.groupingBy { it }.eachCount()
    }
    if (creating) {
        CategoryEditDialog(null, error, onDismiss = { creating = false }) { name, icon, color ->
            scope.launch {
                runCatching { cats.create(name, icon, color) }
                    .onSuccess { id -> cats.addGames(id, gameIds); creating = false }
                    .onFailure { error = it.message }
            }
        }
        return
    }
    CategoryAssignDialog(
        categories = categories, gameCount = gameIds.size, membersPerCategory = perCategory, initialMode = initialMode,
        onCreateCategory = { error = null; creating = true }, onDismiss = onClose,
    ) { mode, ids ->
        scope.launch {
            when (mode) {
                CategoryAssignMode.ADD -> ids.forEach { cats.addGames(it, gameIds) }
                CategoryAssignMode.REMOVE -> ids.forEach { cats.removeGames(it, gameIds) }
                CategoryAssignMode.MOVE -> gameIds.forEach { cats.setGameCategories(it, ids) }
            }
            Toast.makeText(ctx, "Categories updated", Toast.LENGTH_SHORT).show()
        }
        onClose()
    }
}

fun openFolder(ctx: Context, game: GameEntity) {
    val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(game.treeUri), game.rootDocId)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No file manager on this device can open folders directly. Folder: ${game.folderLabel}", Toast.LENGTH_LONG).show()
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Your file manager refused to open this folder.", Toast.LENGTH_LONG).show()
    }
}

suspend fun createShortcut(ctx: Context, game: GameEntity) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
        Toast.makeText(ctx, "Your launcher doesn't support pinned shortcuts.", Toast.LENGTH_LONG).show()
        return
    }
    val bitmap: Bitmap? = ctx.container.games.coverModel(game)?.let { model ->
        val result = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(model).size(256).allowHardware(false).build())
        ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    }
    val icon = bitmap?.let { IconCompat.createWithBitmap(it) } ?: IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)
    val intent = Intent(ctx, ShortcutLaunchActivity::class.java).setAction(Intent.ACTION_VIEW)
        .putExtra(MainActivity.EXTRA_LAUNCH_GAME_ID, game.id)
    val info = ShortcutInfoCompat.Builder(ctx, "game_${game.id}")
        .setShortLabel(game.title.take(24)).setLongLabel(game.title.take(48))
        .setIcon(icon).setIntent(intent).build()
    ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}
