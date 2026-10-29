package com.acecore.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TombstoneTest {
    // --- tiny protobuf encoder for building test tombstones ---
    private class Pb {
        val out = ByteArrayOutputStream()
        private fun raw(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun int(f: Int, v: Long) = apply { raw((f shl 3).toLong()); raw(v) }
        fun str(f: Int, s: String) = bytes(f, s.toByteArray())
        fun msg(f: Int, m: Pb) = bytes(f, m.out.toByteArray())
        fun bytes(f: Int, b: ByteArray) = apply { raw(((f shl 3) or 2).toLong()); raw(b.size.toLong()); out.write(b) }
        fun fixed64(f: Int) = apply { raw(((f shl 3) or 1).toLong()); out.write(ByteArray(8)) }
    }

    private fun frame(rel: Long, file: String, fn: String = "") =
        Pb().int(1, rel).int(2, rel + 0x7000000000).str(4, fn).int(5, 12).str(6, file).str(8, "abc123")

    private fun thread(id: Int, name: String, vararg frames: Pb) = Pb().int(1, id.toLong()).str(2, name).apply {
        frames.forEach { msg(4, it) }
        msg(3, Pb().str(1, "pc").int(2, 0x1234))
    }

    private fun sample(): ByteArray = Pb()
        .int(1, 1) // arch
        .str(2, "samsung/m3q:17")
        .int(5, 22555).int(6, 22598)
        .msg(10, Pb().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(8, 1).int(9, -1L))
        .msg(15, Pb().str(1, "null pointer dereference"))
        .msg(16, Pb().int(1, 22555).msg(2, thread(22555, "SDLActivity", frame(0x10, "/apex/com.android.runtime/lib64/bionic/libc.so", "__epoll_pwait"))))
        .msg(16, Pb().int(1, 22598).msg(2, thread(22598, "SDLThread",
            frame(0x20, "/apex/com.android.runtime/lib64/bionic/libc.so", "strlen"),
            frame(0x4567, "/data/app/~~x/com.acecore.app-y/base.apk!libmkxp-z.so", "Config::read"))))
        .fixed64(30) // unknown fixed64 field must be skipped
        .int(22, 16384)
        .out.toByteArray()

    @Test fun decodesCrashingThreadNotOthers() {
        val s = Tombstone.parse(sample())
        assertNotNull(s); s!!
        assertEquals("SIGSEGV", s.signal)
        assertEquals("SEGV_MAPERR", s.signalCode)
        assertEquals(-1L, s.faultAddress)
        assertEquals(16384, s.pageSize)
        assertEquals("SDLThread", s.crashing?.name)
        assertEquals(2, s.crashing?.frames?.size)
        assertEquals("Config::read", s.culprit?.function)
        assertEquals("libmkxp-z.so", Tombstone.libName(s.culprit!!.file))
        val text = Tombstone.format(s)
        assertTrue(text, text.contains("fault address 0xffffffffffffffff"))
        assertTrue(text, text.contains("page size: 16 KB"))
        assertTrue(text, text.contains("Crashing thread: SDLThread (tid 22598)"))
        assertTrue(text, text.contains("Other threads (not the cause): SDLActivity (22555)"))
        assertTrue(text, text.contains("null pointer dereference"))
    }

    @Test fun rejectsNonProtobuf() {
        assertNull(Tombstone.parse("*** *** *** text tombstone".toByteArray()))
    }
}
