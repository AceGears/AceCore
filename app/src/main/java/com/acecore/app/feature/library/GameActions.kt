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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.acecore.app.MainActivity
import com.acecore.app.R
import com.acecore.app.ShortcutLaunchActivity
import com.acecore.app.container
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.designsystem.StatusChip
import com.acecore.app.core.detection.LaunchCandidate
import com.acecore.app.feature.LocalGameLauncher
import kotlinx.coroutines.launch

/** Dialogs that several screens can open for a game. */
enum class GameDialog { NONE, RENAME, LAUNCH_FILE, REMOVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameActionsSheet(game: GameEntity, onDismiss: () -> Unit, onDetails: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val scope = rememberCoroutineScope()
    val launcher = LocalGameLauncher.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dialog by remember { mutableStateOf(GameDialog.NONE) }
    val pickCover = rememberCoverPicker(game) { onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(game, Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(game.effectiveEngine.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(game.compatStatus)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SheetItem(Icons.Rounded.PlayArrow, "Play", enabled = GameRepository.isPlayable(game)) { onDismiss(); launcher.launch(game) }
            SheetItem(Icons.Rounded.Info, "Game details & compatibility") { onDetails() }
            SheetItem(Icons.Rounded.Edit, "Edit title & category") { dialog = GameDialog.RENAME }
            SheetItem(Icons.Rounded.Image, "Change cover") { pickCover() }
            if (game.customCoverPath != null) SheetItem(Icons.Rounded.Image, "Restore detected cover") {
                scope.launch { repo.clearCustomCover(game) }; onDismiss()
            }
            SheetItem(Icons.Rounded.Description, "Change launch file") { dialog = GameDialog.LAUNCH_FILE }
            SheetItem(Icons.Rounded.Tune, "Game settings") { onSettings() }
            SheetItem(Icons.Rounded.Gamepad, "Controller mapping") { onSettings() }
            SheetItem(Icons.Rounded.FolderOpen, "Open game folder") { openFolder(ctx, game); onDismiss() }
            SheetItem(Icons.Rounded.Home, "Create home-screen shortcut") {
                scope.launch { createShortcut(ctx, game) }; onDismiss()
            }
            SheetItem(if (game.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (game.favorite) "Remove from favorites" else "Add to favorites") {
                scope.launch { repo.setFavorite(game.id, !game.favorite) }; onDismiss()
            }
            SheetItem(if (game.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (game.hidden) "Unhide game" else "Hide game") {
                scope.launch { repo.setHidden(game.id, !game.hidden) }; onDismiss()
            }
            SheetItem(Icons.Rounded.Delete, "Remove from library", destructive = true) { dialog = GameDialog.REMOVE }
        }
    }
    GameDialogs(game, dialog, onClose = { dialog = GameDialog.NONE }, onRemoved = onDismiss)
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (destructive || !enabled) color else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
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
            var category by remember(game.id) { mutableStateOf(game.category.orEmpty()) }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Edit game") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                        OutlinedTextField(category, { category = it }, label = { Text("Category (optional)") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(enabled = title.isNotBlank(), onClick = {
                        scope.launch { repo.update(game.copy(title = title.trim(), category = category.trim().ifEmpty { null })) }
                        onClose()
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            )
        }
        GameDialog.LAUNCH_FILE -> {
            val candidates = remember(game.detectionJson) { repo.decodeDetection(game)?.launchCandidates.orEmpty() }
            var selected by remember(game.id) { mutableStateOf(candidates.firstOrNull { it.path.equals(game.launchFile, true) } ?: candidates.firstOrNull()) }
            LaunchFileDialog(
                candidates = candidates, selected = selected, onSelect = { selected = it }, onDismiss = onClose,
                onSave = { c -> scope.launch { repo.setLaunchFile(game, c) }; onClose() },
            )
        }
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
                    Text("This removes the game from your AceCore library. The game's original files on your device are not deleted, and you can add the folder again at any time.")
                    if (installed > 0) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { deleteData = !deleteData },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = deleteData, onCheckedChange = { deleteData = it })
                        Text(
                            "Also delete AceCore's private copy (${installed / 1_048_576} MB). This includes any saves made while playing in AceCore.",
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

/** "Select Game Launch File" dialog. Only files a bundled runtime can handle are selectable. */
@Composable
fun LaunchFileDialog(
    candidates: List<LaunchCandidate>,
    selected: LaunchCandidate?,
    onSelect: (LaunchCandidate) -> Unit,
    onDismiss: () -> Unit,
    onSave: (LaunchCandidate) -> Unit,
    confirmLabel: String = "Save",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select game launch file") },
        text = {
            if (candidates.isEmpty()) Text("No launch files were found in this folder.")
            else Column(Modifier.verticalScroll(rememberScrollState())) {
                candidates.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = c.playable) { onSelect(c) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c == selected, onClick = if (c.playable) ({ onSelect(c) }) else null, enabled = c.playable)
                        Column(Modifier.weight(1f)) {
                            Text(c.path, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (c.playable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                            Text(c.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Your choice is remembered for future launches.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = selected?.playable == true, onClick = { selected?.let(onSave) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
