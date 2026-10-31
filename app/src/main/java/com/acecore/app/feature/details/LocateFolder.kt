package com.acecore.app.feature.details

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.acecore.app.container
import com.acecore.app.core.database.GameEntity
import kotlinx.coroutines.launch

/** Opens Android's folder picker and reconnects [game] to the chosen folder (see GameRepository.relink). */
@Composable
fun rememberLocateFolder(game: GameEntity, onDone: (Boolean) -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { ctx.container.games.relink(game, uri) }
                .onSuccess { Toast.makeText(ctx, "Reconnected to “${it.folderLabel}”", Toast.LENGTH_SHORT).show(); onDone(true) }
                .onFailure { Toast.makeText(ctx, it.message ?: "Couldn't use that folder", Toast.LENGTH_LONG).show(); onDone(false) }
        }
    }
    return { picker.launch(null) }
}

/**
 * Shown when a game's folder can't be opened: a short explanation, "Locate folder" (keeps the game's settings,
 * statistics, cheats and saves) and the technical details on request.
 */
@Composable
fun FolderUnavailableCard(game: GameEntity, details: String?, modifier: Modifier = Modifier, onRelinked: () -> Unit = {}) {
    val locate = rememberLocateFolder(game) { ok -> if (ok) onRelinked() }
    var showDetails by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FolderOff, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            Text("  Game folder unavailable", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Text(
            "AceArcade can't open “${game.folderLabel}” any more. If you moved or renamed it, select its current folder: " +
                "settings, statistics, cheats and saves stay with the game.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = locate) { Text("Locate folder") }
            if (!details.isNullOrBlank()) TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "View details") }
        }
        if (showDetails && details != null) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
