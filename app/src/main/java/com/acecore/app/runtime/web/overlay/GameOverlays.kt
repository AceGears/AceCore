package com.acecore.app.runtime.web.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acecore.app.core.config.WebKey

internal val PanelBg = Color(0xF00C0A1E)

/** On-screen PC keyboard with sticky modifiers. Sends real keydown/keyup pairs into the game. */
@Composable
fun VirtualKeyboard(accent: Color, haptics: Boolean, onKey: (WebKey, Boolean) -> Unit, onClose: () -> Unit) {
    val rows = listOf(
        listOf("ESCAPE", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"),
        listOf("D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8", "D9", "D0", "BACKSPACE"),
        listOf("TAB", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", "ENTER"),
        listOf("SHIFT", "Z", "X", "C", "V", "B", "N", "M", "UP", "PAGEUP"),
        listOf("CTRL", "ALT", "SPACE", "INSERT", "DELETE", "LEFT", "DOWN", "RIGHT", "PAGEDOWN"),
    )
    val modifiers = setOf("SHIFT", "CTRL", "ALT")
    var held by remember { mutableStateOf(emptySet<String>()) }
    val haptic = LocalHapticFeedback.current

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Compact: about half the height of a full keyboard and at most 600dp wide, so the game stays visible.
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth()
                .background(PanelBg, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(horizontal = 5.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keyboard", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 6.dp))
                Icon(Icons.Rounded.Close, "Close keyboard", tint = Color.White,
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).clickable {
                        held.forEach { id -> WebKey.byId(id)?.let { onKey(it, false) } }
                        held = emptySet(); onClose()
                    }.padding(3.dp))
            }
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { id ->
                        val key = WebKey.byId(id) ?: return@forEach
                        val isMod = id in modifiers
                        val active = id in held
                        val weight = when (id) { "SPACE" -> 3f; "ENTER", "BACKSPACE", "SHIFT", "TAB" -> 1.6f; else -> 1f }
                        Box(
                            Modifier.weight(weight).height(28.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (active) accent else Color.White.copy(alpha = 0.10f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .pointerInput(id) {
                                    awaitEachGesture {
                                        awaitFirstDown()
                                        if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (isMod) {
                                            // Sticky toggle: first tap holds the modifier, second tap releases it.
                                            waitForUpOrCancellation()
                                            if (id in held) { onKey(key, false); held = held - id } else { onKey(key, true); held = held + id }
                                        } else {
                                            onKey(key, true)
                                            waitForUpOrCancellation()
                                            onKey(key, false)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(key.label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                }
            }
        }
    }
}

data class PauseState(
    val title: String,
    val sessionText: String,
    val gamepadVisible: Boolean,
    val fpsVisible: Boolean,
    val cacheText: String,
    val lastErrors: String?,
)

@Composable
fun PauseMenu(
    state: PauseState,
    accent: Color,
    onResume: () -> Unit,
    onToggleGamepad: () -> Unit,
    onKeyboard: () -> Unit,
    onEditLayout: () -> Unit,
    onToggleFps: () -> Unit,
    onReload: () -> Unit,
    onRebuildCache: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onResume),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(20.dp)
                .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.sessionText, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            MenuItem(Icons.Rounded.PlayArrow, "Resume", accent, highlight = true, onClick = onResume)
            MenuItem(Icons.Rounded.Gamepad, if (state.gamepadVisible) "Hide on-screen controls" else "Show on-screen controls", accent, onClick = onToggleGamepad)
            MenuItem(Icons.Rounded.Edit, "Edit controls layout", accent, onClick = onEditLayout)
            MenuItem(Icons.Rounded.Keyboard, "Virtual keyboard", accent, onClick = onKeyboard)
            MenuItem(Icons.Rounded.Speed, if (state.fpsVisible) "Hide performance overlay" else "Show performance overlay", accent, onClick = onToggleFps)
            MenuItem(Icons.Rounded.Refresh, "Reload game", accent, onClick = onReload)
            MenuItem(Icons.Rounded.Storage, "Rebuild file cache & reload", accent, subtitle = state.cacheText, onClick = onRebuildCache)
            if (!state.lastErrors.isNullOrBlank()) {
                Row(Modifier.fillMaxWidth().background(Color(0x33F03AD6), RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Icon(Icons.Rounded.BugReport, null, tint = accent, modifier = Modifier.size(18.dp))
                    Text(" Recent script errors:\n${state.lastErrors}", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                }
            }
            MenuItem(Icons.AutoMirrored.Rounded.ExitToApp, "Exit to library", accent, onClick = onExit)
        }
    }
}

@Composable
internal fun MenuItem(icon: ImageVector, text: String, accent: Color, highlight: Boolean = false, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (highlight) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}
