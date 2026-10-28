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

/** Subtle crimson-to-transparent wash used on headers and hero areas. */
@Composable
fun aceHeroBrush(): Brush {
    val t = AceTheme.tokens
    val bg = MaterialTheme.colorScheme.background
    return Brush.verticalGradient(
        0f to t.glow.copy(alpha = 0.32f * t.gradientIntensity).compositeOverSafe(bg),
        0.55f to bg,
        1f to bg,
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
    Surface(
        modifier = modifier
            .then(if (glow) Modifier.aceGlow(t.glow, t.glowIntensity * 0.6f, t.corner) else Modifier)
            .then(if (t.borderWidth > 0.dp) Modifier.border(t.borderWidth, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        content = content,
    )
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
