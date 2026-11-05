package com.acecore.app.core.handheld.pokemon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Generation 3 party/bag logic against a synthetic game memory built to the pret structure layouts (no commercial
 * ROM is used or needed). Addresses are FireRed's (USA 1.0) from [Gen3Game].
 */
class Gen3ToolsTest {
    private val game = Gen3Game.FIRERED

    /** EWRAM, IWRAM and the parts of "ROM" the tools read. */
    private class Fake : Gen3Memory, Gen3Rom {
        val ewram = ByteArray(0x40000)
        val iwram = ByteArray(0x8000)
        val rom = HashMap<Long, ByteArray>()
        var writes = 0
        private fun region(a: Long, n: Int): Pair<ByteArray, Int>? = when {
            a >= 0x02000000 && a + n <= 0x02040000 -> ewram to (a - 0x02000000).toInt()
            a >= 0x03000000 && a + n <= 0x03008000 -> iwram to (a - 0x03000000).toInt()
            else -> null
        }
        override fun read(address: Long, length: Int): ByteArray? {
            if (address >= 0x08000000) {
                rom.forEach { (base, b) -> if (address >= base && address + length <= base + b.size) return b.copyOfRange((address - base).toInt(), (address - base).toInt() + length) }
                return null
            }
            val (b, o) = region(address, length) ?: return null
            return b.copyOfRange(o, o + length)
        }
        override fun write(address: Long, data: ByteArray): Boolean {
            val (b, o) = region(address, data.size) ?: return false
            data.copyInto(b, o); writes++; return true
        }
        fun put32(a: Long, v: Long) { write(a, byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
        fun put16(a: Long, v: Int) { write(a, byteArrayOf(v.toByte(), (v shr 8).toByte())) }
    }

    private val f = Fake()
    private val tools = Gen3Tools(game, f, f)

    // Species 1 gets Garchomp's base stats (108/130/95/102/80/85) for the Bulbapedia stat example; growth rate 0.
    private fun romTables() {
        val species = ByteArray(game.speciesCount * 28)
        intArrayOf(108, 130, 95, 102, 80, 85).forEachIndexed { i, v -> species[28 + i] = v.toByte() }
        species[28 + 19] = 0
        f.rom[game.speciesInfo] = species
        val exp = ByteArray(8 * 101 * 4)
        for (l in 0..100) { val v = l.toLong() * l * l; for (k in 0..3) exp[l * 4 + k] = (v shr (8 * k)).toByte() } // growth 0: L^3
        f.rom[game.expTables] = exp
        val names = ByteArray(game.speciesCount * 11) { 0xFF.toByte() }
        "TESTMON".forEachIndexed { i, c -> names[11 + i] = (0xBB + (c - 'A')).toByte() }
        f.rom[game.speciesNames] = names
        val moves = ByteArray(game.moveCount * 12)
        moves[33 * 12 + 4] = 35 // move 33: 35 PP
        f.rom[game.battleMoves] = moves
        val items = ByteArray(game.itemCount * 44)
        fun item(id: Int, name: String, pocket: Int) {
            name.forEachIndexed { i, c -> items[id * 44 + i] = when (c) { in 'A'..'Z' -> 0xBB + (c - 'A'); in '0'..'9' -> 0xA1 + (c - '0'); else -> 0 }.toByte() }
            items[id * 44 + name.length] = 0xFF.toByte()
            items[id * 44 + 26] = pocket.toByte()
        }
        val fam = game.family
        item(1, "MASTERBALL", fam.ballsPocket); item(4, "POKEBALL", fam.ballsPocket)
        item(13, "POTION", fam.itemsPocket); item(14, "ANTIDOTE", fam.itemsPocket); item(15, "BURNHEAL", fam.itemsPocket)
        item(52, "????????", fam.itemsPocket)
        item(259, "BICYCLE", fam.keyPocket)
        item(289, "TM01", fam.tmPocket); item(339, "HM01", fam.tmPocket)
        item(133, "CHERIBERRY", fam.berriesPocket)
        f.rom[game.items] = items
    }

    /** A party Pokémon whose secure data decrypts to all zeros, then filled in. */
    private fun mon(personality: Long, otId: Long, build: (Gen3Mon) -> Unit): Gen3Mon {
        val raw = ByteArray(100)
        val key = personality xor otId
        for (i in 0..3) { raw[i] = (personality shr (8 * i)).toByte(); raw[4 + i] = (otId shr (8 * i)).toByte() }
        for (w in 0 until 12) for (i in 0..3) raw[32 + w * 4 + i] = (key shr (8 * i)).toByte()
        return Gen3Mon(raw).also(build)
    }

    private fun garchompLike(): Gen3Mon = mon(3L + 25 * 1000, 0x12345678) { m ->
        m.species = 1; m.level = 78; m.experience = 78L * 78 * 78; m.friendship = 70
        intArrayOf(24, 12, 30, 5, 16, 23).forEachIndexed { i, v -> m.setIv(i, v) }
        intArrayOf(74, 190, 91, 23, 48, 84).forEachIndexed { i, v -> m.setEv(i, v) }
        m.setMove(0, 33); m.setPp(0, 10)
        m.setStat(0, 200); m.hp = 150
    }

    private fun putParty(vararg mons: Gen3Mon) {
        f.write(game.partyCount, byteArrayOf(mons.size.toByte()))
        mons.forEachIndexed { i, m -> f.write(game.party + i * 100, m.encode()) }
    }

    @Test fun encryptedPartyRoundTrips() {
        romTables()
        val m = garchompLike()
        putParty(m)
        val p = tools.party()!!
        assertEquals(1, p.size)
        assertTrue(p[0].valid)
        assertEquals(1, p[0].species)
        assertEquals(24, p[0].iv(0)); assertEquals(23, p[0].iv(5)); assertEquals(190, p[0].ev(1))
        assertEquals("Adamant", NATURES[p[0].nature])
        assertEquals("Testmon".uppercase(), tools.speciesName(1))
    }

    @Test fun statsMatchTheGamesFormula() {
        // Bulbapedia's worked example (same formula in Generation 3): Lv. 78 Adamant Garchomp → 289/278/193/171/135/171.
        romTables()
        val m = garchompLike()
        tools.recalculate(m)
        assertEquals(listOf(289, 278, 193, 171, 135, 171), (0 until 6).map { m.stat(it) })
        assertEquals(150 + 289 - 200, m.hp) // damage taken is kept
    }

    @Test fun editsAreWrittenAndStayValid() {
        romTables()
        putParty(garchompLike())
        tools.setIvs(0, 31).getOrThrow()
        tools.maxFriendship(0).getOrThrow()
        tools.heal(0).getOrThrow()
        val m = tools.party()!![0]
        assertTrue((0 until 6).all { m.iv(it) == 31 })
        assertEquals(255, m.friendship)
        assertEquals(m.maxHp, m.hp)
        assertEquals(35, m.pp(0))
        assertEquals(190, m.ev(1)) // untouched
        tools.setLevel(0, 100).getOrThrow()
        val l = tools.party()!![0]
        assertEquals(100, l.level); assertEquals(1_000_000L, l.experience)
        assertTrue(l.valid)
        tools.setEvs(0, intArrayOf(6, 252, 0, 252, 0, 0)).getOrThrow()
        assertEquals(252, tools.party()!![0].ev(3))
        assertTrue(tools.setEvs(0, intArrayOf(252, 252, 252, 0, 0, 0)).isFailure) // over 510
    }

    @Test fun shinyKeepsNatureGenderAndData() {
        romTables()
        val before = garchompLike()
        putParty(before)
        assertFalse(before.shiny)
        tools.makeShiny(0).getOrThrow()
        val m = tools.party()!![0]
        assertTrue(m.shiny)
        assertEquals(before.nature, m.nature)
        assertEquals(before.personality and 0xFF, m.personality and 0xFF)
        assertEquals(listOf(24, 12, 30, 5, 16, 23), (0 until 6).map { m.iv(it) })
        assertEquals(190, m.ev(1)); assertEquals(1, m.species); assertTrue(m.valid)
    }

    @Test fun refusesWhenTheMemoryDoesNotMatch() {
        romTables()
        val bytes = garchompLike().encode()
        bytes[40] = (bytes[40] + 1).toByte() // corrupt the encrypted data: checksum fails
        f.write(game.partyCount, byteArrayOf(1)); f.write(game.party, bytes)
        assertNull(tools.party())
        val writes = f.writes
        assertTrue(tools.setIvs(0, 31).isFailure)
        assertEquals(writes, f.writes)
        f.write(game.partyCount, byteArrayOf(0))
        assertNull(tools.party()) // title screen: no party
    }

    // ---- bag: FireRed keeps gBagPockets in RAM and XORs quantities with the save's key

    private val key = 0xBEEF
    private val sb2 = 0x02024588L
    private val pocketBase = 0x02030000L

    private fun bag(vararg contents: Pair<Int, List<Pair<Int, Int>>>) {
        f.put32(game.saveBlock2Ptr!!, sb2); f.put32(sb2 + game.keyOffset, key.toLong())
        val caps = mapOf(1 to 42, 2 to 30, 3 to 13, 4 to 58, 5 to 43)
        for (p in 1..5) {
            val slots = pocketBase + p * 0x200
            f.put32(game.bagPockets + (p - 1) * 8, slots); f.write(game.bagPockets + (p - 1) * 8 + 4, byteArrayOf(caps[p]!!.toByte()))
        }
        for ((p, list) in contents) list.forEachIndexed { s, (id, q) ->
            f.put16(pocketBase + p * 0x200 + s * 4, id); f.put16(pocketBase + p * 0x200 + s * 4 + 2, q xor key)
        }
    }

    @Test fun bagReadsAndChangesQuantities() {
        romTables()
        val fam = game.family
        bag(fam.itemsPocket to listOf(13 to 5, 14 to 2), fam.keyPocket to listOf(259 to 1))
        val items = tools.bag().getOrThrow()
        assertEquals(listOf("POTION" to 5, "ANTIDOTE" to 2, "BICYCLE" to 1), items.map { it.name to it.quantity })
        tools.setQuantity(items[0], 999).getOrThrow()
        assertEquals(999, tools.bag().getOrThrow()[0].quantity)
        assertTrue(tools.setQuantity(items[2], 5).isFailure) // key item
        tools.setQuantity(tools.bag().getOrThrow()[0], 0).getOrThrow() // remove: the list closes up
        assertEquals(listOf("ANTIDOTE" to 2, "BICYCLE" to 1), tools.bag().getOrThrow().map { it.name to it.quantity })
    }

    @Test fun addAllItemsSkipsKeyItemsHmsAndPlaceholders() {
        romTables()
        val fam = game.family
        bag(fam.itemsPocket to listOf(13 to 50))
        val msg = tools.addAllItems(setOf(fam.itemsPocket, fam.ballsPocket, fam.tmPocket, fam.berriesPocket), 10).getOrThrow()
        val names = tools.bag().getOrThrow().associate { it.name to it.quantity }
        assertEquals(50, names["POTION"]) // never lowered
        assertEquals(10, names["ANTIDOTE"]); assertEquals(10, names["MASTERBALL"]); assertEquals(10, names["TM01"]); assertEquals(10, names["CHERIBERRY"])
        assertFalse("HM01" in names); assertFalse("BICYCLE" in names); assertFalse(names.keys.any { it.startsWith("?") })
        assertTrue(msg, msg.startsWith("Added 6"))
        assertTrue(tools.addAllItems(setOf(fam.keyPocket), 1).isFailure)
    }

    @Test fun addAllItemsStopsWhenAPocketIsFull() {
        romTables()
        val fam = game.family
        // Balls pocket (13 slots) already full of Poké Balls stacks except none free.
        bag(fam.ballsPocket to List(13) { 4 to 1 })
        val msg = tools.addAllItems(setOf(fam.ballsPocket), 5).getOrThrow()
        assertTrue(msg, msg.contains("didn't fit"))
    }

    @Test fun gameTableIsConsistent() {
        assertEquals(Gen3Game.entries.size, Gen3Game.entries.map { it.sha1 }.toSet().size)
        assertEquals(Gen3Game.EMERALD, Gen3Game.bySha1("F3AE088181BF583E55DAF962A92BB46F4F1D07B7"))
        assertNull(Gen3Game.bySha1("0000000000000000000000000000000000000000"))
        for (g in Gen3Game.entries) {
            assertTrue(g.party in 0x02000000L until 0x03008000L)
            assertTrue(listOf(g.expTables, g.speciesInfo, g.speciesNames, g.battleMoves, g.items).all { it in 0x08000000L until 0x0A000000L })
            assertEquals(g.saveBlock2Ptr == null, g.family == Gen3Family.RS)
        }
    }

    @Test fun shinyPersonalitySearch() {
        for (p in listOf(0L, 3L, 0xDEADBEEFL, 0x12345678L)) {
            val s = Gen3Tools.shinyPersonality(0xA1B2C3D4L, p)!!
            assertTrue(Gen3Mon.isShiny(0xA1B2C3D4L, s)); assertEquals(p % 25, s % 25); assertEquals(p and 0xFF, s and 0xFF)
        }
        assertArrayEquals(intArrayOf(0, 1, 2, 3), Gen3Mon.ORDER[0])
    }
}
