package com.acecore.app.core.handheld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** org.json needs the Android runtime (Robolectric). */
@RunWith(RobolectricTestRunner::class)
class CheatStoreTest {
    private fun tmp() = File.createTempFile("cheats", ".json").also { it.delete() }

    @Test fun roundTrip() {
        val f = tmp()
        CheatStore(f).apply {
            enabled += CheatStore.dbKey(7, 2); values["db:7:3"] = "63"; favorites += "db:7:2"
            custom += CheatStore.Custom("c1", "Mine", "82000568+423F"); presets["Speedrun"] = listOf("db:7:2")
            acceptedSets += 9L; recordVerification("db:7:2", "abc", true, "82000568+423F", now = 5); warned = true
            save()
        }
        val s = CheatStore(f)
        assertEquals(setOf("db:7:2"), s.enabled); assertEquals("63", s.values["db:7:3"]); assertEquals(setOf(9L), s.acceptedSets)
        assertEquals("Mine", s.custom.single().name); assertEquals(listOf("db:7:2"), s.presets["Speedrun"]); assertTrue(s.warned)
        assertTrue(s.verification("db:7:2", "ABC", "82000568+423F")!!.works)
        assertNull(s.verification("db:7:2", "ABC", "other code"))   // a changed code needs a new check
        assertNull(s.verification("db:7:2", "DEF", "82000568+423F")) // another ROM needs its own check
    }

    @Test fun importsChtAndSkipsInvalid() {
        val s = CheatStore(tmp())
        val cht = "cheats = 3\n\ncheat0_desc = \"Max Money\"\ncheat0_code = \"82000568+423F\"\ncheat0_enable = false\n" +
            "cheat1_desc = \"Folder\"\ncheat1_code = \"\"\ncheat2_desc = \"Bad\"\ncheat2_code = \"\$0D24206E\"\n"
        assertEquals(1 to 1, s.importFrom(cht, HandheldPlatform.GBA))
        assertEquals("Max Money", s.custom.single().name)
        val export = s.exportJson("Test", HandheldPlatform.GBA)
        val other = CheatStore(tmp())
        assertEquals(1 to 0, other.importFrom(export, HandheldPlatform.GBA))
    }
}
