package com.acecore.app.core.handheld.pokemon

/** The emulated GBA's memory (reads and writes go through the emulator, between frames). */
interface Gen3Memory {
    fun read(address: Long, length: Int): ByteArray?
    fun write(address: Long, data: ByteArray): Boolean
}

/** The game's ROM (address 0x08000000 = file offset 0). */
fun interface Gen3Rom {
    fun read(address: Long, length: Int): ByteArray?
}

private fun ByteArray.u8(o: Int) = this[o].toInt() and 0xFF
private fun ByteArray.u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
private fun ByteArray.u32(o: Int): Long = (u16(o).toLong() or (u16(o + 2).toLong() shl 16))
private fun ByteArray.put16(o: Int, v: Int) { this[o] = v.toByte(); this[o + 1] = (v shr 8).toByte() }
private fun ByteArray.put32(o: Int, v: Long) { put16(o, (v and 0xFFFF).toInt()); put16(o + 2, ((v shr 16) and 0xFFFF).toInt()) }

/** Generation 3 English text (pret charmap.txt). */
object Gen3Text {
    fun decode(b: ByteArray, from: Int = 0, max: Int = b.size - from): String = buildString {
        for (i in from until minOf(b.size, from + max)) {
            val c = b.u8(i)
            if (c == 0xFF) break
            append(
                when (c) {
                    0x00 -> ' '; in 0xA1..0xAA -> '0' + (c - 0xA1); 0xAB -> '!'; 0xAC -> '?'; 0xAD -> '.'; 0xAE -> '-'
                    0xB0 -> '…'; 0xB1 -> '“'; 0xB2 -> '”'; 0xB3 -> '‘'; 0xB4 -> '\''; 0xB5 -> '♂'; 0xB6 -> '♀'; 0xB8 -> ','; 0xBA -> '/'
                    in 0xBB..0xD4 -> 'A' + (c - 0xBB); in 0xD5..0xEE -> 'a' + (c - 0xD5); 0xF0 -> ':'; 0x1B -> 'é'; 0x2D -> '&'; 0x2E -> '+'
                    0x35 -> '='; 0x36 -> ';'; 0x5B -> '%'; 0x5C -> '('; 0x5D -> ')'; 0x85 -> '<'; 0x86 -> '>'
                    else -> '·'
                },
            )
        }
    }.trim()
}

/** Nature names, in the games' order (personality % 25). */
val NATURES = listOf(
    "Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile", "Relaxed", "Impish", "Lax", "Timid", "Hasty", "Serious",
    "Jolly", "Naive", "Modest", "Mild", "Quiet", "Bashful", "Rash", "Calm", "Gentle", "Sassy", "Careful", "Quirky",
)

/**
 * One party Pokémon (struct Pokemon: 80-byte BoxPokemon + 20 bytes of party data). The 48-byte "secure" part holds
 * four 12-byte substructures (growth, attacks, EVs, misc) in an order chosen by personality % 24, each 32-bit word
 * XORed with personality ^ otId, with a 16-bit checksum of the decrypted words (pret src/pokemon.c).
 */
class Gen3Mon(val raw: ByteArray) {
    init { require(raw.size == SIZE) }

    val personality get() = raw.u32(0)
    val otId get() = raw.u32(4)
    val checksum get() = raw.u16(28)
    private var dec: ByteArray = decrypt()

    private fun key() = personality xor otId
    private fun decrypt(): ByteArray {
        val k = key(); val out = ByteArray(48)
        for (i in 0 until 12) out.put32(i * 4, raw.u32(32 + i * 4) xor k)
        return out
    }

    /** Byte offset of substructure [type] (0 growth, 1 attacks, 2 EVs, 3 misc) inside the decrypted block. */
    private fun sub(type: Int): Int = ORDER[(personality % 24).toInt()][type] * 12

    fun computeChecksum(): Int { var s = 0; for (i in 0 until 24) s += dec.u16(i * 2); return s and 0xFFFF }
    val valid get() = computeChecksum() == checksum && species in 1 until 412 && level in 1..100

