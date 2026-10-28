package com.acecore.app.core.filesystem

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.acecore.app.core.detection.GameSnapshot
import com.acecore.app.core.detection.SnapshotEntry
import java.io.InputStream

/** A document inside a SAF tree, with its path relative to the walk root. */
data class SafNode(val docId: String, val name: String, val path: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

/**
 * Fast Storage Access Framework access using raw DocumentsContract queries (one query per directory),
 * which is an order of magnitude faster than DocumentFile.listFiles()/findFile() on large game folders.
 */
class SafTree(private val resolver: ContentResolver, val treeUri: Uri) {

    val rootDocId: String get() = DocumentsContract.getTreeDocumentId(treeUri)

    fun documentUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    fun children(parentDocId: String, parentPath: String): List<SafNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = ArrayList<SafNode>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val iMod = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: continue
                if (name == "." || name == "..") continue
                val isDir = c.getString(iMime) == DocumentsContract.Document.MIME_TYPE_DIR
                out += SafNode(
                    docId = c.getString(iId) ?: continue,
                    name = name,
                    path = if (parentPath.isEmpty()) name else "$parentPath/$name",
                    isDirectory = isDir,
                    size = if (c.isNull(iSize)) -1L else c.getLong(iSize),
                    lastModified = if (c.isNull(iMod)) 0L else c.getLong(iMod),
                )
            }
        }
        return out
    }

    data class WalkResult(val nodes: List<SafNode>, val truncated: Boolean)

    /** Breadth-first walk. Stops at [maxDepth] (0 = only direct children) or [maxEntries]. */
    fun walk(rootDocId: String, maxDepth: Int, maxEntries: Int, isCancelled: () -> Boolean = { false }): WalkResult {
        val out = ArrayList<SafNode>()
        val queue = ArrayDeque<Pair<SafNode?, Int>>()
        queue.add(null to 0)
        var truncated = false
        while (queue.isNotEmpty()) {
            if (isCancelled()) { truncated = true; break }
            val (dir, depth) = queue.removeFirst()
            val kids = children(dir?.docId ?: rootDocId, dir?.path ?: "")
            for (k in kids) {
                if (out.size >= maxEntries) { truncated = true; break }
                out += k
                if (k.isDirectory) {
                    if (depth < maxDepth) queue.add(k to depth + 1) else truncated = true
                }
            }
            if (out.size >= maxEntries) { truncated = true; break }
        }
        return WalkResult(out, truncated)
    }

    /** Finds a file or folder by '/'-separated relative path (case-insensitive), one query per path segment. */
    fun findPath(rootDocId: String, path: String): SafNode? {
        var parentId = rootDocId
        var parentPath = ""
        var node: SafNode? = null
        for (seg in path.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            node = children(parentId, parentPath).firstOrNull { it.name.equals(seg, ignoreCase = true) } ?: return null
            parentId = node.docId
            parentPath = node.path
        }
        return node
    }

    fun open(docId: String): InputStream? = resolver.openInputStream(documentUri(docId))

    fun read(docId: String, maxBytes: Int): ByteArray? = open(docId)?.use { input ->
        val buf = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val n = input.read(buf, total, maxBytes - total)
            if (n <= 0) break
            total += n
        }
        buf.copyOf(total)
    }

    /** Builds a detection snapshot of the folder [rootDocId]. Bounded so huge folders stay responsive. */
    fun snapshot(rootDocId: String, maxDepth: Int = 4, maxEntries: Int = 6000): GameSnapshot {
        val walk = walk(rootDocId, maxDepth, maxEntries)
        val idByPath = walk.nodes.associate { it.path to it.docId }
        return GameSnapshot(
            entries = walk.nodes.map { SnapshotEntry(it.path, it.isDirectory, it.size) },
            reader = { path, max -> idByPath[path]?.let { read(it, max) } },
            truncated = walk.truncated,
        )
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }
}
