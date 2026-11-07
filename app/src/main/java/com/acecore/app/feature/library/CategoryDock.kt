package com.acecore.app.feature.library

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import com.acecore.app.core.designsystem.Contrast
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acecore.app.core.datastore.CategoryBarStyle
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.designsystem.aceGlassFill
import com.acecore.app.core.designsystem.aceGlow
import kotlin.math.roundToInt

/** One category in the dock. */
internal data class DockTab(
    val key: Any, val icon: ImageVector, val label: String, val color: Color, val count: Int?,
    val onClick: () -> Unit, val onLongClick: (() -> Unit)? = null,
)

/** The bottom-dock category bar styles (the reference "glassy navigation tabs"). */
internal val CategoryBarStyle.isDock get() = this == CategoryBarStyle.DOCK || this == CategoryBarStyle.WAVE ||
    this == CategoryBarStyle.SPOTLIGHT || this == CategoryBarStyle.DROP

private val BAR_HEIGHT = DockLayout.BAR_HEIGHT.dp
private val BUBBLE = DockLayout.BUBBLE.dp
private val RISE = 22.dp // room above the bar for the bubble / wave crest / drop
private val NOTCH_HALF = DockLayout.NOTCH_HALF.dp // half-width of the notch (and the wave crest) along the bar's top edge

