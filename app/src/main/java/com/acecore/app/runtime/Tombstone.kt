package com.acecore.app.runtime

/**
 * Minimal decoder for Android's native crash record (tombstone protobuf, API 31+, returned by
 * ApplicationExitInfo.getTraceInputStream for REASON_CRASH_NATIVE). Field numbers follow
 * system/core/debuggerd/proto/tombstone.proto. Only what a crash report needs is read: the signal, the fault
 * address, the abort message, Android's own cause analysis, the page size, and the backtrace and registers of the
 * thread that actually crashed (other threads are listed by name only, so they can't be mistaken for the cause).
 */
object Tombstone {
    data class Frame(val relPc: Long, val function: String, val functionOffset: Long, val file: String, val buildId: String)
    data class Thread(val id: Int, val name: String, val frames: List<Frame>, val registers: List<Pair<String, Long>>, val notes: List<String>)
    data class Summary(
        val pid: Int,
        val tid: Int,
        val signal: String?,
        val signalCode: String?,
        val faultAddress: Long?,
        val abortMessage: String?,
        val causes: List<String>,
        val pageSize: Int,
        val crashing: Thread?,
        val otherThreads: List<String>,
    ) {
        /** First frame of the crashing thread inside the app's own libraries (mkxp-z, Ruby, SDL…), else the top frame. */
        val culprit: Frame? get() = crashing?.frames?.firstOrNull { isAppLibrary(it.file) } ?: crashing?.frames?.firstOrNull()
    }

    fun isAppLibrary(file: String) = file.contains("/data/app/") || file.contains("base.apk")

    fun libName(file: String) = file.substringAfterLast('/').substringAfterLast('!')

    /** Returns null when the bytes aren't a tombstone protobuf (e.g. older Android's text format). */
    fun parse(bytes: ByteArray): Summary? = runCatching {
        var pid = 0; var tid = 0; var pageSize = 0
        var signal: String? = null; var code: String? = null; var fault: Long? = null
        var abort: String? = null
        val causes = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        Reader(bytes).forEach { field, wire, r ->
            when (field) {
                5 -> pid = r.uint().toInt()
                6 -> tid = r.uint().toInt()
                22 -> pageSize = r.uint().toInt()
                10 -> r.message { s ->
                    var hasFault = false; var addr = 0L
                    s.forEach { f, _, sr ->
                        when (f) {
                            2 -> signal = sr.string()
                            4 -> code = sr.string()
                            8 -> hasFault = sr.uint() != 0L
                            9 -> addr = sr.uint()
                            else -> sr.skip()
                        }
                    }
                    if (hasFault) fault = addr
                }
                14 -> abort = r.string().ifBlank { null }
                15 -> r.message { c -> c.forEach { f, _, cr -> if (f == 1) causes += cr.string() else cr.skip() } }
                16 -> r.message { entry -> // map<uint32, Thread>
                    entry.forEach { f, _, er -> if (f == 2) er.message { threads += readThread(it) } else er.skip() }
                }
                else -> r.skip(wire)
            }
        }
        if (signal == null && threads.isEmpty()) return null
        val crashing = threads.firstOrNull { it.id == tid }
        Summary(pid, tid, signal, code, fault, abort, causes, pageSize, crashing,
            threads.filter { it.id != tid }.map { "${it.name} (${it.id})" })
    }.getOrNull()

    private fun readThread(r: Reader): Thread {
        var id = 0; var name = ""
        val frames = mutableListOf<Frame>(); val regs = mutableListOf<Pair<String, Long>>(); val notes = mutableListOf<String>()
        r.forEach { f, _, tr ->
            when (f) {
                1 -> id = tr.uint().toInt()
                2 -> name = tr.string()
                3 -> tr.message { rr -> var n = ""; var v = 0L; rr.forEach { rf, _, x -> when (rf) { 1 -> n = x.string(); 2 -> v = x.uint(); else -> x.skip() } }; regs += n to v }
                4 -> tr.message { fr ->
                    var rel = 0L; var fn = ""; var off = 0L; var file = ""; var build = ""
                    fr.forEach { ff, _, x ->
                        when (ff) { 1 -> rel = x.uint(); 4 -> fn = x.string(); 5 -> off = x.uint(); 6 -> file = x.string(); 8 -> build = x.string(); else -> x.skip() }
                    }
                    frames += Frame(rel, fn, off, file, build)
                }
                7 -> notes += tr.string()
                else -> tr.skip()
            }
        }
        return Thread(id, name, frames, regs, notes)
    }

    fun format(s: Summary): String = buildString {
        appendLine("Signal: ${s.signal ?: "?"} (${s.signalCode ?: "?"})" + (s.faultAddress?.let { ", fault address 0x%x".format(it) } ?: ""))
        if (s.pageSize > 0) appendLine("Device memory page size: ${s.pageSize / 1024} KB")
        s.abortMessage?.let { appendLine("Abort message: $it") }
        s.causes.forEach { appendLine("Android's analysis: $it") }
        val t = s.crashing
        if (t == null) appendLine("Crashing thread: tid ${s.tid} (no backtrace recorded)") else {
            appendLine("Crashing thread: ${t.name} (tid ${t.id})")
            t.notes.forEach { appendLine("  note: $it") }
            t.frames.forEachIndexed { i, f ->
                val fn = if (f.function.isNotEmpty()) " (${f.function}+${f.functionOffset})" else ""
                val id = if (f.buildId.isNotEmpty()) " (BuildId: ${f.buildId})" else ""
                appendLine("  #%02d pc %016x  %s%s%s".format(i, f.relPc, f.file, fn, id))
            }
            val wanted = setOf("x0", "x1", "x2", "x3", "x8", "x19", "x20", "x21", "lr", "sp", "pc")
            t.registers.filter { it.first in wanted }.takeIf { it.isNotEmpty() }?.let { regs ->
                appendLine("  registers: " + regs.joinToString("  ") { "%s %016x".format(it.first, it.second) })
            }
        }
        if (s.otherThreads.isNotEmpty()) appendLine("Other threads (not the cause): ${s.otherThreads.joinToString()}")
    }

    /** Tiny protobuf wire-format reader. */
    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        private var wire = 0
        fun forEach(block: (field: Int, wire: Int, r: Reader) -> Unit) {
            while (pos < end) {
                val key = varint()
                wire = (key and 7).toInt()
                val start = pos
                block((key ushr 3).toInt(), wire, this)
                if (pos == start) skip(wire) // field not consumed by the block
            }
        }
        fun uint(): Long { require(wire == 0) { "wire type" }; return varint() }
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val x = b[pos++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                require(shift < 70) { "bad varint" }
            }
        }
        private fun len(): Int = varint().toInt().also { require(it >= 0 && pos + it <= end) { "bad length" } }
        fun string(): String { require(wire == 2) { "wire type" }; val n = len(); return String(b, pos, n, Charsets.UTF_8).also { pos += n } }
        fun message(block: (Reader) -> Unit) { require(wire == 2) { "wire type" }; val n = len(); block(Reader(b, pos, pos + n)); pos += n }
        fun skip() = skip(wire)
        fun skip(w: Int) {
            when (w) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val n = len(); pos += n } // not `pos += len()`: that reads pos before len() advances it
                5 -> pos += 4
                else -> error("unsupported wire type $w")
            }
        }
    }
}
