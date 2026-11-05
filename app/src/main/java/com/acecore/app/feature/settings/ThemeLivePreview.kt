package com.acecore.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.designsystem.aceGlow

/**
 * A miniature library drawn with the theme in use right now (the app re-themes as soon as a value changes), so colour,
 * glow, border, corner and surface changes can be judged in one place while editing.
 */
@Composable
internal fun ThemeLivePreview() {
    val cs = MaterialTheme.colorScheme
    val t = AceTheme.tokens
    val corner = RoundedCornerShape(t.corner)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).clip(corner).background(cs.background)
            .border(1.dp, cs.outlineVariant, corner).padding(14.dp)
            .semantics { contentDescription = "Preview of the current theme" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Preview", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab("Favourites", selected = true)
            Tab("RPGs", selected = false)
            Tab("Handheld", selected = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { i ->
                val shape = RoundedCornerShape((t.corner.value * 0.75f).dp)
                Box(
                    Modifier.weight(1f).aspectRatio(0.75f)
                        .aceGlow(t.glow, if (i == 0) t.glowIntensity * 0.25f else 0f, (t.corner.value * 0.75f).dp)
                        .clip(shape)
                        .background(Brush.verticalGradient(listOf(cs.surfaceContainerHighest, cs.surfaceContainerLow)))
                        .border(t.borderWidth, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), t.glow.copy(alpha = 0.28f))), shape),
                ) {
                    Text(
                        listOf("Adventure", "Quest", "Racer")[i], color = cs.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().clip(corner).background(cs.surface.copy(alpha = t.surfaceAlpha)).padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Card title", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                Text("Secondary text on a card.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Drawn (not real buttons): the preview only shows the colours.
                    Box(Modifier.clip(RoundedCornerShape(50)).background(cs.primary).padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text("Play", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, cs.outline, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text("Details", color = cs.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onSurface else cs.onSurfaceVariant)
        Box(
            Modifier.padding(top = 4.dp).width(if (selected) 24.dp else 0.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected) AceTheme.tokens.glow else Color.Transparent),
        )
    }
}
