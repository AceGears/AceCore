package com.acecore.app.core.detection

import com.acecore.app.core.handheld.HandheldPlatform
import com.acecore.app.core.handheld.RomInfo
import com.acecore.app.core.model.CompatStatus
import com.acecore.app.core.model.Engine
import kotlinx.serialization.Serializable

/**
 * Where a handheld ROM is, as stored in [DetectionResult.rom]. The library entry's treeUri/rootDocId point at the ROM
 * file itself (a ROM picked as a file: treeUri is that file's document URI; a ROM found in a folder scan: treeUri is the
 * folder grant and rootDocId the file's document id).
 */
@Serializable
data class RomRef(
    /** File name as stored (e.g. "Pokemon - Emerald Version (USA, Europe).gba", or "game.zip"). */
    val fileName: String,
    /** HandheldPlatform id ("gb", "gbc", "gba", "nds"). */
    val platform: String,
    /** For a .zip: the ROM inside it (path within the archive); null for a plain ROM file. */
    val zipEntry: String? = null,
    /** Path relative to the folder that was scanned ("" parent = the folder itself), for display only. */
    val path: String = fileName,
    val size: Long = -1,
    val lastModified: Long = 0,
)

/** Pure rules for Nintendo handheld ROM files: what counts as a ROM, titles, zip contents and folder decisions. */
object RomRules {
    /** Sub-folder depth searched for ROMs below the picked folder (0 = only the folder itself). */
    const val MAX_DEPTH = 3

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isRomName(name: String): Boolean = ext(name) in HandheldPlatform.ALL_EXTENSIONS
    fun isZipName(name: String): Boolean = ext(name) == "zip"

    private fun ignored(path: String): Boolean {
        val segs = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return segs.isEmpty() || segs.any { it.startsWith('.') || it.equals("__MACOSX", ignoreCase = true) }
    }

    /**
     * Files of a folder walk that may be ROMs: ROM extensions, plus .zip archives (whose contents decide later, see
     * [zipRomEntry]), at most [maxDepth] folders deep. [path] is relative to the picked folder.
     */
    fun <T> romFileCandidates(entries: List<T>, path: (T) -> String, isDirectory: (T) -> Boolean, maxDepth: Int = MAX_DEPTH): List<T> =
        entries.filter { e ->
            val p = path(e)
            !isDirectory(e) && !ignored(p) && p.count { it == '/' } <= maxDepth && (isRomName(p) || isZipName(p))
        }

    /** The single ROM inside a zip archive (entry names from its directory), or null when it has none or several. */
    fun zipRomEntry(entryNames: List<String>): String? {
        val roms = entryNames.filter { !it.endsWith("/") && !ignored(it) && isRomName(it) }.distinct()
        return roms.singleOrNull()
    }

    /** Platform from the file extension, refined by the header for Game Boy files ([header] = first bytes of the ROM). */
    fun platformFor(fileName: String, header: ByteArray?): HandheldPlatform? {
        val byExt = HandheldPlatform.byExtension(fileName) ?: return null
        if ((byExt == HandheldPlatform.GB || byExt == HandheldPlatform.GBC) && header != null && header.size >= 0x150) {
            return RomInfo.parseHeader(header, byExt)?.platform ?: byExt
        }
        return byExt
    }

    /** Whether the header bytes are needed to tell the platform (only .gb-type files can turn out to be Game Boy Color). */
    fun needsHeader(fileName: String): Boolean = HandheldPlatform.byExtension(fileName) == HandheldPlatform.GB

    /**
     * Library title of a ROM file: the file name without its extension (No-Intro tags such as "(USA)" or "(Rev 1)" are
     * kept, since they tell versions apart). Underscores stand for spaces in names that have none.
     */
    fun cleanTitle(fileName: String): String {
        val base = fileName.replace('\\', '/').substringAfterLast('/')
        var t = if (isRomName(base) || isZipName(base)) base.substringBeforeLast('.') else base
        if (!t.contains(' ') && t.contains('_')) t = t.replace('_', ' ')
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.ifEmpty { "Untitled ROM" }
    }

    /**
     * Whether the ROMs found in a picked folder become the library entries instead of what the folder's own detection
     * found. A game folder that happens to contain a ROM (a confidently detected RPG Maker, Godot, web game…) stays one
     * game. Folders with only a Windows program besides the ROMs (an emulator for PC) become ROM lists when they hold
     * two or more ROMs.
     */
    fun romsTakePrecedence(root: DetectionResult, romCount: Int): Boolean = when {
        romCount <= 0 -> false
        root.engine == Engine.UNKNOWN -> true
        root.engine.family == com.acecore.app.core.model.EngineFamily.HANDHELD -> true
        root.engine == Engine.NATIVE_WINDOWS -> romCount >= 2
        root.engine == Engine.HTML5 && root.entry == null -> true
        else -> root.confidence < CollectionRules.CONFIDENT
    }

