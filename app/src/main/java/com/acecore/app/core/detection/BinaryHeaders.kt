package com.acecore.app.core.detection

/** Small, defensive parsers for binary headers used as detection evidence. */
object BinaryHeaders {

    enum class PeMachine(val label: String) { X86("x86 (32-bit)"), X64("x64"), ARM64("ARM64"), OTHER("other") }

    data class PeInfo(val machine: PeMachine, val isGui: Boolean)

    /** Parses enough of a Windows PE header to confirm the file is a real executable. Returns null if not PE. */
    fun parsePe(bytes: ByteArray): PeInfo? {
        if (bytes.size < 0x40 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) return null
        val peOffset = readIntLE(bytes, 0x3C)
        if (peOffset < 0 || peOffset + 0x5E > bytes.size) return null
        if (bytes[peOffset] != 'P'.code.toByte() || bytes[peOffset + 1] != 'E'.code.toByte() ||
            bytes[peOffset + 2].toInt() != 0 || bytes[peOffset + 3].toInt() != 0
        ) return null
        val machine = when (readShortLE(bytes, peOffset + 4)) {
            0x014C -> PeMachine.X86
            0x8664 -> PeMachine.X64
            0xAA64 -> PeMachine.ARM64
            else -> PeMachine.OTHER
        }
        // Optional header starts at peOffset+24; Subsystem is at +68 for both PE32 and PE32+.
        val subsystemOffset = peOffset + 24 + 68
        val isGui = subsystemOffset + 2 <= bytes.size && readShortLE(bytes, subsystemOffset) == 2
        return PeInfo(machine, isGui)
    }

    data class GodotPck(val formatVersion: Int, val major: Int, val minor: Int, val patch: Int)

    /** Godot .pck header: "GDPC" magic, then u32 format, u32 major, u32 minor, u32 patch (little-endian). */
    fun parseGodotPck(bytes: ByteArray): GodotPck? {
        if (bytes.size < 20) return null
        if (bytes[0] != 'G'.code.toByte() || bytes[1] != 'D'.code.toByte() ||
            bytes[2] != 'P'.code.toByte() || bytes[3] != 'C'.code.toByte()
        ) return null
        return GodotPck(readIntLE(bytes, 4), readIntLE(bytes, 8), readIntLE(bytes, 12), readIntLE(bytes, 16))
    }

    /** Ren'Py .rpa archives start with "RPA-3.0", "RPA-2.0" or "RPA-4.0" (some forks). */
    fun rpaVersion(bytes: ByteArray): String? {
        if (bytes.size < 7) return null
        val head = String(bytes, 0, 7, Charsets.US_ASCII)
        return if (head.startsWith("RPA-")) head.removePrefix("RPA-") else null
    }

    data class SwfInfo(val version: Int, val compression: String, /** null when it can't be determined (LZMA). */ val actionScript3: Boolean?)

    /**
     * Parses a SWF header and, where possible, its FileAttributes tag to learn whether it is ActionScript 3.
     * Handles uncompressed (FWS) and zlib (CWS) files from a partial read; LZMA (ZWS) reports AS3 as unknown.
     */
    fun parseSwf(bytes: ByteArray): SwfInfo? {
        if (bytes.size < 9 || bytes[1] != 'W'.code.toByte() || bytes[2] != 'S'.code.toByte()) return null
        val sig = bytes[0].toInt().toChar()
        val version = bytes[3].toInt() and 0xFF
        val body: ByteArray? = when (sig) {
            'F' -> bytes.copyOfRange(8, bytes.size)
            'C' -> runCatching {
                val inf = java.util.zip.Inflater()
                inf.setInput(bytes, 8, bytes.size - 8)
                val out = ByteArray(256)
                val n = inf.inflate(out)
                inf.end()
                out.copyOf(n)
            }.getOrNull()
            'Z' -> null
            else -> return null
        }
        val compression = when (sig) { 'F' -> "none"; 'C' -> "zlib"; else -> "lzma" }
        if (body == null || body.isEmpty()) return SwfInfo(version, compression, null)
        val nbits = (body[0].toInt() and 0xFF) ushr 3
        val rectBytes = (5 + 4 * nbits + 7) / 8
        val tagOffset = rectBytes + 4 // frame rate (u16) + frame count (u16)
        if (tagOffset + 2 > body.size) return SwfInfo(version, compression, null)
        val header = readShortLE(body, tagOffset)
        val code = header ushr 6
        var len = header and 0x3F
        var dataOffset = tagOffset + 2
        if (len == 0x3F) { if (dataOffset + 4 > body.size) return SwfInfo(version, compression, null); len = readIntLE(body, dataOffset); dataOffset += 4 }
        if (code != 69) return SwfInfo(version, compression, false) // no FileAttributes tag → pre-SWF8 → AS1/2
        if (dataOffset >= body.size) return SwfInfo(version, compression, null)
        return SwfInfo(version, compression, (body[dataOffset].toInt() and 0x08) != 0)
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2].toInt() == 3 && bytes[3].toInt() == 4

    /** RGSS encrypted archives: "RGSSAD\0" + version byte (1 = XP/VX, 3 = VX Ace). */
    fun rgssadVersion(bytes: ByteArray): Int? {
        if (bytes.size < 8) return null
        val magic = String(bytes, 0, 6, Charsets.US_ASCII)
        if (magic != "RGSSAD" || bytes[6].toInt() != 0) return null
        return bytes[7].toInt()
    }

    private fun readIntLE(b: ByteArray, o: Int): Int =
        if (o + 4 > b.size) -1
        else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(b: ByteArray, o: Int): Int =
        if (o + 2 > b.size) -1 else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
