package com.acecore.app.runtime.windows

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal VNC (RFB 3.8) client for the Windows runtime's display: the X server inside the runtime (TigerVNC Xvnc)
 * listens on 127.0.0.1 only. Supports what a local connection needs: no authentication, 32-bit true colour, Raw and
 * CopyRect rectangles, and DesktopSize (a game switching its screen resolution). Pointer and key events go back.
 * With [cursorHidden] the client takes the Cursor pseudo-encoding (the server then leaves the mouse pointer out of the
 * picture and sends its shape separately) and ignores the shape, so no pointer is shown.
 *
 * The frame buffer is [pixels] (0xFFRRGGBB per pixel, row-major, [width] x [height]); [onFrame] runs on the reader
 * thread after each complete update, while [lock] is held by nobody.
 */
class RfbClient(
    private val port: Int,
    private val onFrame: () -> Unit,
    private val onResize: (Int, Int) -> Unit,
    cursorHidden: Boolean = false,
) {
    @Volatile var cursorHidden = cursorHidden; private set
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var pixels = IntArray(0); private set
    val lock = Any()
    @Volatile var connected = false; private set
    @Volatile private var closed = false
    private var socket: Socket? = null
    private var out: DataOutputStream? = null

    /** Connects (retrying until [timeoutMs]) and processes updates until the connection ends. Blocking. */
    @Throws(IOException::class)
    fun run(timeoutMs: Long) {
        val s = connect(timeoutMs)
        socket = s
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        out = o
        handshake(input, o)
        connected = true
        requestUpdate(incremental = false)
        var raw = ByteArray(0)
        while (!closed) {
            when (val type = input.readUnsignedByte()) {
                0 -> { // FramebufferUpdate
                    input.readUnsignedByte()
                    val rects = input.readUnsignedShort()
                    repeat(rects) {
                        val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
                        when (val enc = input.readInt()) {
                            ENC_RAW -> {
                                val n = w * h * 4
                                if (raw.size < n) raw = ByteArray(n)
                                input.readFully(raw, 0, n)
                                synchronized(lock) { blitRaw(raw, x, y, w, h) }
                            }
                            ENC_COPYRECT -> {
                                val sx = input.readUnsignedShort(); val sy = input.readUnsignedShort()
                                synchronized(lock) { copyRect(sx, sy, x, y, w, h) }
                            }
                            ENC_DESKTOP_SIZE -> resize(w, h)
                            ENC_CURSOR -> skipFully(input, w * h * 4 + (w + 7) / 8 * h) // pointer shape + mask: not drawn
                            else -> throw IOException("Unexpected VNC encoding $enc")
                        }
                    }
                    onFrame()
                    requestUpdate(incremental = true)
                }
                1 -> { // SetColourMapEntries (not used with true colour)
                    input.readUnsignedByte(); input.readUnsignedShort()
                    val n = input.readUnsignedShort(); skipFully(input, n * 6)
                }
                2 -> Unit // Bell
                3 -> { skipFully(input, 3); val n = input.readInt(); skipFully(input, n) } // ServerCutText
                else -> throw IOException("Unexpected VNC message $type")
            }
        }
    }

    /** Skips exactly [n] bytes (DataInputStream.skipBytes may skip fewer). */
    private fun skipFully(input: DataInputStream, n: Int) {
        val scratch = ByteArray(minOf(n, 1 shl 16).coerceAtLeast(1))
        var left = n
        while (left > 0) { val k = minOf(left, scratch.size); input.readFully(scratch, 0, k); left -= k }
    }

    private fun connect(timeoutMs: Long): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: IOException? = null
        while (!closed && System.currentTimeMillis() < deadline) {
            try {
                val target = InetSocketAddress("127.0.0.1", port) // outside apply: inside it, "port" would be the Socket's own
                return Socket().apply { tcpNoDelay = true; connect(target, 2000) }
            } catch (e: IOException) {
                last = e
                Thread.sleep(250)
            }
        }
        throw last ?: IOException("Display connection cancelled")
    }

    private fun handshake(input: DataInputStream, o: DataOutputStream) {
        val version = ByteArray(12).also { input.readFully(it) }
        if (!String(version, Charsets.US_ASCII).startsWith("RFB ")) throw IOException("Not a VNC server")
        o.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); o.flush()
        val count = input.readUnsignedByte()
        if (count == 0) {
            val n = input.readInt(); val why = ByteArray(n).also { input.readFully(it) }
            throw IOException("VNC server refused: ${String(why)}")
        }
        val types = ByteArray(count).also { input.readFully(it) }
        if (types.none { it.toInt() == 1 }) throw IOException("VNC server requires authentication")
        o.writeByte(1); o.flush()
        if (input.readInt() != 0) throw IOException("VNC security handshake failed")
        o.writeByte(1); o.flush() // ClientInit: shared
        val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
        skipFully(input, 16) // server pixel format (we set ours below)
        val nameLen = input.readInt(); skipFully(input, nameLen)
        resize(w, h)
        // SetPixelFormat: 32 bpp, depth 24, little endian, true colour, 8 bits per channel, R<<16 G<<8 B.
        o.writeByte(0); o.write(ByteArray(3))
        o.writeByte(32); o.writeByte(24); o.writeByte(0); o.writeByte(1)
        o.writeShort(255); o.writeShort(255); o.writeShort(255)
        o.writeByte(16); o.writeByte(8); o.writeByte(0); o.write(ByteArray(3))
        writeEncodings(o)
        o.flush()
    }

    /** SetEncodings: CopyRect, Raw, DesktopSize, and Cursor while the pointer is hidden. */
    private fun writeEncodings(o: DataOutputStream) {
        val encodings = if (cursorHidden) intArrayOf(ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE, ENC_CURSOR)
            else intArrayOf(ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE)
        o.writeByte(2); o.writeByte(0); o.writeShort(encodings.size)
        encodings.forEach { o.writeInt(it) }
    }

    /** Shows or hides the mouse pointer in the picture (takes effect with the next update). */
    fun setCursorHidden(hidden: Boolean) {
        if (hidden == cursorHidden) return
        cursorHidden = hidden
        send { writeEncodings(this) }
        if (connected) requestUpdate(incremental = false)
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            width = w; height = h
            pixels = IntArray(w * h)
        }
        onResize(w, h)
    }

    private fun blitRaw(raw: ByteArray, x: Int, y: Int, w: Int, h: Int) {
        val fb = pixels; val fw = width
        if (x + w > fw || y + h > height) return
        var i = 0
        for (row in 0 until h) {
            var p = (y + row) * fw + x
            for (col in 0 until w) {
                fb[p++] = OPAQUE or (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8) or ((raw[i + 2].toInt() and 0xFF) shl 16)
                i += 4
            }
        }
    }

    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        val fb = pixels; val fw = width
        if (sx + w > fw || x + w > fw || sy + h > height || y + h > height) return
        if (sy < y || (sy == y && sx < x)) {
            for (row in h - 1 downTo 0) System.arraycopy(fb, (sy + row) * fw + sx, fb, (y + row) * fw + x, w)
        } else {
            for (row in 0 until h) System.arraycopy(fb, (sy + row) * fw + sx, fb, (y + row) * fw + x, w)
        }
    }

    private fun requestUpdate(incremental: Boolean) = send {
        writeByte(3); writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
    }

    /** Mouse at frame buffer position (x, y); [buttons] bit 0 = left, 1 = middle, 2 = right. */
    fun pointer(x: Int, y: Int, buttons: Int) = send {
        writeByte(5); writeByte(buttons)
        writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0))); writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
    }

    /** X11 keysym press/release (see [Keysyms]). */
    fun key(keysym: Int, down: Boolean) = send {
        writeByte(4); writeByte(if (down) 1 else 0); writeShort(0); writeInt(keysym)
    }

    private fun send(block: DataOutputStream.() -> Unit) {
        val o = out ?: return
        try {
            synchronized(o) { o.block(); o.flush() }
        } catch (_: IOException) {
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    companion object {
        const val ENC_RAW = 0
        private const val OPAQUE = -0x1000000 // 0xFF000000: pixels are stored ready for an ARGB bitmap
        const val ENC_COPYRECT = 1
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_CURSOR = -239
    }
}

/** X11 keysyms for keys AceArcade sends (VNC key events carry keysyms). */
object Keysyms {
    /** Keysym for an Android KeyEvent keycode, or 0 when the key has none here. */
    fun fromAndroid(code: Int): Int = when (code) {
        in 29..54 -> 0x61 + (code - 29)              // KEYCODE_A..Z -> a..z
        in 7..16 -> 0x30 + (code - 7)                // KEYCODE_0..9
        in 131..142 -> 0xFFBE + (code - 131)         // F1..F12
        66, 160 -> 0xFF0D                            // Enter, numpad Enter
        111 -> 0xFF1B                                // Escape
        67 -> 0xFF08                                 // Backspace (DEL)
        112 -> 0xFFFF                                // Forward delete
        61 -> 0xFF09                                 // Tab
        62 -> 0x20                                   // Space
        19 -> 0xFF52; 20 -> 0xFF54; 21 -> 0xFF51; 22 -> 0xFF53 // Up, Down, Left, Right
        92 -> 0xFF55; 93 -> 0xFF56; 122 -> 0xFF50; 123 -> 0xFF57; 124 -> 0xFF63 // PgUp PgDn Home End Insert
        59 -> 0xFFE1; 60 -> 0xFFE2                   // Shift L/R
        113 -> 0xFFE3; 114 -> 0xFFE4                 // Ctrl L/R
        57 -> 0xFFE9; 58 -> 0xFFEA                   // Alt L/R
        55 -> 0x2C; 56 -> 0x2E; 69 -> 0x2D; 70 -> 0x3D; 71 -> 0x5B; 72 -> 0x5D // , . - = [ ]
        73 -> 0x5C; 74 -> 0x3B; 75 -> 0x27; 76 -> 0x2F; 68 -> 0x60 // \ ; ' / `
        else -> 0
    }
}
