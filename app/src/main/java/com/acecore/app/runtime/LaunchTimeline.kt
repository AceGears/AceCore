package com.acecore.app.runtime

import android.os.SystemClock
import java.io.File

/**
 * Start-up timeline of the last launch, for measuring where launch time goes.
 *
 * Every event is stamped with SystemClock.elapsedRealtime() (CLOCK_BOOTTIME, monotonic and the same clock in every
 * process), so the launcher, the game process and the game's Ruby code (acecore_compat.rb, Process::CLOCK_BOOTTIME)
 * write to one comparable timeline in the game's helper folder. The engine's own step durations ("AceArcade timing:"
 * lines in the engine log) are read alongside.
 */
object LaunchTimeline {
    const val FILE = "timeline"

    data class Entry(val at: Long, val event: String)

    fun file(helperDir: File) = File(helperDir, FILE)

    fun now() = SystemClock.elapsedRealtime()

    fun mark(helperDir: File, event: String, at: Long = now()) {
        runCatching {
            helperDir.mkdirs()
            file(helperDir).appendText("$at\t${event.replace('\n', ' ').replace('\t', ' ')}\n")
        }
    }

    fun read(helperDir: File): List<Entry> = runCatching {
        file(helperDir).readLines().mapNotNull { line ->
            val at = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
            Entry(at, line.substringAfter('\t'))
        }.sortedBy { it.at }
    }.getOrDefault(emptyList())

    /** "AceArcade timing: file index 4210 ms" lines from the engine log → ("file index", "4210 ms"). */
    fun engineSteps(runtimeLog: String?): List<Pair<String, String>> =
        Regex("AceArcade timing: (.+?) (\\d+) ?ms(.*)").findAll(runtimeLog.orEmpty())
            .map { it.groupValues[1].trim() to (it.groupValues[2] + " ms" + it.groupValues[3].trim().let { s -> if (s.isEmpty()) "" else " $s" }) }
            .distinct().toList()

    fun format(entries: List<Entry>, engineSteps: List<Pair<String, String>> = emptyList()): String = buildString {
        if (entries.isEmpty()) { append("No launch timeline recorded yet."); return@buildString }
        val start = entries.first().at
        var prev = start
        entries.forEach { e ->
            append("+%6.2f s  (%+.2f)  %s\n".format((e.at - start) / 1000.0, (e.at - prev) / 1000.0, e.event))
            prev = e.at
        }
        if (engineSteps.isNotEmpty()) {
            append("Engine steps:\n")
            engineSteps.forEach { (name, v) -> append("  $name: $v\n") }
        }
    }
}
