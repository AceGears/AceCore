package com.acecore.app.runtime.handheld

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acecore.app.core.handheld.CheatCodes
import com.acecore.app.core.handheld.CheatSetRef
import com.acecore.app.core.handheld.CheatStore
import com.acecore.app.core.handheld.MatchKind
import com.acecore.app.runtime.web.overlay.PanelBg
import kotlin.math.roundToInt

private val Green = Color(0xFF6EE7A0)
private val Amber = Color(0xFFFFC46B)
private val Red = Color(0xFFFF8A80)
private val Dim = Color.White.copy(alpha = 0.6f)

/** The small floating button that opens the cheat menu; drag to move. Size, opacity and visibility: in the menu. */
@Composable
internal fun CheatShortcut(act: HandheldActivity, ctl: CheatController, accent: Color) {
    val s = ctl.store
    if (!s.shortcutShown) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
        var fx by remember { mutableFloatStateOf(s.shortcutX) }
        var fy by remember { mutableFloatStateOf(s.shortcutY) }
        val sizeDp = (40 * s.shortcutSize / 100).dp
        val sizePx = with(density) { sizeDp.toPx() }
        Box(
            Modifier.offset { IntOffset(((wPx - sizePx) * fx).roundToInt(), ((hPx - sizePx) * fy).roundToInt()) }
                .size(sizeDp).alpha(s.shortcutOpacity / 100f)
                .background(PanelBg, CircleShape).border(1.5.dp, accent, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { s.shortcutX = fx; s.shortcutY = fy; s.save() }) { change, d ->
                        change.consume()
                        fx = (fx + d.x / (wPx - sizePx)).coerceIn(0f, 1f); fy = (fy + d.y / (hPx - sizePx)).coerceIn(0f, 1f)
                    }
                }
                .clickable { act.cheatsOpen = true },
            contentAlignment = Alignment.Center,
        ) { Text("✦", color = Color.White, fontSize = (18 * s.shortcutSize / 100).sp) }
    }
}

