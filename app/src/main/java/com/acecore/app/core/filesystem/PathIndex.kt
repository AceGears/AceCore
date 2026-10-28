package com.acecore.app.core.filesystem

import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.net.URLDecoder

/** A file known to the asset path cache. */
data class IndexedFile(val path: String, val docId: String, val size: Long)

/**
 * The AceCore asset path cache: maps every relative path in a game folder to its SAF document id so the
 * runtime never has to search the filesystem while a game runs. Lookups are case-insensitive and tolerate
 * Windows-style separators, which fixes the very common "Graphics/Faces/Actor1.PNG" vs "actor1.png" mismatch.
 *
 * Pure Kotlin (no Android types) so it is unit-testable.
 */
class PathIndex(files: Collection<IndexedFile>, val builtAt: Long = System.currentTimeMillis()) {

    private val exact = HashMap<String, IndexedFile>(files.size * 2)
    private val lower = HashMap<String, IndexedFile>(files.size * 2)

    init {
        for (f in files) {
            val n = normalize(f.path)
            exact[n] = f
            lower.putIfAbsent(n.lowercase(), f)
        }
    }

    val size: Int get() = exact.size
    val totalBytes: Long get() = exact.values.sumOf { it.size.coerceAtLeast(0) }
    fun all(): Collection<IndexedFile> = exact.values

    @Volatile var hits = 0L
        private set
    @Volatile var misses = 0L
        private set
    @Volatile var caseFixups = 0L
        private set

    fun lookup(path: String): IndexedFile? {
        val n = normalize(path)
        exact[n]?.let { hits++; return it }
        lower[n.lowercase()]?.let { hits++; caseFixups++; return it }
        misses++
        return null
    }

    fun writeTo(out: Writer) {
        out.write("ACEIDX1\t$builtAt\n")
        for (f in exact.values) {
            // Tabs/newlines are not legal in SAF display names on any provider we support; escape defensively.
            out.write(esc(f.path)); out.write("\t"); out.write(esc(f.docId)); out.write("\t"); out.write(f.size.toString()); out.write("\n")
        }
    }

    companion object {
        fun normalize(path: String): String {
            val parts = ArrayList<String>()
            for (p in path.replace('\\', '/').split('/')) {
                when {
                    p.isEmpty() || p == "." -> Unit
                    p == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) // never escapes the root
                    else -> parts += p
                }
            }
            return parts.joinToString("/")
        }

        /** Decodes a URL path ("img/faces/Actor%201.png") into a relative file path; never escapes the root. */
        fun fromUrlPath(urlPath: String): String {
            val decoded = runCatching { URLDecoder.decode(urlPath.replace("+", "%2B"), "UTF-8") }.getOrDefault(urlPath)
            return normalize(decoded)
        }

        fun readFrom(reader: BufferedReader): PathIndex? {
            val header = reader.readLine() ?: return null
            if (!header.startsWith("ACEIDX1\t")) return null
            val builtAt = header.substringAfter('\t').toLongOrNull() ?: 0L
            val files = ArrayList<IndexedFile>()
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.split('\t')
                if (parts.size != 3) continue
                files += IndexedFile(unesc(parts[0]), unesc(parts[1]), parts[2].toLongOrNull() ?: -1L)
            }
            return PathIndex(files, builtAt)
        }

        fun load(file: File): PathIndex? = runCatching {
            if (!file.exists()) null else file.bufferedReader().use { readFrom(it) }
        }.getOrNull()

        fun save(index: PathIndex, file: File) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { index.writeTo(it) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) } // atomic replace where the FS allows
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        private fun unesc(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}
