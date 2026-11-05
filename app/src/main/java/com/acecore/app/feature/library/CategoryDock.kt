package com.acecore.app.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.designsystem.aceGlassFill
import com.acecore.app.core.designsystem.aceGlow
import kotlin.math.roundToInt

/** One category in the dock. */
internal data class DockTab(
    val key: Any, val icon: ImageVector, val label: String, val color: Color, val count: Int?,
    val onClick: () -> Unit, val onLongClick: (() -> Unit)? = null,
)

private val BAR_HEIGHT = 64.dp
private val BUBBLE = 48.dp
private val RISE = 22.dp // how far the bubble rises above the bar's top edge
private val SLOT_MIN = 64.dp
private val NOTCH_HALF = BUBBLE / 2 + 18.dp // half-width of the notch along the bar's top edge

/**
 * Floating category dock (from the reference "glassy navigation tabs"): a glass bar whose top edge dips into a
 * smooth notch under a raised bubble holding the selected category's icon. Choosing another category slides the
 * notch and bubble across with a spring (a liquid overshoot with Liquid motion), the bar's outline flowing with it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CategoryDock(tabs: List<DockTab>, selectedKey: Any, modifier: Modifier = Modifier) {
    if (tabs.isEmpty()) return
    val t = AceTheme.tokens
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val index = tabs.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val glassFill = aceGlassFill().let { if (t.glassAlpha >= 1f) it else it.copy(alpha = (it.alpha + 0.12f).coerceAtMost(1f)) }
    val edgeTop = Color.White.copy(alpha = if (t.isDark) 0.24f else 0.9f)
    val selColor = tabs[index].color
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp)) {
        // The notch must stay on the bar's straight top edge, clear of the rounded ends: the first and last slots are
        // inset so a bubble there still has a full notch (corner radius + notch half-width from each end).
        val reach = BAR_HEIGHT / 2 + NOTCH_HALF
        var slot = maxOf(SLOT_MIN, maxWidth / tabs.size)
        var pad = maxOf(0.dp, reach - slot / 2)
        repeat(2) { slot = maxOf(SLOT_MIN, (maxWidth - pad * 2) / tabs.size); pad = maxOf(0.dp, reach - slot / 2) }
        val contentWidth = slot * tabs.size + pad * 2
        val scroll = rememberScrollState()
        val slotPx = with(density) { slot.toPx() }
        val padPx = with(density) { pad.toPx() }
        val cx = remember { Animatable(padPx + slotPx * (index + 0.5f)) }
        LaunchedEffect(index, slotPx, padPx) {
            val target = padPx + slotPx * (index + 0.5f)
            if (t.reduceMotion) cx.snapTo(target)
            else cx.animateTo(target, if (t.liquidMotion) spring(dampingRatio = 0.62f, stiffness = 320f) else spring(dampingRatio = 1f, stiffness = 900f))
            // Keep the selected slot in view when the dock scrolls.
            val viewport = with(density) { maxWidth.toPx() }
            if (target - scroll.value > viewport - slotPx / 2 || target - scroll.value < slotPx / 2) scroll.animateScrollTo((target - viewport / 2).roundToInt().coerceAtLeast(0))
        }
        Box(Modifier.horizontalScroll(scroll)) {
            Box(
                Modifier.width(contentWidth).height(BAR_HEIGHT + RISE)
                    .drawBehind {
                        val top = RISE.toPx()
                        val r = BUBBLE.toPx() / 2f
                        val depth = 2 * r + 6.dp.toPx() - top // dip so the bubble sits in it with a 6 dp gap
                        val half = NOTCH_HALF.toPx()
                        val corner = (BAR_HEIGHT / 2).toPx()
                        val w = size.width; val h = size.height
                        // Mid-animation overshoot can't push the notch into a rounded end either.
                        val x = cx.value.coerceIn(corner + half, w - corner - half)
                        val path = Path().apply {
                            moveTo(corner, top)
                            lineTo(x - half, top)
                            // Smooth S-curves down into the dip and back up (tangent to the top edge at both ends).
                            cubicTo(x - half * 0.5f, top, x - r * 1.05f, top + depth, x, top + depth)
                            cubicTo(x + r * 1.05f, top + depth, x + half * 0.5f, top, x + half, top)
                            lineTo(w - corner, top)
                            arcTo(androidx.compose.ui.geometry.Rect(w - 2 * corner, top, w, h), -90f, 180f, false)
                            lineTo(corner, h)
                            arcTo(androidx.compose.ui.geometry.Rect(0f, top, 2 * corner, h), 90f, 180f, false)
                            close()
                        }
                        drawPath(path, glassFill)
                        drawPath(path, Brush.verticalGradient(listOf(edgeTop, edgeTop.copy(alpha = edgeTop.alpha * 0.35f)), startY = top, endY = h), style = Stroke(1.dp.toPx()))
                        // Underglow in the notch, in the selected category's colour (light pooling under the bubble).
                        drawCircle(Brush.radialGradient(listOf(selColor.copy(alpha = 0.22f * (0.4f + t.glowIntensity)), Color.Transparent), Offset(x, top + depth), r * 1.5f),
                            r * 1.5f, Offset(x, top + depth))
                    },
            ) {
                // The bubble with the selected category's icon.
                val sel = tabs[index]
                Box(
                    Modifier.offset {
                        val edge = (BAR_HEIGHT / 2).toPx() + NOTCH_HALF.toPx()
                        val x = cx.value.coerceIn(edge, contentWidth.toPx() - edge)
                        IntOffset((x - BUBBLE.toPx() / 2f).roundToInt(), 0)
                    }.size(BUBBLE)
                        .aceGlow(sel.color, t.glowIntensity, BUBBLE / 2, 12.dp)
                        .background(Brush.verticalGradient(listOf(sel.color.copy(alpha = 0.95f), sel.color.copy(alpha = 0.7f))), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(sel.icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                Row(Modifier.offset(x = pad, y = RISE).height(BAR_HEIGHT), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { i, tab ->
                        val selected = i == index
                        Column(
                            Modifier.width(slot).height(BAR_HEIGHT)
                                .semantics { this.selected = selected }
                                .combinedClickable(
                                    role = Role.Tab, onClickLabel = tab.label, onClick = tab.onClick,
                                    onLongClick = tab.onLongClick?.let { lc -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            // Every label sits on the same line at the bottom; the selected icon lives in the bubble.
                            if (!selected) Icon(tab.icon, tab.label, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 3.dp).size(22.dp))
                            Text(
                                tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) tab.color else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
