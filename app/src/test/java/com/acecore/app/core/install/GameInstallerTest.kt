package com.acecore.app.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class GameInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSource(val files: MutableMap<String, Pair<ByteArray, Long>>) : InstallSource {
        override fun list() = files.map { (p, v) -> SourceFile(p, v.first.size.toLong(), v.second) }
        override fun open(path: String) = ByteArrayInputStream(files.getValue(path).first)
    }

    private fun src(vararg f: Pair<String, String>) =
        FakeSource(f.associate { (p, c) -> p to (c.toByteArray() to 1000L) }.toMutableMap())

    @Test fun firstInstallCopiesEverythingThenSkipsUnchanged() {
        val dir = tmp.newFolder("game")
        val source = src("Game.ini" to "[Game]", "Data/Scripts.rxdata" to "scripts", "Graphics/Pictures/a.png" to "png")
        val inst = GameInstaller(dir)
        val r1 = inst.install(source)
        assertEquals(3, r1.copied)
        assertEquals("scripts", File(dir, "Data/Scripts.rxdata").readText())
        assertTrue(inst.isInstalled())
        val r2 = GameInstaller(dir).install(source)
        assertEquals(0, r2.copied)
        assertEquals(3, r2.skipped)
    }

    @Test fun outdatedMarkerAndGameCreatedFiles() {
        val dir = tmp.newFolder("game")
        val source = src("Game.ini" to "[Game]", "Data/Scripts.rxdata" to "s")
        val inst = GameInstaller(dir)
        assertFalse(inst.needsResync())
        inst.markOutdated() // no copy yet: nothing to mark
        assertFalse(inst.needsResync())
        inst.install(source)
        File(dir, "Save1.rxdata").writeText("save")
        File(dir, "Data/extra.rxdata").writeText("x")
        assertEquals(setOf("Save1.rxdata", "Data/extra.rxdata"), inst.gameCreatedFiles().toSet())
        inst.markOutdated()
        assertTrue(inst.needsResync())
        inst.install(source)
        assertFalse(inst.needsResync())
        assertEquals("save", File(dir, "Save1.rxdata").readText())
    }

    @Test fun gameCreatedSavesSurviveSyncs() {
        val dir = tmp.newFolder("game")
        val source = src("Game.ini" to "[Game]")
        GameInstaller(dir).install(source)
        File(dir, "Save1.rxdata").writeText("my progress")
        // The original later gains a file with the same name: the player's save must win.
        source.files["Save1.rxdata"] = "stale".toByteArray() to 2000L
        GameInstaller(dir).install(source)
        assertEquals("my progress", File(dir, "Save1.rxdata").readText())
        // And removing everything from the original never deletes game-created files.
        source.files.clear()
        GameInstaller(dir).install(source)
        assertTrue(File(dir, "Save1.rxdata").exists())
        assertFalse(File(dir, "Game.ini").exists())
    }

    @Test fun updatedSourceFileIsRecopiedUnlessGameModifiedIt() {
        val dir = tmp.newFolder("game")
        val source = src("Data/Map001.rxdata" to "v1", "Data/System.rxdata" to "sys1")
        GameInstaller(dir).install(source)
        source.files["Data/Map001.rxdata"] = "v2-longer".toByteArray() to 5000L
        // Simulate the game editing its own copy of System.rxdata, then the original also changing.
        val sys = File(dir, "Data/System.rxdata")
        sys.writeText("edited by game"); sys.setLastModified(sys.lastModified() + 10_000)
        source.files["Data/System.rxdata"] = "sys2".toByteArray() to 6000L
        GameInstaller(dir).install(source)
        assertEquals("v2-longer", File(dir, "Data/Map001.rxdata").readText())
        assertEquals("edited by game", sys.readText())
    }

    @Test fun pathsCannotEscapeTheInstallFolder() {
        val dir = tmp.newFolder("game")
        GameInstaller(dir).install(src("../../evil.txt" to "x", "a/../../b.txt" to "y"))
        assertFalse(File(dir.parentFile, "evil.txt").exists())
        assertTrue(File(dir, "evil.txt").exists())
        assertTrue(File(dir, "b.txt").exists())
    }

    @Test fun refusesWhenSpaceIsInsufficient() {
        val dir = tmp.newFolder("game")
        try {
            GameInstaller(dir, freeSpace = { 1024 }).install(src("big.bin" to "x".repeat(10)))
            fail("expected InsufficientSpaceException")
        } catch (e: InsufficientSpaceException) {
            assertTrue(e.needed > e.available)
        }
    }

    @Test fun cancellationLeavesNoPartialFiles() {
        val dir = tmp.newFolder("game")
        var calls = 0
        try {
            GameInstaller(dir).install(src("a" to "1", "b" to "2", "c" to "3"), isCancelled = { ++calls > 1 })
            fail("expected cancel")
        } catch (e: InstallCancelledException) { assertTrue(e.message == "Cancelled") }
        assertTrue(dir.walk().none { it.name.endsWith(".acecore-tmp") })
        // A later full install still completes.
        assertEquals(2, GameInstaller(dir).install(src("a" to "1", "b" to "2", "c" to "3")).copied)
    }
}