    var species: Int
        get() = dec.u16(sub(0))
        set(v) = dec.put16(sub(0), v)
    fun setMove(i: Int, move: Int) = dec.put16(sub(1) + i * 2, move)
    var heldItem: Int
        get() = dec.u16(sub(0) + 2)
        set(v) = dec.put16(sub(0) + 2, v)
    var experience: Long
        get() = dec.u32(sub(0) + 4)
        set(v) = dec.put32(sub(0) + 4, v)
    val ppBonuses get() = dec.u8(sub(0) + 8)
    var friendship: Int
        get() = dec.u8(sub(0) + 9)
        set(v) { dec[sub(0) + 9] = v.coerceIn(0, 255).toByte() }
    fun move(i: Int) = dec.u16(sub(1) + i * 2)
    fun pp(i: Int) = dec.u8(sub(1) + 8 + i)
    fun setPp(i: Int, v: Int) { dec[sub(1) + 8 + i] = v.coerceIn(0, 255).toByte() }
    /** EVs: HP, Attack, Defense, Speed, Sp. Atk, Sp. Def. */
    fun ev(i: Int) = dec.u8(sub(2) + i)
    fun setEv(i: Int, v: Int) { dec[sub(2) + i] = v.coerceIn(0, 255).toByte() }
    private var ivWord: Long
        get() = dec.u32(sub(3) + 4)
        set(v) = dec.put32(sub(3) + 4, v)
    /** IVs: HP, Attack, Defense, Speed, Sp. Atk, Sp. Def (0–31). */
    fun iv(i: Int) = ((ivWord shr (i * 5)) and 31).toInt()
    fun setIv(i: Int, v: Int) { ivWord = (ivWord and (31L shl (i * 5)).inv()) or (v.coerceIn(0, 31).toLong() shl (i * 5)) }
    val isEgg get() = (ivWord shr 30) and 1L == 1L
    val nature get() = (personality % 25).toInt()
    val shiny get() = isShiny(otId, personality)

    var level: Int
        get() = raw.u8(84)
        set(v) { raw[84] = v.toByte() }
    var hp: Int
        get() = raw.u16(86)
        set(v) = raw.put16(86, v)
    val maxHp get() = raw.u16(88)
    /** Stats: max HP, Attack, Defense, Speed, Sp. Atk, Sp. Def (party data). */
    fun stat(i: Int) = raw.u16(88 + i * 2)
    fun setStat(i: Int, v: Int) = raw.put16(88 + i * 2, v)
    var status: Long
        get() = raw.u32(80)
        set(v) = raw.put32(80, v)

    /** A new personality (keeps the encrypted data consistent: re-ordered, re-keyed, new checksum on [encode]). */
    fun changePersonality(p: Long) {
        val parts = (0 until 4).map { t -> dec.copyOfRange(sub(t), sub(t) + 12) }
        raw.put32(0, p)
        val next = ByteArray(48)
        parts.forEachIndexed { t, bytes -> bytes.copyInto(next, sub(t)) }
        dec = next
    }

    /** Writes the decrypted data back (checksum, encryption) and returns the 100 bytes. */
    fun encode(): ByteArray {
        raw.put16(28, computeChecksum())
        val k = key()
        for (i in 0 until 12) raw.put32(32 + i * 4, dec.u32(i * 4) xor k)
        return raw.copyOf()
    }

    companion object {
        const val SIZE = 100
        /** personality % 24 → slot of each substructure type (pret SUBSTRUCT_CASE table). */
        val ORDER = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(0, 1, 3, 2), intArrayOf(0, 2, 1, 3), intArrayOf(0, 3, 1, 2), intArrayOf(0, 2, 3, 1), intArrayOf(0, 3, 2, 1),
            intArrayOf(1, 0, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(2, 0, 1, 3), intArrayOf(3, 0, 1, 2), intArrayOf(2, 0, 3, 1), intArrayOf(3, 0, 2, 1),
            intArrayOf(1, 2, 0, 3), intArrayOf(1, 3, 0, 2), intArrayOf(2, 1, 0, 3), intArrayOf(3, 1, 0, 2), intArrayOf(2, 3, 0, 1), intArrayOf(3, 2, 0, 1),
            intArrayOf(1, 2, 3, 0), intArrayOf(1, 3, 2, 0), intArrayOf(2, 1, 3, 0), intArrayOf(3, 1, 2, 0), intArrayOf(2, 3, 1, 0), intArrayOf(3, 2, 1, 0),
        )
        fun isShiny(otId: Long, p: Long): Boolean =
            ((otId shr 16) xor (otId and 0xFFFF) xor (p shr 16) xor (p and 0xFFFF)) < 8

        /** Nature effect on stat index 1–5 (Atk, Def, Spe, SpA, SpD): +1, -1 or 0 (pret gNatureStatTable). */
        fun natureEffect(nature: Int, stat: Int): Int {
            val up = nature / 5; val down = nature % 5
            if (up == down || stat !in 1..5) return 0
            return when (stat - 1) { up -> 1; down -> -1; else -> 0 }
        }
    }
}