@Composable
internal fun CheatMenu(act: HandheldActivity, ctl: CheatController, accent: Color, revision: Int) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var confirmSet by remember { mutableStateOf<CheatSetRef?>(null) }
    var warnFor by remember { mutableStateOf<CheatController.Item?>(null) }
    var editing by remember { mutableStateOf<CheatStore.Custom?>(null) }
    var presetName by remember { mutableStateOf<String?>(null) }
    var showSets by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val store = ctl.store
    @Suppress("UNUSED_VARIABLE") val r = revision
    val all = remember(revision, store.acceptedSets.size, store.custom.size) { ctl.items() }
    val categories = remember(all) {
        val order = listOf("Required", "Money", "Items", "Pokémon", "Encounters", "Battles & stats", "Movement", "Progression", "Miscellaneous", "Custom cheats")
        all.map { it.category }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
    }
    val shown = all.filter { item ->
        (category == null || (category == "Favorites" && item.key in store.favorites) || item.category == category) &&
            (query.isBlank() || item.name.contains(query, true) || (item.group?.contains(query, true) == true))
    }
    fun toggle(item: CheatController.Item, on: Boolean) {
        if (on && !store.warned) { warnFor = item; return }
        if (on) store.enabled += item.key else store.enabled -= item.key
        if (!on && ctl.needsRestartToRemove(item)) act.say2("Game Genie codes change the game itself: restart the game (☰ › Restart game) to remove it fully.")
        act.applyCheats()
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act.cheatsOpen = false }) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 640.dp).fillMaxWidth().fillMaxSize().padding(14.dp)
                .background(PanelBg, RoundedCornerShape(22.dp)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cheats", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSets = !showSets }) { Text("Cheat sets", color = accent) }
                TextButton(onClick = { showOptions = !showOptions }) { Text("More", color = accent) }
                TextButton(onClick = { act.cheatsOpen = false }) { Text("Done", color = accent, fontWeight = FontWeight.Bold) }
            }
            IdentificationLine(ctl)
            if (showSets) SetsSection(ctl, accent) { confirmSet = it }
            if (showOptions) OptionsSection(act, ctl, accent, onPreset = { presetName = "" }, onAdd = { editing = CheatStore.Custom("", "", "") })
            val missing = ctl.missingRequired(all)
            if (missing.isNotEmpty()) Text("This cheat set needs “${missing.first().name}” switched on for its other codes to work.", color = Amber, fontSize = 12.sp)
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search cheats") })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val chips = listOf<String?>(null) + (if (store.favorites.isNotEmpty()) listOf("Favorites") else emptyList()) + categories
                items(chips) { c -> Chip(c ?: "All", category == c, accent) { category = c } }
            }
            if (all.isEmpty()) {
                Text(
                    if (ctl.sets.isEmpty()) "No cheats for this game in AceArcade's database. You can add your own (More › Add a cheat) or import a cheat file."
                    else "This game's cheats are for a possibly different version: open “Cheat sets” to review and accept them.",
                    color = Dim, fontSize = 13.sp,
                )
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.key }) { item -> CheatCard(act, ctl, item, accent, onToggle = { toggle(item, it) }, onEdit = {
                    editing = store.custom.firstOrNull { c -> CheatStore.customKey(c.id) == item.key }
                }) }
            }
        }
    }

    confirmSet?.let { set ->
        AlertDialog(onDismissRequest = { confirmSet = null }, containerColor = PanelBg,
            title = { Text("Use cheats for another version?", color = Color.White) },
            text = {
                Text("“${set.name}” isn't confirmed to match your ROM" +
                    (ctl.identification?.release?.let { " (identified as ${it.name})" } ?: " (your ROM isn't in the catalogue, e.g. a ROM hack)") +
                    ". Codes for a different version or a hack often use other memory addresses: they may do nothing, or change the wrong data and damage your save. Make a save state first.",
                    color = Dim)
            },
            confirmButton = { TextButton(onClick = { store.acceptedSets += set.id; store.save(); confirmSet = null; act.applyCheats(false) }) { Text("Use them", color = accent) } },
            dismissButton = { TextButton(onClick = { confirmSet = null }) { Text("Cancel", color = accent) } })
    }
    warnFor?.let { item ->
        AlertDialog(onDismissRequest = { warnFor = null }, containerColor = PanelBg,
            title = { Text("Before you use cheats", color = Color.White) },
            text = { Text("Cheats change the game's memory while it runs. Some can corrupt your save. AceArcade backs up your battery save now, and your ROM file is never changed. A save state before enabling cheats lets you undo their effects.", color = Dim) },
            confirmButton = { TextButton(onClick = {
                store.warned = true; warnFor = null; Thread { act.backupBattery("before-cheats") }.start(); toggle(item, true)
            }) { Text("Continue", color = accent) } },
            dismissButton = { TextButton(onClick = { warnFor = null }) { Text("Cancel", color = accent) } })
    }
    editing?.let { c -> EditCustomDialog(act, ctl, c, accent) { editing = null } }
    presetName?.let { n ->
        var name by remember { mutableStateOf(n) }
        AlertDialog(onDismissRequest = { presetName = null }, containerColor = PanelBg,
            title = { Text("Save the cheats that are on as a preset", color = Color.White) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Preset name") }) },
            confirmButton = { TextButton(onClick = {
                if (name.isNotBlank()) { store.presets[name.trim()] = store.enabled.toList(); store.save(); act.say2("Preset “${name.trim()}” saved") }
                presetName = null
            }) { Text("Save", color = accent) } },
            dismissButton = { TextButton(onClick = { presetName = null }) { Text("Cancel", color = accent) } })
    }
}

@Composable
private fun IdentificationLine(ctl: CheatController) {
    val id = ctl.identification
    val (text, color) = when {
        id?.exact == true -> "Identified: ${id.release?.name}" + (if (id.release?.kind == "hack") " (ROM hack)" else "") to Green
        id?.sameGameCode?.isNotEmpty() == true -> "Modified or unknown dump of ${id.sameGameCode.first().name} (game code ${id.sameGameCode.first().serial}): cheats are only probable matches" to Amber
        ctl.romSha1 == null -> "Identifying your ROM…" to Dim
        else -> "Your ROM isn't in the catalogue: cheats found by title are only probable matches" to Amber
    }
    Text(text, color = color, fontSize = 12.sp)
}

