package com.acecore.app.runtime.rgss

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One Pokémon as reported by the runtime helper. Stats order: HP, Atk, Def, SpA, SpD, Spe. */
data class PartyMember(
    val index: Int, val name: String, val species: String, val level: Int, val nature: String, val ability: String,
    val happiness: Int?, val ivs: List<Int>, val evs: List<Int>, val shiny: Boolean, val egg: Boolean,
)

/** What the running game supports right now, as probed by the helper (see acecore_runtime.rb). */
data class GameCheatState(
    val kind: String,
    val essentialsVersion: String?,
    val caps: Set<String>,
    val flags: Map<String, String>,
    val money: Long?,
    val moneyMax: Long?,
    val gold: Long?,
    val party: List<PartyMember>,
    val actors: List<Pair<String, Int>>,
) {
    fun has(cap: String) = cap in caps

    companion object {
        fun parse(text: String): GameCheatState {
            val map = LinkedHashMap<String, String>()
            text.lines().forEach { l -> val i = l.indexOf('='); if (i > 0) map[l.substring(0, i)] = l.substring(i + 1) }
            val party = map.keys.filter { it.startsWith("pkmn") }.sortedBy { it.removePrefix("pkmn").toIntOrNull() ?: 0 }.mapNotNull { k ->
                val f = map.getValue(k).split('\t')
                if (f.size < 10) return@mapNotNull null
                PartyMember(
                    index = k.removePrefix("pkmn").toIntOrNull() ?: 0, name = f[0], species = f[1], level = f[2].toIntOrNull() ?: 0,
                    nature = f[3], ability = f[4], happiness = f[5].toIntOrNull(),
                    ivs = f[6].split(',').map { it.toIntOrNull() ?: 0 }, evs = f[7].split(',').map { it.toIntOrNull() ?: 0 },
                    shiny = f[8] == "1", egg = f[9] == "1",
                )
            }
            val actors = map.keys.filter { it.startsWith("actor") }.sortedBy { it.removePrefix("actor").toIntOrNull() ?: 0 }.map { k ->
                val f = map.getValue(k).split('\t')
                (f.getOrNull(0) ?: "?") to (f.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            return GameCheatState(
                kind = map["kind"] ?: "",
                essentialsVersion = map["essentials_version"],
                caps = map["caps"].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                flags = map["flags"].orEmpty().split(',').mapNotNull { p -> p.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap(),
                money = map["money"]?.toLongOrNull(), moneyMax = map["money_max"]?.toLongOrNull(), gold = map["gold"]?.toLongOrNull(),
                party = party, actors = actors,
            )
        }
    }
}

data class CheatResult(val ok: Boolean, val message: String)

/**
 * File-based channel to the runtime helper running inside the game (same process, Ruby thread). AceCore writes
 * commands to `cmd`; the helper executes them between frames and answers in `result` and `state`.
 */
class CheatChannel(private val dir: File) {
    private val lock = Mutex()
    private var nextId = 1

    suspend fun send(vararg parts: String, timeoutMs: Long = 4000): CheatResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val id = nextId++
            val result = File(dir, "result")
            result.delete()
            val line = (listOf(id.toString()) + parts.map { it.replace('\t', ' ').replace('\n', ' ') }).joinToString("\t") + "\n"
            val tmp = File(dir, "cmd.tmp")
            tmp.writeText(line)
            if (!tmp.renameTo(File(dir, "cmd"))) File(dir, "cmd").writeText(line)
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                if (result.exists()) {
                    val text = runCatching { result.readText() }.getOrNull()
                    val mine = text?.lines()?.firstOrNull { it.startsWith("$id\t") }
                    if (mine != null) {
                        result.delete()
                        val f = mine.split('\t', limit = 3)
                        return@withContext CheatResult(f.getOrNull(1) == "ok", f.getOrNull(2).orEmpty())
                    }
                }
                delay(60)
            }
            File(dir, "cmd").delete()
            CheatResult(false, "The game didn't respond. It may be paused on a loading screen; try again in a moment.")
        }
    }

    suspend fun state(): GameCheatState? = withContext(Dispatchers.IO) {
        runCatching { GameCheatState.parse(File(dir, "state").readText()) }.getOrNull()
    }

    /** Tab-separated list written by a listing command ("items", "natures", "species"). */
    suspend fun list(name: String): List<List<String>> = withContext(Dispatchers.IO) {
        runCatching { File(dir, name).readLines().filter { it.isNotBlank() }.map { it.split('\t') } }.getOrDefault(emptyList())
    }
}