/**
 * Floating category dock, in four styles taken from the reference:
 *  - DOCK (notch): the bar's top edge dips under a raised bubble holding the selected icon; both slide to a new tab.
 *  - WAVE: a glowing rim that swells into a crest over the selected tab; the icon rides up into it.
 *  - SPOTLIGHT: a lamp on the bar's top edge throws a cone of light down onto the selected tab.
 *  - DROP: a liquid drop stretches up out of the bar over the tab you pick, then falls back in; a dot marks it.
 * Movement uses a spring (overshooting like liquid with Liquid motion); reduce-motion jumps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CategoryDock(tabs: List<DockTab>, selectedKey: Any, style: CategoryBarStyle, modifier: Modifier = Modifier) {
    if (tabs.isEmpty()) return
    val t = AceTheme.tokens
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val index = tabs.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val glassFill = aceGlassFill().let { if (t.glassAlpha >= 1f) it else it.copy(alpha = (it.alpha + 0.12f).coerceAtMost(1f)) }
    val rim = Color.White.copy(alpha = if (t.isDark) 0.24f else 0.9f)
    val sel = tabs[index]
    val selColor = sel.color
    // Category colours are the user's choice; on the glass they are darkened/lightened until labels and icons read
    // (4.5:1). The shapes keep the original colour. The bubble icon is black or white, whichever reads on the colour.
    val glassBg = glassFill.compositeOver(t.base).toArgb().toLong() and 0xFFFFFFFFL
    fun readable(c: Color) = Color(Contrast.ensure(c.toArgb().toLong() and 0xFFFFFFFFL, glassBg, 4.5).toInt())
    val bubbleIcon = Color(Contrast.onFill(selColor.copy(alpha = 1f).toArgb().toLong() and 0xFFFFFFFFL).toInt())
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
        // Shapes on the top edge (notch, crest) must stay on its straight part, clear of the rounded ends: the first
        // and last slots are inset by (corner radius + half-width) minus half a slot when that's positive.
        // See DockLayout (unit-tested): equal slots, end slots inset so the shapes clear the rounded ends, capped and
        // centred on wide screens, scrolling only when the tabs can't fit.
        val lay = DockLayout.of(maxWidth.value, tabs.size)
        val slot = lay.slot.dp
        val pad = lay.pad.dp
        val contentWidth = lay.contentWidth.dp
        val scroll = rememberScrollState()
        val slotPx = with(density) { slot.toPx() }
        val padPx = with(density) { pad.toPx() }
        val cx = remember { Animatable(padPx + slotPx * (index + 0.5f)) }
        // DROP: 0 → 1 → 0 as the drop rises out of the bar and falls back.
        val drop = remember { Animatable(0f) }
        var dropAt by remember { mutableIntStateOf(index) }
        LaunchedEffect(index, slotPx, padPx, style) {
            val target = padPx + slotPx * (index + 0.5f)
            coroutineScope {
                // Keep the selected slot in view when the dock scrolls (alongside the slide, not after it).
                val viewport = with(density) { lay.width.dp.toPx() }
                if (target - scroll.value > viewport - slotPx / 2 || target - scroll.value < slotPx / 2) {
                    val to = (target - viewport / 2).roundToInt().coerceAtLeast(0)
                    launch { if (t.reduceMotion) scroll.scrollTo(to) else scroll.animateScrollTo(to) }
                }
                if (t.reduceMotion) { cx.snapTo(target); drop.snapTo(0f); dropAt = index }
                else if (style == CategoryBarStyle.DROP) {
                    cx.snapTo(target)
                    if (dropAt != index) {
                        dropAt = index
                        // From wherever an interrupted drop was (no pop back to the bar on quick taps).
                        drop.animateTo(1f, tween((260 * t.animationScale * (1f - drop.value)).toInt().coerceAtLeast(1), easing = FastOutSlowInEasing))
                        drop.animateTo(0f, spring(dampingRatio = if (t.liquidMotion) 0.45f else 1f, stiffness = 260f))
                    }
                } else cx.animateTo(target, if (t.liquidMotion) spring(dampingRatio = 0.62f, stiffness = 320f) else spring(dampingRatio = 1f, stiffness = 900f))
            }
        }
        val edge = with(density) { lay.reach.dp.toPx() }
        val contentPx = with(density) { contentWidth.toPx() }
        fun clampX(x: Float) = x.coerceIn(edge, (contentPx - edge).coerceAtLeast(edge))
        Box(Modifier.width(lay.width.dp).horizontalScroll(scroll)) {
            Box(
                Modifier.width(contentWidth).height(BAR_HEIGHT + RISE)
                    .drawBehind {
                        // Tabs (Row, offset) mirror in right-to-left layouts; the drawing has to as well.
                        val x = clampX(cx.value).let { if (layoutDirection == LayoutDirection.Rtl) size.width - it else it }
                        when (style) {
                            CategoryBarStyle.WAVE -> drawWave(x, glassFill, rim, selColor, t.glowIntensity)
                            CategoryBarStyle.SPOTLIGHT -> drawSpotlight(x, glassFill, rim, selColor, slotPx, t.glowIntensity)
                            CategoryBarStyle.DROP -> drawDrop(x, glassFill, rim, selColor, drop.value)
                            else -> drawNotch(x, glassFill, rim, selColor, t.glowIntensity)
                        }
                    },
            ) {
                if (style == CategoryBarStyle.DOCK) {
                    // The bubble with the selected category's icon.
                    Box(
                        Modifier.offset { IntOffset((clampX(cx.value) - BUBBLE.toPx() / 2f).roundToInt(), 0) }.size(BUBBLE)
                            .aceGlow(selColor, t.glowIntensity, BUBBLE / 2, 12.dp)
                            .background(Brush.verticalGradient(listOf(selColor.copy(alpha = 0.95f), selColor.copy(alpha = 0.7f))), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { CountBadge(sel.count, readable(selColor)) { Icon(sel.icon, null, tint = bubbleIcon, modifier = Modifier.size(22.dp)) } }
                }
                Row(Modifier.offset(x = pad, y = RISE).height(BAR_HEIGHT).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { i, tab ->
                        val selected = i == index
                        Column(
                            Modifier.width(slot).height(BAR_HEIGHT)
                                .semantics { this.selected = selected }
                                .combinedClickable(
                                    role = Role.Tab, onClick = tab.onClick,
                                    onLongClickLabel = tab.onLongClick?.let { "Category options" },
                                    onLongClick = tab.onLongClick?.let { lc -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            // Every label sits on the same line at the bottom; in the notch style the selected icon is
                            // in the bubble, in the wave style it rides up into the crest.
                            val iconLift by animateDpAsState(
                                if (selected && style == CategoryBarStyle.WAVE) (-10).dp else 0.dp,
                                if (t.reduceMotion) snap() else spring(dampingRatio = 0.6f, stiffness = 400f), label = "waveLift",
                            )
                            // The label below names the tab, so the icon is decorative for TalkBack (read once).
                            if (!(selected && style == CategoryBarStyle.DOCK)) CountBadge(tab.count, if (selected) readable(selColor) else MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(bottom = 3.dp).offset(y = iconLift)
                                .graphicsLayer {
                                    // The drop carries the icon up with it.
                                    if (selected && style == CategoryBarStyle.DROP) translationY = -drop.value * 26.dp.toPx()
                                }) {
                                Icon(tab.icon, null, tint = if (selected) readable(selColor) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            }
                            Text(
                                tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) readable(selColor) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = if (style == CategoryBarStyle.DROP) 4.dp else 9.dp),
                            )
                            if (style == CategoryBarStyle.DROP) Box(
                                Modifier.padding(bottom = 3.dp).size(4.dp)
                                    .background(if (selected) selColor else Color.Transparent, CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * [content] with the category's game count as a small badge (when "Show category counts" is on). Neutral, like the
 * count pills of the other bar styles: Material's default red badge reads as "unread notifications".
 */
