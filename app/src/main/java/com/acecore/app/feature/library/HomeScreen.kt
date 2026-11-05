@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.acecore.app.feature.library

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.acecore.app.core.datastore.TapAction
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.acecore.app.core.database.CategoryEntity
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acecore.app.R
import com.acecore.app.container
import com.acecore.app.core.database.CategoryWithCount
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.datastore.CategoryBarStyle
import com.acecore.app.core.datastore.LibraryLayout
import com.acecore.app.core.datastore.LibrarySettings
import com.acecore.app.core.datastore.LibrarySort
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.designsystem.EmptyState
import com.acecore.app.core.designsystem.StatusChip
import com.acecore.app.core.designsystem.aceHeroBrush
import com.acecore.app.feature.LocalGameLauncher
import com.acecore.app.feature.categories.CategoryAssignMode
import com.acecore.app.feature.categories.CategoryIcons
import com.acecore.app.runtime.web.GameActivity
import kotlinx.coroutines.launch

/**
 * Library home: header (logo, search, layout, settings), category bar, and the cover grid.
 * Tap a cover to play; long-press for the game menu; "Select" enters multi-select for batch category changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenGame: (Long) -> Unit,
    onOpenGameSettings: (Long) -> Unit,
    onOpenCheats: (Long) -> Unit,
    onImport: (Uri, Long?) -> Unit,
    onImportFile: (file: Uri, destination: Uri, category: Long?) -> Unit = { _, _, _ -> },
    onImportRoms: (files: List<Uri>, category: Long?) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit,
    onManageCategories: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val catRepo = ctx.container.categories
    val settingsRepo = ctx.container.settings
    val scope = rememberCoroutineScope()
    val launcher = LocalGameLauncher.current
    val all by repo.games.collectAsStateWithLifecycle(initialValue = null)
    val categories by catRepo.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val membership by catRepo.membership.collectAsStateWithLifecycle(initialValue = emptyMap())
    val appSettings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
    val lib = appSettings?.library ?: LibrarySettings()

    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<GameEntity?>(null) }
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var assignMode by remember { mutableStateOf<CategoryAssignMode?>(null) }
    var confirmBatchRemove by remember { mutableStateOf(false) }

    val visibleCategories = remember(categories, lib.showHiddenCategories) {
        categories.filter { !it.category.hidden || lib.showHiddenCategories }
    }
    // Games in no category (shown under "Other" when the "All games" tab is off).
    val uncategorized = remember(all, membership, lib.showHidden) {
        all.orEmpty().count { g -> (!g.hidden || lib.showHidden) && membership[g.id].isNullOrEmpty() }
    }
    val showAllTab = lib.showAllGames || visibleCategories.isEmpty()
    val showOtherTab = !showAllTab && uncategorized > 0
    // The selected tab: a visible category, "Other", or (when shown) all games. Without the "All games" tab, the
    // first category (or "Other") is shown when the saved choice no longer exists.
    val selectedCategory: Long? = when {
        lib.selectedCategory == LibrarySettings.OTHER && showOtherTab -> LibrarySettings.OTHER
        visibleCategories.any { it.category.id == lib.selectedCategory } -> lib.selectedCategory
        showAllTab -> null
        else -> visibleCategories.firstOrNull()?.category?.id ?: if (showOtherTab) LibrarySettings.OTHER else null
    }
    /** The selected real category (not "All games" or "Other"), e.g. for adding new games to it. */
    val categoryId: Long? = selectedCategory?.takeIf { it >= 0 }
    fun selectCategory(id: Long) { scope.launch { settingsRepo.updateLibrary { it.copy(selectedCategory = id) } } }
    /** The tabs in the order the category bar shows them (null = "All games"). */
    val tabOrder: List<Long?> = buildList {
        if (showAllTab) add(null)
        visibleCategories.forEach { add(it.category.id) }
        if (showOtherTab) add(LibrarySettings.OTHER)
    }
    fun stepCategory(delta: Int) {
        val i = tabOrder.indexOf(selectedCategory)
        val next = tabOrder.getOrNull(i + delta) ?: return
        selectCategory(next ?: LibrarySettings.ALL_GAMES)
    }
    var creatingCategory by remember { mutableStateOf(false) }
    var categoryActions by remember { mutableStateOf<CategoryWithCount?>(null) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var deletingCategory by remember { mutableStateOf<CategoryWithCount?>(null) }
    var fabExpanded by remember { mutableStateOf(true) }
    val fabScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -4f) fabExpanded = false else if (available.y > 4f) fabExpanded = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    var createError by remember { mutableStateOf<String?>(null) }
    var pickGamesFor by remember { mutableStateOf<CategoryEntity?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { repo.takePermission(uri); onImport(uri, categoryId) }
    }
    // Adding a game that is one file (.zip / .7z / self-extracting .exe): pick the file, then where to unpack it.
    var addChooser by remember { mutableStateOf(false) }
    var archiveFile by remember { mutableStateOf<Uri?>(null) }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { dest ->
        val f = archiveFile
        archiveFile = null
        if (dest != null && f != null) { repo.takePermission(dest); onImportFile(f, dest, categoryId) }
    }
    var askDestination by remember { mutableStateOf(false) }
    // Nintendo handheld ROMs picked as files: each file is its own game, played from where it is (read-only access).
    val pickRoms = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val granted = uris.filter { u ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        }
        if (uris.isNotEmpty() && granted.isEmpty()) android.widget.Toast.makeText(ctx, "Android didn't let AceArcade keep access to those files.", android.widget.Toast.LENGTH_LONG).show()
        if (granted.isNotEmpty()) onImportRoms(granted, categoryId)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { archiveFile = uri; askDestination = true }
    }
    if (addChooser) AlertDialog(
        onDismissRequest = { addChooser = false },
        title = { Text("Add a game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addChooser = false; pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Game folder")
                }
                OutlinedButton(onClick = { addChooser = false; pickFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Game file (.zip, .7z, packed .exe)")
                }
                OutlinedButton(onClick = { addChooser = false; pickRoms.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.SportsEsports, null); Spacer(Modifier.width(8.dp)); Text("ROM files (Game Boy, GBA, DS)")
                }
                Text("A folder is played from where it is (a folder of ROMs adds every ROM in it). A game file is unpacked once into a folder you choose. " +
                    "ROM files (.gb, .gbc, .gba, .nds, or a .zip with one ROM) are each added as a game and never changed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { addChooser = false }) { Text("Cancel") } },
    )
    if (askDestination) AlertDialog(
        onDismissRequest = { askDestination = false; archiveFile = null },
        title = { Text("Where should it go?") },
        text = { Text("Choose the folder to unpack the game into, for example your AceArcade games folder. A new folder named after the file is created there.") },
        confirmButton = { TextButton(onClick = { askDestination = false; pickDestination.launch(null) }) { Text("Choose folder") } },
        dismissButton = { TextButton(onClick = { askDestination = false; archiveFile = null }) { Text("Cancel") } },
    )

    if (creatingCategory) com.acecore.app.feature.categories.CategoryEditDialog(null, createError, onDismiss = { creatingCategory = false }) { name, icon, color ->
        scope.launch {
            runCatching { catRepo.create(name, icon, color) }
                .onSuccess { id ->
                    creatingCategory = false
                    selectCategory(id)
                    pickGamesFor = catRepo.get(id)
                }
                .onFailure { createError = it.message }
        }
    }
    pickGamesFor?.let { cat ->
        CategoryGamesDialog(cat, all.orEmpty().filter { !it.hidden || lib.showHidden }, membership, onDismiss = { pickGamesFor = null }) { add, remove ->
            scope.launch {
                if (add.isNotEmpty()) catRepo.addGames(cat.id, add)
                if (remove.isNotEmpty()) catRepo.removeGames(cat.id, remove)
            }
            pickGamesFor = null
        }
    }

    categoryActions?.let { cw ->
        val c = cw.category
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { categoryActions = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${cw.gameCount} game${if (cw.gameCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                val order = visibleCategories.map { it.category.id }
                val i = order.indexOf(c.id)
                val all = categories.map { it.category.id }
                SheetAction(Icons.Rounded.Checklist, "Add or remove games") { categoryActions = null; pickGamesFor = c }
                SheetAction(Icons.Rounded.Edit, "Rename, icon and colour") { categoryActions = null; createError = null; editingCategory = c }
                if (i > 0) SheetAction(Icons.Rounded.ChevronLeft, "Move left") {
                    categoryActions = null
                    scope.launch { catRepo.reorder(all.toMutableList().apply { val j = indexOf(c.id); add(j - 1, removeAt(j)) }) }
                }
                if (i in 0 until order.size - 1) SheetAction(Icons.Rounded.ChevronRight, "Move right") {
                    categoryActions = null
                    scope.launch { catRepo.reorder(all.toMutableList().apply { val j = indexOf(c.id); add(j + 1, removeAt(j)) }) }
                }
                SheetAction(if (c.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (c.hidden) "Show in the category bar" else "Hide from the category bar") {
                    categoryActions = null; scope.launch { catRepo.setHidden(c.id, !c.hidden) }
                }
                SheetAction(Icons.Rounded.Tune, "All categories, folders and order") { categoryActions = null; onManageCategories() }
                SheetAction(Icons.Rounded.Delete, "Delete category") { categoryActions = null; deletingCategory = cw }
            }
        }
    }
    editingCategory?.let { c ->
        com.acecore.app.feature.categories.CategoryEditDialog(c, createError, onDismiss = { editingCategory = null; createError = null }) { name, icon, color ->
            scope.launch {
                runCatching { catRepo.update(c.copy(name = name, icon = icon, color = color)) }
                    .onSuccess { editingCategory = null; createError = null }.onFailure { createError = it.message }
            }
        }
    }
    deletingCategory?.let { cw ->
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            title = { Text("Delete “${cw.category.name}”?") },
            text = { Text("The category is removed. Its ${cw.gameCount} game${if (cw.gameCount == 1) "" else "s"} stay in your library, and no files are deleted.") },
            confirmButton = { TextButton(onClick = { val c = cw.category; deletingCategory = null; scope.launch { catRepo.delete(c) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingCategory = null }) { Text("Cancel") } },
        )
    }


    val games = all
    val visible = remember(games, query, selectedCategory, membership, lib.sort, lib.showHidden) {
        games.orEmpty()
            .filter { !it.hidden || lib.showHidden }
            .filter { g ->
                when (selectedCategory) {
                    null -> true
                    LibrarySettings.OTHER -> membership[g.id].isNullOrEmpty()
                    else -> membership[g.id]?.contains(selectedCategory) == true
                }
            }
            .filter { g ->
                query.isBlank() || g.title.contains(query, true) || g.effectiveEngine.displayName.contains(query, true) || g.folderLabel.contains(query, true)
            }
            .let { sortGames(it, lib.sort) }
    }
    // Batch actions only ever apply to games that are visible right now.
    LaunchedEffect(visible) {
        val ids = visible.mapTo(HashSet()) { it.id }
        if (selection.any { it !in ids }) selection = selection.filterTo(HashSet()) { it in ids }
    }
    val inSelection = selecting
    BackHandler(enabled = inSelection || searchOpen) {
        if (inSelection) { selecting = false; selection = emptySet() } else { searchOpen = false; query = "" }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            // Compact: shrinks to its icon while the library scrolls down, so it covers less artwork.
            if (!inSelection && games?.isNotEmpty() == true) ExtendedFloatingActionButton(
                onClick = { addChooser = true },
                icon = { Icon(Icons.Rounded.Add, "Add game") },
                text = { Text("Add game") },
                expanded = fabExpanded,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (inSelection) {
                SelectionBar(
                    count = selection.size,
                    inCategory = categoryId != null,
                    onClose = { selecting = false; selection = emptySet() },
                    onSelectAll = { selection = visible.map { it.id }.toSet() },
                    onCategories = { if (selection.isNotEmpty()) assignMode = CategoryAssignMode.ADD },
                    onRemoveFromCategory = {
                        val c = categoryId ?: return@SelectionBar
                        scope.launch { catRepo.removeGames(c, selection) }
                        selecting = false; selection = emptySet()
                    },
                    onFavorite = {
                        val ids = selection
                        scope.launch {
                            val list = repo.getMany(ids.toList())
                            val target = list.any { !it.favorite }
                            list.forEach { repo.setFavorite(it.id, target) }
                        }
                    },
                    onRemove = { if (selection.isNotEmpty()) confirmBatchRemove = true },
                )
            } else {
                Header(
                    searchOpen = searchOpen,
                    onSearch = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                    onLayout = { layoutOpen = true },
                    onSettings = onOpenSettings,
                )
            }
            AnimatedVisibility(searchOpen && !inSelection, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SearchField(query) { query = it }
            }
            if (games != null && games.isNotEmpty()) {
                CategoryBar(
                    categories = visibleCategories,
                    total = games.count { !it.hidden || lib.showHidden },
                    otherCount = if (showOtherTab) uncategorized else null,
                    showAll = showAllTab,
                    selected = selectedCategory,
                    style = lib.categoryBar,
                    showCounts = lib.showCategoryCounts,
                    onSelect = { selectCategory(it ?: LibrarySettings.ALL_GAMES) },
                    onManage = onManageCategories,
                    onNew = { createError = null; creatingCategory = true },
                    onLongPress = { categoryActions = it },
                )
            }
            // Category change: the new games slide in from the side of the new tab (a fade only, or nothing, with reduced motion).
            val tokens = AceTheme.tokens
            val tabIndex = tabOrder.indexOf(selectedCategory)
            var prevIndex by remember { mutableStateOf(tabIndex) }
            val enter = remember { androidx.compose.animation.core.Animatable(1f) }
            val direction = if (tabIndex >= prevIndex) 1f else -1f
            LaunchedEffect(tabIndex) {
                if (tabIndex != prevIndex && !tokens.reduceMotion) {
                    enter.snapTo(0f)
                    enter.animateTo(1f, androidx.compose.animation.core.tween((240 * tokens.animationScale).toInt().coerceAtLeast(1)))
                }
                prevIndex = tabIndex
            }
            Box(
                Modifier.weight(1f).nestedScroll(fabScroll)
                    .graphicsLayer {
                        val e = enter.value
                        alpha = 0.35f + 0.65f * e
                        translationX = (1f - e) * direction * 32.dp.toPx()
                    }
                    .then(if (lib.swipeCategories && lib.layout != LibraryLayout.CAROUSEL && !inSelection && query.isBlank() && tabOrder.size > 1) Modifier.categorySwipe(selectedCategory to tabOrder, ::stepCategory) else Modifier),
            ) {
                when {
                    games == null -> Unit
                    games.isEmpty() -> EmptyState(
                        Icons.Rounded.SportsEsports, "Your library is empty",
                        "Add a game folder. AceArcade detects the engine and plays the game from where it is. Nothing is copied unless you ask for it.",
                        Modifier.padding(top = 40.dp),
                    ) {
                        ExtendedFloatingActionButton(onClick = { addChooser = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add a game") })
                    }
                    visible.isEmpty() && query.isNotBlank() -> EmptyState(Icons.Rounded.Search, "No matches", "No game matches “$query”.", Modifier.padding(top = 24.dp))
                    visible.isEmpty() && categoryId != null -> {
                        val c = visibleCategories.first { it.category.id == categoryId }.category
                        EmptyState(CategoryIcons.of(c.icon), "No games in ${c.name} yet",
                            "Pick games from your library, add a new game here with “Add game”, or link a folder to this category.",
                            Modifier.padding(top = 24.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { pickGamesFor = c }) { Icon(Icons.Rounded.Checklist, null); Spacer(Modifier.width(6.dp)); Text("Add games") }
                                OutlinedButton(onClick = { addChooser = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add game") }
                                OutlinedButton(onClick = onManageCategories) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("Link folder") }
                            }
                        }
                    }
                    else -> LibraryContent(
                        games = visible, lib = lib, selecting = inSelection, selection = selection,
                        banner = categoryId?.let { id -> visibleCategories.firstOrNull { it.category.id == id } },
                        onEditCategoryGames = { cw -> pickGamesFor = cw.category },
                        onClick = { g ->
                            when {
                                inSelection -> selection = if (g.id in selection) selection - g.id else selection + g.id
                                lib.tapAction == TapAction.DETAILS -> onOpenGame(g.id)
                                else -> launcher.launch(g)
                            }
                        },
                        onDoubleClick = if (lib.tapAction == TapAction.DETAILS && !inSelection) { g -> launcher.launch(g) } else null,
                        onColumns = { n -> scope.launch { settingsRepo.updateLibrary { it.copy(columns = n) } } },
                        onLongClick = { g ->
                            if (inSelection) selection = if (g.id in selection) selection - g.id else selection + g.id
                            else menuFor = g
                        },
                    )
                }
            }
        }
    }

    if (layoutOpen) LibraryLayoutSheet(lib) { layoutOpen = false }

    menuFor?.let { g ->
        GameQuickMenu(
            game = g,
            targets = GameMenuTargets(
                onDetails = { onOpenGame(g.id) },
                onSettings = { onOpenGameSettings(g.id) },
                onControls = { onOpenGameSettings(g.id) },
                onCheats = { onOpenCheats(g.id) },
                onStatistics = { onOpenGame(g.id) },
                onSelect = { selecting = true; selection = setOf(g.id) },
            ),
            onDismiss = { menuFor = null },
        )
    }
    assignMode?.let { mode ->
        GameCategoriesDialog(selection.toList(), mode) { assignMode = null; selecting = false; selection = emptySet() }
    }
    if (confirmBatchRemove) AlertDialog(
        onDismissRequest = { confirmBatchRemove = false },
        title = { Text("Remove ${selection.size} game(s) from the library?") },
        text = { Text("Only the library entries are removed. Game files stay on your device, and AceArcade keeps its own saved data for them.") },
        confirmButton = {
            TextButton(onClick = {
                val ids = selection.toList()
                scope.launch(kotlinx.coroutines.NonCancellable) { repo.getMany(ids).forEach { repo.remove(it, deleteAceCoreData = false) } }
                confirmBatchRemove = false; selecting = false; selection = emptySet()
            }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmBatchRemove = false }) { Text("Cancel") } },
    )
}

fun sortGames(list: List<GameEntity>, sort: LibrarySort): List<GameEntity> = when (sort) {
    LibrarySort.ALPHABETICAL -> list.sortedBy { it.title.lowercase() }
    LibrarySort.RECENTLY_ADDED -> list.sortedByDescending { it.addedAt }
    LibrarySort.RECENTLY_PLAYED -> list.sortedWith(compareByDescending<GameEntity> { it.lastPlayedAt ?: 0L }.thenBy { it.title.lowercase() })
    LibrarySort.MOST_PLAYED -> list.sortedByDescending { it.playtimeMs }
    LibrarySort.ENGINE -> list.sortedWith(compareBy<GameEntity> { it.effectiveEngine.displayName }.thenBy { it.title.lowercase() })
    LibrarySort.CUSTOM -> list.sortedWith(compareBy<GameEntity> { it.sortOrder }.thenBy { it.title.lowercase() })
}

@Composable
private fun Header(searchOpen: Boolean, onSearch: () -> Unit, onLayout: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(aceHeroBrush()).windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.acearcade_logo), contentDescription = "AceArcade",
            contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f).height(56.dp),
        )
        IconButton(onClick = onSearch) { Icon(if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search, if (searchOpen) "Close search" else "Search") }
        IconButton(onClick = onLayout) { Icon(Icons.Rounded.GridView, "Library layout") }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text("Search titles, engines, folders") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Clear, "Clear search") } },
        shape = RoundedCornerShape(50),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
    )
}

