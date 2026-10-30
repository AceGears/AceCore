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
    /** Plain-language diagnosis for the dialog (never a raw dump). */
    val diagnosis: LaunchDiagnosis.Result = LaunchDiagnosis.Result("", "", LaunchDiagnosis.Fix.RETRY),
)

/**
 * Turns the evidence of a failed launch into a plain-language issue and ONE recommended, safe next step. Rules are
 * evidence-based (they look at what actually failed); unknown failures get a plain retry, never a cycle of settings.
 */
object LaunchDiagnosis {
    enum class Fix { NONE, RETRY, NO_HELPER, PRIVATE_COPY }
    data class Result(val issue: String, val recommendation: String, val fix: Fix)

    fun classify(cause: String, rubyError: String?, engineCrash: String?, nativeCrash: Boolean, helperUsed: Boolean, wasDirect: Boolean): Result {
        val ruby = rubyError.orEmpty()
        val bug = engineCrash.orEmpty()
        return when {
            // AceArcade's own helper/compatibility script is in the failing backtrace: retry without the helper.
            helperUsed && (ruby.contains("acecore_preload") || ruby.contains("acecore_runtime")) -> Result(
                "AceArcade's game helper hit an error with this game's scripts.",
                "Start the game without the helper (cheats and the in-game AceArcade menu tools are off for that session).", Fix.NO_HELPER)
            // Files the engine can't open in the user's folder: a private copy has plain file semantics.
            wasDirect && Regex("(?i)PHYSFS|No such file|Permission denied|Errno::E(ACCES|NOENT|PERM)").containsMatchIn(ruby + bug) -> Result(
                "The engine couldn't open some of the game's files in their folder.",
                "Run the game from AceArcade's private copy of the folder (made once; your original files are not changed).", Fix.PRIVATE_COPY)
            bug.isNotEmpty() && Regex("(?i)stack level too deep|stack overflow|SystemStackError").containsMatchIn(bug) -> Result(
                "The game's scripts needed more memory for nested code than the engine had.",
                "Retry. If it happens again, send the report: this game needs a larger engine stack than AceArcade provides.", Fix.RETRY)
            bug.isNotEmpty() -> Result(
                "The Ruby engine inside AceArcade crashed while starting the game's scripts.",
                "Retry once. If it happens again, the game uses something this engine version can't run yet; the report shows where.", Fix.RETRY)
            ruby.isNotEmpty() -> Result(
                "The game's own scripts stopped with an error.",
                "This usually means the game needs a feature AceArcade's engine doesn't provide yet. Retrying won't change it; the report shows the script and line.", Fix.NONE)
            nativeCrash -> Result(
                "The game engine crashed (native code).",
                "Retry once. If it happens again, the report names the crashing library.", Fix.RETRY)
            else -> Result(
                if (cause.startsWith("The game process ended without")) "The game stopped during start-up without reporting why." else cause,
                "Retry once. If it happens again, the full report has the details.", Fix.RETRY)
        }
    }

    /**
     * Ruby's crash dump ("[BUG] Segmentation fault ...") ends with the process memory map, which says nothing about the
     * cause. Returns the [BUG] line and the dump up to the memory map / loaded-features list, or null if it isn't one.
     */
    fun rubyCrash(output: String): Pair<String, String>? {
        val lines = output.lines()
        val start = lines.indexOfFirst { it.contains("[BUG]") }.takeIf { it >= 0 } ?: return null
        val kept = ArrayList<String>()
        var skipping = false
        for (l in lines.drop(start)) {
            if (l.startsWith("* Process memory map") ) break
            if (l.startsWith("* Loaded features")) { skipping = true; kept += "* Loaded features: (omitted)"; continue }
            if (skipping && (l.startsWith("* ") || l.startsWith("-- "))) skipping = false
            if (!skipping) kept += l
        }
        // AceArcade's engine patch prints the native call chain just before Ruby's dump: keep it, it names the
        // C function that crashed (Ruby's own dump has no native backtrace on Android).
        // Also keeps AceArcade's own start-up notes ("AceArcade: ...") printed before the crash.
        val nb = lines.indexOfFirst { it.startsWith("AceArcade native backtrace (") }
        val nbEnd = if (nb < 0) -1 else lines.indexOfFirst { it.startsWith("AceArcade native backtrace end") }.let { if (it < nb) minOf(lines.size, nb + 70) else it + 1 }
        val notes = lines.subList(0, start).withIndex()
            .filter { (i, l) -> l.startsWith("AceArcade") || (nb >= 0 && i in nb until nbEnd) }
            .map { it.value }.takeLast(120)
        val native = if (notes.isEmpty()) "" else notes.joinToString("\n") + "\n\n"
        return lines[start].trim() to (native + kept.joinToString("\n")).take(20_000)
    }
}

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

    data class ExitRecord(
        val reason: String, val description: String?, val status: Int, val trace: String?, val signal: String?,
        /** Decoded native crash record (API 31+), when Android provided one. */
        val tombstone: Tombstone.Summary? = null,
    )

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
        val bytes = runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull()
        val native = info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        val tomb = if (native && bytes != null) Tombstone.parse(bytes) else null
        val trace = when {
            bytes == null -> null
            tomb != null -> Tombstone.format(tomb)
            native -> printableStrings(bytes) // pre-protobuf format or undecodable: readable strings only
            else -> String(bytes).take(20_000)
        }
        val signal = if (native || info.reason == ApplicationExitInfo.REASON_SIGNALED) signalName(info.status) else null
        return ExitRecord(reason, info.description, info.status, trace, signal, tomb)
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

    /**
     * The shared libraries named in a native crash, in the order they appear. Only meaningful for undecoded traces,
     * where it can include libraries from threads that did not crash; prefer [Tombstone.Summary.culprit].
     */
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
        appendLine("AceArcade ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Game: $gameTitle · Engine: $engine · Runtime: ${RuntimeRegistry.info(RuntimeId.RGSS_MKXPZ).version ?: "mkxp-z"}")
        appendLine("Last stage reached: $stage")
    }
}
