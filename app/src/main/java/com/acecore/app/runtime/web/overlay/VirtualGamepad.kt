package com.acecore.app.runtime.web.overlay

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acecore.app.core.config.PadButton
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class PadGroup { DPAD, FACE, LEFT_SHOULDER, RIGHT_SHOULDER, CENTER, TOOLS, CHEATS }

/**
 * Fast-forward / quick save / quick load buttons (FF, QS, QL). Only runtimes that implement these features pass
 * this, so no button is ever shown without a working action behind it.
 */
data class GamepadTools(
    val showFastForward: Boolean,
    val showQuickSave: Boolean,
    val showQuickLoad: Boolean,
    /** Fast-forward currently on (lights the FF button). */
    val fastForwardActive: Boolean,
    /** Speed shown on the FF button, e.g. "2×". */
    val speedLabel: String,
    /** Size of these buttons (0.6–1.6) and their own opacity (0.15–1). */
    val scale: Float = 1f,
    val opacity: Float = 0.7f,
    /** FF pressed (true) / released (false); toggle or hold is decided by the caller. */
    val onFastForward: (Boolean) -> Unit,
    val onQuickSave: () -> Unit,
    val onQuickLoad: () -> Unit,
    /** Cheat button (shown when cheats are on for the game): same look, size and opacity as FF/QS/QL, moved on its own. */
    val showCheats: Boolean = false,
    val onCheats: () -> Unit = {},
)

/** Stores per-orientation group offsets. Used only inside the game process (plain SharedPreferences). */
class GamepadLayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("gamepad_layout", Context.MODE_PRIVATE)
    fun load(orientation: String): Map<PadGroup, Offset> = PadGroup.entries.associateWith {
        Offset(prefs.getFloat("$orientation.${it.name}.x", 0f), prefs.getFloat("$orientation.${it.name}.y", 0f))
    }
    fun save(orientation: String, offsets: Map<PadGroup, Offset>) {
        prefs.edit().apply {
            offsets.forEach { (g, o) -> putFloat("$orientation.${g.name}.x", o.x); putFloat("$orientation.${g.name}.y", o.y) }
        }.apply()
    }
    fun reset(orientation: String) {
        prefs.edit().apply { PadGroup.entries.forEach { remove("$orientation.${it.name}.x"); remove("$orientation.${it.name}.y") } }.apply()
    }
}

/**
 * Fully touch-driven on-screen controller. Every control is its own pointer target, so multi-touch
 * (e.g. hold a direction while tapping A) works. In [editing] mode each group can be dragged and the
 * layout is saved per orientation.
 */