/** An item in the bag. */
data class Gen3BagItem(val pocket: Int, val slot: Int, val item: Int, val name: String, val quantity: Int)

/** Item data from the ROM (name and pocket). */
data class Gen3ItemInfo(val id: Int, val name: String, val pocket: Int)

/**
 * Party and bag tools for one of the [Gen3Game]s. Everything is checked before anything is written: the party must
 * decode with valid checksums, bag pointers must point into the game's RAM and every slot must hold a real item with a
 * quantity within the game's limit. If anything doesn't match (another game state, a modified game) the tools refuse.
 */
class Gen3Tools(val game: Gen3Game, private val mem: Gen3Memory, private val rom: Gen3Rom) {
    private val fam = game.family

    // ---------------------------------------------------------------- ROM tables

    private val speciesNames: List<String> by lazy {
        val b = rom.read(game.speciesNames, game.speciesCount * 11) ?: ByteArray(0)
        List(game.speciesCount) { i -> if (b.size >= (i + 1) * 11) Gen3Text.decode(b, i * 11, 11) else "#$i" }
    }
    private val baseStats: ByteArray by lazy { rom.read(game.speciesInfo, game.speciesCount * 28) ?: ByteArray(0) }
    private val expTables: ByteArray by lazy { rom.read(game.expTables, 8 * 101 * 4) ?: ByteArray(0) }
    private val movePp: ByteArray by lazy { rom.read(game.battleMoves, game.moveCount * 12) ?: ByteArray(0) }
    val itemInfo: List<Gen3ItemInfo> by lazy {
        val b = rom.read(game.items, game.itemCount * 44) ?: ByteArray(0)
        List(game.itemCount) { i -> if (b.size < (i + 1) * 44) Gen3ItemInfo(i, "", 0) else Gen3ItemInfo(i, Gen3Text.decode(b, i * 44, 14), b.u8(i * 44 + 26)) }
    }

    fun speciesName(s: Int) = speciesNames.getOrNull(s) ?: "#$s"
    fun itemName(id: Int) = itemInfo.getOrNull(id)?.name ?: "#$id"
    private fun growthRate(species: Int) = baseStats.u8(species * 28 + 19)
    fun expFor(species: Int, level: Int): Long = expTables.u32((growthRate(species) * 101 + level) * 4)

    // ---------------------------------------------------------------- party

    fun partyCount(): Int? = mem.read(game.partyCount, 1)?.u8(0)

    /** The party, or null when the game's memory doesn't hold a valid party (title screen, intro, unsupported state). */
    fun party(): List<Gen3Mon>? {
        val n = partyCount() ?: return null
        if (n !in 1..6) return null
        val b = mem.read(game.party, Gen3Mon.SIZE * n) ?: return null
        if (b.size < Gen3Mon.SIZE * n) return null
        val mons = List(n) { Gen3Mon(b.copyOfRange(it * Gen3Mon.SIZE, (it + 1) * Gen3Mon.SIZE)) }
        return mons.takeIf { l -> l.all { it.valid } }
    }

    /** Stats from base stats, IVs, EVs, level and nature (pret CalculateMonStats); HP keeps the damage taken. */
    fun recalculate(m: Gen3Mon) {
        val sp = m.species
        val base = IntArray(6) { baseStats.u8(sp * 28 + it) }
        val l = m.level
        val oldMax = m.maxHp
        val newMax = if (sp == SHEDINJA) 1 else ((2 * base[0] + m.iv(0) + m.ev(0) / 4) * l) / 100 + l + 10
        m.setStat(0, newMax)
        for (i in 1..5) {
            var n = ((2 * base[i] + m.iv(i) + m.ev(i) / 4) * l) / 100 + 5
            n = when (Gen3Mon.natureEffect(m.nature, i)) { 1 -> n * 110 / 100; -1 -> n * 90 / 100; else -> n }
            m.setStat(i, n)
        }
        m.hp = when {
            sp == SHEDINJA -> if (m.hp != 0) 1 else 0
            m.hp == 0 -> 0
            else -> (m.hp + newMax - oldMax).coerceIn(1, newMax)
        }
    }

    fun maxPp(m: Gen3Mon, slot: Int): Int {
        val move = m.move(slot)
        if (move == 0 || move >= game.moveCount) return 0
        val base = movePp.u8(move * 12 + 4)
        val bonus = (m.ppBonuses shr (2 * slot)) and 3
        return base + (base * 20 * bonus) / 100
    }

