package com.acecore.app.feature.cheats

import android.content.Context
import android.net.Uri
import com.acecore.app.runtime.rgss.CheatFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class CheatCategory(val label: String) {
    MONEY_ITEMS("Money & items"),
    POKEMON("Pokémon"),
    PARTY("Party & storage"),
    FIELD("Movement & encounters"),
    RPG("RPG Maker"),
}

enum class CheatKind { TOGGLE, CHOICE, ACTION }

/**
 * One cheat. [cap] is the capability the runtime helper must report for the running game (see acecore_runtime.rb);
 * cheats whose capability is missing are shown as unavailable, never as working.
 */
data class CheatDef(
    val id: String,
    val title: String,
    val description: String,
    val category: CheatCategory,
    val kind: CheatKind,
    val cap: String,
    /** For TOGGLE/CHOICE cheats: the persistent flag they set. */
    val flag: String? = null,
    val choices: List<Pair<String, String>> = emptyList(),
    val essentialsOnly: Boolean = false,
    val rpgOnly: Boolean = false,
)

object CheatCatalog {
    val ALL: List<CheatDef> = listOf(
        CheatDef("money", "Set money", "Sets the player's money (capped at the game's maximum).", CheatCategory.MONEY_ITEMS, CheatKind.ACTION, "money", essentialsOnly = true),
        CheatDef("item", "Give items", "Adds any item to the bag.", CheatCategory.MONEY_ITEMS, CheatKind.ACTION, "items", essentialsOnly = true),
        CheatDef("balls", "Give Poké Balls", "Adds Poké Balls of any kind to the bag.", CheatCategory.MONEY_ITEMS, CheatKind.ACTION, "balls", essentialsOnly = true),
        CheatDef("pkmn_level", "Level", "Sets a party Pokémon's level; stats are recalculated by the game.", CheatCategory.POKEMON, CheatKind.ACTION, "pkmn_edit", essentialsOnly = true),
        CheatDef("pkmn_exp", "Experience", "Adds or removes experience points.", CheatCategory.POKEMON, CheatKind.ACTION, "pkmn_edit", essentialsOnly = true),
        CheatDef("pkmn_iv", "IVs", "Individual values (0–31), one stat or all.", CheatCategory.POKEMON, CheatKind.ACTION, "pkmn_edit", essentialsOnly = true),
        CheatDef("pkmn_ev", "EVs", "Effort values (0–252 each, 510 total).", CheatCategory.POKEMON, CheatKind.ACTION, "pkmn_edit", essentialsOnly = true),
        CheatDef("pkmn_nature", "Nature", "Changes the nature.", CheatCategory.POKEMON, CheatKind.ACTION, "pkmn_edit", essentialsOnly = true),
        CheatDef("pkmn_ability", "Ability", "Switches between the species' abilities (including the hidden one).", CheatCategory.POKEMON, CheatKind.ACTION, "pkmn_edit", essentialsOnly = true),
        CheatDef("pkmn_happiness", "Friendship", "Sets friendship (0–255).", CheatCategory.POKEMON, CheatKind.ACTION, "pkmn_edit", essentialsOnly = true),
        CheatDef("pkmn_shiny", "Shiny", "Makes a Pokémon shiny or not.", CheatCategory.POKEMON, CheatKind.ACTION, "pkmn_edit", essentialsOnly = true),
        CheatDef("party_heal", "Heal party", "Restores HP, PP and status of the whole party.", CheatCategory.PARTY, CheatKind.ACTION, "party_heal", essentialsOnly = true),
        CheatDef("pkmn_add", "Add a Pokémon", "Adds a Pokémon to the party, or to the PC when the party is full.", CheatCategory.PARTY, CheatKind.ACTION, "pkmn_add", essentialsOnly = true),
        CheatDef("pkmn_to_pc", "Send to PC", "Moves a party Pokémon into the PC storage.", CheatCategory.PARTY, CheatKind.ACTION, "pkmn_to_pc", essentialsOnly = true),
        CheatDef(
            "walk_speed", "Movement speed", "Makes the player move faster on the map.", CheatCategory.FIELD, CheatKind.CHOICE, "walk_speed",
            flag = CheatFlags.WALK_SPEED, choices = listOf("0" to "Normal", "5" to "Fast", "6" to "Very fast"),
        ),
        CheatDef(
            "no_encounters", "No random encounters", "Stops wild Pokémon / random battles while walking.", CheatCategory.FIELD, CheatKind.TOGGLE, "no_encounters",
            flag = CheatFlags.NO_ENCOUNTERS,
        ),
        CheatDef("gold", "Set gold", "Sets the party's gold.", CheatCategory.RPG, CheatKind.ACTION, "gold", rpgOnly = true),
        CheatDef("rpg_item", "Give items", "Adds items by database ID.", CheatCategory.RPG, CheatKind.ACTION, "rpg_items", rpgOnly = true),
        CheatDef("rpg_heal", "Recover party", "Fully recovers every party member.", CheatCategory.RPG, CheatKind.ACTION, "rpg_heal", rpgOnly = true),
        CheatDef("rpg_level", "Set level", "Sets a party member's level.", CheatCategory.RPG, CheatKind.ACTION, "rpg_level", rpgOnly = true),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
    val PERSISTENT = ALL.filter { it.flag != null }
}

@Serializable
data class CheatProfile(
    /** Persistent switches (CheatFlags) applied whenever the game starts with this profile. */
    val flags: Map<String, String> = emptyMap(),
    /** Remembered values for one-shot cheats, e.g. "money" -> "999999". */
    val presets: Map<String, String> = emptyMap(),
)

@Serializable
data class CheatConfig(
    val enabled: Boolean = false,
    val activeProfile: String = DEFAULT_PROFILE,
    val profiles: Map<String, CheatProfile> = mapOf(DEFAULT_PROFILE to CheatProfile()),
    val favorites: Set<String> = emptySet(),
    /** Back up save files automatically before the first cheat of each session. */
    val autoBackup: Boolean = true,
) {
    val profile: CheatProfile get() = profiles[activeProfile] ?: CheatProfile()
    val flags: Map<String, String> get() = CheatFlags.KEYS.associateWith { profile.flags[it] ?: "0" }
    val activeCount: Int get() = flags.count { it.value != "0" }

    fun withProfile(p: CheatProfile) = copy(profiles = profiles + (activeProfile to p))

    companion object { const val DEFAULT_PROFILE = "Default" }
}

/** Per-game cheat settings, stored as JSON in AceCore's private storage (readable by the game process too). */
class CheatStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "cheats")

    fun file(gameId: Long) = File(dir, "game_$gameId.json")

    fun load(gameId: Long): CheatConfig = runCatching { json.decodeFromString(CheatConfig.serializer(), file(gameId).readText()) }
        .getOrDefault(CheatConfig())

    fun save(gameId: Long, config: CheatConfig) {
        dir.mkdirs()
        val f = file(gameId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(CheatConfig.serializer(), config))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun reset(gameId: Long) { file(gameId).delete() }

    suspend fun export(context: Context, gameId: Long, target: Uri) = withContext(Dispatchers.IO) {
        val text = json.encodeToString(CheatExport.serializer(), CheatExport(config = load(gameId)))
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) } ?: error("Could not write the file")
    }

    /** Imports profiles and favorites (merged; existing profiles with the same name are replaced). */
    suspend fun import(context: Context, gameId: Long, source: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Could not read the file")
        val incoming = json.decodeFromString(CheatExport.serializer(), text)
        require(incoming.format == 1) { "Unsupported cheat file version ${incoming.format}" }
        val cur = load(gameId)
        val profiles = incoming.config.profiles.mapValues { (_, p) ->
            p.copy(flags = p.flags.filterKeys { it in CheatFlags.KEYS }, presets = p.presets.filterKeys { CheatCatalog.byId(it) != null })
        }
        save(gameId, cur.copy(profiles = cur.profiles + profiles, favorites = cur.favorites + incoming.config.favorites.filter { CheatCatalog.byId(it) != null }))
        profiles.size
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}

@Serializable
data class CheatExport(val format: Int = 1, val app: String = "AceCore", val config: CheatConfig)
