package com.acecore.app.runtime.web

/** Single-range HTTP "Range: bytes=a-b" parsing. Needed so audio/video seeking works for large game media. */
object RangeHeader {
    data class Range(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    /** Returns null when absent/unsupported (caller should send the full body), or the clamped range. */
    fun parse(header: String?, size: Long): Range? {
        if (header == null || size <= 0) return null
        val h = header.trim()
        if (!h.startsWith("bytes=", ignoreCase = true)) return null
        val spec = h.substring(6).split(',').first().trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        val range = when {
            a.isEmpty() && b.isNotEmpty() -> { // suffix range: last N bytes
                val n = b.toLongOrNull() ?: return null
                if (n <= 0) return null
                Range((size - n).coerceAtLeast(0), size - 1)
            }
            a.isNotEmpty() -> {
                val start = a.toLongOrNull() ?: return null
                val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(size - 1)
                Range(start, end)
            }
            else -> return null
        }
        return if (range.start < 0 || range.start >= size || range.endInclusive < range.start) null else range
    }
}
