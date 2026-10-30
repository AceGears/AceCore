package com.acecore.app.runtime.web

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.filesystem.IndexedFile
import com.acecore.app.core.filesystem.MimeTypes
import com.acecore.app.core.filesystem.PathIndex
import com.acecore.app.core.filesystem.GameTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Serves a game's files to the WebView from the user's SAF folder, using the asset path cache for O(1)
 * case-insensitive lookups. All requests to other hosts are blocked unless the user allowed network access.
 * Path traversal outside the game folder is impossible: paths are normalised and only indexed files can be served.
 */
class WebGameServer(
    private val context: Context,
    private val spec: GameLaunchSpec,
    private val tree: GameTree,
    private val index: PathIndex,
) {
    private val allowNetwork = ConfigKey.ALLOW_NETWORK.bool(spec.config[ConfigKey.ALLOW_NETWORK.id] ?: "false")
    private val rootPrefix = if (spec.contentRoot.isEmpty()) "" else spec.contentRoot + "/"
    @Volatile var exitRequested: (() -> Unit)? = null
    @Volatile var blockedRequests = 0
        private set

    private val bridgeJs: ByteArray by lazy {
        val template = context.assets.open("acecore/bridge.js").use { it.readBytes().toString(Charsets.UTF_8) }
        val cfg = buildJsonObject {
            put("engine", spec.engine)
            put("sharp", spec.config[ConfigKey.FILTERING.id] == "sharp")
            put("fps", ConfigKey.SHOW_FPS.bool(spec.config[ConfigKey.SHOW_FPS.id] ?: "false"))
            put("volume", ConfigKey.MASTER_VOLUME.int(spec.config[ConfigKey.MASTER_VOLUME.id] ?: "100") / 100.0)
            put("exitUrl", JsonPrimitive("${spec.origin}/__acecore/exit"))
        }
        template.replace("__ACECORE_CONFIG__", Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), cfg)).toByteArray()
    }

    fun handle(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != spec.host) {
            if (url.scheme == "data" || url.scheme == "blob") return null
            if (allowNetwork) return null
            blockedRequests++
            return text(403, "Forbidden", "Network access is disabled for this game in AceArcade settings.")
        }
        val path = url.encodedPath ?: "/"
        if (path.startsWith("/__acecore/")) return internal(path)
        return try {
            serveFile(path, request.requestHeaders)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost for $path", e)
            text(403, "Forbidden", "AceArcade lost access to the game folder. Re-add it from the library.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serve $path", e)
            text(500, "Internal Error", e.message ?: "error")
        }
    }

    private fun internal(path: String): WebResourceResponse = when {
        path == "/__acecore/bridge.js" -> WebResourceResponse("text/javascript", "utf-8", 200, "OK", noStore(), ByteArrayInputStream(bridgeJs))
        path == "/__acecore/exit" -> { exitRequested?.invoke(); text(204, "No Content", "") }
        path == FLASH_HOST_PATH -> WebResourceResponse("text/html", "utf-8", 200, "OK", noStore(), ByteArrayInputStream(flashHostHtml))
        path.startsWith("/__acecore/ruffle/") -> ruffleAsset(path.removePrefix("/__acecore/ruffle/"))
        else -> text(404, "Not Found", "")
    }

    /** Ruffle (Flash) is bundled in app assets and served on the game's own origin so its WASM can load. */
    private fun ruffleAsset(name: String): WebResourceResponse {
        // Only plain file names from the bundled folder; never paths.
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.startsWith(".")) return text(404, "Not Found", "")
        val stream = runCatching { context.assets.open("ruffle/$name") }.getOrNull() ?: return text(404, "Not Found", "")
        val mime = MimeTypes.forPath(name)
        return WebResourceResponse(mime, if (MimeTypes.isText(mime)) "utf-8" else null, 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000"), stream)
    }

    private val flashHostHtml: ByteArray by lazy {
        val template = context.assets.open("acecore/flash.html").use { it.readBytes().toString(Charsets.UTF_8) }
        val swfUrl = "/" + spec.entryInRoot.split('/').joinToString("/") { Uri.encode(it) }
        val ruffleConfig = buildJsonObject {
            put("publicPath", "/__acecore/ruffle/")
            put("polyfills", false)
            put("autoplay", "on")
            put("unmuteOverlay", "hidden")
            put("splashScreen", false)
            put("letterbox", "on")
            put("contextMenu", "off")
            put("warnOnUnsupportedContent", false)
            put("showSwfDownload", false)
            put("upgradeToHttps", false)
            put("openUrlMode", "deny")
            put("allowScriptAccess", false)
            put("allowNetworking", if (allowNetwork) "all" else "internal")
            put("logLevel", "warn")
        }
        template
            .replace("__RUFFLE_CONFIG__", Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), ruffleConfig))
            .replace("__SWF_URL__", Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(swfUrl)))
            .toByteArray()
    }

    private fun serveFile(urlPath: String, headers: Map<String, String>): WebResourceResponse {
        var rel = PathIndex.fromUrlPath(urlPath)
        if (rel.isEmpty()) rel = spec.entryInRoot
        val file = index.lookup(rootPrefix + rel)
            ?: index.lookup(rootPrefix + rel + "/index.html")
            ?: return text(404, "Not Found", "")
        val mime = MimeTypes.forPath(file.path)

        // Inject the AceCore bridge into HTML documents (input, pause/resume, exit, overlays).
        if (mime == "text/html") {
            val html = tree.open(file.docId)?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: return text(404, "Not Found", "")
            return WebResourceResponse("text/html", "utf-8", 200, "OK", noStore(), ByteArrayInputStream(injectBridge(html).toByteArray()))
        }

        val size = file.size
        val range = RangeHeader.parse(headers.entries.firstOrNull { it.key.equals("Range", true) }?.value, size)
        val stream = openStream(file) ?: return text(404, "Not Found", "")
        val charset = if (MimeTypes.isText(mime)) "utf-8" else null
        val baseHeaders = mutableMapOf("Access-Control-Allow-Origin" to "*", "Accept-Ranges" to "bytes", "Cache-Control" to "no-cache")
        if (range != null) {
            skipFully(stream, range.start)
            baseHeaders["Content-Range"] = "bytes ${range.start}-${range.endInclusive}/$size"
            baseHeaders["Content-Length"] = range.length.toString()
            return WebResourceResponse(mime, charset, 206, "Partial Content", baseHeaders, BoundedInputStream(stream, range.length))
        }
        if (size >= 0) baseHeaders["Content-Length"] = size.toString()
        return WebResourceResponse(mime, charset, 200, "OK", baseHeaders, stream)
    }

    private fun openStream(file: IndexedFile): InputStream? = tree.open(file.docId)

    private fun injectBridge(html: String): String {
        val tag = "<script src=\"/__acecore/bridge.js\"></script>"
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
        else {
            val htmlTag = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
            if (htmlTag != null) html.substring(0, htmlTag.range.last + 1) + tag + html.substring(htmlTag.range.last + 1) else tag + html
        }
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val skipped = s.skip(left)
            if (skipped > 0) { left -= skipped; continue }
            val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r <= 0) break
            left -= r
        }
    }

    private fun noStore() = mapOf("Cache-Control" to "no-store")

    private fun text(code: Int, reason: String, body: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, noStore(), ByteArrayInputStream(body.toByteArray()))

    /** Limits reads to [limit] bytes for 206 responses. */
    private class BoundedInputStream(private val inner: InputStream, private var limit: Long) : InputStream() {
        override fun read(): Int {
            if (limit <= 0) return -1
            val r = inner.read()
            if (r >= 0) limit--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val r = inner.read(b, off, minOf(len.toLong(), limit).toInt())
            if (r > 0) limit -= r
            return r
        }
        override fun close() = inner.close()
    }

    companion object {
        private const val TAG = "AceArcadeWeb"

        /** Loads the cached index or walks the folder to build one. */
        fun loadOrBuildIndex(
            tree: GameTree, spec: GameLaunchSpec, cacheFile: File, progress: (Int) -> Unit,
        ): PathIndex {
            if (!spec.forceReindex) {
                PathIndex.load(cacheFile)?.let { cached ->
                    // Validate: the entry must still exist in the cached index; otherwise rebuild.
                    if (cached.lookup(spec.entry) != null) return cached
                }
            }
            val files = ArrayList<IndexedFile>()
            val queue = ArrayDeque<Pair<String, String>>() // docId, path
            queue.add(spec.rootDocId to "")
            while (queue.isNotEmpty()) {
                val (docId, path) = queue.removeFirst()
                for (n in tree.children(docId, path)) {
                    if (n.isDirectory) queue.add(n.docId to n.path)
                    else {
                        files += IndexedFile(n.path, n.docId, n.size)
                        if (files.size % 250 == 0) progress(files.size)
                    }
                    if (files.size > MAX_FILES) throw IllegalStateException("Game folder has more than $MAX_FILES files")
                }
            }
            val index = PathIndex(files)
            runCatching { PathIndex.save(index, cacheFile) }
            return index
        }

        const val MAX_FILES = 300_000
        const val FLASH_HOST_PATH = "/__acecore/flash.html"

        /** URL the WebView opens for [spec]: the game's HTML entry, or the Ruffle host page for .swf games. */
        fun startUrl(spec: GameLaunchSpec): String =
            if (spec.entry.endsWith(".swf", ignoreCase = true)) spec.origin + FLASH_HOST_PATH
            else "${spec.origin}/${spec.entryInRoot.split('/').joinToString("/") { Uri.encode(it) }}"

        @Suppress("unused")
        fun documentUri(spec: GameLaunchSpec, docId: String): Uri =
            android.provider.DocumentsContract.buildDocumentUriUsingTree(Uri.parse(spec.treeUri), docId)
    }
}
