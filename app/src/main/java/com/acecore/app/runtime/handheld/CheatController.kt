package com.acecore.app.runtime.handheld

import com.acecore.app.core.handheld.CheatCodes
import com.acecore.app.core.handheld.CheatSetRef
import com.acecore.app.core.handheld.CheatStore
import com.acecore.app.core.handheld.HandheldDb
import com.acecore.app.core.handheld.HandheldPlatform
import com.acecore.app.core.handheld.Identification
import com.acecore.app.core.handheld.MatchKind

/**
 * Everything the cheat menu shows and applies for one game: the cheat sets matching the ROM (exact first; probable
 * ones only after the user accepted a warning), the user's own cheats, and their state from [CheatStore].
 */
class CheatController(
    val platform: HandheldPlatform,
    val store: CheatStore,
    private val db: HandheldDb?,
    val identification: Identification?,
    val romSha1: String?,
    gameTitle: String,
) {
    enum class Status { VERIFIED, FAILED_HERE, UNVERIFIED, CUSTOM, INCOMPATIBLE }

    data class Item(
        val key: String,
        val name: String,
        val group: String?,
        val code: String,
        val category: String,
        val required: Boolean,
        val custom: Boolean,
        val setName: String?,
        val match: MatchKind?,
        /** Hex digits the user must choose (modifier codes), 0 = none. */
        val valueDigits: Int,
        val check: CheatCodes.Check,
        val status: Status,
        val source: String?,
    ) {
        val valid get() = check is CheatCodes.Check.Valid
        val formatLabel get() = (check as? CheatCodes.Check.Valid)?.format?.label
        val invalidReason get() = (check as? CheatCodes.Check.Invalid)?.reason
    }

    val pokemonGame: Boolean = Regex("pok[eé]mon|pocket monsters", RegexOption.IGNORE_CASE).containsMatchIn(
        gameTitle + " " + (identification?.release?.name ?: ""))

    /** Exact sets and probable sets for this ROM. Probable sets are listed, but their cheats only once accepted. */
    val sets: List<CheatSetRef> = run {
        val d = db ?: return@run emptyList()
        val rel = identification?.release
        val list = when {
            rel != null -> d.cheatSetsFor(rel.id)
            identification?.sameGameCode?.isNotEmpty() == true ->
                identification.sameGameCode.flatMap { d.cheatSetsFor(it.id) }.map { it.copy(match = MatchKind.PROBABLE) }
            else -> d.cheatSetsByTitle(platform, HandheldDb.normalizeTitle(gameTitle))
        }
        list.distinctBy { it.id }
    }

    /** Sets whose cheats are shown: every exact set, plus probable ones the user accepted (or added from search). */
    fun activeSets(): List<CheatSetRef> = sets.filter { it.match == MatchKind.EXACT || it.id in store.acceptedSets } +
        extraSets.filter { it.id in store.acceptedSets && sets.none { s -> s.id == it.id } }

    /** Sets the user found by searching the database (always probable). */
    val extraSets = mutableListOf<CheatSetRef>()

    private val cheatCache = mutableMapOf<Long, List<com.acecore.app.core.handheld.DbCheat>>()

    fun items(): List<Item> {
        val out = mutableListOf<Item>()
        for (set in activeSets()) {
            val cheats = cheatCache.getOrPut(set.id) { db?.cheats(set.id).orEmpty() }
            cheats.forEachIndexed { i, c ->
                val key = CheatStore.dbKey(set.id, i)
                val digits = CheatCodes.placeholderDigits(c.code)
                val check = CheatCodes.check(platform, c.code)
                out += Item(
                    key, c.description, c.group, c.code, CheatCodes.categoryOf(c.description, c.group, pokemonGame),
                    c.master || CheatCodes.categoryOf(c.description, c.group, pokemonGame) == "Required", false, set.name, set.match,
                    digits, check, status(key, c.code, check, custom = false), "${set.source} (${set.licence})",
                )
            }
        }
        for (c in store.custom) {
            val key = CheatStore.customKey(c.id)
            val check = CheatCodes.check(platform, c.code)
            out += Item(key, c.name, null, c.code, "Custom cheats", false, true, null, null,
                CheatCodes.placeholderDigits(c.code), check, status(key, c.code, check, custom = true), null)
        }
        return out
    }

    private fun status(key: String, code: String, check: CheatCodes.Check, custom: Boolean): Status = when {
        check is CheatCodes.Check.Invalid -> Status.INCOMPATIBLE
        else -> store.verification(key, romSha1, code)?.let { if (it.works) Status.VERIFIED else Status.FAILED_HERE }
            ?: if (custom) Status.CUSTOM else Status.UNVERIFIED
    }

    /** The code that would be sent for [item]: placeholders filled with the chosen value; null if it can't be sent. */
    fun codeToSend(item: Item): String? {
        if (!item.valid) return null
        val raw = if (item.valueDigits > 0) {
            val v = store.values[item.key] ?: return null
            CheatCodes.fill(item.code, v)
        } else item.code
        return (CheatCodes.check(platform, raw) as? CheatCodes.Check.Valid)?.normalized
    }

    /** Codes of every enabled, valid cheat (in menu order), for the emulator. */
    fun activeCodes(all: List<Item> = items()): List<String> =
        all.filter { it.key in store.enabled }.mapNotNull { codeToSend(it) }

    /** A set's cheats need its master/"must be on" code and the user hasn't enabled it. */
    fun missingRequired(all: List<Item>): List<Item> {
        val enabledSets = all.filter { it.key in store.enabled && !it.custom && !it.required }.map { it.setName }.toSet()
        return all.filter { it.required && it.setName in enabledSets && it.key !in store.enabled && it.valid }
    }

    /** Game Genie codes change the ROM in mGBA; switching one off takes effect only after the game restarts. */
    fun needsRestartToRemove(item: Item): Boolean =
        (item.check as? CheatCodes.Check.Valid)?.format == CheatCodes.Format.GAME_GENIE

    fun searchDatabase(text: String): List<CheatSetRef> = db?.searchCheatSets(platform, text).orEmpty()
}