    /** Applies [edit] to party slot [index] and writes it back, or explains why it can't. */
    fun editMon(index: Int, edit: (Gen3Mon) -> Unit): Result<Gen3Mon> = runCatching {
        val party = party() ?: error("The party couldn't be read (open the tools while walking around in the game).")
        val m = party.getOrNull(index) ?: error("No Pokémon in slot ${index + 1}.")
        if (m.isEgg) error("That's an Egg.")
        edit(m)
        val bytes = m.encode()
        check(Gen3Mon(bytes.copyOf()).valid) { "The edited data didn't check out; nothing was written." }
        check(mem.write(game.party + index * Gen3Mon.SIZE, bytes)) { "The game's memory couldn't be written." }
        m
    }

    fun setIvs(index: Int, value: Int) = editMon(index) { m -> for (i in 0 until 6) m.setIv(i, value); recalculate(m) }

    /** EVs (each 0–255, total at most 510 as in the games). */
    fun setEvs(index: Int, evs: IntArray) = editMon(index) { m ->
        require(evs.size == 6 && evs.all { it in 0..255 } && evs.sum() <= 510) { "EVs must be 0–255 each and 510 in total." }
        for (i in 0 until 6) m.setEv(i, evs[i]); recalculate(m)
    }

    fun setLevel(index: Int, level: Int) = editMon(index) { m ->
        require(level in 1..100)
        m.experience = expFor(m.species, level); m.level = level; recalculate(m)
    }

    fun maxFriendship(index: Int) = editMon(index) { it.friendship = 255 }

    /** Full HP, no status, full PP. */
    fun heal(index: Int) = editMon(index) { m ->
        m.status = 0; m.hp = m.maxHp
        for (i in 0 until 4) if (m.move(i) != 0) m.setPp(i, maxPp(m, i))
    }

    /**
     * Makes a Pokémon shiny with a new personality that keeps its nature, gender (low byte), ability slot (bit 0) and
     * the encrypted data consistent. Not offered for Unown (its letter comes from the personality) or Wurmple (its
     * evolution does).
     */
    fun makeShiny(index: Int) = editMon(index) { m ->
        require(m.species != UNOWN && m.species != WURMPLE) { "Not for Unown or Wurmple (their personality decides their form)." }
        if (m.shiny) return@editMon
        val p = shinyPersonality(m.otId, m.personality) ?: error("No matching shiny personality found.")
        m.changePersonality(p)
    }

    // ---------------------------------------------------------------- bag

    private fun key16(): Int {
        val ptrAddr = game.saveBlock2Ptr ?: return 0
        val ptr = mem.read(ptrAddr, 4)?.u32(0) ?: error("Save data not found.")
        require(ptr in EWRAM) { "Save data not found." }
        return mem.read(ptr + game.keyOffset, 2)?.u16(0) ?: error("Save data not found.")
    }

    private data class Pocket(val number: Int, val slots: Long, val capacity: Int)

    private fun pockets(): List<Pocket> {
        val b = if (game.saveBlock2Ptr == null) rom.read(game.bagPockets, 40) else mem.read(game.bagPockets, 40)
        b ?: error("The bag couldn't be read.")
        return List(5) { i ->
            val ptr = b.u32(i * 8); val cap = b.u8(i * 8 + 4)
            require(ptr in EWRAM && cap in 1..64) { "The bag isn't set up yet (open the tools while walking around in the game)." }
            Pocket(i + 1, ptr, cap)
        }
    }

    fun capFor(pocket: Int) = if (pocket == fam.berriesPocket) fam.berryCap else fam.itemCap

    fun pocketName(p: Int) = when (p) {
        fam.itemsPocket -> "Items"; fam.keyPocket -> "Key items"; fam.ballsPocket -> "Poké Balls"
        fam.tmPocket -> "TMs & HMs"; fam.berriesPocket -> "Berries"; else -> "Pocket $p"
    }

    /** The whole bag, checked: every slot holds a real item of that pocket with a quantity within the limit. */
    fun bag(): Result<List<Gen3BagItem>> = runCatching {
        val key = key16()
        pockets().flatMap { p ->
            val b = mem.read(p.slots, p.capacity * 4) ?: error("The bag couldn't be read.")
            (0 until p.capacity).mapNotNull { s ->
                val id = b.u16(s * 4)
                if (id == 0) return@mapNotNull null
                val q = b.u16(s * 4 + 2) xor key
                val info = itemInfo.getOrNull(id) ?: error("The bag holds an unknown item ($id): this doesn't look like ${game.label}.")
                check(info.pocket == p.number && q in 1..capFor(p.number)) { "The bag doesn't match ${game.label}'s layout; nothing was changed." }
                Gen3BagItem(p.number, s, id, info.name, q)
            }
        }
    }

