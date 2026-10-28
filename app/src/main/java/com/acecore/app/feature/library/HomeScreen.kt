package com.acecore.app.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.R
import com.acecore.app.container
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.datastore.LibraryLayout
import com.acecore.app.core.datastore.LibrarySettings
import com.acecore.app.core.datastore.LibrarySort
import com.acecore.app.core.designsystem.EmptyState
import com.acecore.app.core.designsystem.SectionHeader
import com.acecore.app.core.designsystem.StatusChip
import com.acecore.app.core.designsystem.aceHeroBrush
import com.acecore.app.feature.LocalGameLauncher
import com.acecore.app.runtime.web.GameActivity
import kotlinx.coroutines.launch

private enum class Filter(val label: String) { ALL("All"), FAVORITES("Favorites"), PLAYABLE("Playable"), NOT_PLAYABLE("Not yet playable"), HIDDEN("Hidden") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenGame: (Long) -> Unit,
    onOpenGameSettings: (Long) -> Unit,
    onImport: (Uri) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val settingsRepo = ctx.container.settings
    val scope = rememberCoroutineScope()
    val launcher = LocalGameLauncher.current
    val all by repo.games.collectAsStateWithLifecycle(initialValue = null)
    val appSettings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
    val lib = appSettings?.library ?: LibrarySettings()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<GameEntity?>(null) }
    var sortOpen by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { repo.takePermission(uri); onImport(uri) }
    }

    val games = all
    val visible = remember(games, query, filter, category, lib.sort, lib.showHidden) {
        games.orEmpty()
            .filter { g ->
                when (filter) {
                    Filter.HIDDEN -> g.hidden
                    else -> !g.hidden || lib.showHidden
                }
            }
            .filter { g ->
                when (filter) {
                    Filter.FAVORITES -> g.favorite
                    Filter.PLAYABLE -> GameRepository.isPlayable(g)
                    Filter.NOT_PLAYABLE -> !GameRepository.isPlayable(g)
                    else -> true
                }
            }
            .filter { category == null || it.category == category }
            .filter { q -> query.isBlank() || q.title.contains(query, true) || q.effectiveEngine.displayName.contains(query, true) || q.folderLabel.contains(query, true) }
            .let { sortGames(it, lib.sort) }
    }
    val categories = remember(games) { games.orEmpty().mapNotNull { it.category }.distinct().sorted() }
    val recent = remember(games) { games.orEmpty().filter { it.lastPlayedAt != null && !it.hidden }.sortedByDescending { it.lastPlayedAt }.take(10) }
    val favorites = remember(games) { games.orEmpty().filter { it.favorite && !it.hidden } }
    val searching = query.isNotBlank() || filter != Filter.ALL || category != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFolder.launch(null) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add game") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val columns = when (lib.layout) {
                LibraryLayout.COMPACT -> ((maxWidth - 32.dp) / (lib.coverWidth * 0.72f).dp).toInt()
                LibraryLayout.LARGE -> ((maxWidth - 32.dp) / (lib.coverWidth * 1.45f).dp).toInt()
                else -> ((maxWidth - 32.dp) / (lib.coverWidth.dp + lib.spacing.dp)).toInt()
            }.coerceIn(2, 10)
            val tileWidth = (maxWidth - 32.dp - lib.spacing.dp * (columns - 1)) / columns

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
                item(key = "header") {
                    Header(
                        query = query, onQuery = { query = it }, onSettings = onOpenSettings,
                        sortOpen = sortOpen, onSortOpen = { sortOpen = it }, sort = lib.sort,
                        onSort = { s -> scope.launch { settingsRepo.updateLibrary { it.copy(sort = s) } } },
                    )
                }
                item(key = "filters") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Filter.entries.filter { it != Filter.HIDDEN || games.orEmpty().any { g -> g.hidden } }, key = { it.name }) { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                        items(categories, key = { "cat_$it" }) { c ->
                            FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c) })
                        }
                    }
                }
                if (games == null) return@LazyColumn
                if (games.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            Icons.Rounded.SportsEsports, "Your library is empty",
                            "Add a game folder. AceCore detects the engine automatically and tells you honestly whether this version can run it.",
                            Modifier.padding(top = 40.dp),
                        ) {
                            ExtendedFloatingActionButton(onClick = { pickFolder.launch(null) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Choose a game folder") })
                        }
                    }
                    return@LazyColumn
                }
                if (!searching && recent.isNotEmpty()) {
                    item(key = "recentHeader") { SectionHeader("Continue playing") }
                    item(key = "recent") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(lib.spacing.dp)) {
                            items(recent, key = { "r${it.id}" }) { g ->
                                GameTile(g, (lib.coverWidth * 1.15f).dp, lib, onPlay = { launcher.launch(g) }, onMenu = { menuFor = g })
                            }
                        }
                    }
                }
                if (!searching && favorites.isNotEmpty()) {
                    item(key = "favHeader") { SectionHeader("Favorites") }
                    item(key = "fav") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(lib.spacing.dp)) {
                            items(favorites, key = { "f${it.id}" }) { g ->
                                GameTile(g, lib.coverWidth.dp, lib, onPlay = { launcher.launch(g) }, onMenu = { menuFor = g })
                            }
                        }
                    }
                }
                item(key = "allHeader") { SectionHeader(if (searching) "Results (${visible.size})" else "All games (${visible.size})") }
                when (lib.layout) {
                    LibraryLayout.LIST -> items(visible, key = { "l${it.id}" }) { g ->
                        GameListRow(g, onPlay = { launcher.launch(g) }, onMenu = { menuFor = g })
                    }
                    LibraryLayout.CAROUSEL -> item(key = "carousel") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(lib.spacing.dp)) {
                            items(visible, key = { "c${it.id}" }) { g ->
                                GameTile(g, (lib.coverWidth * 1.6f).dp, lib, onPlay = { launcher.launch(g) }, onMenu = { menuFor = g })
                            }
                        }
                    }
                    else -> items(visible.chunked(columns), key = { row -> "g" + row.joinToString("_") { it.id.toString() } }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = lib.spacing.dp / 2),
                            horizontalArrangement = Arrangement.spacedBy(lib.spacing.dp),
                        ) {
                            row.forEach { g -> GameTile(g, tileWidth, lib, onPlay = { launcher.launch(g) }, onMenu = { menuFor = g }) }
                        }
                    }
                }
                if (visible.isEmpty()) item(key = "noresults") {
                    Text("No games match.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    menuFor?.let { g ->
        GameActionsSheet(
            game = g,
            onDismiss = { menuFor = null },
            onDetails = { menuFor = null; onOpenGame(g.id) },
            onSettings = { menuFor = null; onOpenGameSettings(g.id) },
        )
    }
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
private fun Header(
    query: String, onQuery: (String) -> Unit, onSettings: () -> Unit,
    sortOpen: Boolean, onSortOpen: (Boolean) -> Unit, sort: LibrarySort, onSort: (LibrarySort) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(aceHeroBrush()).windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.acecore_logo), contentDescription = "AceCore",
                contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f).height(64.dp),
            )
            Box {
                IconButton(onClick = { onSortOpen(true) }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort library") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { onSortOpen(false) }) {
                    LibrarySort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            leadingIcon = { RadioButton(selected = s == sort, onClick = null) },
                            onClick = { onSort(s); onSortOpen(false) },
                        )
                    }
                }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Search your games") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Clear, "Clear search") } },
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameListRow(game: GameEntity, onPlay: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onPlay, onLongClick = onMenu).padding(horizontal = 16.dp, vertical = 8.dp),
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
        StatusChip(game.compatStatus)
    }
}

