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
    EngineGroup("Other engines", listOf(Engine.GODOT, Engine.FLASH, Engine.GAMEMAKER, Engine.WOLF_RPG, Engine.UNITY, Engine.UNREAL)),
)

/** Status levels shown per engine, from strongest to weakest evidence. */
private enum class EngineStatus(val label: String, val color: Long) {
    VERIFIED("Partially compatible · games have run here", 0xFF1ED69B),
    INSTALLED("Installed · untested", 0xFF2E86FF),
    MISSING("Unavailable · runtime not included", 0xFFE8B931),
    WINDOWS_ONLY("Windows builds can't run on Android", 0xFFFF6B7D),
}

/** Short, legible engine marks for the icon tiles (never truncated). */
private fun Engine.iconLabel(): String = when (this) {
    Engine.RPG_MAKER_2K -> "2K3"; Engine.RPG_MAKER_XP -> "XP"; Engine.RPG_MAKER_VX -> "VX"; Engine.RPG_MAKER_VX_ACE -> "ACE"
    Engine.RPG_MAKER_MV -> "MV"; Engine.RPG_MAKER_MZ -> "MZ"; Engine.POKEMON_ESSENTIALS -> "PKMN"; Engine.RENPY -> "RPY"
    Engine.TYRANO -> "TYR"; Engine.HTML5 -> "HTML"; Engine.CONSTRUCT_2 -> "C2"; Engine.CONSTRUCT_3 -> "C3"; Engine.GODOT -> "GD"
    Engine.FLASH -> "SWF"; Engine.GAMEMAKER -> "GM"; Engine.WOLF_RPG -> "WOLF"; Engine.UNITY -> "UNITY"; Engine.UNREAL -> "UE"
    else -> shortLabel().take(4)
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
        "Every engine AceArcade recognises. “Installed” means its runtime is inside this build; “Verified” appears only after a game " +
            "actually started with it on this device. Tap an engine for details and its default settings.",
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
        e.family == EngineFamily.UNSUPPORTED_NATIVE -> EngineStatus.WINDOWS_ONLY
        !info.bundled -> EngineStatus.MISSING
        verifiedAt != null -> EngineStatus.VERIFIED
        else -> EngineStatus.INSTALLED
    }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EngineIcon(e, status)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                // Status sits under the name (a small dot + label), so long names are never squeezed by a badge.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(status.color.toInt())))
                    Spacer(Modifier.width(6.dp))
                    Text(status.label, style = MaterialTheme.typography.labelMedium, color = Color(status.color.toInt()), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text(
                    (if (e.family == EngineFamily.UNSUPPORTED_NATIVE) "No runtime" else info.id.displayName) +
                        if (games > 0) " · $games game${if (games == 1) "" else "s"}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Hide details" else "Show details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp, start = 58.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.family == EngineFamily.UNSUPPORTED_NATIVE) Text(
                    "${e.displayName} games for PC are compiled Windows programs (.exe). Android can't run Windows programs, " +
                        "and there's no open runtime that plays these games' PC builds on Android. If the developer released an " +
                        "Android version, it installs as its own app from the store or as an APK.",
                    style = MaterialTheme.typography.bodySmall,
                ) else Text(info.status, style = MaterialTheme.typography.bodySmall)
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
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.copy(alpha = 0.14f)).border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val label = e.iconLabel()
        Text(
            label, fontSize = when (label.length) { in 0..2 -> 15.sp; 3 -> 13.sp; 4 -> 11.sp; else -> 10.sp },
            fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, letterSpacing = 0.3.sp,
        )
    }
}
