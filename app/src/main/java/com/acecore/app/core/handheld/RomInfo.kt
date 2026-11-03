package com.acecore.app.core.handheld

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** The four Nintendo handheld systems AceArcade emulates. [id] matches the handheld database's platform column. */
enum class HandheldPlatform(val id: String, val displayName: String, val extensions: List<String>) {
    GB("gb", "Game Boy", listOf("gb", "dmg", "sgb")),
    GBC("gbc", "Game Boy Color", listOf("gbc", "cgb")),
    GBA("gba", "Game Boy Advance", listOf("gba", "agb")),
    NDS("nds", "Nintendo DS", listOf("nds", "dsi", "srl")),
    ;

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
        fun byExtension(name: String): HandheldPlatform? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
        /** Every ROM extension, for file pickers and folder scans. */
        val ALL_EXTENSIONS: Set<String> = entries.flatMap { it.extensions }.toSet()
    }
}

/**
 * What a ROM's header says about it. Headers identify the game family (title, game code, maker, version) but not a
 * dump: a ROM hack usually keeps its base game's header, so the checksum ([RomHashes]) decides the exact identity.
 */
data class RomHeader(
    val platform: HandheldPlatform,
    /** Internal title (upper-case ASCII as stored). */
    val title: String,
    /** 4-character game code (GBA, DS; GBC games from 1999 on also have one at 0x13F), or null. */
    val gameCode: String?,
    val maker: String?,
    /** ROM version byte ("revision" 0 = first release). */
    val version: Int,
    /** Region letter of the game code, decoded (e.g. "USA", "Europe", "Japan"), or null. */
    val region: String?,
    /** Header checksum matches (GB: 0x14D, GBA: 0xBD, DS: CRC-16 at 0x15E). False suggests a modified ROM or a bad dump. */
    val headerChecksumOk: Boolean,
    /** GB: cartridge type byte (MBC), for display. */
    val cartridgeType: Int? = null,
)

data class RomHashes(val crc32: String, val sha1: String, val sha256: String, val size: Long)

object RomInfo {
    /** Bytes of a ROM that [parseHeader] needs (the DS header is 0x200 bytes; GB/GBA headers end before 0x150/0xC0). */
    const val HEADER_BYTES = 0x200

    /**
     * Parses the header in the first [HEADER_BYTES] of a ROM. [platformHint] (from the file extension) decides between
     * Game Boy and Game Boy Color files, which share one header layout; the CGB flag can also promote a .gb file
     * that only runs in colour to Game Boy Color.
     */
    fun parseHeader(bytes: ByteArray, platformHint: HandheldPlatform?): RomHeader? = when (platformHint) {
        HandheldPlatform.GBA -> parseGba(bytes)
        HandheldPlatform.NDS -> parseNds(bytes)
        HandheldPlatform.GB, HandheldPlatform.GBC -> parseGb(bytes, platformHint)
        null -> parseGba(bytes)?.takeIf { it.headerChecksumOk } ?: parseNds(bytes)?.takeIf { it.headerChecksumOk }
            ?: parseGb(bytes, null)?.takeIf { it.headerChecksumOk }
    }

    private fun ascii(b: ByteArray, from: Int, len: Int): String =
        String(CharArray(len) { i -> (b[from + i].toInt() and 0xFF).let { c -> if (c in 0x20..0x7E) c.toChar() else ' ' } }).trimEnd(' ', '\u0000')

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    fun regionOfCode(code: String?): String? = when (code?.getOrNull(3)) {
        'E' -> "USA"; 'P' -> "Europe"; 'J' -> "Japan"; 'D' -> "Germany"; 'F' -> "France"; 'S' -> "Spain"; 'I' -> "Italy"
        'K' -> "Korea"; 'C' -> "China"; 'U' -> "Australia"; 'H' -> "Netherlands"; 'X', 'Y', 'Z' -> "Europe"; 'O' -> "USA, Europe"
        'T' -> "USA, Australia"; 'V' -> "Europe, Australia"; 'A' -> "World"; else -> null
    }

    private fun parseGba(b: ByteArray): RomHeader? {
        if (b.size < 0xC0) return null
        val code = ascii(b, 0xAC, 4).takeIf { it.length == 4 && it.all { c -> c.isLetterOrDigit() } }
        var sum = 0
        for (i in 0xA0..0xBC) sum += u8(b, i)
        val chk = (-(sum + 0x19)) and 0xFF
        return RomHeader(
            HandheldPlatform.GBA, ascii(b, 0xA0, 12), code, ascii(b, 0xB0, 2).ifBlank { null }, u8(b, 0xBC),
            regionOfCode(code), chk == u8(b, 0xBD) && u8(b, 0xB2) == 0x96,
        )
    }

    private fun parseNds(b: ByteArray): RomHeader? {
        if (b.size < 0x160) return null
        val code = ascii(b, 0x0C, 4).takeIf { it.length == 4 && it.all { c -> c.isLetterOrDigit() } }
        val stored = u8(b, 0x15E) or (u8(b, 0x15F) shl 8)
        return RomHeader(
            HandheldPlatform.NDS, ascii(b, 0x00, 12), code, ascii(b, 0x10, 2).ifBlank { null }, u8(b, 0x1E),
            regionOfCode(code), crc16Modbus(b, 0, 0x15E) == stored,
        )
    }

    private fun parseGb(b: ByteArray, hint: HandheldPlatform?): RomHeader? {
        if (b.size < 0x150) return null
        val cgbFlag = u8(b, 0x143)
        // Titles are 16 bytes on the original Game Boy, 15 (or 11 + a game code) on Game Boy Color cartridges.
        val newCode = ascii(b, 0x13F, 4).takeIf { cgbFlag and 0x80 != 0 && it.length == 4 && it.all { c -> c.isUpperCase() || c.isDigit() } }
        val title = when {
            newCode != null -> ascii(b, 0x134, 11)
            cgbFlag and 0x80 != 0 -> ascii(b, 0x134, 15)
            else -> ascii(b, 0x134, 16)
        }
        var x = 0
        for (i in 0x134..0x14C) x = (x - u8(b, i) - 1) and 0xFF
        val platform = when {
            cgbFlag == 0xC0 -> HandheldPlatform.GBC                 // runs only on Game Boy Color
            hint != null -> hint
            cgbFlag == 0x80 -> HandheldPlatform.GBC
            else -> HandheldPlatform.GB
        }
        val maker = if (u8(b, 0x14B) == 0x33) ascii(b, 0x144, 2) else "%02X".format(u8(b, 0x14B))
        return RomHeader(platform, title, newCode, maker, u8(b, 0x14C), regionOfCode(newCode)
            ?: if (u8(b, 0x14A) == 0) "Japan" else null, x == u8(b, 0x14D), u8(b, 0x147))
    }

    /** CRC-16 (MODBUS polynomial 0xA001, initial 0xFFFF) as used by the DS header. */
    fun crc16Modbus(b: ByteArray, from: Int, len: Int): Int {
        var crc = 0xFFFF
        for (i in from until from + len) {
            crc = crc xor u8(b, i)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** CRC32, SHA-1 and SHA-256 of a whole ROM in one pass (upper-case hex, as the database stores them). */
    fun hash(input: InputStream, progress: (Long) -> Unit = {}): RomHashes {
        val crc = CRC32()
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        var total = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n); sha1.update(buf, 0, n); sha256.update(buf, 0, n)
            total += n
            if (total - lastReport >= 8L shl 20) { lastReport = total; progress(total) }
        }
        return RomHashes("%08X".format(crc.value), sha1.digest().hex(), sha256.digest().hex(), total)
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
}