    /** ROM paths that don't lie inside one of [gameFolders] (top-level sub-folders detected as games of their own). */
    fun outsideGameFolders(romPath: String, gameFolders: Set<String>): Boolean {
        val top = romPath.replace('\\', '/').substringBefore('/', "")
        return top.isEmpty() || gameFolders.none { it.equals(top, ignoreCase = true) }
    }

    /** Name of the folder that holds a ROM ([rootName] for ROMs directly in the picked folder). */
    fun parentFolderName(romPath: String, rootName: String): String =
        romPath.replace('\\', '/').substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { rootName }

    /** The detection record of one ROM. [canLaunch] = this build can run the platform's engine. */
    fun detection(
        platform: HandheldPlatform, fileName: String, zipEntry: String?, path: String, size: Long, lastModified: Long,
        canLaunch: (Engine) -> Boolean,
    ): DetectionResult {
        val engine = Engine.of(platform)
        val runnable = canLaunch(engine)
        val diagnostics = buildList {
            if (zipEntry != null) add(Diagnostic(Severity.INFO, "The ROM is inside $fileName (${zipEntry.substringAfterLast('/')}); AceArcade unpacks it into its own storage to play it."))
            if (!runnable) add(Diagnostic(Severity.ERROR, "This APK doesn't include the ${engine.displayName} emulator."))
            if (platform == HandheldPlatform.NDS && runnable) add(Diagnostic(Severity.INFO, "Nintendo DS emulation is experimental; some games run slowly on slower phones."))
        }
        return DetectionResult(
            engine = engine,
            confidence = 100,
            entry = fileName,
            title = cleanTitle(zipEntry ?: fileName),
            diagnostics = diagnostics,
            scores = listOf(EngineScore(engine, 100, listOf("+100 ${platform.displayName} ROM (.${(zipEntry ?: fileName).substringAfterLast('.').lowercase()})"))),
            status = if (runnable) CompatStatus.PLAYABLE_UNTESTED else CompatStatus.RUNTIME_NOT_BUILT,
            tags = listOf("handheld"),
            rom = RomRef(fileName, platform.id, zipEntry, path, size, lastModified),
        )
    }
}

/** Reads the names in a zip archive's central directory (the end of the file), without reading the whole archive. */
object ZipListing {
    private const val EOCD = 0x06054b50L
    private const val ZIP64_LOCATOR = 0x07064b50L
    private const val ZIP64_EOCD = 0x06064b50L
    private const val CEN = 0x02014b50L
    private const val MAX_ENTRIES = 20_000
    private const val MAX_CD_BYTES = 8 shl 20

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)
    private fun u64(b: ByteArray, i: Int): Long = u32(b, i) or (u32(b, i + 4) shl 32)

    /**
     * Entry names of the zip archive of [size] bytes, or null when it isn't a readable zip. [read] returns up to
     * len bytes at pos (fewer only at the end of the file).
     */
    fun entryNames(size: Long, read: (pos: Long, len: Int) -> ByteArray): List<String>? = runCatching {
        if (size < 22) return null
        val tailLen = minOf(size, 22L + 65_535L).toInt()
        val tailStart = size - tailLen
        val tail = read(tailStart, tailLen)
        if (tail.size < 22) return null
        var e = -1
        for (i in tail.size - 22 downTo 0) if (u32(tail, i) == EOCD) { e = i; break }
        if (e < 0) return null
        var count = u16(tail, e + 10).toLong()
        var cdSize = u32(tail, e + 12)
        var cdOffset = u32(tail, e + 16)
        if ((cdOffset == 0xFFFFFFFFL || count == 0xFFFFL || cdSize == 0xFFFFFFFFL) && e >= 20 && u32(tail, e - 20) == ZIP64_LOCATOR) {
            val z = read(u64(tail, e - 20 + 8), 56)
            if (z.size >= 56 && u32(z, 0) == ZIP64_EOCD) {
                count = u64(z, 32); cdSize = u64(z, 40); cdOffset = u64(z, 48)
            }
        }
        if (cdSize <= 0 || cdSize > MAX_CD_BYTES || cdOffset < 0 || cdOffset + cdSize > size) return null
        val cd = read(cdOffset, cdSize.toInt())
        val out = ArrayList<String>()
        var p = 0
        while (p + 46 <= cd.size && out.size < minOf(count, MAX_ENTRIES.toLong())) {
            if (u32(cd, p) != CEN) break
            val flags = u16(cd, p + 8)
            val n = u16(cd, p + 28); val x = u16(cd, p + 30); val c = u16(cd, p + 32)
            if (p + 46 + n > cd.size) break
            out += String(cd, p + 46, n, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
            p += 46 + n + x + c
        }
        out
    }.getOrNull()
}
