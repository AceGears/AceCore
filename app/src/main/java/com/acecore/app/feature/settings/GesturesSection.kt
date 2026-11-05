package com.acecore.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.acecore.app.container
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.ConfigSource
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.datastore.AppSettings
import com.acecore.app.core.datastore.LibrarySettings
import com.acecore.app.core.datastore.TapAction
import com.acecore.app.core.input.Gesture
import com.acecore.app.core.input.GestureConfig
import kotlinx.coroutines.launch

/** The in-game gesture keys (the "Gestures" section of the layered config). */
internal val GESTURE_KEYS = ConfigKey.entries.filter { it.section == "Gestures" }

/** Settings › Controllers & input › Gestures: library gestures, in-game gestures and a test area that uses them live. */
@Composable
internal fun GesturesSection(s: AppSettings) {
    val ctx = LocalContext.current
    val repo = ctx.container.settings
    val scope = rememberCoroutineScope()
    val lib = s.library
    var confirmReset by remember { mutableStateOf(false) }
    fun update(f: (LibrarySettings) -> LibrarySettings) { scope.launch { repo.updateLibrary(f) } }

    SettingsGroupTitle("Library")
    SwitchRow("Swipe to change category", "Swipe left or right over your games for the next or previous category.", lib.swipeCategories) { v -> update { it.copy(swipeCategories = v) } }
    SwitchRow("Pinch to resize covers", "Spread two fingers on the grid for bigger covers, pinch for smaller ones (sets the number of columns).", lib.pinchGrid) { v -> update { it.copy(pinchGrid = v) } }
    ChoiceRow("Tap a game", null, TapAction.entries.map { it.name to it.label }, lib.tapAction.name) { v ->
        update { it.copy(tapAction = TapAction.valueOf(v)) }
    }
    Text(
        "Long-press a game for its options; long-press a category tab to edit, move, hide or delete it.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )

    SettingsGroupTitle("In games")
    Text(
        "Gestures on the game picture of Game Boy, Game Boy Color, Game Boy Advance and Nintendo DS games. These are the " +
            "global defaults; each game can have its own (the game's page › Game settings › Gestures). On the DS, one-finger " +
            "touches always go to the game's touch screen.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    val config = LayeredConfig(s.globalConfig, emptyMap(), emptyMap())
    ConfigEditor(GESTURE_KEYS, config, ConfigSource.GLOBAL, showSectionTitles = false) { key, value ->
        scope.launch { repo.setGlobalConfig(key, value) }
    }

    SettingsGroupTitle("Try it")
    GestureTestArea(GestureConfig.from { config.string(it) })

    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        OutlinedButton(onClick = { confirmReset = true }) { Text("Restore default gestures") }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Restore default gestures?") },
        text = { Text("Resets the library gestures and the global in-game gestures. Gestures you set for individual games stay as they are.") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                scope.launch {
                    val d = LibrarySettings()
                    repo.updateLibrary { it.copy(swipeCategories = d.swipeCategories, pinchGrid = d.pinchGrid, tapAction = d.tapAction) }
                    GESTURE_KEYS.forEach { repo.setGlobalConfig(it, null) }
                }
            }) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

/** A touch area running the same recogniser as the games, with the current settings, showing what it recognised. */
@Composable
private fun GestureTestArea(cfg: GestureConfig) {
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    var last by remember { mutableStateOf("Tap, double-tap, tap with two or three fingers, swipe with two fingers or pinch here.") }
    var count by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf(1f) }
    val current by rememberUpdatedState(cfg)
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(200.dp)
            .background(cs.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .border(1.dp, cs.primary.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .semantics { contentDescription = "Gesture test area" }
            .pointerInput(cfg) {
                fun report(g: Gesture) {
                    val action = current.actionFor(g)
                    if (current.haptics && action != "off") haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    count++
                    last = "${g.label} → in a game: ${GestureConfig.actionLabel(action)}" +
                        if (g == Gesture.DOUBLE_TAP) " (not on the DS touch screen)" else ""
                }
                val tracker = cfg.tracker(density, ::report) { f ->
                    zoom = (zoom * f).coerceIn(0.25f, 3f)
                    last = "Pinch → in a game: ${GestureConfig.actionLabel(current.actionFor(Gesture.PINCH))} (${(zoom * 100).toInt()}%)"
                }
                tracker.width = size.width.toFloat()
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        for (c in ev.changes) {
                            val id = c.id.value.toInt()
                            when {
                                c.pressed && !c.previousPressed -> tracker.onDown(id, c.position.x, c.position.y, c.uptimeMillis)
                                !c.pressed && c.previousPressed -> tracker.onUp(id, c.uptimeMillis)
                                c.pressed -> tracker.onMove(id, c.position.x, c.position.y, c.uptimeMillis)
                            }
                            c.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
            Text(
                last, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (count > 0) Text("$count recognised", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (!cfg.enabled) Text("Gestures are off, so games would ignore these.", style = MaterialTheme.typography.bodySmall, color = cs.error)
        }
    }
}
