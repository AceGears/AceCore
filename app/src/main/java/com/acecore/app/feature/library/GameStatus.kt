package com.acecore.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.model.CompatStatus

/**
 * What AceCore honestly knows about a game, from weakest to strongest evidence. A runtime being bundled only makes a
 * game "Ready to try"; it is called working only after it actually reached its first frame / page load on this device.
 */
enum class GameState(val label: String, val detail: String) {
    UNSUPPORTED("Not supported", "No runtime for this engine can exist on Android (e.g. native Windows code)."),
    RUNTIME_MISSING("Not playable yet", "The engine was detected, but its runtime isn't in this version of AceCore."),
    PACKED("Needs extraction", "The game files are inside an archive that has to be extracted first."),
    FILES_MISSING("Launch file missing", "Choose the file that starts the game."),
    READY("Ready to try", "Runtime installed and game detected. It hasn't been launched on this device yet."),
    WORKS("Runs on this device", "The game reached its first frame / page load on this device."),
    LAST_FAILED("Last launch failed", "The most recent launch didn't reach the game. See Troubleshooting."),
}

fun gameState(g: GameEntity): GameState = when (g.compatStatus) {
    CompatStatus.UNSUPPORTED -> GameState.UNSUPPORTED
    CompatStatus.RUNTIME_NOT_BUILT -> GameState.RUNTIME_MISSING
    CompatStatus.PACKED -> GameState.PACKED
    CompatStatus.MISSING_FILES -> GameState.FILES_MISSING
    CompatStatus.PLAYABLE, CompatStatus.PLAYABLE_UNTESTED -> when (g.lastLaunchOk) {
        true -> GameState.WORKS
        false -> GameState.LAST_FAILED
        null -> if (g.successfulLaunches > 0) GameState.WORKS else GameState.READY
    }
}

@Composable
fun GameStateChip(game: GameEntity, modifier: Modifier = Modifier) {
    val s = gameState(game)
    val (bg, fg) = when (s) {
        GameState.WORKS -> Color(0xFF1C7C54) to Color.White
        GameState.READY -> Color(0xFF2F5D8A) to Color.White
        GameState.LAST_FAILED -> Color(0xFF9A3B12) to Color.White
        GameState.RUNTIME_MISSING -> Color(0xFF8A6D1A) to Color.White
        GameState.PACKED -> Color(0xFF345A9E) to Color.White
        GameState.FILES_MISSING, GameState.UNSUPPORTED -> Color(0xFF7A1D26) to Color.White
    }
    Box(modifier.background(bg.copy(alpha = 0.92f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(s.label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}