@Composable
private fun CountBadge(count: Int?, color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (count == null) Box(modifier) { content() }
    else BadgedBox(
        badge = {
            Badge(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = color) {
                Text(if (count > 999) "999+" else count.toString(), fontWeight = FontWeight.SemiBold)
            }
        },
        modifier = modifier,
    ) { content() }
}

/** The bar outline with rounded ends; [topEdge] draws the top edge from the left corner to the right one. */
private fun DrawScope.barPath(topEdge: Path.(top: Float, corner: Float, w: Float) -> Unit): Path {
    val top = RISE.toPx(); val corner = (BAR_HEIGHT / 2).toPx(); val w = size.width; val h = size.height
    return Path().apply {
        moveTo(corner, top)
        topEdge(top, corner, w)
        lineTo(w - corner, top)
        arcTo(Rect(w - 2 * corner, top, w, h), -90f, 180f, false)
        lineTo(corner, h)
        arcTo(Rect(0f, top, 2 * corner, h), 90f, 180f, false)
        close()
    }
}

private fun DrawScope.fillAndRim(path: Path, fill: Color, rim: Color) {
    drawPath(path, fill)
    drawPath(path, Brush.verticalGradient(listOf(rim, rim.copy(alpha = rim.alpha * 0.35f)), startY = RISE.toPx(), endY = size.height), style = Stroke(1.dp.toPx()))
}

/** Notch: the top edge dips smoothly under the bubble (tangent to the edge on both sides). */
private fun DrawScope.drawNotch(x: Float, fill: Color, rim: Color, color: Color, glow: Float) {
    val r = BUBBLE.toPx() / 2f
    val half = NOTCH_HALF.toPx()
    val top = RISE.toPx()
    val depth = 2 * r + 6.dp.toPx() - top // the bubble sits in the dip with a 6 dp gap
    val path = barPath { tp, _, _ ->
        lineTo(x - half, tp)
        cubicTo(x - half * 0.5f, tp, x - r * 1.05f, tp + depth, x, tp + depth)
        cubicTo(x + r * 1.05f, tp + depth, x + half * 0.5f, tp, x + half, tp)
    }
    fillAndRim(path, fill, rim)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.22f * (0.4f + glow)), Color.Transparent), Offset(x, top + depth), r * 1.5f), r * 1.5f, Offset(x, top + depth))
}

