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
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
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

/**
 * Cover art. Without artwork (or while it loads) a neutral graphite card shows the game's title and its system or
 * engine, instead of a coloured block with initials; loading shows a slow shimmer (still with reduced motion).
 */
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
            loading = { CoverShimmer(Modifier.fillMaxSize()) },
        )
    }
}

@Composable
private fun CoverShimmer(modifier: Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    if (AceTheme.tokens.reduceMotion) { Box(modifier.background(base)); return }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        -1f, 2f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "shimmer-x",
    )
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(base)) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent),
            start = androidx.compose.ui.geometry.Offset(w * x - w * 0.5f, 0f), end = androidx.compose.ui.geometry.Offset(w * x + w * 0.5f, constraints.maxHeight.toFloat()),
        )))
    }
}

@Composable
private fun CoverFallback(game: GameEntity, modifier: Modifier) {
    val t = AceTheme.tokens
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .background(Brush.verticalGradient(listOf(cs.surfaceContainerHighest, cs.surfaceContainerLow)))
            .drawBehind {
                // A faint diagonal sheen and a thin accent line at the top: depth without colour noise.
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                    start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.6f)))
                drawRect(t.glow.copy(alpha = 0.55f), size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
            }
            .padding(10.dp),
    ) {
        Text(
            game.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface.copy(alpha = 0.92f),
            fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            game.effectiveEngine.shortLabel(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart)
                .background(cs.surfaceContainerLowest.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

fun Engine.shortLabel(): String = when (this) {
    Engine.RPG_MAKER_MV -> "MV"; Engine.RPG_MAKER_MZ -> "MZ"; Engine.RPG_MAKER_XP -> "XP"; Engine.RPG_MAKER_VX -> "VX"
    Engine.RPG_MAKER_VX_ACE -> "VX ACE"; Engine.POKEMON_ESSENTIALS -> "ESSENTIALS"; Engine.RPG_MAKER_2K -> "2K/2K3"
    Engine.RENPY -> "REN'PY"; Engine.GODOT -> "GODOT"; Engine.FLASH -> "FLASH"; Engine.CONSTRUCT_2 -> "C2"
    Engine.CONSTRUCT_3 -> "C3"; Engine.TYRANO -> "TYRANO"; Engine.HTML5 -> "HTML5"
    Engine.GAME_BOY -> "GB"; Engine.GAME_BOY_COLOR -> "GBC"; Engine.GAME_BOY_ADVANCE -> "GBA"; Engine.NINTENDO_DS -> "DS"
    else -> displayName.uppercase()
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
                // Depth from a soft shadow; the accent glow is kept faint (it used to outline every cover in bright red).
                .shadow(6.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .then(if (lib.coverGlow && playable && !selectionMode) Modifier.aceGlow(borderColor, t.glowIntensity * 0.25f, lib.coverRadius.dp, 8.dp) else Modifier)
                .clip(shape)
                .then(
                    when {
                        selected -> Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, shape)
                        lib.coverBorder -> Modifier.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), borderColor.copy(alpha = 0.28f))), shape)
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
            // The cover is shown as it is: no engine name or other AceArcade text on top (the engine is on the game page,
            // in sorting/filters and in the long-press menu).
            CoverImage(game, Modifier.fillMaxSize())
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
