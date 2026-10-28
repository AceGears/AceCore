package com.acecore.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acecore.app.container
import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.ConfigSource
import com.acecore.app.core.config.ConfigType
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.config.WebKey
import com.acecore.app.core.model.Engine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Renders [keys] for one layer ([scope]) of the layered configuration. Each row shows the effective value and
 * where it comes from; editing writes to [scope], and the reset icon removes this layer's override.
 */
@Composable
fun ConfigEditor(keys: List<ConfigKey>, config: LayeredConfig, scope: ConfigSource, onSet: (ConfigKey, String?) -> Unit) {
    keys.groupBy { it.section }.forEach { (section, list) ->
        SettingsGroupTitle(section)
        list.forEach { key ->
            val r = config.resolve(key)
            val own = r.source == scope
            val trailing: @Composable () -> Unit = {
                if (own) {
                    IconButton(onClick = { onSet(key, null) }) { Icon(Icons.Rounded.RestartAlt, "Reset ${key.title} to inherited value") }
                } else if (scope != ConfigSource.GLOBAL) {
                    Text(
                        "Inherited",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val sourceNote = when {
                own && scope == ConfigSource.GLOBAL -> key.description
                own -> "${key.description} (overridden here)"
                else -> "${key.description} (from ${r.source.label.lowercase()})"
            }
            when (key.type) {
                ConfigType.BOOL -> SwitchRow(key.title, sourceNote, key.bool(r.value), trailing = trailing) { onSet(key, it.toString()) }
                ConfigType.INT -> SliderRow(key.title, sourceNote, key.int(r.value), key.range, unit = "%", trailing = trailing) { onSet(key, it.toString()) }
                ConfigType.CHOICE -> ChoiceRow(key.title, sourceNote, key.choices, r.value, trailing = trailing) { onSet(key, it) }
                ConfigType.BUTTON_MAP -> {
                    var open by remember { mutableStateOf(false) }
                    val summary = ButtonMap.decode(r.value).entries.take(4).joinToString("  ") { (b, k) -> "${b.name}→${WebKey.byId(k)?.label}" }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = true }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(key.title, style = MaterialTheme.typography.titleMedium)
                            Text("$summary …", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            Text(sourceNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        trailing()
                    }
                    if (open) ButtonMapDialog(r.value, resetTo = config.inherited(key, scope).value, onDismiss = { open = false }) { onSet(key, it); open = false }
                }
            }
        }
    }
}

/** Per-game (gameId != null) or per-engine (engine != null) settings page. */
@Composable
fun ConfigScopeScreen(gameId: Long?, engine: Engine?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val games = ctx.container.games
    val settingsRepo = ctx.container.settings
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
    val gameFlow = remember(gameId) { if (gameId != null) games.game(gameId) else flowOf(null) }
    val game by gameFlow.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    if (gameId != null && game == null) return
    val g = game
    val effectiveEngine = g?.effectiveEngine ?: engine ?: Engine.HTML5
    val layer = if (g != null) ConfigSource.GAME else ConfigSource.ENGINE
    val config = LayeredConfig(s.globalConfig, s.engineConfig[effectiveEngine].orEmpty(), if (g != null) games.overrides(g) else emptyMap(), effectiveEngine)
    val keys = ConfigKey.entries.filter { it.appliesTo(effectiveEngine) }

    SettingsScaffold(if (g != null) "Game settings" else "${effectiveEngine.displayName} defaults", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                if (g != null) "Settings for “${g.title}”. Changes here override the ${effectiveEngine.displayName} defaults and your global settings."
                else "Defaults for every ${effectiveEngine.displayName} game. Individual games can still override them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (keys.isEmpty()) {
                Text(
                    "This engine's runtime isn't built into this version yet, so there are no runtime settings to configure.",
                    modifier = Modifier.padding(20.dp),
                )
            }
            ConfigEditor(keys, config, layer) { key, value ->
                scope.launch {
                    if (g != null) games.setOverride(g.id, key.id, value)
                    else settingsRepo.setEngineConfig(effectiveEngine, key, value)
                }
            }
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        if (g != null) games.clearOverrides(g.id)
                        else settingsRepo.resetSection("engine.${effectiveEngine.name}.")
                    }
                }) {
                    Icon(Icons.Rounded.RestartAlt, null, modifier = Modifier.size(18.dp))
                    Text("  Restore all inherited values")
                }
            }
        }
    }
}