@Composable
fun VirtualGamepad(
    store: GamepadLayoutStore,
    opacity: Float,
    scale: Float,
    haptics: Boolean,
    accent: Color,
    editing: Boolean,
    onEditDone: () -> Unit,
    onButton: (PadButton, Boolean) -> Unit,
    onMenu: () -> Unit,
    /** 4-way D-pad (no diagonals): RPG Maker XP/VX/Ace move in four directions only. */
    fourWay: Boolean = false,
    /** Reports where the D-pad is on screen (window pixels), for diagnostics and the emulator input test. */
    onDpadBounds: ((android.graphics.Rect) -> Unit)? = null,
    tools: GamepadTools? = null,
) {
    val orientation = if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "port" else "land"
    val offsets = remember(orientation) { mutableStateMapOf<PadGroup, Offset>().apply { putAll(store.load(orientation)) } }
    val haptic = LocalHapticFeedback.current
    val press: (PadButton, Boolean) -> Unit = { b, down ->
        if (down && haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onButton(b, down)
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        @Composable
        fun BoxScope.Group(group: PadGroup, align: Alignment, alpha: Float = opacity, content: @Composable () -> Unit) {
            val o = offsets[group] ?: Offset.Zero
            Box(
                Modifier.align(align)
                    .offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                    .then(
                        if (editing) Modifier
                            .border(2.dp, accent, RoundedCornerShape(16.dp))
                            .pointerInput(group) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    offsets[group] = (offsets[group] ?: Offset.Zero) + drag
                                }
                            }
                        else Modifier,
                    )
                    .alpha(if (editing) 1f else alpha),
            ) { content() }
        }

        Group(PadGroup.DPAD, Alignment.BottomStart) { DPad((150 * scale).dp, accent, enabled = !editing, fourWay = fourWay, onButton = press, onBounds = onDpadBounds) }
        Group(PadGroup.FACE, Alignment.BottomEnd) { FaceButtons((58 * scale).dp, accent, enabled = !editing, onButton = press) }
        Group(PadGroup.LEFT_SHOULDER, Alignment.TopStart) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PadKey("L2", PadButton.L2, Modifier.size((72 * scale).dp, (36 * scale).dp), RoundedCornerShape(12.dp), accent, !editing, press)
                PadKey("L1", PadButton.L1, Modifier.size((72 * scale).dp, (36 * scale).dp), RoundedCornerShape(12.dp), accent, !editing, press)
            }
        }
        Group(PadGroup.RIGHT_SHOULDER, Alignment.TopEnd) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                PadKey("R2", PadButton.R2, Modifier.size((72 * scale).dp, (36 * scale).dp), RoundedCornerShape(12.dp), accent, !editing, press)
                PadKey("R1", PadButton.R1, Modifier.size((72 * scale).dp, (36 * scale).dp), RoundedCornerShape(12.dp), accent, !editing, press)
            }
        }
        Group(PadGroup.CENTER, Alignment.BottomCenter) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PadKey("SELECT", PadButton.SELECT, Modifier.size((70 * scale).dp, (28 * scale).dp), RoundedCornerShape(50), accent, !editing, press)
                PadKey("START", PadButton.START, Modifier.size((70 * scale).dp, (28 * scale).dp), RoundedCornerShape(50), accent, !editing, press)
            }
        }
        if (tools != null && tools.showCheats) {
            // Right after the FF / QS / QL row by default; its own group, so "Move on-screen controls" can place it anywhere.
            val w = (50 * tools.scale).dp
            val shown = listOf(tools.showFastForward, tools.showQuickSave, tools.showQuickLoad).count { it }
            Group(PadGroup.CHEATS, Alignment.TopStart, alpha = tools.opacity) {
                Box(Modifier.padding(start = (84 * scale).dp + (w + 8.dp) * shown)) {
                    ToolKey("Cheats", w * 1.4f, (34 * tools.scale).dp, accent, false, !editing, haptics, onPress = {}, onRelease = tools.onCheats)
                }
            }
        }
        if (tools != null && (tools.showFastForward || tools.showQuickSave || tools.showQuickLoad)) {
            // Next to the left shoulder buttons by default: clear of the D-pad, the face buttons, the menu button and
            // the bottom of the screen where RPG dialogue appears. Draggable in "Edit layout" like the other groups.
            Group(PadGroup.TOOLS, Alignment.TopStart, alpha = tools.opacity) {
                Row(Modifier.padding(start = (84 * scale).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val w = (50 * tools.scale).dp; val h = (34 * tools.scale).dp
                    if (tools.showFastForward) ToolKey(if (tools.fastForwardActive) "FF ${tools.speedLabel}" else "FF", w * (if (tools.fastForwardActive) 1.5f else 1f), h,
                        accent, tools.fastForwardActive, !editing, haptics, onPress = { tools.onFastForward(true) }, onRelease = { tools.onFastForward(false) })
                    if (tools.showQuickSave) ToolKey("QS", w, h, accent, false, !editing, haptics, onPress = {}, onRelease = tools.onQuickSave)
                    if (tools.showQuickLoad) ToolKey("QL", w, h, accent, false, !editing, haptics, onPress = {}, onRelease = tools.onQuickLoad)
                }
            }
        }
        // Menu button: always visible (also in edit mode) so the player can always reach the pause menu.
        if (!editing) {
            Box(
                Modifier.align(Alignment.TopCenter).size(40.dp).alpha(opacity.coerceAtLeast(0.5f)).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) onMenu() } },
                contentAlignment = Alignment.Center,
            ) { Text("☰", color = Color.White, fontSize = 18.sp) }
        } else {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    store.reset(orientation); offsets.clear(); offsets.putAll(store.load(orientation))
                }) { Text("Reset", color = Color.White) }
                Button(onClick = { store.save(orientation, offsets.toMap()); onEditDone() }) { Text("Save layout") }
            }
        }
    }
}

@Composable
private fun PadKey(
    label: String, button: PadButton, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, accent: Color,
    enabled: Boolean, onButton: (PadButton, Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.clip(shape)
            .background(if (pressed) accent.copy(alpha = 0.75f) else Color.Black.copy(alpha = 0.45f))
            .border(1.5.dp, if (pressed) accent else Color.White.copy(alpha = 0.45f), shape)
            .then(if (enabled) Modifier.pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true; onButton(button, true)
                    waitForUpOrCancellation()
                    pressed = false; onButton(button, false)
                }
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }
}

