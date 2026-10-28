package com.acecore.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.acecore.app.container
import com.acecore.app.core.datastore.CategoryBarStyle
import com.acecore.app.core.datastore.CoverAspect
import com.acecore.app.core.datastore.LibraryLayout
import com.acecore.app.core.datastore.LibrarySettings
import com.acecore.app.core.datastore.LibrarySort
import kotlinx.coroutines.launch

/** Library layout panel opened from the home screen header: every option applies live and is saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryLayoutSheet(lib: LibrarySettings, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.settings
    val scope = rememberCoroutineScope()
    fun update(f: (LibrarySettings) -> LibrarySettings) { scope.launch { repo.updateLibrary(f) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Library layout", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Label("Style")
            ChipRow(listOf(LibraryLayout.GRID, LibraryLayout.COMPACT, LibraryLayout.LARGE, LibraryLayout.LIST).map { it to it.label }, lib.layout) { v -> update { it.copy(layout = v) } }
            Label("Sort by")
            ChipRow(LibrarySort.entries.map { it to it.label }, lib.sort) { v -> update { it.copy(sort = v) } }

            if (lib.layout != LibraryLayout.LIST) {
                Label("Columns")
                ChipRow(listOf(0 to "Auto") + (2..6).map { it to "$it" }, lib.columns) { v -> update { it.copy(columns = v) } }
                if (lib.columns == 0) LiveSlider("Cover size", lib.coverWidth, 80..200, "dp") { v -> update { it.copy(coverWidth = v) } }
                Label("Cover shape")
                ChipRow(CoverAspect.entries.map { it to it.label }, lib.aspect) { v -> update { it.copy(aspect = v) } }
                LiveSlider("Spacing", lib.spacing, 4..28, "dp") { v -> update { it.copy(spacing = v) } }
                LiveSlider("Corner radius", lib.coverRadius, 0..28, "dp") { v -> update { it.copy(coverRadius = v) } }
                ToggleRow("Game titles", lib.showTitles) { v -> update { it.copy(showTitles = v) } }
                ToggleRow("Cover borders", lib.coverBorder) { v -> update { it.copy(coverBorder = v) } }
                ToggleRow("Cover glow", lib.coverGlow) { v -> update { it.copy(coverGlow = v) } }
                if (lib.coverBorder || lib.coverGlow) {
                    Label("Border & glow colour")
                    val colors = listOf(0L, 0xFFE3132BL, 0xFFFF4D5EL, 0xFFC9CDD3L, 0xFF4C8DFFL, 0xFF9B5CFFL, 0xFF1ED69BL, 0xFFE8B931L, 0xFFFFFFFFL)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        colors.forEach { c ->
                            val sel = lib.borderColor == c
                            Box(
                                Modifier.size(36.dp).background(if (c == 0L) MaterialTheme.colorScheme.primary else Color(c.toInt()), CircleShape)
                                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.2f), CircleShape)
                                    .clickable { update { it.copy(borderColor = c) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (sel) Icon(Icons.Rounded.Check, "Selected", tint = Color.White, modifier = Modifier.size(18.dp))
                                else if (c == 0L) Text("A", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text("“A” follows your theme's accent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Label("Category bar")
            ChipRow(CategoryBarStyle.entries.map { it to it.label }, lib.categoryBar) { v -> update { it.copy(categoryBar = v) } }
            ToggleRow("Show game counts on categories", lib.showCategoryCounts) { v -> update { it.copy(showCategoryCounts = v) } }
            ToggleRow("Show hidden categories", lib.showHiddenCategories) { v -> update { it.copy(showHiddenCategories = v) } }
            ToggleRow("Show hidden games", lib.showHidden) { v -> update { it.copy(showHidden = v) } }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                update { LibrarySettings().copy(selectedCategory = it.selectedCategory, sort = it.sort) }
            }) { Text("Reset layout") }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) }) }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

/** Slider that updates the preview while dragging and saves when released. */
@Composable
private fun LiveSlider(title: String, value: Int, range: IntRange, unit: String, onCommit: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("${v.toInt()} $unit", style = MaterialTheme.typography.labelLarge)
    }
    Slider(
        value = v, onValueChange = { v = it }, onValueChangeFinished = { onCommit(v.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
    Spacer(Modifier.width(0.dp))
}
