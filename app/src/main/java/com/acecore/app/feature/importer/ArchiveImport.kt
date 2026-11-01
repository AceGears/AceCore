package com.acecore.app.feature.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel

/**
 * Adding a game that arrives as one file: a .zip or .7z archive, or a Windows .exe that is a self-extracting archive
 * (7-Zip or ZIP stub + archive). The archive is unpacked once into a new folder inside a folder the user chooses
 * (through the Storage Access Framework), and that folder is then scanned and imported like any game folder. The
 * original file is never changed.
 *
 * Windows installers and launchers (Inno Setup, NSIS, a downloader program) aren't archives of the game: they have to
 * run on Windows to produce the game folder. [inspect] recognises the common ones and says so plainly instead of
 * failing later.
 */
object ArchiveImport {
    sealed interface Kind {
        /** [offset]: where the archive starts inside the file (0 for a plain archive, after the stub for an .exe). */
        data class Zip(val offset: Long) : Kind
        data class SevenZip(val offset: Long) : Kind
        data class Unsupported(val reason: String) : Kind
    }

    private val SEVEN_Z = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private val INSTALLERS = listOf(
        "Inno Setup Setup Data" to "an Inno Setup installer",
        "Nullsoft Install System" to "an NSIS installer",
        "NullsoftInst" to "an NSIS installer",
        "InstallShield" to "an InstallShield installer",
        "Go build ID:" to "a Windows program (for example a launcher that downloads the game)",
    )

