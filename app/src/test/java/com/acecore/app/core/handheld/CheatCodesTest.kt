package com.acecore.app.core.handheld

import com.acecore.app.core.handheld.CheatCodes.Check
import com.acecore.app.core.handheld.CheatCodes.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codes as they appear in the libretro cheat files (the bundled database's source). */
class CheatCodesTest {
    private fun valid(p: HandheldPlatform, code: String) = CheatCodes.check(p, code) as? Check.Valid
        ?: throw AssertionError("expected valid: $code -> ${CheatCodes.check(p, code)}")

    private fun invalid(p: HandheldPlatform, code: String) =
        assertTrue("expected invalid: $code", CheatCodes.check(p, code) is Check.Invalid)

    @Test fun gameBoy() {
        assertEquals(Format.GAME_GENIE, valid(HandheldPlatform.GB, "007-E48").format)
        assertEquals(Format.GAME_GENIE, valid(HandheldPlatform.GBC, "00A-17B-C49+01A-18B-E6E").format)
        assertEquals(Format.GAMESHARK_GB, valid(HandheldPlatform.GBC, "916412CB").format)
        invalid(HandheldPlatform.GB, "\$0D24206E")          // Xploder codes: not supported by the emulator
        invalid(HandheldPlatform.GB, "007-E48+916412CB")    // mixed devices in one cheat
    }

    @Test fun gameBoyAdvance() {
        assertEquals(Format.CODEBREAKER, valid(HandheldPlatform.GBA, "82000568+423F+8200056A+000F").format)
        assertEquals(2, valid(HandheldPlatform.GBA, "82000568+423F+8200056A+000F").lines)
        assertEquals(Format.GAMESHARK_AR, valid(HandheldPlatform.GBA, "D8BAE4D9+4864DCE5+A86CDBA5+19BA49B3").format)
        assertEquals("82000568+423F", valid(HandheldPlatform.GBA, "82000568 423f").normalized)
        invalid(HandheldPlatform.GBA, "82000568")
        invalid(HandheldPlatform.GBA, "8200056+423F")
    }

    @Test fun nintendoDs() {
        val v = valid(HandheldPlatform.NDS, "94000130+FFFB0000+62101D2C+00000000+B2101D2C+00000000+2000E302+00000001+D2000000+00000000")
        assertEquals(Format.ACTION_REPLAY_DS, v.format); assertEquals(5, v.lines)
        invalid(HandheldPlatform.NDS, "master+00000000+023F0000+00000000")
        invalid(HandheldPlatform.NDS, "94000130+FFFB0000+621B3800")   // odd count (cut off)
    }

    @Test fun modifierCodes() {
        assertEquals(1, CheatCodes.placeholderDigits("320232DA+000X"))
        assertEquals(2, CheatCodes.placeholderDigits("3300075C+00??"))
        assertEquals(2, CheatCodes.placeholderDigits("01??9AC3"))
        assertEquals(0, CheatCodes.placeholderDigits("82000568+423F"))
        assertEquals("3300075C+0063", CheatCodes.fill("3300075C+00??", "63"))
        assertEquals("01FF9AC3", CheatCodes.fill("01??9AC3", "ff"))
        assertEquals(Format.CODEBREAKER, valid(HandheldPlatform.GBA, "3300075C+00??").format)
        assertEquals(Format.GAMESHARK_GB, valid(HandheldPlatform.GBC, "01??9AC3").format)
    }

    @Test fun categories() {
        assertEquals("Required", CheatCodes.categoryOf("Master Code", null, true))
        assertEquals("Money", CheatCodes.categoryOf("Max Money", null, true))
        assertEquals("Encounters", CheatCodes.categoryOf("No Random Battle", null, true))
        assertEquals("Movement", CheatCodes.categoryOf("Walk Through Walls", null, true))
        assertEquals("Items", CheatCodes.categoryOf("Have All Items", null, false))
        assertEquals("Pokémon", CheatCodes.categoryOf("Max IVs for first Pokemon", null, true))
    }
}
