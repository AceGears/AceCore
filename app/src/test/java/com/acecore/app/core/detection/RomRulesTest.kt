package com.acecore.app.core.detection

import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.config.PadButton
import com.acecore.app.core.handheld.HandheldPlatform
import com.acecore.app.core.model.CompatStatus
import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RomRulesTest {
    private data class E(val path: String, val dir: Boolean = false)

    @Test fun romFileCandidates() {
        val entries = listOf(
            E("Pokemon Emerald.gba"), E("Tetris.GB"), E("readme.txt"), E("GBA", true), E("GBA/Zelda.gbc"),
            E("a/b/c/deep.nds"), E("a/b/c/d/tooDeep.nds"), E("__MACOSX/._x.gba"), E(".hidden.gba"), E("Games.zip"),
            E("folder.gba", true),
        )
        val got = RomRules.romFileCandidates(entries, { it.path }, { it.dir }).map { it.path }
        assertEquals(listOf("Pokemon Emerald.gba", "Tetris.GB", "GBA/Zelda.gbc", "a/b/c/deep.nds", "Games.zip"), got)
    }

    @Test fun zipRomSelection() {
        assertEquals("Mario.gba", RomRules.zipRomEntry(listOf("readme.txt", "Mario.gba")))
        assertEquals("sub/Mario.nds", RomRules.zipRomEntry(listOf("sub/", "sub/Mario.nds", "__MACOSX/sub/._Mario.nds")))
        assertNull(RomRules.zipRomEntry(listOf("a.gba", "b.gba")))
        assertNull(RomRules.zipRomEntry(listOf("Game.exe", "data.win")))
        assertNull(RomRules.zipRomEntry(emptyList()))
    }

    @Test fun titleCleaning() {
        assertEquals("Pokemon - Emerald Version (USA, Europe)", RomRules.cleanTitle("Pokemon - Emerald Version (USA, Europe).gba"))
        assertEquals("Tetris (World) (Rev 1)", RomRules.cleanTitle("Tetris (World) (Rev 1).gb"))
        assertEquals("super mario land", RomRules.cleanTitle("super_mario_land.gb"))
        assertEquals("New Super Mario Bros. (USA)", RomRules.cleanTitle("roms/New Super Mario Bros. (USA).zip"))
        assertEquals("Version 1.2", RomRules.cleanTitle("Version 1.2"))
        assertEquals("Untitled ROM", RomRules.cleanTitle(".gba"))
    }

    @Test fun platformFromExtensionAndHeader() {
        assertEquals(HandheldPlatform.GBA, RomRules.platformFor("x.gba", null))
        assertEquals(HandheldPlatform.NDS, RomRules.platformFor("x.nds", null))
        assertEquals(HandheldPlatform.GBC, RomRules.platformFor("x.gbc", null))
        assertEquals(HandheldPlatform.GB, RomRules.platformFor("x.gb", null))
        assertNull(RomRules.platformFor("x.zip", null))
        val header = ByteArray(0x200)
        header[0x143] = 0xC0.toByte() // Game Boy Color only
        assertEquals(HandheldPlatform.GBC, RomRules.platformFor("x.gb", header))
        header[0x143] = 0x80.toByte() // works on both: the .gb extension decides
        assertEquals(HandheldPlatform.GB, RomRules.platformFor("x.gb", header))
        assertTrue(RomRules.needsHeader("a.gb")); assertFalse(RomRules.needsHeader("a.gba"))
    }

    private fun det(engine: Engine, confidence: Int, entry: String? = "x") =
        DetectionResult(engine = engine, confidence = confidence, entry = entry, status = CompatStatus.PLAYABLE_UNTESTED)

    @Test fun folderDecision() {
        assertTrue(RomRules.romsTakePrecedence(det(Engine.UNKNOWN, 0, null), 1))
        assertFalse(RomRules.romsTakePrecedence(det(Engine.UNKNOWN, 0, null), 0))
        // A confidently detected game that happens to contain one ROM stays a game.
        assertFalse(RomRules.romsTakePrecedence(det(Engine.RPG_MAKER_XP, 90), 1))
        assertFalse(RomRules.romsTakePrecedence(det(Engine.RPG_MAKER_MV, 100), 3))
        assertTrue(RomRules.romsTakePrecedence(det(Engine.HTML5, 35), 2))
        assertTrue(RomRules.romsTakePrecedence(det(Engine.HTML5, 60, null), 1))
        assertFalse(RomRules.romsTakePrecedence(det(Engine.NATIVE_WINDOWS, 50), 1))
        assertTrue(RomRules.romsTakePrecedence(det(Engine.NATIVE_WINDOWS, 50), 2))
        assertTrue(RomRules.outsideGameFolders("Tetris.gb", setOf("Pokemon Z")))
        assertFalse(RomRules.outsideGameFolders("pokemon z/extra/x.gba", setOf("Pokemon Z")))
        assertEquals("GBA", RomRules.parentFolderName("roms/GBA/x.gba", "Root"))
        assertEquals("Root", RomRules.parentFolderName("x.gba", "Root"))
    }

    @Test fun romDetectionRecord() {
        val d = RomRules.detection(HandheldPlatform.GBA, "Game.zip", "Game (USA).gba", "GBA/Game.zip", 10, 5) { true }
        assertEquals(Engine.GAME_BOY_ADVANCE, d.engine)
        assertEquals(EngineFamily.HANDHELD, d.engine.family)
        assertEquals("Game (USA)", d.title)
        assertEquals("Game.zip", d.entry)
        assertEquals("Game (USA).gba", d.rom?.zipEntry)
        assertEquals(CompatStatus.PLAYABLE_UNTESTED, d.status)
        assertEquals(CompatStatus.RUNTIME_NOT_BUILT, RomRules.detection(HandheldPlatform.NDS, "a.nds", null, "a.nds", 1, 0) { false }.status)
    }

    @Test fun engineMapping() {
        HandheldPlatform.entries.forEach { assertEquals(it, Engine.of(it).handheldPlatform) }
        assertNull(Engine.GODOT.handheldPlatform)
    }

    private fun zip(names: List<String>, comment: String? = null): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            names.forEach { n -> z.putNextEntry(ZipEntry(n)); if (!n.endsWith("/")) z.write(ByteArray(1000) { it.toByte() }); z.closeEntry() }
            if (comment != null) z.setComment(comment)
        }
        return bos.toByteArray()
    }

    private fun reader(b: ByteArray): (Long, Int) -> ByteArray = { pos, len ->
        b.copyOfRange(pos.toInt(), minOf(b.size, pos.toInt() + len))
    }

    @Test fun zipCentralDirectory() {
        val names = listOf("dir/", "dir/Pokémon (Europe).gba", "readme.txt")
        val b = zip(names, comment = "x".repeat(300))
        assertEquals(names, ZipListing.entryNames(b.size.toLong(), reader(b)))
        assertEquals("dir/Pokémon (Europe).gba", RomRules.zipRomEntry(ZipListing.entryNames(b.size.toLong(), reader(b))!!))
        val notZip = ByteArray(5000) { 7 }
        assertNull(ZipListing.entryNames(notZip.size.toLong(), reader(notZip)))
        assertNull(ZipListing.entryNames(3, reader(ByteArray(3))))
    }

    @Test fun handheldButtonMapDefaults() {
        val m = ButtonMap.HANDHELD_DEFAULT
        assertEquals("HH_A", m[PadButton.A]); assertEquals("HH_B", m[PadButton.B])
        assertEquals("HH_X", m[PadButton.X]); assertEquals("HH_Y", m[PadButton.Y])
        assertEquals("HH_L", m[PadButton.L1]); assertEquals("HH_R", m[PadButton.R1])
        assertEquals("NONE", m[PadButton.L2]); assertEquals("NONE", m[PadButton.R2])
        assertEquals("HH_START", m[PadButton.START]); assertEquals("HH_SELECT", m[PadButton.SELECT])
        assertEquals("HH_UP", m[PadButton.UP]); assertEquals("HH_RIGHT", m[PadButton.RIGHT])
        assertEquals(m, ButtonMap.decode(ButtonMap.HANDHELD_DEFAULT_ENCODED, ButtonMap.HANDHELD_DEFAULT))
        // A keyboard mapping (from another engine) never reaches a handheld game.
        val mixed = ButtonMap.decodeHandheld("A=Z;B=HH_A;START=ENTER")
        assertEquals("HH_A", mixed[PadButton.A]); assertEquals("HH_A", mixed[PadButton.B]); assertEquals("HH_START", mixed[PadButton.START])
        // Per-engine default, and a global keyboard mapping is ignored for handhelds but not for others.
        val global = mapOf(ConfigKey.BUTTON_MAP.id to ButtonMap.DEFAULT_ENCODED)
        assertEquals(ButtonMap.HANDHELD_DEFAULT_ENCODED, LayeredConfig(global, emptyMap(), emptyMap(), Engine.GAME_BOY_ADVANCE).string(ConfigKey.BUTTON_MAP))
        assertEquals(ButtonMap.DEFAULT_ENCODED, LayeredConfig(global, emptyMap(), emptyMap(), Engine.HTML5).string(ConfigKey.BUTTON_MAP))
        assertEquals("sharp", LayeredConfig(emptyMap(), emptyMap(), emptyMap(), Engine.NINTENDO_DS).string(ConfigKey.FILTERING))
        assertTrue(ConfigKey.HANDHELD_DS_LAYOUT.appliesTo(Engine.NINTENDO_DS)); assertFalse(ConfigKey.HANDHELD_DS_LAYOUT.appliesTo(Engine.GAME_BOY))
        assertTrue(ConfigKey.HANDHELD_GB_PALETTE.appliesTo(Engine.GAME_BOY_COLOR)); assertFalse(ConfigKey.HANDHELD_GB_PALETTE.appliesTo(Engine.GAME_BOY_ADVANCE))
        assertTrue(ConfigKey.BUTTON_MAP.appliesTo(Engine.GAME_BOY)); assertTrue(ConfigKey.SCREEN_FIT.appliesTo(Engine.NINTENDO_DS))
        assertEquals(16, ConfigKey.HANDHELD_GB_PALETTE.choices.size)
        assertEquals("GBC Reverse →B", ConfigKey.HANDHELD_GB_PALETTE.choices.last().first)
    }

    @Test fun coverNextToRomOrInArtFolder() {
        val imgs = listOf("GBA/Pokemon Emerald.PNG", "GBA/boxart/Pokemon FireRed.jpg", "covers/Zelda.webp", "GBA/other.png")
        assertEquals("GBA/Pokemon Emerald.PNG", RomRules.coverFor("GBA/Pokemon Emerald.gba", imgs))
        assertEquals("GBA/boxart/Pokemon FireRed.jpg", RomRules.coverFor("GBA/Pokemon FireRed.zip", imgs))
        assertEquals("covers/Zelda.webp", RomRules.coverFor("GB/Zelda.gb", imgs))
        assertEquals(null, RomRules.coverFor("GBA/Pokemon Ruby.gba", imgs))   // no guessing by similar names
    }
}