/** A small rounded tool button; [active] keeps it lit (fast-forward on). [onRelease] runs only if released on it. */
@Composable
private fun ToolKey(
    label: String, w: Dp, h: Dp, accent: Color, active: Boolean, enabled: Boolean, haptics: Boolean,
    onPress: () -> Unit, onRelease: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val lit = pressed || active
    Box(
        Modifier.size(w, h).clip(RoundedCornerShape(10.dp))
            .background(if (lit) accent.copy(alpha = if (pressed) 0.85f else 0.6f) else Color.Black.copy(alpha = 0.45f))
            .border(1.5.dp, if (lit) accent else Color.White.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.pointerInput(label.take(2)) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPress()
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) onRelease() else if (label.startsWith("FF")) onRelease()
                }
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1) }
}

@Composable
private fun FaceButtons(size: Dp, accent: Color, enabled: Boolean, onButton: (PadButton, Boolean) -> Unit) {
    val gap = size * 0.9f
    Box(Modifier.size(size * 2 + gap)) {
        PadKey("Y", PadButton.Y, Modifier.align(Alignment.TopCenter).size(size), CircleShape, accent, enabled, onButton)
        PadKey("X", PadButton.X, Modifier.align(Alignment.CenterStart).size(size), CircleShape, accent, enabled, onButton)
        PadKey("B", PadButton.B, Modifier.align(Alignment.CenterEnd).size(size), CircleShape, accent, enabled, onButton)
        PadKey("A", PadButton.A, Modifier.align(Alignment.BottomCenter).size(size), CircleShape, accent, enabled, onButton)
    }
}

@Composable
private fun DPad(size: Dp, accent: Color, enabled: Boolean, fourWay: Boolean = false, onButton: (PadButton, Boolean) -> Unit,
                 onBounds: ((android.graphics.Rect) -> Unit)? = null) {
    var active by remember { mutableStateOf(emptySet<PadButton>()) }
    fun setDirs(next: Set<PadButton>) {
        (active - next).forEach { onButton(it, false) }
        (next - active).forEach { onButton(it, true) }
        active = next
    }
    Box(
        Modifier.size(size)
            .then(if (onBounds != null) Modifier.onGloballyPositioned { c ->
                // Screen coordinates (a window can start right of a display cutout / navigation bar).
                val p = c.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
                onBounds(android.graphics.Rect(p.x.toInt(), p.y.toInt(), (p.x + c.size.width).toInt(), (p.y + c.size.height).toInt()))
            } else Modifier)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.55f))))
            .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .then(if (enabled) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val w = this.size.width.toFloat(); val h = this.size.height.toFloat()
                    fun dirs(p: Offset): Set<PadButton> {
                        val dx = p.x - w / 2; val dy = p.y - h / 2
                        if (hypot(dx, dy) < w * 0.12f) return emptySet()
                        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
                        if (fourWay) {
                            // One direction at a time. RPG Maker's movement reads a single direction and only walks
                            // when that same key has been held >20 ms (Input.time?); two keys at once made the player
                            // turn on the spot instead of walking. A 15° margin keeps the current direction while
                            // the thumb rests near a boundary, so it doesn't flicker between two keys.
                            val centers = mapOf(PadButton.RIGHT to 0.0, PadButton.DOWN to 90.0, PadButton.LEFT to 180.0, PadButton.UP to 270.0)
                            fun dist(a: Double, b: Double) = Math.abs(((a - b + 540) % 360) - 180)
                            val current = active.singleOrNull()
                            if (current != null && dist(angle, centers.getValue(current)) <= 45 + 15) return setOf(current)
                            return setOf(centers.minBy { dist(angle, it.value) }.key)
                        }
                        // 8 sectors of 45°: right=0°, down=90°, left=180°, up=270°.
                        return when (((angle + 22.5) / 45).toInt() % 8) {
                            0 -> setOf(PadButton.RIGHT)
                            1 -> setOf(PadButton.RIGHT, PadButton.DOWN)
                            2 -> setOf(PadButton.DOWN)
                            3 -> setOf(PadButton.DOWN, PadButton.LEFT)
                            4 -> setOf(PadButton.LEFT)
                            5 -> setOf(PadButton.LEFT, PadButton.UP)
                            6 -> setOf(PadButton.UP)
                            else -> setOf(PadButton.UP, PadButton.RIGHT)
                        }
                    }
                    setDirs(dirs(first.position))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == first.id } ?: break
                        if (!c.pressed) break
                        setDirs(dirs(c.position))
                        c.consume()
                    }
                    setDirs(emptySet())
                }
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val arm = size / 3
        listOf(
            PadButton.UP to Alignment.TopCenter, PadButton.DOWN to Alignment.BottomCenter,
            PadButton.LEFT to Alignment.CenterStart, PadButton.RIGHT to Alignment.CenterEnd,
        ).forEach { (b, a) ->
            Box(
                Modifier.align(a).padding(6.dp).size(arm).clip(RoundedCornerShape(8.dp))
                    .background(if (b in active) accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.16f)),
            )
        }
    }
}
