package com.acecore.app.runtime.rgss

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acecore.app.feature.cheats.CheatStore
import com.acecore.app.feature.saves.SaveBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Panel = Color(0xF20E0B22)
private val Card = Color(0xFF1A1536)
private val Muted = Color.White.copy(alpha = 0.62f)

/** Something the user is about to change, shown in a confirm/edit dialog before anything is sent to the game. */
private sealed interface Pending {
    data class Number(val title: String, val hint: String, val initial: String, val min: Long, val max: Long, val command: (Long) -> Array<String>) : Pending
    data class Pick(val title: String, val listCommand: String, val listFile: String, val filter: (List<String>) -> Boolean, val withQuantity: Boolean, val maxQty: Int, val command: (String, Int) -> Array<String>) : Pending
    data class Confirm(val title: String, val body: String, val command: Array<String>) : Pending
}

/**
 * In-game cheat panel for RPG Maker XP/VX/VX Ace and Pokémon Essentials. Everything shown here comes from probing the
 * running game, so only what this game supports right now is offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RgssCheatPanel(spec: RgssLaunchSpec, appContext: Context, accent: Color, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val channel = remember { CheatChannel(RgssRuntime.helperDir(File(spec.dataPath))) }
    val store = remember { CheatStore(appContext) }
    var config by remember { mutableStateOf(store.load(spec.gameId)) }
    var state by remember { mutableStateOf<GameCheatState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var editing by remember { mutableStateOf<PartyMember?>(null) }
    var backedUp by remember { mutableStateOf(false) }

    suspend fun refresh() {
        busy = true
        val r = channel.send("probe")
        state = if (r.ok) channel.state() else null
        if (!r.ok) message = false to r.message
        busy = false
    }

    /** Sends a command that changes the game: backs up saves first (once per session) when enabled. */
    fun apply(vararg cmd: String) {
        scope.launch {
            busy = true
            if (config.autoBackup && !backedUp) {
                withContext(Dispatchers.IO) {
                    runCatching { SaveBackupManager(appContext).backup(spec.gameId, File(spec.gamePath), "Before cheats in ${spec.title}") }
                }
                backedUp = true
            }
            val r = channel.send(*cmd)
            message = r.ok to r.message
            if (r.ok) state = channel.state()
            busy = false
        }
    }

    fun setFlag(key: String, value: String) {
        val p = config.profile
        config = config.withProfile(p.copy(flags = p.flags + (key to value)))
        store.save(spec.gameId, config)
        apply("set", key, value)
    }

    LaunchedEffect(Unit) { if (spec.cheatsEnabled) refresh() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(12.dp).heightIn(max = 640.dp)
                .background(Panel, RoundedCornerShape(24.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cheats", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(
                        state?.let { s -> if (s.kind == "essentials") "Pokémon Essentials ${s.essentialsVersion ?: ""}".trim() else "RPG Maker" } ?: spec.title,
                        color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (spec.cheatsEnabled) IconButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Color.White) }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text("Talking to the game…", color = Muted, fontSize = 12.sp)
            }
            message?.let { (ok, text) ->
                Text(text, color = if (ok) Color(0xFF7BE3B5) else Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!spec.cheatsEnabled) {
                    Section("Cheats are off for this game") {
                        Text(
                            if (config.enabled) "Cheats are turned on and will work the next time you start the game."
                            else "Turn them on now; they take effect the next time the game starts. AceArcade only loads its hooks into games with cheats on.",
                            color = Muted, fontSize = 13.sp,
                        )
                        if (!config.enabled) Chip("Turn on for next launch", accent) { config = config.copy(enabled = true); store.save(spec.gameId, config) }
                    }
                    return@Column
                }
                val s = state
                if (s == null) {
                    if (!busy) Text("The game hasn't answered yet. Wait for the title screen, then tap Refresh.", color = Muted, fontSize = 13.sp)
                    return@Column
                }
                if (s.caps.isEmpty() || (s.kind == "essentials" && !s.has("money") && !s.has("pkmn_edit"))) {
                    Text("Most cheats need a loaded game. Load your save (or start a new game), then tap Refresh.", color = Muted, fontSize = 13.sp)
                }
                // ---------------------------------------------------------------- Pokémon Essentials
                if (s.has("money") || s.has("items")) Section("Money & items") {
                    if (s.has("money")) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Money: ${s.money ?: "?"}", color = Color.White, modifier = Modifier.weight(1f))
                        Chip("Set", accent) {
                            pending = Pending.Number("Set money", "0 – ${s.moneyMax ?: 999999}", (s.money ?: 0).toString(), 0, s.moneyMax ?: 999_999) { arrayOf("money", "set", it.toString()) }
                        }
                        Spacer(Modifier.width(6.dp))
                        Chip("Max", accent) { pending = Pending.Confirm("Set money to the maximum?", "Money becomes ${s.moneyMax ?: 999999}.", arrayOf("money", "set", (s.moneyMax ?: 999_999).toString())) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.has("items")) Chip("Give item", accent) {
                            pending = Pending.Pick("Give item", "items", "items", { true }, withQuantity = true, maxQty = 999) { id, q -> arrayOf("item", id, q.toString()) }
                        }
                        if (s.has("balls")) Chip("Give Poké Balls", accent) {
                            pending = Pending.Pick("Give Poké Balls", "items", "items", { it.getOrNull(3) == "1" }, withQuantity = true, maxQty = 999) { id, q -> arrayOf("item", id, q.toString()) }
                        }
                    }
                }
                if (s.has("pkmn_edit")) Section("Party") {
                    s.party.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable(enabled = !p.egg) { editing = p }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (p.shiny) Icon(Icons.Rounded.Star, "Shiny", tint = Color(0xFFE8B931), modifier = Modifier.padding(start = 4.dp).size(14.dp))
                                }
                                Text(if (p.egg) "Egg" else "${p.species} · Lv. ${p.level} · ${p.nature} · ${p.ability}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!p.egg) Text("Edit", color = accent, fontSize = 13.sp)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.has("party_heal")) Chip("Heal party", accent) { apply("party_heal") }
                        if (s.has("pkmn_add")) Chip("Add a Pokémon", accent) {
                            pending = Pending.Pick("Add a Pokémon", "species", "species", { true }, withQuantity = true, maxQty = 100) { id, lv -> arrayOf("pkmn_add", id, lv.toString()) }
                        }
                    }
                }
                // ---------------------------------------------------------------- RPG Maker
                if (s.has("gold") || s.has("rpg_items") || s.has("rpg_heal")) Section("Party & gold") {
                    if (s.has("gold")) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gold: ${s.gold ?: "?"}", color = Color.White, modifier = Modifier.weight(1f))
                        Chip("Set", accent) { pending = Pending.Number("Set gold", "0 – 9999999", (s.gold ?: 0).toString(), 0, 9_999_999) { arrayOf("gold", "set", it.toString()) } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.has("rpg_items")) Chip("Give item by ID", accent) {
                            pending = Pending.Number("Item ID (from the game's database)", "1 – 999", "1", 1, 999) { id -> arrayOf("rpg_item", id.toString(), "1") }
                        }
                        if (s.has("rpg_heal")) Chip("Recover party", accent) { apply("rpg_heal") }
                    }
                    if (s.has("rpg_level")) s.actors.forEachIndexed { i, (name, lv) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("$name · Lv. $lv", color = Color.White, modifier = Modifier.weight(1f))
                            Chip("Level", accent) { pending = Pending.Number("Level for $name", "1 – 99", lv.toString(), 1, 99) { arrayOf("rpg_level", i.toString(), it.toString()) } }
                        }
                    }
                }
                // ---------------------------------------------------------------- field
                if (s.has("walk_speed") || s.has("no_encounters")) Section("Movement & encounters") {
                    if (s.has("walk_speed")) {
                        Text("Movement speed", color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("0" to "Normal", "5" to "Fast", "6" to "Very fast").forEach { (v, l) ->
                                Chip(l, accent, selected = (s.flags["walk_speed"] ?: "0") == v) { setFlag(CheatFlags.WALK_SPEED, v) }
                            }
                        }
                    }
                    if (s.has("no_encounters")) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("No random encounters", color = Color.White, modifier = Modifier.weight(1f))
                        Switch(
                            checked = s.flags["no_encounters"] == "1", onCheckedChange = { setFlag(CheatFlags.NO_ENCOUNTERS, if (it) "1" else "0") },
                            colors = SwitchDefaults.colors(checkedTrackColor = accent),
                        )
                    }
                    Text("These switches are saved in your cheat profile and apply every time the game starts.", color = Muted, fontSize = 12.sp)
                }
                Text(
                    "Changes happen in the running game and become permanent when you save in the game." +
                        if (config.autoBackup) " Your saves are backed up before the first change of each session." else "",
                    color = Muted, fontSize = 12.sp,
                )
            }
        }
    }

    editing?.let { p ->
        PokemonEditor(p, accent, onClose = { editing = null }) { cmd ->
            editing = null
            pending = cmd
        }
    }
    pending?.let { pd ->
        PendingDialog(pd, channel, accent, onDismiss = { pending = null }) { cmd -> pending = null; apply(*cmd) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.04f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Chip(label: String, accent: Color, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Color.White else accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) accent else accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Edits one party Pokémon. Every change is confirmed in the next dialog before it is sent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PokemonEditor(p: PartyMember, accent: Color, onClose: () -> Unit, onAction: (Pending) -> Unit) {
    val stats = listOf("HP", "Atk", "Def", "SpA", "SpD", "Spe")
    val i = p.index.toString()
    Overlay(onClose) {
        Text("${p.name} (${p.species})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Lv. ${p.level} · ${p.nature} · ${p.ability}" + (p.happiness?.let { " · friendship $it" } ?: ""), color = Muted, fontSize = 12.sp)
        Text("IVs ${stats.zip(p.ivs).joinToString { "${it.first} ${it.second}" }}", color = Muted, fontSize = 12.sp)
        Text("EVs ${stats.zip(p.evs).joinToString { "${it.first} ${it.second}" }}", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Level", accent) { onAction(Pending.Number("Level for ${p.name}", "1 – 100", p.level.toString(), 1, 100) { arrayOf("pkmn", i, "level", it.toString()) }) }
            Chip("Add EXP", accent) { onAction(Pending.Number("EXP to add (negative removes)", "-9999999 – 9999999", "1000", -9_999_999, 9_999_999) { arrayOf("pkmn", i, "exp", it.toString()) }) }
            Chip("Max all IVs", accent) { onAction(Pending.Confirm("Set all IVs of ${p.name} to 31?", "Stats are recalculated by the game.", arrayOf("pkmn", i, "iv", "all", "31"))) }
            stats.forEachIndexed { s, name ->
                Chip("IV $name", accent) { onAction(Pending.Number("$name IV for ${p.name}", "0 – 31", p.ivs.getOrElse(s) { 0 }.toString(), 0, 31) { arrayOf("pkmn", i, "iv", s.toString(), it.toString()) }) }
            }
            stats.forEachIndexed { s, name ->
                Chip("EV $name", accent) { onAction(Pending.Number("$name EV for ${p.name}", "0 – 252 (510 total)", p.evs.getOrElse(s) { 0 }.toString(), 0, 252) { arrayOf("pkmn", i, "ev", s.toString(), it.toString()) }) }
            }
            Chip("Nature", accent) {
                onAction(Pending.Pick("Nature for ${p.name}", "natures", "natures", { true }, withQuantity = false, maxQty = 1) { id, _ -> arrayOf("pkmn", i, "nature", id) })
            }
            listOf(0, 1, 2).forEach { a ->
                Chip(if (a == 2) "Hidden ability" else "Ability ${a + 1}", accent) {
                    onAction(Pending.Confirm("Switch ${p.name} to ${if (a == 2) "its hidden ability" else "ability ${a + 1}"}?", "Uses the species' ability list.", arrayOf("pkmn", i, "ability", a.toString())))
                }
            }
            Chip("Friendship", accent) { onAction(Pending.Number("Friendship for ${p.name}", "0 – 255", (p.happiness ?: 70).toString(), 0, 255) { arrayOf("pkmn", i, "happiness", it.toString()) }) }
            Chip(if (p.shiny) "Make not shiny" else "Make shiny", accent) {
                onAction(Pending.Confirm(if (p.shiny) "Make ${p.name} not shiny?" else "Make ${p.name} shiny?", "Changes its appearance.", arrayOf("pkmn", i, "shiny", if (p.shiny) "0" else "1")))
            }
            Chip("Heal", accent) { onAction(Pending.Confirm("Heal ${p.name}?", "Restores HP, PP and status.", arrayOf("pkmn", i, "heal"))) }
            Chip("Send to PC", accent) { onAction(Pending.Confirm("Send ${p.name} to the PC?", "It moves to the first box with space.", arrayOf("pkmn_to_pc", i))) }
        }
    }
}

@Composable
private fun Overlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(16.dp).heightIn(max = 600.dp)
                .background(Color(0xFF151130), RoundedCornerShape(22.dp)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun PendingDialog(pd: Pending, channel: CheatChannel, accent: Color, onDismiss: () -> Unit, onApply: (Array<String>) -> Unit) {
    when (pd) {
        is Pending.Confirm -> Overlay(onDismiss) {
            Text(pd.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(pd.body, color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Apply", accent, selected = true) { onApply(pd.command) }; Chip("Cancel", accent) { onDismiss() } }
        }
        is Pending.Number -> {
            var text by remember(pd) { mutableStateOf(pd.initial) }
            val value = text.trim().toLongOrNull()
            val valid = value != null && value in pd.min..pd.max
            Overlay(onDismiss) {
                Text(pd.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                OutlinedTextField(
                    text, { text = it.filter { c -> c.isDigit() || c == '-' }.take(9) }, singleLine = true, isError = !valid,
                    supportingText = { Text(pd.hint, color = Muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Apply", accent, selected = valid) { if (valid) onApply(pd.command(value!!)) }
                    Chip("Cancel", accent) { onDismiss() }
                }
            }
        }
        is Pending.Pick -> {
            var rows by remember(pd) { mutableStateOf<List<List<String>>?>(null) }
            var error by remember(pd) { mutableStateOf<String?>(null) }
            var query by remember(pd) { mutableStateOf("") }
            var chosen by remember(pd) { mutableStateOf<List<String>?>(null) }
            var qty by remember(pd) { mutableStateOf(if (pd.listCommand == "species") "5" else "1") }
            LaunchedEffect(pd) {
                val r = channel.send(pd.listCommand, timeoutMs = 8000)
                if (r.ok) rows = channel.list(pd.listFile).filter(pd.filter) else error = r.message
            }
            Overlay(onDismiss) {
                Text(pd.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                val c = chosen
                if (c == null) {
                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search", color = Muted) }, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White))
                    when {
                        error != null -> Text(error!!, color = Color(0xFFFF8A80), fontSize = 13.sp)
                        rows == null -> CircularProgressIndicator(color = accent)
                        else -> {
                            val shown = rows!!.filter { query.isBlank() || it.getOrNull(1)?.contains(query, true) == true || it[0].contains(query, true) }.take(300)
                            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                items(shown, key = { it[0] }) { row ->
                                    Text(
                                        row.getOrNull(1) ?: row[0], color = Color.White,
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                            if (pd.withQuantity) chosen = row else onApply(pd.command(row[0], 1))
                                        }.padding(10.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val n = qty.toIntOrNull()
                    val valid = n != null && n in 1..pd.maxQty
                    Text(c.getOrNull(1) ?: c[0], color = Color.White)
                    OutlinedTextField(
                        qty, { qty = it.filter(Char::isDigit).take(3) }, singleLine = true, isError = !valid,
                        label = { Text(if (pd.listCommand == "species") "Level" else "Quantity", color = Muted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Apply", accent, selected = valid) { if (valid) onApply(pd.command(c[0], n!!)) }
                        Chip("Back", accent) { chosen = null }
                    }
                }
            }
        }
    }
}
