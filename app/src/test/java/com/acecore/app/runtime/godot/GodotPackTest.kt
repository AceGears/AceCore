package com.acecore.app.runtime.godot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class GodotPackTest {
    private fun header(major: Int, minor: Int, patch: Int, format: Int = 3): ByteArray =
        ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("GDPC".toByteArray()); putInt(format); putInt(major); putInt(minor); putInt(patch)
        }.array()

    private fun dir(vararg files: Pair<String, ByteArray>): File = Files.createTempDirectory("gd").toFile().apply {
        files.forEach { (n, b) -> File(this, n).also { it.parentFile.mkdirs() }.writeBytes(b) }
    }

    @Test fun readsSeparatePack() {
        val d = dir("Game.pck" to header(4, 3, 0))
        val p = GodotPack.read(File(d, "Game.pck"))!!
        assertEquals("4.3.0", p.version); assertEquals(0L, p.offset)
        assertTrue(GodotPack.check(d, "Game.pck") is GodotPack.Check.Ok)
    }

    @Test fun readsPackEmbeddedInExecutable() {
        val exe = ByteArrayOutputStream()
        exe.write(ByteArray(5000) { 0x4D })                // stand-in for the executable
        val pck = header(4, 7, 0)
        exe.write(pck)
        exe.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(pck.size.toLong()).put("GDPC".toByteArray()).array())
        val d = dir("Game.exe" to exe.toByteArray())
        val p = GodotPack.find(d, "Game.exe")!!
        assertEquals(5000L, p.offset); assertTrue(p.embedded); assertEquals(7, p.minor)
    }

    @Test fun rejectsGodot3NewerAndDotnet() {
        assertTrue(GodotPack.check(dir("g.pck" to header(3, 5, 2, 1)), null) is GodotPack.Check.Unsupported)
        assertTrue(GodotPack.check(dir("g.pck" to header(4, GodotPack.ENGINE_MINOR + 1, 0)), null) is GodotPack.Check.Unsupported)
        assertTrue(GodotPack.check(dir("g.pck" to header(4, 2, 0), "data_Game_windows_x86_64/x.dll" to ByteArray(1)), null) is GodotPack.Check.Unsupported)
        assertTrue(GodotPack.check(dir("project.godot" to ByteArray(1)), null) is GodotPack.Check.Unsupported)
    }

    @Test fun notAPack() {
        assertNull(GodotPack.read(File(dir("Game.exe" to ByteArray(100)), "Game.exe")))
    }
}