    fun displayName(resolver: ContentResolver, uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Game"

    /** Folder name for the unpacked game: the file name without its extension, made safe for a folder. */
    fun folderName(fileName: String): String =
        fileName.substringBeforeLast('.').ifBlank { fileName }.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifEmpty { "Game" }

    /** Finds what [file] is. Reads at most the first 64 MB when looking for an archive inside an .exe. */
    fun inspect(file: File): Kind {
        val head = ByteArray(8)
        val n = file.inputStream().use { it.read(head) }
        if (n >= 6 && head.copyOf(6).contentEquals(SEVEN_Z)) return Kind.SevenZip(0)
        if (n >= 4 && head.copyOf(4).contentEquals(ZIP_LOCAL)) return Kind.Zip(0)
        if (n < 2 || head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte()) {
            return Kind.Unsupported("This file isn't a ZIP or 7-Zip archive or a self-extracting .exe.")
        }
        // A Windows program: look for an archive appended after its code (self-extracting archive).
        val limit = minOf(file.length(), 64L shl 20).toInt()
        val data = ByteArray(limit)
        file.inputStream().use { input -> var off = 0; while (off < limit) { val r = input.read(data, off, limit - off); if (r < 0) break; off += r } }
        indexOf(data, SEVEN_Z, from = 64)?.let { return Kind.SevenZip(it.toLong()) }
        // ZIP self-extractors: the archive is read from the end of the file (its central directory), stub included.
        if (indexOf(data, ZIP_LOCAL, from = 64) != null && runCatching {
                ZipFile.builder().setFile(file).get().use { it.entries.hasMoreElements() }
            }.getOrDefault(false)
        ) return Kind.Zip(0)
        val text = String(data, 0, minOf(limit, 8 shl 20), Charsets.ISO_8859_1)
        val installer = INSTALLERS.firstOrNull { text.contains(it.first) }?.second
        return Kind.Unsupported(
            if (installer != null) "This is $installer, not the game itself. Run it on a Windows PC, then copy the folder it installs (the one with Game.exe) to your phone and add that folder."
            else "This Windows program doesn't contain a ZIP or 7-Zip archive AceArcade can unpack. If it's an installer or launcher, run it on a PC and add the folder it creates."
        )
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int? {
        val first = needle[0]
        var i = from
        val end = data.size - needle.size
        while (i <= end) {
            if (data[i] == first) {
                var k = 1
                while (k < needle.size && data[i + k] == needle[k]) k++
                if (k == needle.size) return i
            }
            i++
        }
        return null
    }

    /** Copies [src] into AceArcade's cache (archives need random access; SAF streams don't seek). */
    fun copyToCache(context: Context, src: Uri, name: String, progress: (Long) -> Unit): File {
        val dir = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, name.replace('/', '_'))
        context.contentResolver.openInputStream(src)!!.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16); var total = 0L
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    o.write(buf, 0, r); total += r
                    if (total % (8L shl 20) < r) progress(total)
                }
            }
        }
        return out
    }

    /** One file of the archive: [path] with "/" separators, [open] reads its contents. */
    private class Entry(val path: String, val isDirectory: Boolean, val open: () -> InputStream)

    /**
     * Unpacks [archive] into a new folder [folder] under the root of [treeUri]. Returns the new folder's document id.
     * Paths that would leave the folder ("../") are skipped.
     */
    fun extract(context: Context, archive: File, kind: Kind, treeUri: Uri, folder: String, progress: (Int, Int) -> Unit): String {
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val top = DocumentsContract.createDocument(resolver, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, folder)
            ?: error("Couldn't create the folder “$folder” there.")
        val dirs = HashMap<String, Uri>().apply { put("", top) }
        fun dirFor(path: String): Uri {
            dirs[path]?.let { return it }
            val parent = dirFor(path.substringBeforeLast('/', ""))
            val made = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, path.substringAfterLast('/'))
                ?: error("Couldn't create the folder $path")
            dirs[path] = made
            return made
        }
        fun write(e: Entry) {
            val clean = e.path.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() && it != "." }
            if (clean.any { it == ".." } || clean.isEmpty()) return
            val path = clean.joinToString("/")
            if (e.isDirectory) { dirFor(path); return }
            val parent = dirFor(path.substringBeforeLast('/', ""))
            val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", clean.last())
                ?: error("Couldn't create $path")
            resolver.openOutputStream(doc, "w")!!.use { out -> e.open().use { it.copyTo(out, 1 shl 16) } }
        }
        when (kind) {
            is Kind.Zip -> ZipFile.builder().setFile(archive).get().use { zip ->
                val entries = zip.entries.toList()
                entries.forEachIndexed { i, ze ->
                    progress(i + 1, entries.size)
                    write(Entry(ze.name, ze.isDirectory) { zip.getInputStream(ze) })
                }
            }
            is Kind.SevenZip -> SevenZFile.builder().setSeekableByteChannel(OffsetChannel(archive, kind.offset)).get().use { sz ->
                val total = sz.entries.count()
                var i = 0
                while (true) {
                    val e = sz.nextEntry ?: break
                    progress(++i, total)
                    if (e.isAntiItem) continue
                    write(Entry(e.name, e.isDirectory) { sz.getInputStream(e) })
                }
            }
            is Kind.Unsupported -> error(kind.reason)
        }
        return DocumentsContract.getDocumentId(top)
    }

    /** A read-only view of [file] that starts at [offset]: the archive inside a self-extracting .exe. */
    class OffsetChannel(file: File, private val offset: Long) : SeekableByteChannel {
        private val ch: FileChannel = RandomAccessFile(file, "r").channel
        private var pos = 0L
        override fun read(dst: ByteBuffer): Int {
            val n = ch.read(dst, offset + pos)
            if (n > 0) pos += n
            return n
        }
        override fun write(src: ByteBuffer): Int = throw java.nio.channels.NonWritableChannelException()
        override fun position(): Long = pos
        override fun position(newPosition: Long): SeekableByteChannel { pos = newPosition; return this }
        override fun size(): Long = ch.size() - offset
        override fun truncate(size: Long): SeekableByteChannel = throw java.nio.channels.NonWritableChannelException()
        override fun isOpen(): Boolean = ch.isOpen
        override fun close() = ch.close()
    }
}
