package com.acecore.app.runtime

import com.acecore.app.runtime.rgss.CheatFlags
import com.acecore.app.runtime.rgss.GameCheatState
import com.acecore.app.runtime.rgss.RgssRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeHelpersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun rubyStringEscapesEverythingDangerous() {
        assertEquals("\"/a b/\\\"q\\\"\\\\x\\#{y}\"", RgssRuntime.rubyString("/a b/\"q\"\\x#{y}"))
        assertEquals("\"\\n\\t\"", RgssRuntime.rubyString("\n\t"))
    }

    @Test fun helperHeaderDefinesBootConstants() {
        val h = RgssRuntime.helperHeader(File("/data/x/acecore"), 123L, 3, true, mapOf(CheatFlags.WALK_SPEED to "6"))
        assertTrue(h.contains("DIR = \"/data/x/acecore\""))
        assertTrue(h.contains("LAUNCH_AT = 123"))
        assertTrue(h.contains("RGSS = 3"))
        assertTrue(h.contains("CHEATS = true"))
        assertTrue(h.contains("\"walk_speed\" => \"6\""))
        assertTrue(h.contains("\"no_encounters\" => \"0\""))
    }

    @Test fun configJsonAddsPreloadOnlyWithHelper() {
        assertTrue(RgssRuntime.overrideConfigJson(emptyMap(), listOf("/p/acecore_compat.rb", "/p/acecore_preload.rb")).contains("\"preloadScript\":[\"/p/acecore_compat.rb\",\"/p/acecore_preload.rb\"]"))
        assertFalse(RgssRuntime.overrideConfigJson(emptyMap()).contains("preloadScript"))
    }

    @Test fun sessionRecordRoundTripAndLegacy() {
        val f = tmp.newFile("s.ms")
        SessionRecord.write(f, 65_000, 1_700_000_000_000)
        assertEquals(SessionRecord.Data(65_000, 1_700_000_000_000), SessionRecord.read(f))
        f.writeText("4200")
        assertEquals(SessionRecord.Data(4200, null), SessionRecord.read(f))
        assertNull(SessionRecord.read(File(tmp.root, "missing")))
    }

    @Test fun parsesHelperState() {
        val s = GameCheatState.parse(
            "rgss=1\nkind=essentials\nessentials_version=21.1\ncaps=money,items,pkmn_edit\nflags=walk_speed:6,no_encounters:0\nmoney=5000\nmoney_max=999999\n" +
                "pkmn0=Sparky\tPikachu\t12\tHardy\tStatic\t70\t1,2,3,4,5,6\t0,0,0,0,0,252\t1\t0\n",
        )
        assertEquals("essentials", s.kind)
        assertTrue(s.has("money") && !s.has("gold"))
        assertEquals("6", s.flags["walk_speed"])
        assertEquals(5000L, s.money)
        val p = s.party.single()
        assertEquals("Pikachu", p.species)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), p.ivs)
        assertEquals(252, p.evs[5])
        assertTrue(p.shiny)
    }
}
