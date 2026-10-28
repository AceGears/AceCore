package com.acecore.app.feature.categories

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acecore.app.container
import com.acecore.app.core.database.CategoryDirEntity
import com.acecore.app.core.database.CategoryEntity
import com.acecore.app.core.database.CategoryWithCount
import com.acecore.app.core.designsystem.AceCard
import com.acecore.app.feature.settings.SettingsScaffold
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Category management: create, edit (name/icon/colour/cover), hide/unhide, reorder by dragging, delete, and link
 * folders that are scanned for games. None of this moves or copies game files.
 */
@Composable
fun CategoryManagerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.categories
    val scope = rememberCoroutineScope()
    val cats by repo.categories.collectAsStateWithLifecycle(initialValue = null)
    val dirs by repo.directories.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<CategoryWithCount?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    // Local order while dragging; synced from the database otherwise.
    val order = remember { mutableStateListOf<CategoryWithCount>() }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(cats) { if (!dragging) { order.clear(); order.addAll(cats.orEmpty()) } }

    SettingsScaffold("Categories", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Categories only organise your library. Moving games between categories, hiding or deleting a category never touches game files, " +
                    "and deleting a category never removes its games. Long-press the handle to drag categories into a new order.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Button(onClick = { error = null; creating = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("New category")
            }
            if (cats != null && order.isEmpty()) Text(
                "No categories yet. Create one, then add games from the library (long-press a game › Change category) or link folders to scan.",
                modifier = Modifier.padding(20.dp),
            )
            ReorderableList(
                items = order,
                key = { it.category.id },
                onDragState = { dragging = it },
                onMove = { from, to -> order.add(to, order.removeAt(from)) },
                onDrop = { scope.launch { repo.reorder(order.map { it.category.id }) } },
            ) { cw, handle ->
                CategoryCard(
                    cw = cw,
                    dirs = dirs.filter { it.categoryId == cw.category.id },
                    expanded = expanded == cw.category.id,
                    handle = handle,
                    onToggle = { expanded = if (expanded == cw.category.id) null else cw.category.id },
                    onEdit = { error = null; editing = cw.category },
                    onDelete = { deleting = cw },
                )
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (creating) CategoryEditDialog(null, error, onDismiss = { creating = false }) { name, icon, color ->
        scope.launch {
            runCatching { repo.create(name, icon, color) }.onSuccess { creating = false }.onFailure { error = it.message }
        }
    }
    editing?.let { c ->
        CategoryEditDialog(c, error, onDismiss = { editing = null }) { name, icon, color ->
            scope.launch {
                runCatching { repo.update(c.copy(name = name, icon = icon, color = color)) }.onSuccess { editing = null }.onFailure { error = it.message }
            }
        }
    }
    deleting?.let { cw ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${cw.category.name}”?") },
            text = {
                Text(
                    "The category and its folder links are removed. Its ${cw.gameCount} game(s) stay in your library and in any other categories. " +
                        "No files are deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.delete(cw.category) }; deleting = null }) {
                    Text("Delete category", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryCard(
    cw: CategoryWithCount,
    dirs: List<CategoryDirEntity>,
    expanded: Boolean,
    handle: Modifier,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = ctx.container.categories
    val scope = rememberCoroutineScope()
    val c = cw.category
    var scanning by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf<CategoryDirEntity?>(null) }

    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { repo.addDirectory(c.id, uri) }
                .onSuccess { if (!it) Toast.makeText(ctx, "That folder is already linked", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Couldn't add folder: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val changeFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val d = changing
        changing = null
        if (uri != null && d != null) scope.launch { runCatching { repo.changeDirectory(d, uri) } }
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { repo.setCover(c, uri) }.onFailure { Toast.makeText(ctx, "Couldn't use that image: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(handle.padding(8.dp)) { Icon(Icons.Rounded.DragIndicator, "Drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (c.coverPath != null) {
                    AsyncImage(model = java.io.File(c.coverPath), contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                } else CategoryBadge(c.icon, c.color, size = 40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("${cw.gameCount} game${if (cw.gameCount == 1) "" else "s"}")
                            if (dirs.isNotEmpty()) append(" · ${dirs.size} folder${if (dirs.size == 1) "" else "s"}")
                            if (c.hidden) append(" · hidden")
                            if (c.autoScan) append(" · auto-scan")
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { scope.launch { repo.setHidden(c.id, !c.hidden) } }) {
                    Icon(if (c.hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (c.hidden) "Unhide category" else "Hide category",
                        tint = if (c.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit") }
                        OutlinedButton(onClick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (c.coverPath == null) "Cover" else "Change cover")
                        }
                        if (c.coverPath != null) TextButton(onClick = { scope.launch { repo.setCover(c, null) } }) { Text("Remove cover") }
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text("Folders", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        "Games found in these folders are added to this category. They're played from where they are; nothing is copied.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    dirs.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = Color(c.color.toInt()), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(d.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { changing = d; changeFolder.launch(null) }) { Icon(Icons.Rounded.SwapHoriz, "Change folder") }
                            IconButton(onClick = { scope.launch { repo.removeDirectory(d) } }) { Icon(Icons.Rounded.Delete, "Remove folder link") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { addFolder.launch(null) }) {
                            Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add folder")
                        }
                        Button(enabled = dirs.isNotEmpty() && scanning == null, onClick = {
                            scanning = "Starting…"
                            scope.launch {
                                val r = runCatching { repo.scan(c.id) { msg -> scanning = msg } }
                                scanning = null
                                r.onSuccess { rep ->
                                    Toast.makeText(
                                        ctx,
                                        "Found ${rep.found} game(s): ${rep.added} new, ${rep.alreadyInLibrary} already in your library" +
                                            if (rep.failed.isNotEmpty()) ". ${rep.failed.size} problem(s): ${rep.failed.first()}" else "",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }.onFailure { Toast.makeText(ctx, "Scan failed: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                        }) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scan now") }
                    }
                    scanning?.let {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Scan automatically", style = MaterialTheme.typography.bodyLarge)
                            Text("When AceCore starts (at most every 6 hours).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = c.autoScan, enabled = dirs.isNotEmpty(), onCheckedChange = { v -> scope.launch { repo.update(c.copy(autoScan = v)) } })
                    }
                }
            }
        }
    }
}

/**
 * A simple drag-to-reorder column (categories are few, so no lazy list is needed). Long-press the handle, drag, drop.
 * [content] receives the item and the Modifier to put on its drag handle.
 */
@Composable
fun <T> ReorderableList(
    items: List<T>,
    key: (T) -> Any,
    onDragState: (Boolean) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    content: @Composable (item: T, handle: Modifier) -> Unit,
) {
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { HashMap<Any, Int>() }
    Column {
        items.forEach { item ->
            val k = key(item)
            val isDragging = draggingKey == k
            val elevation by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "drag")
            val handle = Modifier.pointerInput(k) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { draggingKey = k; dragOffset = 0f; onDragState(true) },
                    onDragEnd = { draggingKey = null; dragOffset = 0f; onDragState(false); onDrop() },
                    onDragCancel = { draggingKey = null; dragOffset = 0f; onDragState(false); onDrop() },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val cur = items.indexOfFirst { key(it) == k }
                        if (cur < 0) return@detectDragGesturesAfterLongPress
                        val h = heights[k] ?: return@detectDragGesturesAfterLongPress
                        if (dragOffset > h / 2f && cur < items.lastIndex) {
                            val next = heights[key(items[cur + 1])] ?: h
                            onMove(cur, cur + 1); dragOffset -= next
                        } else if (dragOffset < -h / 2f && cur > 0) {
                            val prev = heights[key(items[cur - 1])] ?: h
                            onMove(cur, cur - 1); dragOffset += prev
                        }
                    },
                )
            }
            Box(
                Modifier.zIndex(if (isDragging) 1f else 0f)
                    .onSizeChanged { heights[k] = it.height }
                    .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                    .then(if (isDragging) Modifier.shadow(elevation, RoundedCornerShape(16.dp)) else Modifier),
            ) { content(item, handle) }
        }
    }
}