    private fun writeSlot(p: Pocket, slot: Int, item: Int, qty: Int, key: Int): Boolean {
        val b = ByteArray(4); b.put16(0, item); b.put16(2, (if (item == 0) 0 else qty) xor key)
        return mem.write(p.slots + slot * 4, b)
    }

    /** Sets how many of a bag item there are (0 removes it). Key items can't be changed here. */
    fun setQuantity(item: Gen3BagItem, qty: Int): Result<Unit> = runCatching {
        require(item.pocket != fam.keyPocket) { "Key items can't be changed." }
        require(qty in 0..capFor(item.pocket)) { "At most ${capFor(item.pocket)}." }
        bag().getOrThrow().firstOrNull { it.pocket == item.pocket && it.slot == item.slot && it.item == item.item }
            ?: error("The bag changed; open it again.")
        val p = pockets().first { it.number == item.pocket }
        val key = key16()
        if (qty == 0) {
            // Removing: close the gap so the game's list stays contiguous.
            val b = mem.read(p.slots, p.capacity * 4) ?: error("The bag couldn't be read.")
            val rest = (0 until p.capacity).map { b.u16(it * 4) to (b.u16(it * 4 + 2) xor key) }.filterIndexed { i, (id, _) -> i != item.slot && id != 0 }
            for (s in 0 until p.capacity) {
                val (id, q) = rest.getOrNull(s) ?: (0 to 0)
                check(writeSlot(p, s, id, q, key)) { "The game's memory couldn't be written." }
            }
        } else {
            check(writeSlot(p, item.slot, item.item, qty, key)) { "The game's memory couldn't be written." }
        }
    }

    /** Items that "add all items" would add to [pocket]: real items (named, not the "????????" placeholders). */
    fun obtainable(pocket: Int, includeHms: Boolean = false): List<Gen3ItemInfo> =
        itemInfo.filter { it.id != 0 && it.pocket == pocket && it.name.isNotBlank() && !it.name.all { c -> c == '?' } &&
            (includeHms || pocket != fam.tmPocket || !it.name.startsWith("HM")) }

    /**
     * Adds every item of the chosen pockets with [qty] each (topping up items already there, never lowering them). Key
     * items are never added. Returns what was added and what didn't fit.
     */
    fun addAllItems(pocketNumbers: Set<Int>, qty: Int, includeHms: Boolean = false): Result<String> = runCatching {
        require(fam.keyPocket !in pocketNumbers) { "Key items are never added (they unlock story events)." }
        bag().getOrThrow()
        val key = key16()
        var added = 0; var raised = 0; var full = 0
        for (p in pockets().filter { it.number in pocketNumbers }) {
            val q = qty.coerceIn(1, capFor(p.number))
            val b = mem.read(p.slots, p.capacity * 4) ?: error("The bag couldn't be read.")
            val slots = (0 until p.capacity).map { b.u16(it * 4) to (b.u16(it * 4 + 2) xor key) }.toMutableList()
            for (info in obtainable(p.number, includeHms)) {
                val at = slots.indexOfFirst { it.first == info.id }
                if (at >= 0) {
                    if (slots[at].second < q) { slots[at] = info.id to q; raised++ }
                    continue
                }
                val free = slots.indexOfFirst { it.first == 0 }
                if (free < 0) { full++; continue }
                slots[free] = info.id to q; added++
            }
            slots.forEachIndexed { s, (id, n) -> check(writeSlot(p, s, id, n, key)) { "The game's memory couldn't be written." } }
        }
        buildString {
            append("Added $added item${if (added == 1) "" else "s"}")
            if (raised > 0) append(", topped up $raised")
            if (full > 0) append("; $full didn't fit (the pocket is full)")
        }
    }

    companion object {
        val EWRAM = 0x02000000L until 0x02040000L
        const val SHEDINJA = 303
        const val UNOWN = 201
        const val WURMPLE = 265

        /**
         * A shiny personality with the same nature (p % 25), gender byte (low 8 bits) and ability bit as [p]: keeps the
         * low byte, searches the rest of the low half, sets the high half so the shiny value is below 8.
         */
        fun shinyPersonality(otId: Long, p: Long): Long? {
            val tsv = (otId shr 16) xor (otId and 0xFFFF)
            val low8 = p and 0xFF
            for (mid in 0L..255L) {
                val lo = (mid shl 8) or low8
                for (k in 0L..7L) {
                    val hi = tsv xor lo xor k
                    val cand = (hi shl 16) or lo
                    if (cand % 25 == p % 25 && Gen3Mon.isShiny(otId, cand)) return cand
                }
            }
            return null
        }
    }
}
