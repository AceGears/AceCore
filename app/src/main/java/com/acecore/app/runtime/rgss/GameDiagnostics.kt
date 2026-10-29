package com.acecore.app.runtime.rgss

import com.acecore.app.runtime.LaunchTimeline
import java.io.File

/**
 * What the running game reports about itself (written by acecore_compat.rb into the game's helper folder):
 * a heartbeat about once a second, a scene/map log and the launch timeline.
 */
object GameDiagnostics {
    data class Heartbeat(val frame: Long, val scene: String, val map: String, val ageMs: Long)

    fun heartbeat(helperDir: File): Heartbeat? = runCatching {
        val f = File(helperDir, "heartbeat")
        if (!f.exists()) return null
        val p = f.readText().trim().split(' ')
        Heartbeat(p[0].toLong(), p.getOrElse(1) { "-" }, p.getOrElse(2) { "-" }, System.currentTimeMillis() - f.lastModified())
    }.getOrNull()

    fun sceneLog(helperDir: File, lines: Int = 25): String? = runCatching {
        File(helperDir, "scenes").readLines().takeLast(lines).joinToString("\n") { l ->
            l.substringAfter('\t')
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Timeline + engine steps + scene log, as plain text for reports. */
    fun summary(helperDir: File): String = buildString {
        val runtimeLog = runCatching { File(helperDir, "runtime.log").readText() }.getOrNull()
        appendLine("== Launch timeline ==")
        append(LaunchTimeline.format(LaunchTimeline.read(helperDir), LaunchTimeline.engineSteps(runtimeLog)))
        sceneLog(helperDir)?.let { appendLine(); appendLine("== Scenes and maps (latest last) =="); appendLine(it) }
        heartbeat(helperDir)?.let { appendLine(); appendLine("== Last heartbeat ==\nframe ${it.frame}, scene ${it.scene}, map ${it.map}, ${it.ageMs / 1000} s ago") }
    }
}
