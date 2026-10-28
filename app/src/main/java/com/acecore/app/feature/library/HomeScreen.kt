package com.acecore.app.feature.library

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
    // The selected category must still exist and be visible; otherwise show all games.
    val selectedCategory = lib.selectedCategory.takeIf { id -> visibleCategories.any { it.category.id == id } }
    fun selectCategory(id: Long) { scope.launch { settingsRepo.updateLibrary { it.copy(selectedCategory = id) } } }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { repo.takePermission(uri); onImport(uri, selectedCategory) }
    }

    val games = all
    val visible = remember(games, query, selectedCategory, membership, lib.sort, lib.showHidden) {
        games.orEmpty()
            .filter { !it.hidden || lib.showHidden }
            .filter { g -> selectedCategory == null || membership[g.id]?.contains(selectedCategory) == true }
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
            if (!inSelection && games?.isNotEmpty() == true) ExtendedFloatingActionButton(
                onClick = { pickFolder.launch(null) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add game") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (inSelection) {
                SelectionBar(
                    count = selection.size,
                    inCategory = selectedCategory != null,
                    onClose = { selecting = false; selection = emptySet() },
                    onSelectAll = { selection = visible.map { it.id }.toSet() },
                    onCategories = { if (selection.isNotEmpty()) assignMode = CategoryAssignMode.ADD },
                    onRemoveFromCategory = {
                        val c = selectedCategory ?: return@SelectionBar
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
                    selected = selectedCategory,
                    style = lib.categoryBar,
                    showCounts = lib.showCategoryCounts,
                    onSelect = { selectCategory(it ?: LibrarySettings.ALL_GAMES) },
                    onManage = onManageCategories,
                )
            }
            Box(Modifier.weight(1f)) {
                when {
                    games == null -> Unit
                    games.isEmpty() -> EmptyState(
                        Icons.Rounded.SportsEsports, "Your library is empty",
                        "Add a game folder. AceCore detects the engine and plays the game from where it is. Nothing is copied unless you ask for it.",
                        Modifier.padding(top = 40.dp),
                    ) {
                        ExtendedFloatingActionButton(onClick = { pickFolder.launch(null) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Choose a game folder") })
                    }
                    visible.isEmpty() && query.isNotBlank() -> EmptyState(Icons.Rounded.Search, "No matches", "No game matches “$query”.", Modifier.padding(top = 24.dp))
                    visible.isEmpty() && selectedCategory != null -> {
                        val c = visibleCategories.first { it.category.id == selectedCategory }.category
                        EmptyState(CategoryIcons.of(c.icon), "No games in ${c.name} yet",
                            "Long-press games in “All games” and choose Category, add a game here with “Add game”, or link a folder to this category.",
                            Modifier.padding(top = 24.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pickFolder.launch(null) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add game") }
                                OutlinedButton(onClick = onManageCategories) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("Link folder") }
                            }
                        }
                    }
                    else -> LibraryContent(
                        games = visible, lib = lib, selecting = inSelection, selection = selection,
                        banner = selectedCategory?.let { id -> visibleCategories.firstOrNull { it.category.id == id } },
                        onClick = { g ->
                            if (inSelection) selection = if (g.id in selection) selection - g.id else selection + g.id
                            else launcher.launch(g)
                        },
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
        text = { Text("Only the library entries are removed. Game files stay on your device, and AceCore keeps its own saved data for them.") },
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
            painterResource(R.drawable.acecore_logo), contentDescription = "AceCore",
            contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f).height(52.dp),
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
    categories: List<CategoryWithCount>, total: Int, selected: Long?, style: CategoryBarStyle, showCounts: Boolean,
    onSelect: (Long?) -> Unit, onManage: () -> Unit,
) {
    val state = rememberLazyListState()
    val selIndex = if (selected == null) 0 else categories.indexOfFirst { it.category.id == selected } + 1
    LaunchedEffect(selIndex) { if (selIndex >= 0) runCatching { state.animateScrollToItem(selIndex) } }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (style == CategoryBarStyle.PILLS) 8.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all") {
            CategoryTab(Icons.Rounded.Apps, "All games", if (showCounts) total else null, AceTheme.tokens.glow, selected == null, style, false) { onSelect(null) }
        }
        items(categories, key = { it.category.id }) { cw ->
            val c = cw.category
            CategoryTab(CategoryIcons.of(c.icon), c.name, if (showCounts) cw.gameCount else null, Color(c.color.toInt()), selected == c.id, style, c.hidden) { onSelect(c.id) }
        }
        item(key = "manage") {
            IconButton(onClick = onManage) { Icon(Icons.Rounded.Tune, "Manage categories", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CategoryTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int?, color: Color, selected: Boolean,
    style: CategoryBarStyle, hidden: Boolean, onClick: () -> Unit,
) {
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "tab")
    val mod = when (style) {
        CategoryBarStyle.PILLS -> Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
        CategoryBarStyle.TABS -> Modifier.clip(RoundedCornerShape(12.dp))
    }
    Column(mod.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) color else fg.copy(alpha = if (hidden) 0.5f else 1f), modifier = Modifier.size(18.dp))
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
                    modifier = Modifier.background((if (selected) color else fg).copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        if (style == CategoryBarStyle.TABS) {
            Spacer(Modifier.height(5.dp))
            Box(Modifier.height(3.dp).width(28.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) color else Color.Transparent))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryContent(
    games: List<GameEntity>, lib: LibrarySettings, selecting: Boolean, selection: Set<Long>, banner: CategoryWithCount?,
    onClick: (GameEntity) -> Unit, onLongClick: (GameEntity) -> Unit,
) {
    val bottom = PaddingValues(bottom = 110.dp)
    if (lib.layout == LibraryLayout.LIST) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
            banner?.let { b -> item(key = "banner") { CategoryBanner(b) } }
            items(games, key = { it.id }) { g ->
                GameListRow(g, selecting, g.id in selection, onClick = { onClick(g) }, onLongClick = { onLongClick(g) }, modifier = Modifier.animateItem())
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(lib.spacing.dp),
            verticalArrangement = Arrangement.spacedBy(lib.spacing.dp + if (lib.showTitles) 2.dp else 0.dp),
        ) {
            banner?.let { b -> item(key = "banner", span = { GridItemSpan(maxLineSpan) }) { CategoryBanner(b) } }
            items(games, key = { it.id }) { g ->
                GameTile(
                    game = g, lib = lib, selected = g.id in selection, selectionMode = selecting,
                    onClick = { onClick(g) }, onLongClick = { onLongClick(g) }, modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** Slim header for the selected category, using its cover image when it has one. */
@Composable
private fun CategoryBanner(cw: CategoryWithCount) {
    val c = cw.category
    val color = Color(c.color.toInt())
    Box(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).height(if (c.coverPath != null) 96.dp else 52.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), MaterialTheme.colorScheme.surfaceContainer)))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
    ) {
        if (c.coverPath != null) {
            AsyncImage(model = java.io.File(c.coverPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
        }
        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CategoryIcons.of(c.icon), null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(c.name, style = MaterialTheme.typography.titleMedium, color = if (c.coverPath != null) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${cw.gameCount} game${if (cw.gameCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall,
                    color = if (c.coverPath != null) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameListRow(game: GameEntity, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 8.dp, vertical = 8.dp),
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
