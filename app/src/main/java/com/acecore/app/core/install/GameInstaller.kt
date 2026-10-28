package com.acecore.app.core.install

import com.acecore.app.core.filesystem.PathIndex
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One file in the source folder (relative path, size, last-modified ms; -1/0 when unknown). */
data class SourceFile(val path: String, val size: Long, val lastModified: Long)

/** Abstraction over the user's game folder so the installer is testable without Android. */
interface InstallSource {
    fun list(): List<SourceFile>
    fun open(path: String): InputStream
}

data class InstallProgress(val copiedBytes: Long, val totalBytes: Long, val filesDone: Int, val filesTotal: Int, val current: String?) {
    val fraction: Float get() = if (totalBytes <= 0) (if (filesTotal == 0) 1f else filesDone.toFloat() / filesTotal) else (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class InstallResult(val copied: Int, val skipped: Int, val removed: Int, val bytesCopied: Long)

class InsufficientSpaceException(val needed: Long, val available: Long) :
    IOException("Not enough free space: need ${needed / 1_048_576} MB, ${available / 1_048_576} MB available")

/**
 * Keeps a private working copy of a game folder in sync with the user's original, for runtimes that need real
 * file paths (mkxp-z, EasyRPG…). The original folder is only ever read.
 *
 * Rules:
 *  - Unchanged files (same size + modification time as last sync) are skipped, so relaunching is instant.
 *  - Files the GAME creates in its copy (saves, configs) are never deleted or overwritten by a sync.
 *  - Files removed from the original are removed from the copy only if the copy is still exactly as installed.
 *  - Every file is written to a temp name and renamed, so an interrupted sync never leaves a half-written file.
 */
class GameInstaller(
    private val target: File,
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    private val manifestFile = File(target, MANIFEST)

    private data class Entry(val size: Long, val sourceModified: Long, val installedModified: Long)

    fun install(source: InstallSource, isCancelled: () -> Boolean = { false }, onProgress: (InstallProgress) -> Unit = {}): InstallResult {
        target.mkdirs()
        val previous = readManifest()
        val originalPath = HashMap<String, String>()
        val files = source.list()
            .map { src -> src.copy(path = PathIndex.normalize(src.path)).also { originalPath.putIfAbsent(it.path, src.path) } }
            .distinctBy { it.path }
            .filter { it.path.isNotEmpty() && it.path != MANIFEST && !it.path.startsWith("$MANIFEST.") }

        val todo = files.filter { f -> needsCopy(f, previous[f.path]) }
        val todoPaths = todo.mapTo(HashSet()) { it.path }
        val totalBytes = todo.sumOf { it.size.coerceAtLeast(0) }
        val available = freeSpace(target)
        if (available in 0 until totalBytes + SPACE_MARGIN) throw InsufficientSpaceException(totalBytes + SPACE_MARGIN, available)

        val next = HashMap<String, Entry>(files.size * 2)
        var copiedBytes = 0L
        var done = 0
        val buf = ByteArray(256 * 1024)
        onProgress(InstallProgress(0, totalBytes, 0, todo.size, null))
        for (f in files) {
            val prev = previous[f.path]
            val out = File(target, f.path)
            if (f.path !in todoPaths) { if (prev != null) next[f.path] = prev; continue }
            if (isCancelled()) { writeManifest(previous + next); throw InstallCancelledException() }
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, ".${out.name}.acecore-tmp")
            source.open(originalPath[f.path] ?: f.path).use { input ->
                tmp.outputStream().use { o ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                        copiedBytes += n
                    }
                }
            }
            if (out.exists() && !out.delete()) throw IOException("Cannot replace ${f.path}")
            if (!tmp.renameTo(out)) throw IOException("Cannot write ${f.path}")
            next[f.path] = Entry(f.size, f.lastModified, out.lastModified())
            done++
            onProgress(InstallProgress(copiedBytes, totalBytes, done, todo.size, f.path))
        }

        // Remove files that disappeared from the original, but only if the copy is untouched since we installed it.
        var removed = 0
        val currentPaths = files.mapTo(HashSet()) { it.path }
        for ((path, e) in previous) {
            if (path in currentPaths) continue
            val f = File(target, path)
            if (f.exists() && f.lastModified() == e.installedModified && (e.size < 0 || f.length() == e.size)) { if (f.delete()) removed++ }
        }
        writeManifest(next)
        return InstallResult(copied = done, skipped = files.size - todo.size, removed = removed, bytesCopied = copiedBytes)
    }

    /** True when a usable copy exists (at least one successful sync). */
    fun isInstalled(): Boolean = manifestFile.exists()

    fun sizeOnDisk(): Long = target.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun needsCopy(f: SourceFile, prev: Entry?): Boolean {
        val out = File(target, f.path)
        // New in the source: if the game already created a file with this name in its copy (e.g. a save), keep the game's.
        if (prev == null) return !out.exists()
        if (!out.exists()) return true
        // If the provider can't report size/date, we can't tell whether it changed: re-copy (when the game hasn't edited it).
        val unknown = f.size < 0 || f.lastModified <= 0
        val sourceChanged = unknown || prev.size != f.size || prev.sourceModified != f.lastModified
        if (!sourceChanged) return false
        // Source changed. If the game modified its copy (e.g. a save that also exists in the original), keep the game's version.
        return out.lastModified() == prev.installedModified
    }

    private fun readManifest(): Map<String, Entry> {
        if (!manifestFile.exists()) return emptyMap()
        return runCatching {
            manifestFile.readLines().drop(1).mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 4) null else p[0] to Entry(p[1].toLong(), p[2].toLong(), p[3].toLong())
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeManifest(entries: Map<String, Entry>) {
        val tmp = File(target, "$MANIFEST.tmp")
        tmp.bufferedWriter().use { w ->
            w.write("ACEINST1\n")
            for ((path, e) in entries) w.write("$path\t${e.size}\t${e.sourceModified}\t${e.installedModified}\n")
        }
        if (manifestFile.exists()) manifestFile.delete()
        tmp.renameTo(manifestFile)
    }

    companion object {
        const val MANIFEST = ".acecore-install"
        const val SPACE_MARGIN = 32L * 1024 * 1024
    }
}

class InstallCancelledException : IOException("Cancelled")
