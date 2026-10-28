package com.acecore.app.feature.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.container
import com.acecore.app.core.designsystem.AceCard
import com.acecore.app.core.detection.Severity
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.feature.importer.severityMark
import com.acecore.app.feature.settings.SettingsGroupTitle
import com.acecore.app.feature.settings.SettingsScaffold
import com.acecore.app.runtime.RuntimeRegistry
import com.acecore.app.runtime.rgss.RgssRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Technical details for one game (kept off the game page): runtime, detection diagnostics, recent launches, logs. */
@Composable
fun TroubleshootingScreen(gameId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.games
    val game by repo.game(gameId).collectAsStateWithLifecycle(initialValue = null)
    val sessions by repo.recentSessions(gameId).collectAsStateWithLifecycle(initialValue = emptyList())
    val g = game ?: return
    val detection = remember(g.detectionJson) { repo.decodeDetection(g) }
    val engine = g.effectiveEngine
    val runtime = RuntimeRegistry.info(engine)
    val helperLog by produceState<String?>(null, g.id) {
        value = if (engine.family == EngineFamily.RGSS) withContext(Dispatchers.IO) {
            runCatching { File(RgssRuntime.helperDir(GameStorage.dataDir(ctx, g.id)), "log").readLines().takeLast(30).joinToString("\n") }.getOrNull()
        } else null
    }

    SettingsScaffold("Troubleshooting", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(g.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))

            SettingsGroupTitle("Runtime")
            Card {
                KeyValue("Detected engine", buildString {
                    append(Engine.fromName(g.engine).displayName)
                    g.engineVersion?.let { append(" ").append(it) }
                    append(" · confidence ").append(g.confidence).append("%")
                    if (g.engineOverride != null) append("\nOverridden to ").append(engine.displayName)
                })
                KeyValue("Runtime", runtime.id.displayName + if (runtime.bundled) " (in this build)" else " (not in this version)")
                Text(runtime.status, style = MaterialTheme.typography.bodySmall)
                runtime.blocker?.let { Text("What's needed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                runtime.license?.let { Text("Licence: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            g.lastError?.let { err ->
                SettingsGroupTitle("Last problem")
                Card { Text(err, style = MaterialTheme.typography.bodyMedium) }
            }

            SettingsGroupTitle("Recent launches")
            Card {
                if (sessions.isEmpty()) Text("No launches recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                sessions.forEach { s ->
                    Row {
                        Text(if (s.reachedGame) "✓" else "✕", color = if (s.reachedGame) Color(0xFF1ED69B) else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                DateUtils.formatDateTime(ctx, s.startedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) +
                                    " · " + (if (s.reachedGame) "played ${s.durationMs / 60000} min" else "didn't start") +
                                    (s.latencyMs?.let { " · started in %.1f s".format(it / 1000.0) } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }

            if (detection != null && detection.diagnostics.isNotEmpty()) {
                SettingsGroupTitle("Diagnostics")
                Card {
                    detection.diagnostics.forEach { d ->
                        Row {
                            Text(severityMark(d.severity), color = when (d.severity) {
                                Severity.ERROR -> MaterialTheme.colorScheme.error; Severity.WARNING -> Color(0xFFE8B931); Severity.INFO -> MaterialTheme.colorScheme.primary
                            })
                            Spacer(Modifier.width(10.dp))
                            Text(d.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (detection != null) {
                SettingsGroupTitle("Detection evidence")
                Card {
                    detection.scores.forEach { s ->
                        Text("${s.engine.displayName}: ${s.score} points", style = MaterialTheme.typography.titleSmall)
                        Text(s.evidence.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (detection.tags.isNotEmpty()) Text("Tags: ${detection.tags.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
            }
            helperLog?.takeIf { it.isNotBlank() }?.let { log ->
                SettingsGroupTitle("Game helper log")
                Card { Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
            OutlinedButton(onClick = {
                val report = buildString {
                    appendLine("${g.title} | ${engine.name} ${g.engineVersion ?: ""} | status ${g.status} | entry ${g.entry} | launch ${g.launchFile} (${if (g.isManualLaunch) "manual" else "auto"})")
                    appendLine("attempts ${g.launchAttempts}, ok ${g.successfulLaunches}, failed ${g.failedLaunches}, lastError ${g.lastError}")
                    sessions.forEach { appendLine("session ${it.startedAt} reached=${it.reachedGame} ms=${it.durationMs} latency=${it.latencyMs} err=${it.error}") }
                    detection?.diagnostics?.forEach { appendLine("diag ${it.severity}: ${it.message}") }
                    helperLog?.let { appendLine("helper log:\n$it") }
                }
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AceCore troubleshooting", report))
                Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.padding(20.dp)) { Text("Copy report") }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
