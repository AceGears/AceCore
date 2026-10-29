package com.acecore.app.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.acecore.app.container
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.datastore.LibrarySettings
import com.acecore.app.core.designsystem.AceTheme
import com.acecore.app.core.designsystem.aceGlow
import com.acecore.app.core.designsystem.pressScale
import com.acecore.app.core.model.Engine

/** Cover art with graceful fallback (engine monogram) when no image is available. */
@Composable
fun CoverImage(game: GameEntity, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val model = remember(game.customCoverPath, game.coverDocId) { ctx.container.games.coverModel(game) }
    if (model == null) {
        CoverFallback(game, modifier)
    } else {
        SubcomposeAsyncImage(
            model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier,
            error = { CoverFallback(game, Modifier.fillMaxSize()) },
            loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) },
        )
    }
}

@Composable
private fun CoverFallback(game: GameEntity, modifier: Modifier) {
    val t = AceTheme.tokens
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.surfaceContainerHighest, t.glow.copy(alpha = 0.55f), MaterialTheme.colorScheme.surfaceContainerLow),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                game.title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
                style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Black,
            )
            Text(game.effectiveEngine.shortLabel(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

fun Engine.shortLabel(): String = when (this) {
    Engine.RPG_MAKER_MV -> "MV"; Engine.RPG_MAKER_MZ -> "MZ"; Engine.RPG_MAKER_XP -> "XP"; Engine.RPG_MAKER_VX -> "VX"
    Engine.RPG_MAKER_VX_ACE -> "VX ACE"; Engine.POKEMON_ESSENTIALS -> "ESSENTIALS"; Engine.RPG_MAKER_2K -> "2K/2K3"
    Engine.RENPY -> "REN'PY"; Engine.GODOT -> "GODOT"; Engine.FLASH -> "FLASH"; Engine.CONSTRUCT_2 -> "C2"
    Engine.CONSTRUCT_3 -> "C3"; Engine.TYRANO -> "TYRANO"; Engine.HTML5 -> "HTML5"; else -> displayName.uppercase()
}

/**
 * A library tile: tap = play immediately (or toggle selection in multi-select mode), long-press = game menu.
 * Cover size, shape, border and glow follow the library settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameTile(
    game: GameEntity,
    lib: LibrarySettings,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
) {
    val t = AceTheme.tokens
    val shape = RoundedCornerShape(lib.coverRadius.dp)
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val playable = GameRepository.isPlayable(game)
    val borderColor = if (lib.borderColor != 0L) Color(lib.borderColor.toInt()) else t.glow
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(lib.aspect.ratio)
                .pressScale(interaction)
                .then(if (lib.coverGlow && playable && !selectionMode) Modifier.aceGlow(borderColor, t.glowIntensity * 0.7f, lib.coverRadius.dp, 10.dp) else Modifier)
                .clip(shape)
                .then(
                    when {
                        selected -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                        lib.coverBorder -> Modifier.border(t.borderWidth.coerceAtLeast(1.dp), Brush.linearGradient(listOf(borderColor.copy(alpha = 0.9f), t.glowAlt.copy(alpha = 0.5f))), shape)
                        else -> Modifier
                    },
                )
                .combinedClickable(
                    interactionSource = interaction, indication = null,
                    onClickLabel = if (selectionMode) "Select" else "Play", onLongClickLabel = "Game options",
                    onClick = onClick,
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                )
                .semantics { contentDescription = "${game.title}, ${game.effectiveEngine.displayName}, ${gameState(game).label}" },
        ) {
            CoverImage(game, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Text(
                game.effectiveEngine.shortLabel(), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            if (game.favorite) Icon(Icons.Rounded.Favorite, "Favorite", tint = t.glow, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
            if (!playable) Icon(Icons.Rounded.Lock, "Not playable yet", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp))
            if (selectionMode) {
                Box(Modifier.fillMaxSize().background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.15f)))
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                )
            }
        }
        if (lib.showTitles) {
            Spacer(Modifier.height(6.dp))
            Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 1)
        }
    }
}
