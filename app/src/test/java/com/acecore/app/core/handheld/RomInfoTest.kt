package com.acecore.app.core.handheld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomInfoTest {
    private fun gba(code: String = "BPEE", title: String = "POKEMON EMER", version: Int = 0): ByteArray {
        val b = ByteArray(0x200)
        title.toByteArray().copyInto(b, 0xA0)
        code.toByteArray().copyInto(b, 0xAC)
        "01".toByteArray().copyInto(b, 0xB0)
        b[0xB2] = 0x96.toByte()
        b[0xBC] = version.toByte()
        var sum = 0
        for (i in 0xA0..0xBC) sum += b[i].toInt() and 0xFF
        b[0xBD] = ((-(sum + 0x19)) and 0xFF).toByte()
        return b
    }

    private fun nds(code: String = "CPUE", title: String = "POKEMON PL"): ByteArray {
        val b = ByteArray(0x200)
        title.toByteArray().copyInto(b, 0)
        code.toByteArray().copyInto(b, 0x0C)
        "01".toByteArray().copyInto(b, 0x10)
        val crc = RomInfo.crc16Modbus(b, 0, 0x15E)
        b[0x15E] = (crc and 0xFF).toByte(); b[0x15F] = (crc shr 8).toByte()
        return b
    }

    private fun gb(title: String, cgb: Int, code: String? = null): ByteArray {
        val b = ByteArray(0x200)
        title.toByteArray().copyInto(b, 0x134)
        code?.toByteArray()?.copyInto(b, 0x13F)
        b[0x143] = cgb.toByte()
        b[0x147] = 0x13; b[0x14A] = 1; b[0x14B] = 0x01
        var x = 0
        for (i in 0x134..0x14C) x = (x - (b[i].toInt() and 0xFF) - 1) and 0xFF
        b[0x14D] = x.toByte()
        return b
    }

    @Test fun gbaHeader() {
        val h = RomInfo.parseHeader(gba(version = 1), HandheldPlatform.GBA)!!
        assertEquals(HandheldPlatform.GBA, h.platform)
        assertEquals("POKEMON EMER", h.title)
        assertEquals("BPEE", h.gameCode)
        assertEquals("USA", h.region)
        assertEquals(1, h.version)
        assertTrue(h.headerChecksumOk)
        val bad = gba().also { it[0xA3] = 'X'.code.toByte() }
        assertFalse(RomInfo.parseHeader(bad, HandheldPlatform.GBA)!!.headerChecksumOk)
    }

    @Test fun ndsHeader() {
        val h = RomInfo.parseHeader(nds(code = "CPUP"), HandheldPlatform.NDS)!!
        assertEquals("CPUP", h.gameCode); assertEquals("Europe", h.region); assertTrue(h.headerChecksumOk)
    }

    @Test fun gameBoyAndColor() {
        val dmg = RomInfo.parseHeader(gb("POKEMON RED", 0x00), HandheldPlatform.GB)!!
        assertEquals(HandheldPlatform.GB, dmg.platform); assertEquals("POKEMON RED", dmg.title); assertNull(dmg.gameCode)
        assertTrue(dmg.headerChecksumOk)
        // Colour-only cartridge in a .gb file is still Game Boy Color; dual-mode keeps the file's system.
        assertEquals(HandheldPlatform.GBC, RomInfo.parseHeader(gb("POKEMON_CRY", 0xC0, "BYTE"), HandheldPlatform.GB)!!.platform)
        assertEquals(HandheldPlatform.GB, RomInfo.parseHeader(gb("POKEMON YEL", 0x80), HandheldPlatform.GB)!!.platform)
        val crystal = RomInfo.parseHeader(gb("PM_CRYSTAL", 0xC0, "BYTE"), HandheldPlatform.GBC)!!
        assertEquals("BYTE", crystal.gameCode); assertEquals("USA", crystal.region)
    }

    @Test fun platformFromExtension() {
        assertEquals(HandheldPlatform.GBA, HandheldPlatform.byExtension("Pokemon Emerald.GBA"))
        assertEquals(HandheldPlatform.NDS, HandheldPlatform.byExtension("x.nds"))
        assertEquals(HandheldPlatform.GBC, HandheldPlatform.byExtension("x.gbc"))
        assertNull(HandheldPlatform.byExtension("x.zip"))
    }

    @Test fun hashes() {
        val h = RomInfo.hash("abc".byteInputStream())
        assertEquals("352441C2", h.crc32)
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", h.sha1)
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", h.sha256)
        assertEquals(3L, h.size)
    }
}
