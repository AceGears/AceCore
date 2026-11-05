package com.acecore.app.runtime.handheld

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acecore.app.core.handheld.pokemon.Gen3BagItem
import com.acecore.app.core.handheld.pokemon.Gen3Mon
import com.acecore.app.core.handheld.pokemon.Gen3Tools
import com.acecore.app.core.handheld.pokemon.NATURES
import com.acecore.app.runtime.web.overlay.PanelBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STAT_NAMES = listOf("HP", "Atk", "Def", "Spe", "SpA", "SpD")

/** EV spreads offered as presets (each adds up to 510, the games' limit). */
private val EV_PRESETS = listOf(
    "Clear" to intArrayOf(0, 0, 0, 0, 0, 0),
    "Physical sweeper" to intArrayOf(6, 252, 0, 252, 0, 0),
    "Special sweeper" to intArrayOf(6, 0, 0, 252, 252, 0),
    "Physical wall" to intArrayOf(252, 0, 252, 0, 0, 6),
    "Special wall" to intArrayOf(252, 0, 6, 0, 0, 252),
    "Balanced" to intArrayOf(85, 85, 85, 85, 85, 85),
)

/**
 * Party and bag tools for the original Generation 3 games (exact dumps only, see [Gen3Tools]). Every change goes
 * through the game's memory with the emulator paused; [onBeforeFirstChange] keeps a snapshot so "Undo all" can put
 * the game back. Changes become permanent only when the player saves in the game.
 */
