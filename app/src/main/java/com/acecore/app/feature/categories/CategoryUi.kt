package com.acecore.app.feature.categories

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Castle
import androidx.compose.material.icons.rounded.CatchingPokemon
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.acecore.app.core.database.CategoryEntity
import com.acecore.app.core.database.CategoryWithCount

/** Built-in category icons, stored by key in the database. */
object CategoryIcons {
    val ALL: List<Pair<String, ImageVector>> = listOf(
        "folder" to Icons.Rounded.Folder,
        "games" to Icons.Rounded.SportsEsports,
        "controller" to Icons.Rounded.VideogameAsset,
        "pokemon" to Icons.Rounded.CatchingPokemon,
        "rpg" to Icons.Rounded.Castle,
        "novel" to Icons.Rounded.AutoStories,
        "adventure" to Icons.Rounded.Explore,
        "horror" to Icons.Rounded.Nightlight,
        "action" to Icons.Rounded.Bolt,
        "favorite" to Icons.Rounded.Favorite,
        "star" to Icons.Rounded.Star,
        "completed" to Icons.Rounded.CheckCircle,
        "playing" to Icons.Rounded.PlayCircle,
        "trophy" to Icons.Rounded.EmojiEvents,
        "hot" to Icons.Rounded.LocalFireDepartment,
        "shield" to Icons.Rounded.Shield,
        "puzzle" to Icons.Rounded.Extension,
        "pets" to Icons.Rounded.Pets,
        "space" to Icons.Rounded.RocketLaunch,
        "music" to Icons.Rounded.MusicNote,
        "cinema" to Icons.Rounded.Theaters,
        "gem" to Icons.Rounded.Diamond,
        "science" to Icons.Rounded.Science,
        "web" to Icons.Rounded.Code,
    )
    private val map = ALL.toMap()
    fun of(key: String): ImageVector = map[key] ?: Icons.Rounded.Folder
}

val CATEGORY_COLORS: List<Long> = listOf(
    0xFFF03AD6, 0xFFE5199C, 0xFF2E86FF, 0xFF2ED2FF, 0xFF8A1CF0, 0xFFE3132B, 0xFFFF4D5E, 0xFFFF7A1A, 0xFFE8B931, 0xFF1ED69B, 0xFF00C2D1,
    0xFF4C8DFF, 0xFF9B5CFF, 0xFFE056C1, 0xFFC9CDD3, 0xFF8D6E63, 0xFF5B5F66,
)

/** Round badge with a category's icon in its colour. */
@Composable
fun CategoryBadge(icon: String, color: Long, modifier: Modifier = Modifier, size: Int = 36) {
    val c = Color(color.toInt())
    Box(
        modifier.size(size.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)).border(1.dp, c.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(CategoryIcons.of(icon), null, tint = c, modifier = Modifier.size((size * 0.55f).dp)) }
}

/** Create or edit a category: name, icon and colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditDialog(
    initial: CategoryEntity?,
    error: String? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, icon: String, color: Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.icon ?: "folder") }
    var color by remember { mutableStateOf(initial?.color ?: CATEGORY_COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(icon, color, size = 48)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true,
                        isError = error != null, supportingText = error?.let { { Text(it) } }, modifier = Modifier.weight(1f),
                    )
                }
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryIcons.ALL.forEach { (key, vector) ->
                        val sel = key == icon
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Color(color.toInt()).copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(if (sel) 2.dp else 0.dp, if (sel) Color(color.toInt()) else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) { Icon(vector, key, tint = if (sel) Color(color.toInt()) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
                    }
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CATEGORY_COLORS.forEach { c ->
                        val sel = c == color
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c.toInt()))
                                .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.15f), CircleShape)
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) { if (sel) Icon(Icons.Rounded.Check, "Selected", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, icon, color) }) { Text(if (initial == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

enum class CategoryAssignMode(val label: String) { ADD("Add to"), MOVE("Move to"), REMOVE("Remove from") }

/**
 * Category picker for one or many games. Each category shows whether all, some or none of the selected games are in
 * it. ADD/REMOVE apply to the ticked categories; MOVE puts the games in exactly the ticked categories.
 */
@Composable
fun CategoryAssignDialog(
    categories: List<CategoryWithCount>,
    gameCount: Int,
    /** categoryId -> how many of the selected games are already in it */
    membersPerCategory: Map<Long, Int>,
    initialMode: CategoryAssignMode = CategoryAssignMode.ADD,
    onCreateCategory: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (mode: CategoryAssignMode, categoryIds: Set<Long>) -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var ticked by remember(membersPerCategory, mode) {
        mutableStateOf(
            when (mode) {
                CategoryAssignMode.MOVE -> membersPerCategory.filterValues { it == gameCount }.keys
                else -> emptySet()
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (gameCount == 1) "Categories" else "Categories for $gameCount games") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CategoryAssignMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, CategoryAssignMode.entries.size)) {
                            Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    when (mode) {
                        CategoryAssignMode.ADD -> "Ticked categories are added. Games keep their other categories."
                        CategoryAssignMode.MOVE -> "Games end up in exactly the ticked categories."
                        CategoryAssignMode.REMOVE -> "Games are taken out of the ticked categories (they stay in your library)."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (categories.isEmpty()) Text("No categories yet.", modifier = Modifier.padding(vertical = 12.dp))
                    categories.forEach { cw ->
                        val c = cw.category
                        val members = membersPerCategory[c.id] ?: 0
                        val isTicked = c.id in ticked
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { ticked = if (isTicked) ticked - c.id else ticked + c.id }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (mode == CategoryAssignMode.ADD && !isTicked && members in 1 until gameCount) {
                                TriStateCheckbox(ToggleableState.Indeterminate, onClick = { ticked = ticked + c.id })
                            } else {
                                Checkbox(isTicked || (mode == CategoryAssignMode.ADD && members == gameCount && gameCount > 0), onCheckedChange = {
                                    ticked = if (isTicked) ticked - c.id else ticked + c.id
                                })
                            }
                            CategoryBadge(c.icon, c.color, size = 30)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (members > 0) Text(
                                    if (gameCount == 1) "Already in this category" else "$members of $gameCount already in it",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (c.hidden) Text("Hidden", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = onCreateCategory) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New category")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ticked.isNotEmpty() || mode == CategoryAssignMode.MOVE, onClick = { onApply(mode, ticked) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
