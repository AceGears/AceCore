package com.acecore.app.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.acecore.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What AceCore found out about a game process that ended without reaching the game. */
data class LaunchFailure(
    val gameId: Long,
    val title: String,
    /** Last launch stage that was reached (from the runtime helper's markers). */
    val stage: String,
    /** Short, factual cause; never invented ("not identified" when nothing was reported). */
    val cause: String,
    /** True when the cause comes from an actual error/crash record. */
    val identified: Boolean,
    /** Full local report (also saved under crashreports/). */
    val report: String,
    val reportFile: File?,
    val wasDirect: Boolean,
    val helperUsed: Boolean,
)

/**
 * Local, privacy-conscious crash reports for game processes. Nothing is uploaded. Sources, in order of reliability:
 *  1. Android's own record of how the process ended (ApplicationExitInfo, API 30+), including the native crash
 *     tombstone (API 31+) for SIGSEGV/SIGABRT crashes in mkxp-z, Ruby or SDL;
 *  2. a Java/Kotlin stack written by the game process's uncaught-exception handler;
 *  3. a Ruby exception reported by AceCore's runtime helper;
 *  4. the game process's own log (SDL / mkxp-z / Ruby messages) captured while it ran.
 */
object CrashReports {
    fun dir(context: Context) = File(context.applicationContext.filesDir, "crashreports")

    /** Written by the game process (uncaught Java/Kotlin exception). */
    fun javaCrashFile(helperDir: File) = File(helperDir, "java_crash.txt")

    fun list(context: Context): List<File> = dir(context).listFiles().orEmpty().filter { it.name.endsWith(".txt") }.sortedByDescending { it.lastModified() }

    fun clear(context: Context) { dir(context).deleteRecursively() }

    /** Removes personal storage paths from reports. */
    fun redact(text: String): String = text
        .replace(Regex("/storage/emulated/\\d+"), "<storage>")
        .replace(Regex("/data/user/\\d+/[A-Za-z0-9_.]+"), "<app-data>")
        .replace(Regex("/data/data/[A-Za-z0-9_.]+"), "<app-data>")

    data class ExitRecord(val reason: String, val description: String?, val status: Int, val trace: String?, val signal: String?)

    /** The most recent exit of the ":rgss" (or ":game") process after [since], as recorded by Android. */
    fun lastExit(context: Context, processSuffix: String, since: Long): ExitRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 15) }.getOrNull().orEmpty()
            .firstOrNull { it.processName.endsWith(processSuffix) && it.timestamp >= since - 2_000 } ?: return null
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed by Android: low memory"
            ApplicationExitInfo.REASON_EXIT_SELF -> "Exited normally"
            ApplicationExitInfo.REASON_SIGNALED -> "Killed by a signal"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialisation failure"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed by Android: excessive resource use"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "Stopped by the user"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "A process it depended on died"
            else -> "Ended (reason code ${info.reason})"
        }
        val trace = runCatching {
            info.traceInputStream?.use { input ->
                val bytes = input.readBytes()
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) printableStrings(bytes) else String(bytes).take(20_000)
            }
        }.getOrNull()
        val signal = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_SIGNALED) signalName(info.status) else null
        return ExitRecord(reason, info.description, info.status, trace, signal)
    }

    /** Native tombstones are protobuf (API 31+); the readable strings (signal, abort message, frames) are enough here. */
    fun printableStrings(bytes: ByteArray, min: Int = 5): String {
        val out = StringBuilder()
        val cur = StringBuilder()
        fun flush() { if (cur.length >= min) out.append(cur).append('\n'); cur.setLength(0) }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) cur.append(c.toChar()) else flush()
        }
        flush()
        return out.toString().lines().distinct().take(400).joinToString("\n")
    }

    fun signalName(sig: Int): String = when (sig) {
        4 -> "SIGILL"; 5 -> "SIGTRAP"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"; 15 -> "SIGTERM"
        else -> "signal $sig"
    }

    /** The shared libraries named in a native crash, in the order they appear (e.g. libmkxp-z.so, libruby.so). */
    fun librariesIn(trace: String?): List<String> =
        Regex("lib[A-Za-z0-9_+.-]+\\.so").findAll(trace.orEmpty()).map { it.value }.distinct().take(6).toList()

    fun save(context: Context, gameId: Long, text: String): File? = runCatching {
        val d = dir(context).apply { mkdirs() }
        val f = File(d, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "_game$gameId.txt")
        f.writeText(text)
        // Keep the newest 30 reports.
        list(context).drop(30).forEach { it.delete() }
        f
    }.getOrNull()

    fun header(gameTitle: String, engine: String, stage: String): String = buildString {
        appendLine("AceCore ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Game: $gameTitle · Engine: $engine · Runtime: ${RuntimeRegistry.info(RuntimeId.RGSS_MKXPZ).version ?: "mkxp-z"}")
        appendLine("Last stage reached: $stage")
    }
}