@Composable
private fun SetsSection(ctl: CheatController, accent: Color, onConfirm: (CheatSetRef) -> Unit) {
    var search by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<CheatSetRef>()) }
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ctl.sets.isEmpty()) Text("No cheat set matches this game.", color = Dim, fontSize = 12.sp)
        for (set in ctl.sets + ctl.extraSets.filter { e -> ctl.sets.none { it.id == e.id } }) {
            val active = set.match == MatchKind.EXACT || set.id in ctl.store.acceptedSets
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(set.name, color = Color.White, fontSize = 13.sp)
                    Text("${set.count} cheats · ${if (set.match == MatchKind.EXACT) "matches your ROM" else "probable match"} · ${set.source}${set.device?.let { " · $it" } ?: ""}",
                        color = if (set.match == MatchKind.EXACT) Green else Amber, fontSize = 11.sp)
                }
                if (!active) TextButton(onClick = { onConfirm(set) }) { Text("Use", color = accent) }
                else if (set.match != MatchKind.EXACT) TextButton(onClick = { ctl.store.acceptedSets -= set.id; ctl.store.save() }) { Text("Remove", color = accent) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Find another cheat set") })
            TextButton(onClick = { results = ctl.searchDatabase(search) }) { Text("Search", color = accent) }
        }
        for (set in results.take(20)) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${set.name} (${set.count})", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { ctl.extraSets += set; onConfirm(set) }) { Text("Use", color = accent) }
        }
    }
}

@Composable
private fun OptionsSection(act: HandheldActivity, ctl: CheatController, accent: Color, onPreset: () -> Unit, onAdd: () -> Unit) {
    val s = ctl.store
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onAdd) { Text("Add a cheat", color = accent) }
            TextButton(onClick = onPreset) { Text("Save preset", color = accent) }
            TextButton(onClick = { s.enabled.clear(); act.applyCheats() }) { Text("Turn all off", color = accent) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { act.importCheats() }) { Text("Import file", color = accent) }
            TextButton(onClick = { act.exportCheats() }) { Text("Export mine", color = accent) }
        }
        for ((name, keys) in s.presets) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Preset: $name (${keys.size})", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { s.enabled.clear(); s.enabled += keys; act.applyCheats() }) { Text("Apply", color = accent) }
            TextButton(onClick = { s.presets.remove(name); s.save(); act.say2("Preset deleted") }) { Text("Delete", color = accent) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cheat button on screen", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(s.shortcutShown, { s.shortcutShown = it; s.save(); act.cheatRevision++ }, colors = SwitchDefaults.colors(checkedTrackColor = accent))
        }
        Text("Button size ${s.shortcutSize}% · opacity ${s.shortcutOpacity}%", color = Dim, fontSize = 11.sp)
        androidx.compose.material3.Slider(s.shortcutSize.toFloat(), { s.shortcutSize = it.toInt(); act.cheatRevision++ }, valueRange = 60f..160f,
            onValueChangeFinished = { s.save() }, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
        androidx.compose.material3.Slider(s.shortcutOpacity.toFloat(), { s.shortcutOpacity = it.toInt(); act.cheatRevision++ }, valueRange = 20f..100f,
            onValueChangeFinished = { s.save() }, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(Modifier.background(if (selected) accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
        .border(1.dp, if (selected) accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(50)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)) { Text(label, color = Color.White, fontSize = 12.sp) }
}

@Composable
private fun CheatCard(act: HandheldActivity, ctl: CheatController, item: CheatController.Item, accent: Color,
                      onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val s = ctl.store
    val on = item.key in s.enabled
    var value by remember(item.key) { mutableStateOf(s.values[item.key].orEmpty()) }
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = if (on) 0.09f else 0.04f), RoundedCornerShape(14.dp))
        .border(1.dp, if (on) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = if (item.valid) Color.White else Dim, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                val sub = listOfNotNull(item.group, item.formatLabel, item.setName?.takeIf { item.match == MatchKind.PROBABLE }?.let { "for $it" })
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = Dim, fontSize = 11.sp)
            }
            Text(if (item.key in s.favorites) "★" else "☆", color = if (item.key in s.favorites) Amber else Dim, fontSize = 20.sp,
                modifier = Modifier.clickable { if (item.key in s.favorites) s.favorites -= item.key else s.favorites += item.key; s.save(); act.cheatRevision++ }.padding(horizontal = 8.dp))
            Switch(on, { onToggle(it) }, enabled = item.valid && (item.valueDigits == 0 || value.isNotBlank()),
                colors = SwitchDefaults.colors(checkedTrackColor = accent))
        }
        val (badge, color) = when (item.status) {
            CheatController.Status.VERIFIED -> "Verified: you confirmed it works on this ROM" to Green
            CheatController.Status.FAILED_HERE -> "You reported it doesn't work on this ROM" to Red
            CheatController.Status.INCOMPATIBLE -> "Can't be used: ${item.invalidReason}" to Red
            CheatController.Status.CUSTOM -> "Your cheat · not verified" to Dim
            CheatController.Status.UNVERIFIED -> (if (item.match == MatchKind.PROBABLE) "Unverified · probable match" else "Unverified · from ${item.source ?: "the database"}") to Amber
        }
        Text(badge, color = color, fontSize = 11.sp)
        if (item.valueDigits > 0 && item.valid) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value, { v -> value = v.filter { it.isLetterOrDigit() }.uppercase().take(item.valueDigits) }, singleLine = true,
                label = { Text("Value (${item.valueDigits} hex digit${if (item.valueDigits > 1) "s" else ""})") }, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (value.matches(Regex("[0-9A-F]+"))) { s.values[item.key] = value; s.save(); if (on) act.applyCheats() else act.say2("Value set; switch the cheat on") }
                else act.say2("Use hex digits 0-9 and A-F")
            }) { Text("Set", color = accent) }
        }
        if (on && item.valid) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Did it work?", color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            val sha = ctl.romSha1
            TextButton(enabled = sha != null, onClick = { s.recordVerification(item.key, sha!!, true, item.code); s.save(); act.cheatRevision++ }) { Text("Yes", color = Green) }
            TextButton(enabled = sha != null, onClick = { s.recordVerification(item.key, sha!!, false, item.code); s.save(); act.cheatRevision++ }) { Text("No", color = Red) }
        }
        if (item.custom) TextButton(onClick = onEdit) { Text("Edit", color = accent) }
        Text(item.code.replace("+", " "), color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
    }
}

