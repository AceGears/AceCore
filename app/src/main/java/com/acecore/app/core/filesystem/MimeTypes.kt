package com.acecore.app.core.filesystem

object MimeTypes {
    private val map = mapOf(
        "html" to "text/html", "htm" to "text/html", "js" to "text/javascript", "mjs" to "text/javascript",
        "css" to "text/css", "json" to "application/json", "map" to "application/json", "txt" to "text/plain",
        "xml" to "application/xml", "svg" to "image/svg+xml", "wasm" to "application/wasm",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "bmp" to "image/bmp", "ico" to "image/x-icon", "avif" to "image/avif",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "mp3" to "audio/mpeg", "m4a" to "audio/mp4",
        "wav" to "audio/wav", "aac" to "audio/aac", "flac" to "audio/flac", "mid" to "audio/midi", "midi" to "audio/midi",
        "mp4" to "video/mp4", "webm" to "video/webm", "ogv" to "video/ogg", "m4v" to "video/mp4",
        "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff", "woff2" to "font/woff2",
        "swf" to "application/x-shockwave-flash",
        // RPG Maker MV/MZ encrypted assets are fetched as binary blobs by the game's own decrypter.
        "rpgmvp" to "application/octet-stream", "rpgmvo" to "application/octet-stream", "rpgmvm" to "application/octet-stream",
        "png_" to "application/octet-stream", "ogg_" to "application/octet-stream", "m4a_" to "application/octet-stream",
    )

    fun forPath(path: String): String = map[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    fun isText(mime: String) = mime.startsWith("text/") || mime == "application/json" || mime == "application/xml"
}
