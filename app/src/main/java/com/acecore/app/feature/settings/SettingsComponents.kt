package com.acecore.app.feature.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.config.PadButton
import com.acecore.app.core.config.WebKey
import com.acecore.app.core.designsystem.toColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = behavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) { content() }
    }
}

@Composable
fun SettingsGroupTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun NavRow(icon: ImageVector?, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Optional trailing slot used by the layered-config editor for "reset to inherited". */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { onChange(!checked) }
            .semantics { role = Role.Switch; stateDescription = if (checked) "On" else "Off" }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Slider that only persists when the user lets go, so DataStore isn't written on every frame. */
@Composable
fun SliderRow(
    title: String, subtitle: String?, value: Int, range: IntRange, unit: String = "", steps: Int = 0,
    trailing: (@Composable () -> Unit)? = null, onChange: (Int) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
            Text("${local.toInt()}$unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
        Slider(
            value = local, onValueChange = { local = it }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
            onValueChangeFinished = { onChange(local.toInt()) },
        )
    }
}

@Composable
fun ChoiceRow(title: String, subtitle: String?, options: List<Pair<String, String>>, selected: String, trailing: (@Composable () -> Unit)? = null, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = true }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(options.firstOrNull { it.first == selected }?.second ?: selected, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(value); open = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = value == selected, onClick = { onChange(value); open = false })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
fun ColorRow(title: String, color: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = true }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("#%06X".format(color and 0xFFFFFF), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(32.dp).clip(CircleShape).background(color.toColor()).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
    if (open) ColorPickerDialog(title, color, onDismiss = { open = false }) { onChange(it); open = false }
}

/** HSV + hex colour picker. */
@Composable
fun ColorPickerDialog(title: String, initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val current = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL
    var hex by remember(current) { mutableStateOf("%06X".format(current and 0xFFFFFF)) }
    val presets = listOf(0xFFF03AD6, 0xFFE5199C, 0xFF2E86FF, 0xFF2ED2FF, 0xFF8A1CF0, 0xFFD4D8E4, 0xFFE3132B, 0xFFFF4D5E, 0xFF4C8DFF, 0xFF9B5CFF, 0xFF1ED69B, 0xFFE8B931, 0xFFFF7A1A, 0xFF00C2D1, 0xFFFFFFFF, 0xFF16161B, 0xFF000000)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(current.toColor()))
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })))
                Slider(h, { h = it }, valueRange = 0f..359.9f)
                Text("Saturation", style = MaterialTheme.typography.labelMedium)
                Slider(s, { s = it })
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(v, { v = it })
                OutlinedTextField(hex, { input ->
                    hex = input.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    if (hex.length == 6) {
                        val c = (0xFF000000L or hex.toLong(16)).toInt()
                        val arr = FloatArray(3); android.graphics.Color.colorToHSV(c, arr); h = arr[0]; s = arr[1]; v = arr[2]
                    }
                }, label = { Text("Hex") }, prefix = { Text("#") }, singleLine = true)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(p.toColor()).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable {
                            val arr = FloatArray(3); android.graphics.Color.colorToHSV(p.toInt(), arr); h = arr[0]; s = arr[1]; v = arr[2]
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current or 0xFF000000L) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Visual controller mapping editor: each controller button → the keyboard key the game receives. */
@Composable
fun ButtonMapDialog(encoded: String, resetTo: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var map by remember { mutableStateOf(ButtonMap.decode(encoded)) }
    var editing by remember { mutableStateOf<PadButton?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Controller mapping") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(PadButton.entries) { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { editing = b }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(b.label, modifier = Modifier.weight(1f))
                        Text(
                            WebKey.byId(map[b])?.label ?: "—", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                item {
                    Text(
                        "Defaults follow RPG Maker conventions: A = Z (confirm), B = X (cancel/menu), X = Shift (dash), L1/R1 = Q/W (page).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ButtonMap.encode(map)) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { map = ButtonMap.decode(resetTo) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    editing?.let { b ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Key for ${b.label}") },
            text = {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WebKey.ALL.forEach { k ->
                        val sel = map[b] == k.id
                        Text(
                            k.label,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { map = map + (b to k.id); editing = null }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Close") } },
        )
    }
}