@Composable
private fun SelectionBar(
    count: Int, inCategory: Boolean,
    onClose: () -> Unit, onSelectAll: () -> Unit, onCategories: () -> Unit, onRemoveFromCategory: () -> Unit,
    onFavorite: () -> Unit, onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "End selection") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, "Select all") }
        IconButton(onClick = onCategories, enabled = count > 0) { Icon(Icons.AutoMirrored.Rounded.Label, "Categories") }
        if (inCategory) IconButton(onClick = onRemoveFromCategory, enabled = count > 0) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove from this category") }
        IconButton(onClick = onFavorite, enabled = count > 0) { Icon(Icons.Rounded.Favorite, "Favorite") }
        IconButton(onClick = onRemove, enabled = count > 0) { Icon(Icons.Rounded.Delete, "Remove from library") }
    }
}

/** "All games" plus the user's categories, as tabs (underlined) or pills. */
@Composable
private fun CategoryBar(
    categories: List<CategoryWithCount>, total: Int, otherCount: Int?, showAll: Boolean, selected: Long?, style: CategoryBarStyle,
    showCounts: Boolean, onSelect: (Long?) -> Unit, onManage: () -> Unit, onNew: () -> Unit,
    onLongPress: (CategoryWithCount) -> Unit = {},
) {
    val state = rememberLazyListState()
    val first = if (showAll) 1 else 0
    val selIndex = when (selected) {
        null -> 0
        LibrarySettings.OTHER -> first + categories.size
        else -> categories.indexOfFirst { it.category.id == selected } + first
    }
    LaunchedEffect(selIndex) { if (selIndex >= 0) runCatching { state.animateScrollToItem(selIndex) } }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (style == CategoryBarStyle.PILLS) 8.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAll) item(key = "all") {
            CategoryTab(Icons.Rounded.Apps, "All games", if (showCounts) total else null, AceTheme.tokens.glow, selected == null, style, false) { onSelect(null) }
        }
        items(categories, key = { it.category.id }) { cw ->
            val c = cw.category
            CategoryTab(CategoryIcons.of(c.icon), c.name, if (showCounts) cw.gameCount else null, Color(c.color.toInt()), selected == c.id, style, c.hidden,
                onLongClick = { onLongPress(cw) }) { onSelect(c.id) }
        }
        if (otherCount != null) item(key = "other") {
            CategoryTab(Icons.Rounded.Apps, "Other", if (showCounts) otherCount else null, AceTheme.tokens.glow, selected == LibrarySettings.OTHER, style, false) {
                onSelect(LibrarySettings.OTHER)
            }
        }
        item(key = "new") {
            IconButton(onClick = onNew) { Icon(Icons.Rounded.Add, "New category", tint = MaterialTheme.colorScheme.primary) }
        }
        item(key = "manage") {
            IconButton(onClick = onManage) { Icon(Icons.Rounded.Tune, "Manage categories", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CategoryTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int?, color: Color, selected: Boolean,
    style: CategoryBarStyle, hidden: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "tab")
    val mod = when (style) {
        CategoryBarStyle.PILLS -> Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
        CategoryBarStyle.TABS -> Modifier.clip(RoundedCornerShape(12.dp))
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val indicatorWidth by androidx.compose.animation.core.animateDpAsState(if (selected) 28.dp else 0.dp, label = "indicator")
    Column(
        mod.combinedClickable(onClick = onClick, onLongClick = onLongClick?.let { lc ->
            { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); lc() }
        }).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) color else fg.copy(alpha = if (hidden) 0.45f else 0.75f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = fg.copy(alpha = if (hidden) 0.6f else 1f), modifier = Modifier.widthIn(max = 160.dp),
            )
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "$count", style = MaterialTheme.typography.labelSmall, color = if (selected) color else fg,
                    modifier = Modifier.background((if (selected) color else fg).copy(alpha = 0.12f), RoundedCornerShape(50)).widthIn(min = 22.dp)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        if (style == CategoryBarStyle.TABS) {
            Spacer(Modifier.height(5.dp))
            Box(Modifier.height(3.dp).width(indicatorWidth).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryContent(
    games: List<GameEntity>, lib: LibrarySettings, selecting: Boolean, selection: Set<Long>, banner: CategoryWithCount?,
    onClick: (GameEntity) -> Unit, onLongClick: (GameEntity) -> Unit, onEditCategoryGames: (CategoryWithCount) -> Unit = {},
    onDoubleClick: ((GameEntity) -> Unit)? = null, onColumns: (Int) -> Unit = {},
) {
    val bottom = PaddingValues(bottom = 110.dp)
    if (lib.layout == LibraryLayout.LIST) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
            banner?.let { b -> item(key = "banner") { CategoryBanner(b) { onEditCategoryGames(b) } } }
            items(games, key = { it.id }) { g ->
                GameListRow(g, selecting, g.id in selection, onClick = { onClick(g) }, onLongClick = { onLongClick(g) },
                    onDoubleClick = onDoubleClick?.let { d -> { d(g) } }, modifier = Modifier.animateItem())
            }
        }
        return
    }
    if (lib.layout == LibraryLayout.CAROUSEL) {
        Column(Modifier.fillMaxSize()) {
            banner?.let { b -> Box(Modifier.padding(horizontal = 16.dp)) { CategoryBanner(b) { onEditCategoryGames(b) } } }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(bottom = 90.dp)) {
                // One large cover at a time, snapping to the centre; the neighbours peek in at the sides.
                val itemW = minOf(maxWidth * 0.58f, maxHeight * 0.62f, 340.dp)
                val state = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.foundation.lazy.LazyRow(
                    state = state,
                    flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
                    contentPadding = PaddingValues(horizontal = (maxWidth - itemW) / 2),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(games, key = { it.id }) { g ->
                        GameTile(
                            game = g, lib = lib.copy(showTitles = true), selected = g.id in selection, selectionMode = selecting,
                            onClick = { onClick(g) }, onLongClick = { onLongClick(g) }, modifier = Modifier.width(itemW).animateItem(),
                            onDoubleClick = onDoubleClick?.let { d -> { d(g) } },
                        )
                    }
                }
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizeFactor = when (lib.layout) { LibraryLayout.COMPACT -> 0.72f; LibraryLayout.LARGE -> 1.45f; else -> 1f }
        val auto = ((maxWidth - 32.dp) / ((lib.coverWidth * sizeFactor).dp + lib.spacing.dp)).toInt()
        val columns = (if (lib.columns > 0) lib.columns else auto).coerceIn(2, 10)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().then(
                // Spread two fingers for bigger covers (fewer columns), pinch for smaller ones.
                if (lib.pinchGrid) Modifier.pinchSteps(columns) { step -> (columns - step).coerceIn(2, 6).let { if (it != columns) onColumns(it) } } else Modifier,
            ),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(lib.spacing.dp),
            verticalArrangement = Arrangement.spacedBy(lib.spacing.dp + if (lib.showTitles) 2.dp else 0.dp),
        ) {
            banner?.let { b -> item(key = "banner", span = { GridItemSpan(maxLineSpan) }) { CategoryBanner(b) { onEditCategoryGames(b) } } }
            items(games, key = { it.id }) { g ->
                GameTile(
                    game = g, lib = lib, selected = g.id in selection, selectionMode = selecting,
                    onClick = { onClick(g) }, onLongClick = { onLongClick(g) }, modifier = Modifier.animateItem(),
                    onDoubleClick = onDoubleClick?.let { d -> { d(g) } },
                )
            }
        }
    }
}

/** Slim header for the selected category, using its cover image when it has one. */
@Composable
private fun CategoryBanner(cw: CategoryWithCount, onEditGames: () -> Unit) {
    val c = cw.category
    val color = Color(c.color.toInt())
    val cs = MaterialTheme.colorScheme
    val hasCover = c.coverPath != null
    Box(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).height(if (hasCover) 88.dp else 56.dp).clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainer).border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
    ) {
        if (hasCover) {
            AsyncImage(model = java.io.File(c.coverPath!!), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.2f)))))
        }
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(color.copy(alpha = 0.16f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(CategoryIcons.of(c.icon), null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (hasCover) Color.White else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${cw.gameCount} game${if (cw.gameCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall,
                    color = if (hasCover) Color.White.copy(alpha = 0.75f) else cs.onSurfaceVariant)
            }
            IconButton(onClick = onEditGames) {
                Icon(Icons.Rounded.Checklist, "Add or remove games", tint = if (hasCover) Color.White else cs.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameListRow(
    game: GameEntity, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null, modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onDoubleClick = onDoubleClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(game, Modifier.size(width = 54.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(game.effectiveEngine.displayName)
                    if (game.playtimeMs > 0) append(" · ").append(GameActivity.formatDuration(game.playtimeMs))
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (selecting) {
            androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else GameStateChip(game)
    }
}


/** Choose which library games are in [category]: every game with a checkbox (search to narrow the list). */
@Composable
private fun CategoryGamesDialog(
    category: CategoryEntity, games: List<GameEntity>, membership: Map<Long, Set<Long>>,
    onDismiss: () -> Unit, onSave: (add: Set<Long>, remove: Set<Long>) -> Unit,
) {
    val initial = remember(category.id) { games.filter { membership[it.id]?.contains(category.id) == true }.map { it.id }.toSet() }
    var chosen by remember(category.id) { mutableStateOf(initial) }
    var filter by remember { mutableStateOf("") }
    val shown = games.filter { filter.isBlank() || it.title.contains(filter, true) || it.effectiveEngine.displayName.contains(filter, true) }
        .sortedBy { it.title.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Games in ${category.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Search your games") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { chosen = chosen + shown.map { it.id } }) { Text("Select shown") }
                    TextButton(onClick = { chosen = chosen - shown.map { it.id }.toSet() }) { Text("Clear shown") }
                }
                if (games.isEmpty()) Text("Your library has no games yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { g ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            chosen = if (g.id in chosen) chosen - g.id else chosen + g.id
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(g.id in chosen, { on -> chosen = if (on) chosen + g.id else chosen - g.id })
                            Column(Modifier.weight(1f)) {
                                Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(g.effectiveEngine.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen - initial, initial - chosen) }) { Text("Save (${chosen.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Horizontal swipe over the games: next (swipe left) or previous (swipe right) category. Vertical scrolling is unaffected. */
private fun Modifier.categorySwipe(key: Any?, onStep: (Int) -> Unit): Modifier = pointerInput(key) {
    val threshold = 72.dp.toPx()
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = { if (kotlin.math.abs(total) > threshold) onStep(if (total < 0) 1 else -1); total = 0f },
        onDragCancel = { total = 0f },
    ) { change, dx -> total += dx; change.consume() }
}

/**
 * Two-finger pinch in steps: +1 each time the fingers spread by about a quarter, -1 each time they close by as much.
 * One-finger touches are left alone, so the list still scrolls and taps still work.
 */
private fun Modifier.pinchSteps(key: Any?, onStep: (Int) -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var acc = 1f
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.count { it.pressed } >= 2) {
                acc *= ev.calculateZoom()
                if (acc > 1.25f) { onStep(1); acc = 1f } else if (acc < 0.8f) { onStep(-1); acc = 1f }
                ev.changes.forEach { it.consume() }
            }
        } while (ev.changes.any { it.pressed })
    }
}
