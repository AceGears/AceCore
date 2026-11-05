package com.acecore.app.core.designsystem

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.acecore.app.core.model.CompatStatus

/**
 * Soft neon glow behind a rounded element. Uses a blurred native paint so cost is a single draw call;
 * intensity 0 draws nothing (also used by reduce-glow users).
 */
fun Modifier.aceGlow(color: Color, intensity: Float, corner: Dp, radius: Dp = 14.dp): Modifier =
    if (intensity <= 0.01f) this else drawBehind {
        val r = radius.toPx()
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = color.copy(alpha = (0.85f * intensity).coerceIn(0f, 1f)).toArgb()
                maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner.toPx(), corner.toPx(), paint)
        }
    }

/** Neon wash for headers and hero areas: the theme's glow (neon red in AceArcade Steel) melting into its second tone, then the background. */
@Composable
fun aceHeroBrush(): Brush {
    val t = AceTheme.tokens
    val bg = t.base
    return Brush.verticalGradient(
        0f to t.glow.copy(alpha = 0.34f * t.gradientIntensity).compositeOverSafe(bg),
        0.38f to t.glowAlt.copy(alpha = 0.16f * t.gradientIntensity).compositeOverSafe(bg),
        0.75f to bg,
        1f to bg,
    )
}

/** Signature dual-tone edge (neon red to steel in AceArcade Steel) for highlighted cards, covers and dialogs. */
@Composable
fun aceNeonEdge(alpha: Float = 0.85f): Brush {
    val t = AceTheme.tokens
    return Brush.linearGradient(listOf(t.glow.copy(alpha = alpha), t.glowAlt.copy(alpha = alpha * 0.8f)))
}

/**
 * Machined-metal edge for cards and panels (the 2026 logo's brushed-steel plates): a bright chrome highlight on the
 * top-left edge fading through the outline colour to a faint lower-right edge, so surfaces read as bevelled plates.
 */
@Composable
fun aceBevelEdge(): Brush {
    val t = AceTheme.tokens
    val outline = MaterialTheme.colorScheme.outlineVariant
    return Brush.linearGradient(
        0f to t.chrome.copy(alpha = if (t.isDark) 0.38f else 0.55f),
        0.45f to outline,
        1f to t.chrome.copy(alpha = if (t.isDark) 0.10f else 0.18f),
    )
}

private fun Color.compositeOverSafe(bg: Color) = androidx.compose.ui.graphics.lerp(bg, this.copy(alpha = 1f), this.alpha)

/** Press-scale feedback that respects reduce-motion. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val reduce = AceTheme.tokens.reduceMotion
    val s by animateFloatAsState(if (isPressed && !reduce) pressed else 1f, spring(stiffness = 600f), label = "press")
    return this.scale(s)
}

@Composable
fun AceCard(modifier: Modifier = Modifier, glow: Boolean = false, content: @Composable () -> Unit) {
    val t = AceTheme.tokens
    val shape = RoundedCornerShape(t.corner)
    val glass = t.glassAlpha < 1f
    Surface(
        modifier = modifier
            .then(if (glow) Modifier.aceGlow(t.glow, t.glowIntensity * 0.6f, t.corner) else Modifier)
            .then(
                when {
                    glass -> Modifier.border(maxOf(t.borderWidth, 1.dp), aceGlassEdge(), shape)
                    t.borderWidth > 0.dp -> Modifier.border(t.borderWidth, aceBevelEdge(), shape)
                    else -> Modifier
                },
            ),
        shape = shape,
        color = if (glass) aceGlassFill() else MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        content = content,
    )
}

/** Glass card fill: the card colour, partly see-through over the theme's backdrop (solid without one). */
@Composable
fun aceGlassFill(): Color {
    val t = AceTheme.tokens
    return MaterialTheme.colorScheme.surfaceContainer.copy(alpha = t.glassAlpha)
}

/**
 * Glass edge: a bright sheen on the top edge fading down the sides (light catching the rim of frosted glass), tinted
 * with the theme's glow at the bottom.
 */
@Composable
fun aceGlassEdge(): Brush {
    val t = AceTheme.tokens
    return Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (t.isDark) 0.22f else 0.9f),
        0.5f to Color.White.copy(alpha = if (t.isDark) 0.06f else 0.35f),
        1f to t.glow.copy(alpha = if (t.isDark) 0.22f else 0.25f),
    )
}

/** Glass surface for any shape: translucent fill and glass edge (solid card colour when the theme has no backdrop). */
@Composable
fun Modifier.aceGlass(shape: Shape): Modifier {
    val t = AceTheme.tokens
    return if (t.glassAlpha >= 1f) this.background(MaterialTheme.colorScheme.surfaceContainer, shape)
    else this.background(aceGlassFill(), shape).border(1.dp, aceGlassEdge(), shape)
}

/**
 * The theme's backdrop, drawn behind every screen: a vertical base gradient and soft radial light orbs. Static (no
 * animation), so it costs one draw per frame of whatever is on top.
 */
@Composable
fun Modifier.aceBackdrop(): Modifier {
    val t = AceTheme.tokens
    val b = t.backdrop ?: return this.background(t.base)
    val intensity = 0.55f + 0.45f * t.gradientIntensity
    return this.drawBehind {
        drawRect(Brush.verticalGradient(listOf(Color(b.top.toInt()), Color(b.bottom.toInt()))))
        val span = maxOf(size.width, size.height)
        for (o in b.orbs) {
            val c = androidx.compose.ui.geometry.Offset(o.x * size.width, o.y * size.height)
            val r = o.radius * span
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(o.color.toInt()).copy(alpha = (if (t.isDark) 0.55f else 0.7f) * intensity), Color.Transparent),
                    center = c, radius = r,
                ),
                radius = r, center = c,
            )
        }
    }
}

/**
 * Press lift (the glass dashboard's hover): the element rises a few dp while pressed, then settles with a spring.
 * Respects reduce-motion.
 */
@Composable
fun Modifier.pressLift(interaction: MutableInteractionSource, lift: Dp = 4.dp): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val reduce = AceTheme.tokens.reduceMotion
    val y by animateFloatAsState(if (isPressed && !reduce) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "lift")
    return this.graphicsLayer { translationY = -lift.toPx() * y; scaleX = 1f + 0.015f * y; scaleY = 1f + 0.015f * y }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 4.dp, height = 18.dp)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun StatusChip(status: CompatStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        CompatStatus.PLAYABLE, CompatStatus.PLAYABLE_UNTESTED -> Color(0xFF1C7C54) to Color.White
        CompatStatus.RUNTIME_NOT_BUILT -> Color(0xFF8A6D1A) to Color.White
        CompatStatus.PACKED -> Color(0xFF345A9E) to Color.White
        CompatStatus.MISSING_FILES, CompatStatus.UNSUPPORTED -> Color(0xFF7A1D26) to Color.White
    }
    Box(modifier.background(bg.copy(alpha = 0.92f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(status.label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

@Composable
fun InfoRow(icon: ImageVector, title: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val t = AceTheme.tokens
        Box(
            Modifier.size(72.dp).aceGlow(t.glow, t.glowIntensity, 36.dp, 18.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(36.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

/** Shared shape helper. */
@Composable
fun aceShape(scale: Float = 1f): Shape = RoundedCornerShape(AceTheme.tokens.corner * scale)