/** Wave: the top edge swells into a crest over the selected tab, traced by a glowing line in its colour. */
private fun DrawScope.drawWave(x: Float, fill: Color, rim: Color, color: Color, glow: Float) {
    val half = NOTCH_HALF.toPx()
    val crest = (RISE - 4.dp).toPx()
    val top = RISE.toPx()
    val path = barPath { tp, _, _ ->
        lineTo(x - half, tp)
        cubicTo(x - half * 0.45f, tp, x - half * 0.45f, tp - crest, x, tp - crest)
        cubicTo(x + half * 0.45f, tp - crest, x + half * 0.45f, tp, x + half, tp)
    }
    fillAndRim(path, fill, rim)
    // Neon trace along the crest: wide soft strokes under a bright thin one, fading out along the edge.
    // The fading tails stay on the straight part of the top edge, never past the rounded ends.
    val corner = (BAR_HEIGHT / 2).toPx()
    val l = maxOf(corner, x - half * 1.6f)
    val r = minOf(size.width - corner, x + half * 1.6f)
    val wave = Path().apply {
        moveTo(l, top)
        lineTo(x - half, top)
        cubicTo(x - half * 0.45f, top, x - half * 0.45f, top - crest, x, top - crest)
        cubicTo(x + half * 0.45f, top - crest, x + half * 0.45f, top, x + half, top)
        lineTo(r, top)
    }
    val fade = Brush.horizontalGradient(
        0f to Color.Transparent, ((x - half - l) / (r - l)).coerceIn(0f, 1f) to color,
        ((x + half - l) / (r - l)).coerceIn(0f, 1f) to color, 1f to Color.Transparent,
        startX = l, endX = r,
    )
    drawPath(wave, fade, alpha = 0.18f * (0.4f + glow), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
    drawPath(wave, fade, alpha = 0.35f * (0.4f + glow), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    drawPath(wave, fade, style = Stroke(1.6f.dp.toPx(), cap = StrokeCap.Round))
}

/** Spotlight: a lamp on the bar's top edge and a soft cone of light falling onto the selected tab. */
private fun DrawScope.drawSpotlight(x: Float, fill: Color, rim: Color, color: Color, slot: Float, glow: Float) {
    val top = RISE.toPx()
    val path = barPath { _, _, _ -> }
    fillAndRim(path, fill, rim)
    val lamp = minOf(28.dp.toPx(), slot * 0.4f)
    val beamBottom = size.height - 6.dp.toPx()
    val spread = minOf(slot * 0.42f, 40.dp.toPx())
    val cone = Path().apply {
        moveTo(x - lamp / 2, top); lineTo(x + lamp / 2, top); lineTo(x + spread, beamBottom); lineTo(x - spread, beamBottom); close()
    }
    drawPath(cone, Brush.verticalGradient(listOf(color.copy(alpha = 0.42f * (0.5f + glow)), color.copy(alpha = 0f)), startY = top, endY = beamBottom))
    drawRoundRect(color.copy(alpha = 0.35f), Offset(x - lamp / 2 - 3.dp.toPx(), top - 3.dp.toPx()), Size(lamp + 6.dp.toPx(), 6.dp.toPx()),
        androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
    drawRoundRect(color, Offset(x - lamp / 2, top - 1.5f.dp.toPx()), Size(lamp, 3.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.5f.dp.toPx()))
}

/**
 * Drop: [p] 0–1 raises a drop out of the bar above [x]; its neck thins as it rises (a drawn metaball: two curves
 * from a wide base on the bar's top edge to the sides of the drop).
 */
private fun DrawScope.drawDrop(x: Float, fill: Color, rim: Color, color: Color, p: Float) {
    val top = RISE.toPx()
    val path = barPath { _, _, _ -> }
    fillAndRim(path, fill, rim)
    if (p <= 0.01f) return
    val r = 15.dp.toPx()
    val cy = top - p * 26.dp.toPx() + r * (1f - p) * 0.4f
    val base = 22.dp.toPx() * (1f - p * 0.45f)
    val neck = r * (0.95f - p * 0.55f)
    val blob = Path().apply {
        moveTo(x - base, top + 1)
        cubicTo(x - base * 0.45f, top, x - neck, cy + r * 0.6f, x - r, cy)
        arcTo(Rect(x - r, cy - r, x + r, cy + r), 180f, 180f, false)
        cubicTo(x + neck, cy + r * 0.6f, x + base * 0.45f, top, x + base, top + 1)
        close()
    }
    drawPath(blob, fill)
    drawPath(blob, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.7f), color.copy(alpha = 0.6f)), startY = cy - r, endY = top), style = Stroke(1.dp.toPx()))
    drawCircle(color.copy(alpha = 0.18f * p), r * 1.6f, Offset(x, cy))
}
