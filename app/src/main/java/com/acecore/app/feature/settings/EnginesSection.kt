package com.acecore.app.feature.settings

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.container
import com.acecore.app.core.datastore.AppSettings
import com.acecore.app.core.designsystem.AceCard
import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.feature.library.shortLabel
import com.acecore.app.runtime.RuntimeId
import com.acecore.app.runtime.RuntimeRegistry

private data class EngineGroup(val title: String, val engines: List<Engine>)

private val GROUPS = listOf(
    EngineGroup("RPG Maker", listOf(Engine.RPG_MAKER_2K, Engine.RPG_MAKER_XP, Engine.RPG_MAKER_VX, Engine.RPG_MAKER_VX_ACE, Engine.RPG_MAKER_MV, Engine.RPG_MAKER_MZ, Engine.POKEMON_ESSENTIALS)),
    EngineGroup("Visual novel engines", listOf(Engine.RENPY, Engine.TYRANO)),
    EngineGroup("Web-based engines", listOf(Engine.HTML5, Engine.CONSTRUCT_2, Engine.CONSTRUCT_3)),
    EngineGroup("Other engines", listOf(Engine.GODOT, Engine.FLASH)),
    EngineGroup("Not possible on Android", listOf(Engine.UNITY, Engine.UNREAL, Engine.GAMEMAKER, Engine.WOLF_RPG)),
)

/** Status levels shown per engine, from strongest to weakest evidence. */
private enum class EngineStatus(val label: String, val color: Long) {
    VERIFIED("Runs on this device", 0xFF1C7C54),
    INSTALLED("Installed · not run here yet", 0xFF2F5D8A),
    MISSING("Not in this version", 0xFF8A6D1A),
    IMPOSSIBLE("Not supported", 0xFF7A1D26),
}

@Composable
fun EnginesSection(s: AppSettings, onEngine: (Engine) -> Unit) {
    val ctx = LocalContext.current
    val games by ctx.container.games.games.collectAsStateWithLifecycle(initialValue = emptyList())
    val counts = remember(games) { games.groupingBy { it.effectiveEngine }.eachCount() }
    val webViewVersion = remember {
        runCatching { if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.versionName else null }.getOrNull()
    }
    Text(
        "Every engine AceCore recognises, grouped by family. “Installed” means the runtime is inside this build; an engine is only " +
            "called working on your device after a game actually started with it.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
    )
    GROUPS.forEach { group ->
        SettingsGroupTitle(group.title)
        AceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                group.engines.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    EngineRow(e, s, counts[e] ?: 0, webViewVersion, onEngine)
                }
            }
        }
    }
}

@Composable
private fun EngineRow(e: Engine, s: AppSettings, games: Int, webViewVersion: String?, onEngine: (Engine) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val info = RuntimeRegistry.info(e)
    val runtimeId = RuntimeRegistry.runtimeFor(e)
    val verifiedAt = s.runtimeVerifiedAt[runtimeId.name]
    val status = when {
        e.family == EngineFamily.UNSUPPORTED_NATIVE -> EngineStatus.IMPOSSIBLE
        !info.bundled -> EngineStatus.MISSING
        verifiedAt != null -> EngineStatus.VERIFIED
        else -> EngineStatus.INSTALLED
    }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EngineIcon(e, status)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    info.id.displayName + if (games > 0) " · $games game${if (games == 1) "" else "s"}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Text(
                status.label, style = MaterialTheme.typography.labelSmall, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.background(Color(status.color.toInt()), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, modifier = Modifier.padding(start = 4.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp, start = 52.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(info.status, style = MaterialTheme.typography.bodySmall)
                val version = when (runtimeId) {
                    RuntimeId.WEB -> webViewVersion?.let { "Android System WebView $it" }
                    RuntimeId.RUFFLE -> listOfNotNull(info.version, webViewVersion?.let { "on WebView $it" }).joinToString(" ")
                    else -> info.version
                }
                version?.let { Detail("Version", it) }
                verifiedAt?.let { Detail("Last ran on this device", DateUtils.getRelativeTimeSpanString(it).toString()) }
                info.blocker?.let { Detail("What's needed", it) }
                info.license?.let { Detail("Licence", it) }
                if (info.bundled) FilledTonalButton(onClick = { onEngine(e) }) { Text("Engine defaults") }
                else if (e.family != EngineFamily.UNSUPPORTED_NATIVE) Text(
                    "Games are still detected and listed, so they're ready when a runtime ships.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Detail(k: String, v: String) {
    Column {
        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EngineIcon(e: Engine, status: EngineStatus) {
    val c = Color(status.color.toInt())
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.18f)).border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            e.shortLabel().take(6), fontSize = if (e.shortLabel().length > 4) 9.sp else 12.sp, fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
        )
    }
}
