package com.acecore.app.core.detection

/** One file or directory found inside a candidate game folder. [path] is relative, '/'-separated, no leading '/'. */
data class SnapshotEntry(val path: String, val isDirectory: Boolean, val size: Long = -1L)

/**
 * A bounded, read-only view of a game folder used by [DetectionEngine]. It is deliberately independent of
 * Android (SAF, java.io) so the detection rules are unit-testable on the JVM.
 *
 * Paths are matched case-insensitively because Windows games routinely reference "Graphics/" as "graphics/".
 */
class GameSnapshot(
    entries: List<SnapshotEntry>,
    /** Reads up to maxBytes from the file at the ORIGINAL (case-preserved) relative path. */
    private val reader: (path: String, maxBytes: Int) -> ByteArray?,
    /** True when the walk stopped early because of depth/entry limits. */
    val truncated: Boolean = false,
) {
    private val byLower: Map<String, SnapshotEntry> =
        entries.associateBy { normalize(it.path).lowercase() }

    val files: List<SnapshotEntry> = entries.filter { !it.isDirectory }
    val directories: List<SnapshotEntry> = entries.filter { it.isDirectory }

    fun exists(path: String): Boolean = byLower.containsKey(normalize(path).lowercase())
    fun isDir(path: String): Boolean = byLower[normalize(path).lowercase()]?.isDirectory == true
    fun isFile(path: String): Boolean = byLower[normalize(path).lowercase()]?.isDirectory == false
    fun entry(path: String): SnapshotEntry? = byLower[normalize(path).lowercase()]

    /** Files whose lowercase path matches [predicate]. */
    fun filesWhere(predicate: (lowerPath: String) -> Boolean): List<SnapshotEntry> =
        files.filter { predicate(it.path.lowercase()) }

    /** Direct children (files and dirs) of [dir] ("" for root). */
    fun childrenOf(dir: String): List<SnapshotEntry> {
        val prefix = normalize(dir).lowercase().let { if (it.isEmpty()) "" else "$it/" }
        return byLower.entries
            .filter { (k, _) -> k.startsWith(prefix) && k.length > prefix.length && !k.substring(prefix.length).contains('/') }
            .map { it.value }
    }

    fun readBytes(path: String, maxBytes: Int = 256 * 1024): ByteArray? {
        val e = entry(path) ?: return null
        if (e.isDirectory) return null
        return runCatching { reader(e.path, maxBytes) }.getOrNull()
    }

    fun readText(path: String, maxBytes: Int = 256 * 1024): String? =
        readBytes(path, maxBytes)?.let { bytes ->
            // Strip a UTF-8 BOM, common in RPG Maker JSON files.
            val s = String(bytes, Charsets.UTF_8)
            if (s.startsWith("﻿")) s.substring(1) else s
        }

    companion object {
        fun normalize(path: String): String =
            path.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }
}