@Composable
private fun EditCustomDialog(act: HandheldActivity, ctl: CheatController, c: CheatStore.Custom, accent: Color, onDone: () -> Unit) {
    var name by remember { mutableStateOf(c.name) }
    var code by remember { mutableStateOf(c.code) }
    val check = if (code.isBlank()) null else CheatCodes.check(ctl.platform, code)
    AlertDialog(onDismissRequest = onDone, containerColor = PanelBg,
        title = { Text(if (c.id.isEmpty()) "Add a cheat" else "Edit cheat", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                OutlinedTextField(code, { code = it }, label = { Text("Code (one part per line or separated by +)") })
                Text(when (check) {
                    null -> "Formats: " + when (ctl.platform) {
                        com.acecore.app.core.handheld.HandheldPlatform.NDS -> "Action Replay DS"
                        com.acecore.app.core.handheld.HandheldPlatform.GBA -> "GameShark / Action Replay, CodeBreaker"
                        else -> "Game Genie, GameShark"
                    }
                    is CheatCodes.Check.Valid -> "✓ ${check.format.label}, ${check.lines} line(s)"
                    is CheatCodes.Check.Invalid -> check.reason
                }, color = if (check is CheatCodes.Check.Invalid) Red else Dim, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && check is CheatCodes.Check.Valid, onClick = {
            val s = ctl.store
            val norm = (check as CheatCodes.Check.Valid).normalized
            if (c.id.isEmpty()) s.custom += CheatStore.Custom(s.newCustomId(), name.trim(), norm)
            else s.custom.replaceAll { if (it.id == c.id) it.copy(name = name.trim(), code = norm) else it }
            act.applyCheats(false); onDone()
        }) { Text("Save", color = accent) } },
        dismissButton = {
            Row {
                if (c.id.isNotEmpty()) TextButton(onClick = {
                    ctl.store.custom.removeAll { it.id == c.id }; ctl.store.enabled -= CheatStore.customKey(c.id); act.applyCheats(false); onDone()
                }) { Text("Delete", color = Red) }
                TextButton(onClick = onDone) { Text("Cancel", color = accent) }
            }
        })
}