@Composable
internal fun PokemonToolsPanel(
    tools: Gen3Tools,
    accent: Color,
    onBeforeFirstChange: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("party") }
    var party by remember { mutableStateOf<List<Gen3Mon>?>(null) }
    var bag by remember { mutableStateOf<List<Gen3BagItem>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    var confirmAddAll by remember { mutableStateOf(false) }
    var pockets by remember { mutableStateOf(setOf(tools.game.family.itemsPocket, tools.game.family.ballsPocket)) }
    var includeHms by remember { mutableStateOf(false) }
    var qty by remember { mutableIntStateOf(10) }
    var changed by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            val p = tools.party()
            val b = tools.bag()
            party = p
            bag = b.getOrDefault(emptyList())
            problem = when {
                p == null -> "The party can't be read right now. Open the tools while walking around in the game (not on the title screen, in a menu or in a battle)."
                b.isFailure -> b.exceptionOrNull()?.message
                else -> null
            }
        }
    }

    fun change(what: suspend () -> Result<*>, done: String) {
        scope.launch {
            if (!changed) { withContext(Dispatchers.IO) { onBeforeFirstChange() }; changed = true }
            val r = withContext(Dispatchers.IO) { what() }
            message = r.fold({ (it as? String) ?: done }, { "Not changed: ${it.message}" })
            revision++
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(12.dp).heightIn(max = 900.dp).background(PanelBg, RoundedCornerShape(20.dp))
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pokémon tools", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "${tools.game.label} (exact dump). Changes go into the running game: save in the game to keep them. " +
                    "Use them outside battles.",
                color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Party", tab == "party", accent) { tab = "party" }
                Pill("Bag", tab == "bag", accent) { tab = "bag" }
                if (canUndo) Pill("Undo all changes", false, accent) {
                    scope.launch {
                        withContext(Dispatchers.IO) { onUndo() }
                        changed = false; message = "The game is back to how it was before the first change."; revision++
                    }
                }
                Pill("Close", false, accent, onClose)
            }
            message?.let { Text(it, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            problem?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 13.sp) }

            if (tab == "party") party?.let { mons ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    mons.forEachIndexed { i, m -> Pill(if (m.isEgg) "Egg" else tools.speciesName(m.species), selected == i, accent) { selected = i } }
                }
                mons.getOrNull(selected)?.let { m ->
                    val idx = selected
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${tools.speciesName(m.species)} · Lv. ${m.level} · ${NATURES[m.nature]}" + (if (m.shiny) " · ★ shiny" else ""),
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        )
                        Text("HP ${m.hp}/${m.maxHp} · friendship ${m.friendship}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text("IVs " + STAT_NAMES.indices.joinToString(" ") { "${STAT_NAMES[it]} ${m.iv(it)}" }, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text("EVs " + STAT_NAMES.indices.joinToString(" ") { "${STAT_NAMES[it]} ${m.ev(it)}" }, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text("Stats " + STAT_NAMES.indices.joinToString(" ") { "${STAT_NAMES[it]} ${m.stat(it)}" }, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    if (!m.isEgg) {
                        Flow {
                            Pill("Max IVs (31)", false, accent) { change({ tools.setIvs(idx, 31) }, "IVs set to 31; stats recalculated") }
                            Pill("Heal", false, accent) { change({ tools.heal(idx) }, "Healed: HP, status and PP") }
                            Pill("Max friendship", false, accent) { change({ tools.maxFriendship(idx) }, "Friendship 255") }
                            if (!m.shiny && m.species != Gen3Tools.UNOWN && m.species != Gen3Tools.WURMPLE)
                                Pill("Make shiny", false, accent) { change({ tools.makeShiny(idx) }, "Now shiny (same nature and gender)") }
                        }
                        Text("Level", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        Flow {
                            listOf(-10, -1, 1, 10).forEach { d ->
                                val to = (m.level + d).coerceIn(1, 100)
                                if (to != m.level) Pill((if (d > 0) "+" else "") + d, false, accent) { change({ tools.setLevel(idx, to) }, "Level $to") }
                            }
                            if (m.level != 100) Pill("100", false, accent) { change({ tools.setLevel(idx, 100) }, "Level 100") }
                        }
                        Text("EV spread (total 510 at most)", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        Flow {
                            EV_PRESETS.forEach { (name, evs) -> Pill(name, false, accent) { change({ tools.setEvs(idx, evs) }, "EVs: $name; stats recalculated") } }
                        }
                        Text(
                            "Level changes don't teach the moves learned on the way and don't evolve the Pokémon; the game does that on its next level-up.",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                        )
                    }
                }
            }

            if (tab == "bag") {
                val fam = tools.game.family
                Text("Add all items", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Flow {
                    listOf(fam.itemsPocket, fam.ballsPocket, fam.tmPocket, fam.berriesPocket).forEach { p ->
                        Pill(tools.pocketName(p), p in pockets, accent) { pockets = if (p in pockets) pockets - p else pockets + p }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeHms, { includeHms = it }, colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = Color.White.copy(alpha = 0.6f)))
                    Text("Include HMs", color = Color.White, fontSize = 13.sp)
                }
                Flow {
                    listOf(1, 5, 10, 99, 999).filter { it <= fam.berryCap }.forEach { n -> Pill("× $n", qty == n, accent) { qty = n } }
                }
                Text(
                    "Key items are never added. Each pocket keeps the game's own limit (${fam.itemCap} of an item" +
                        (if (fam.berryCap != fam.itemCap) ", ${fam.berryCap} berries" else "") + ").",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                )
                Pill("Add all items…", false, accent) { if (pockets.isNotEmpty()) confirmAddAll = true }
                Text("In the bag", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(bag, key = { "${it.pocket}-${it.slot}-${it.item}" }) { item ->
                        val cap = tools.capFor(item.pocket)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${item.name} · ${tools.pocketName(item.pocket)}", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("× ${item.quantity}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                            if (item.pocket != fam.keyPocket) {
                                Pill("−", false, accent) { change({ tools.setQuantity(item, item.quantity - 1) }, "${item.name}: ${item.quantity - 1}") }
                                if (item.quantity < cap) Pill("+", false, accent) { change({ tools.setQuantity(item, item.quantity + 1) }, "${item.name}: ${item.quantity + 1}") }
                                if (item.quantity < cap) Pill("Max", false, accent) { change({ tools.setQuantity(item, cap) }, "${item.name}: $cap") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmAddAll) {
        val count = pockets.sumOf { tools.obtainable(it, includeHms).size }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { confirmAddAll = false }, contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).padding(16.dp).background(PanelBg, RoundedCornerShape(18.dp))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Add $count items?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "Every item of ${pockets.sorted().joinToString { tools.pocketName(it) }}, $qty of each (items you already have " +
                        "are topped up, never lowered). Key items and anything already in the bag stay as they are. Undo all " +
                        "changes puts the bag back until you save in the game.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Add", true, accent) { confirmAddAll = false; change({ tools.addAllItems(pockets, qty, includeHms) }, "Done") }
                    Pill("Cancel", false, accent) { confirmAddAll = false }
                }
            }
        }
    }
}

@Composable
private fun Flow(content: @Composable () -> Unit) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Pill(label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (on) accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}
