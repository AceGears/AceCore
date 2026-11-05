package com.acecore.app.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Items being removed with the delete effect: the screen shows them shaking and dissolving, then the removal runs.
 * Keys are "game:<id>" or "category:<id>".
 */
object Vanishing {
    val keys = mutableStateListOf<String>()
    const val DURATION_MS = 680L

    /** Plays the effect on [keys] (when [effect] is on), then runs [action]. Call from a non-cancellable scope. */
    suspend fun run(keys: Collection<String>, effect: Boolean, action: suspend () -> Unit) {
        if (effect && keys.isNotEmpty()) {
            this.keys.addAll(keys)
            delay(DURATION_MS)
        }
        try { action() } finally { this.keys.removeAll(keys.toSet()) }
    }

    fun game(id: Long) = "game:$id"
    fun category(id: Long) = "category:$id"
}

private class Particle(val x: Float, val y: Float, val dx: Float, val dy: Float, val size: Float, val delay: Float, val tint: Int)

/**
 * The delete animation (from the reference "delete button": shake, then disintegrate into particles and smoke): a
 * short shake, then the element shrinks and fades while particles drift up and away from it. Draws nothing extra
 * when [active] is false.
 */
@Composable
fun Modifier.vanishEffect(active: Boolean): Modifier {
    if (!active) return this
    val t = AceTheme.tokens
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(Vanishing.DURATION_MS.toInt(), easing = LinearEasing)) }
    val particles = remember {
        val r = java.util.Random(42)
        List(64) { Particle(r.nextFloat(), r.nextFloat(), r.nextFloat() * 2f - 0.6f, -(0.4f + r.nextFloat()), 2f + r.nextFloat() * 4f, r.nextFloat() * 0.25f, r.nextInt(3)) }
    }
    val glow = t.glow
    val alt = t.glowAlt
    val smoke = if (t.isDark) Color(0xFFB8B8C4) else Color(0xFF6B6B78)
    return this
        .drawWithContent {
            drawContent()
            val p = progress.value
            val q = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
            if (q <= 0f) return@drawWithContent
            val shift = 28.dp.toPx()
            for (pt in particles) {
                val k = ((q - pt.delay) / (1f - pt.delay)).coerceIn(0f, 1f)
                if (k <= 0f || k >= 1f) continue
                val c = when (pt.tint) { 0 -> glow; 1 -> alt; else -> smoke }
                val s = pt.size.dp.toPx() * (1f - k * 0.7f)
                drawRoundRect(
                    c.copy(alpha = (1f - k) * 0.9f),
                    topLeft = Offset(pt.x * size.width + pt.dx * shift * k * 2f, pt.y * size.height + pt.dy * shift * k * 3f),
                    size = Size(s, s), cornerRadius = CornerRadius(s / 3f),
                )
            }
        }
        .graphicsLayer {
            val p = progress.value
            val shake = if (p < 0.3f) sin(p / 0.3f * 6f * PI).toFloat() * 6.dp.toPx() * (1f - p / 0.3f * 0.5f) else 0f
            val q = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
            translationX = shake
            alpha = 1f - q
            scaleX = 1f - 0.12f * q
            scaleY = 1f - 0.12f * q
        }
}

/**
 * Liquid movement between two positions (the glass sidebar and tab bar references): the edge in the direction of
 * travel springs quickly, the trailing edge follows more slowly, so the indicator stretches toward its target and
 * then contracts. With [liquid] off both edges move together; with [reduceMotion] it jumps.
 */
class LiquidEdges {
    val left = Animatable(0f)
    val right = Animatable(0f)
    private var placed = false

    suspend fun moveTo(l: Float, r: Float, liquid: Boolean, reduceMotion: Boolean, speed: Float) {
        if (!placed || reduceMotion) { left.snapTo(l); right.snapTo(r); placed = true; return }
        val forward = l > left.value
        val fast = spring<Float>(dampingRatio = 0.7f, stiffness = 900f * speed)
        val slow = spring<Float>(dampingRatio = 0.62f, stiffness = (if (liquid) 220f else 900f) * speed)
        coroutineScope {
            launch { left.animateTo(l, if (forward) slow else fast) }
            launch { right.animateTo(r, if (forward) fast else slow) }
        }
    }

    suspend fun snap(l: Float, r: Float) { left.snapTo(l); right.snapTo(r); placed = true }
}
