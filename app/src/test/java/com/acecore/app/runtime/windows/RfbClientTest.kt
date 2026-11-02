package com.acecore.app.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The VNC client against a scripted RFB 3.8 server (what TigerVNC's Xvnc sends on a local connection). */
class RfbClientTest {
    @Test fun handshakeRawCopyRectResizeAndInput() {
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val clientMessages = mutableListOf<String>()
        val serverDone = CountDownLatch(1)
        thread {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream()); val o = DataOutputStream(s.getOutputStream())
                o.write("RFB 003.008\n".toByteArray()); o.flush()
                val v = ByteArray(12); i.readFully(v); clientMessages += String(v).trim()
                o.writeByte(1); o.writeByte(1); o.flush()           // one security type: None
                clientMessages += "security ${i.readUnsignedByte()}"
                o.writeInt(0); o.flush()                             // SecurityResult OK
                clientMessages += "shared ${i.readUnsignedByte()}"
                o.writeShort(4); o.writeShort(2); o.write(ByteArray(16)); o.writeInt(4); o.write("test".toByteArray()); o.flush()
                check(i.readUnsignedByte() == 0); i.skipBytes(19)    // SetPixelFormat
                check(i.readUnsignedByte() == 2); i.skipBytes(1)
                val n = i.readUnsignedShort(); val encs = List(n) { i.readInt() }
                clientMessages += "encodings $encs"
                check(i.readUnsignedByte() == 3); clientMessages += "request incremental=${i.readUnsignedByte()}"; i.skipBytes(8)
                // Update 1: a raw 2x1 rectangle at (1,0): red, green (B,G,R,X bytes).
                o.writeByte(0); o.writeByte(0); o.writeShort(1)
                o.writeShort(1); o.writeShort(0); o.writeShort(2); o.writeShort(1); o.writeInt(0)
                o.write(byteArrayOf(0, 0, 0xFF.toByte(), 0, 0, 0xFF.toByte(), 0, 0)); o.flush()
                check(i.readUnsignedByte() == 3); clientMessages += "request incremental=${i.readUnsignedByte()}"; i.skipBytes(8)
                // Update 2: copy (1,0)-(2,0) to row 1.
                o.writeByte(0); o.writeByte(0); o.writeShort(1)
                o.writeShort(1); o.writeShort(1); o.writeShort(2); o.writeShort(1); o.writeInt(1); o.writeShort(1); o.writeShort(0); o.flush()
                // Pointer and key events the test sends after that frame, then the next update request.
                check(i.readUnsignedByte() == 5); clientMessages += "pointer ${i.readUnsignedByte()} ${i.readUnsignedShort()},${i.readUnsignedShort()}"
                check(i.readUnsignedByte() == 4); val down = i.readUnsignedByte(); i.skipBytes(2)
                clientMessages += "key 0x${i.readInt().toString(16)} down=$down"
                check(i.readUnsignedByte() == 3); i.skipBytes(9)
                // Update 3: the game switches resolution (DesktopSize).
                o.writeByte(0); o.writeByte(0); o.writeShort(1)
                o.writeShort(0); o.writeShort(0); o.writeShort(8); o.writeShort(6); o.writeInt(-223); o.flush()
                check(i.readUnsignedByte() == 3); i.skipBytes(9)
                serverDone.countDown()
                Thread.sleep(300)
            }
        }
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val sizes = mutableListOf<String>()
        var client: RfbClient? = null
        client = RfbClient(server.localPort, onFrame = {
            val c = client!!
            when (frames.incrementAndGet()) {
                2 -> { c.pointer(3, 1, 1); c.key(Keysyms.fromAndroid(66), true) }
            }
        }, onResize = { w, h -> sizes += "${w}x$h" })
        val reader = thread { runCatching { client.run(5_000) } }
        assertTrue("server finished", serverDone.await(10, TimeUnit.SECONDS))
        Thread.sleep(200)
        client.close(); reader.join(2_000); server.close()

        assertEquals("RFB 003.008", clientMessages[0])
        assertEquals("security 1", clientMessages[1])
        assertEquals("shared 1", clientMessages[2])
        assertEquals("encodings [1, 0, -223]", clientMessages[3])
        assertEquals("request incremental=0", clientMessages[4])
        assertEquals("request incremental=1", clientMessages[5])
        assertEquals("pointer 1 3,1", clientMessages[6])
        assertEquals("key 0xff0d down=1", clientMessages[7])
        assertEquals(listOf("4x2", "8x6"), sizes)
        assertEquals(8, client.width); assertEquals(6, client.height)
    }

    @Test fun pixelsAfterRawAndCopyRect() {
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val gotFrames = CountDownLatch(2)
        thread {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream()); val o = DataOutputStream(s.getOutputStream())
                o.write("RFB 003.008\n".toByteArray()); o.flush(); i.skipBytes(12)
                o.writeByte(1); o.writeByte(1); o.flush(); i.skipBytes(1); o.writeInt(0); o.flush(); i.skipBytes(1)
                o.writeShort(4); o.writeShort(2); o.write(ByteArray(16)); o.writeInt(0); o.flush()
                i.skipBytes(20); i.skipBytes(4 + 3 * 4); i.skipBytes(10)
                o.writeByte(0); o.writeByte(0); o.writeShort(1)
                o.writeShort(1); o.writeShort(0); o.writeShort(2); o.writeShort(1); o.writeInt(0)
                o.write(byteArrayOf(0, 0, 0xFF.toByte(), 0, 0, 0xFF.toByte(), 0, 0)); o.flush()
                i.skipBytes(10)
                o.writeByte(0); o.writeByte(0); o.writeShort(1)
                o.writeShort(1); o.writeShort(1); o.writeShort(2); o.writeShort(1); o.writeInt(1); o.writeShort(1); o.writeShort(0); o.flush()
                i.skipBytes(10)
                Thread.sleep(500)
            }
        }
        val client = RfbClient(server.localPort, onFrame = { gotFrames.countDown() }, onResize = { _, _ -> })
        val reader = thread { runCatching { client.run(5_000) } }
        assertTrue(gotFrames.await(10, TimeUnit.SECONDS))
        val px = synchronized(client.lock) { client.pixels.copyOf() }
        client.close(); reader.join(2_000); server.close()
        val red = 0xFFFF0000.toInt(); val green = 0xFF00FF00.toInt()
        assertEquals(listOf(0, red, green, 0, 0, red, green, 0), px.toList())
    }

    /**
     * Hidden pointer: the client asks for the Cursor pseudo-encoding (so Xvnc leaves the pointer out of the picture),
     * skips the pointer shape the server sends (pixels + mask) and still reads the rectangle after it correctly; showing
     * the pointer again drops the encoding and asks for a full picture.
     */
    @Test fun hiddenPointerSkipsShapeAndCanBeShownAgain() {
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gotFrame = CountDownLatch(1)
        val serverDone = CountDownLatch(1)
        thread {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream()); val o = DataOutputStream(s.getOutputStream())
                o.write("RFB 003.008\n".toByteArray()); o.flush(); i.skipBytes(12)
                o.writeByte(1); o.writeByte(1); o.flush(); i.skipBytes(1); o.writeInt(0); o.flush(); i.skipBytes(1)
                o.writeShort(4); o.writeShort(2); o.write(ByteArray(16)); o.writeInt(0); o.flush()
                i.skipBytes(20)
                check(i.readUnsignedByte() == 2); i.skipBytes(1)
                messages += "encodings ${List(i.readUnsignedShort()) { i.readInt() }}"
                check(i.readUnsignedByte() == 3); i.skipBytes(9)
                // One update: a 3x2 pointer shape (3*2*4 pixel bytes + 1 mask byte per row), then a raw red pixel.
                o.writeByte(0); o.writeByte(0); o.writeShort(2)
                o.writeShort(1); o.writeShort(1); o.writeShort(3); o.writeShort(2); o.writeInt(-239)
                o.write(ByteArray(3 * 2 * 4) { 0x55 }); o.write(byteArrayOf(0xE0.toByte(), 0xE0.toByte()))
                o.writeShort(0); o.writeShort(1); o.writeShort(1); o.writeShort(1); o.writeInt(0)
                o.write(byteArrayOf(0, 0, 0xFF.toByte(), 0)); o.flush()
                check(i.readUnsignedByte() == 3); i.skipBytes(9)          // the client's next incremental request
                // The test shows the pointer again: new encodings, then a full (non-incremental) request.
                check(i.readUnsignedByte() == 2); i.skipBytes(1)
                messages += "encodings ${List(i.readUnsignedShort()) { i.readInt() }}"
                check(i.readUnsignedByte() == 3); messages += "request incremental=${i.readUnsignedByte()}"; i.skipBytes(8)
                serverDone.countDown()
                Thread.sleep(300)
            }
        }
        val client = RfbClient(server.localPort, onFrame = { gotFrame.countDown() }, onResize = { _, _ -> }, cursorHidden = true)
        val reader = thread { runCatching { client.run(5_000) } }
        assertTrue("frame after the pointer shape", gotFrame.await(10, TimeUnit.SECONDS))
        val px = synchronized(client.lock) { client.pixels.copyOf() }
        Thread.sleep(100)
        client.setCursorHidden(false)
        assertTrue("server finished", serverDone.await(10, TimeUnit.SECONDS))
        client.close(); reader.join(2_000); server.close()
        assertEquals(0xFFFF0000.toInt(), px[4]) // (0,1) on the 4x2 screen
        assertEquals("encodings [1, 0, -223, -239]", messages[0])
        assertEquals("encodings [1, 0, -223]", messages[1])
        assertEquals("request incremental=0", messages[2])
        assertEquals(false, client.cursorHidden)
    }

    @Test fun keysymsForKeysGamesUse() {
        assertEquals(0xFF0D, Keysyms.fromAndroid(66))   // Enter
        assertEquals(0xFF1B, Keysyms.fromAndroid(111))  // Esc
        assertEquals(0x7A, Keysyms.fromAndroid(54))     // z
        assertEquals(0x78, Keysyms.fromAndroid(52))     // x
        assertEquals(0xFF52, Keysyms.fromAndroid(19))   // Up
        assertEquals(0xFFE1, Keysyms.fromAndroid(59))   // Shift
        assertEquals(0xFFBE, Keysyms.fromAndroid(131))  // F1
        assertEquals(0, Keysyms.fromAndroid(4))         // Back has no key
    }
}
