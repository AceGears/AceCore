package com.acecore.app.core.scripts

import com.acecore.app.core.detection.Diagnostic
import com.acecore.app.core.detection.Severity
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

data class GameScript(val id: Long, val name: String, val code: String)

data class ScriptReport(
    val scriptCount: Int,
    val totalChars: Long,
    /** e.g. "21.1" from ESSENTIALS_VERSION / Essentials::VERSION. */
    val essentialsVersion: String?,
    val mkxpAware: Boolean,
    /** DLL name → script names that call it via Win32API. */
    val windowsDlls: Map<String, List<String>>,
    val nativeExtensions: List<String>,
    val diagnostics: List<Diagnostic>,
)

/**
 * Script Compatibility Analyzer for RGSS games: decodes Data/Scripts.* (Ruby Marshal + zlib) and flags code that
 * cannot work on Android, such as Windows DLL calls. Pure Kotlin, so it is unit-tested on the JVM.
 */
object ScriptAnalyzer {

    /** DLLs whose Win32API usage mkxp-z (or harmless no-ops) cover well enough that we only mention them. */
    private val BENIGN_DLLS = setOf("kernel32", "user32")

    fun decode(scriptsFile: ByteArray): List<GameScript> {
        val root = RubyMarshal.read(scriptsFile) as? List<*> ?: throw MarshalException("Scripts file is not an array")
        return root.mapNotNull { entry ->
            val e = entry as? List<*> ?: return@mapNotNull null
            if (e.size < 3) return@mapNotNull null
            val id = (e[0] as? Long) ?: 0L
            val name = (e[1] as? RubyMarshal.RString)?.text() ?: ""
            val compressed = (e[2] as? RubyMarshal.RString)?.bytes ?: return@mapNotNull null
            GameScript(id, name, inflate(compressed))
        }
    }

    private fun inflate(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val inf = Inflater()
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 32 * 1024 * 1024) break // defensive cap
            }
            return out.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            return ""
        } finally {
            inf.end()
        }
    }

    private val WIN32API = Regex("""Win32API\.new\s*\(?\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""")
    private val ESS_VERSION = listOf(
        Regex("""ESSENTIALS_VERSION\s*=\s*["']([^"']+)["']"""),
        Regex("""module\s+Essentials\s+VERSION\s*=\s*["']([^"']+)["']"""),
        Regex("""Essentials::VERSION\s*=\s*["']([^"']+)["']"""),
    )
    private val NATIVE_REQUIRE = Regex("""require\s*\(?\s*['"]([^'"]+\.(?:so|dll))['"]""")

    fun analyze(scripts: List<GameScript>): ScriptReport {
        val dlls = LinkedHashMap<String, MutableSet<String>>()
        val natives = LinkedHashSet<String>()
        var essentials: String? = null
        var mkxpAware = false
        var usesFiddle = false
        for (s in scripts) {
            WIN32API.findAll(s.code).forEach { m ->
                val dll = m.groupValues[1].lowercase().removeSuffix(".dll")
                dlls.getOrPut(dll) { LinkedHashSet() } += s.name.ifBlank { "#${s.id}" }
            }
            NATIVE_REQUIRE.findAll(s.code).forEach { natives += it.groupValues[1] }
            if (essentials == null) essentials = ESS_VERSION.firstNotNullOfOrNull { it.find(s.code)?.groupValues?.get(1) }
            if (s.code.contains("\$MKXP") || s.code.contains("System.is_really_windows?") || s.code.contains("System.platform")) mkxpAware = true
            if (s.code.contains("Fiddle") || s.code.contains("DL.dlopen")) usesFiddle = true
        }
        val diags = mutableListOf<Diagnostic>()
        diags += Diagnostic(Severity.INFO, "Script analysis: ${scripts.size} scripts, ${scripts.sumOf { it.code.length.toLong() } / 1024} KB of Ruby code.")
        if (mkxpAware) diags += Diagnostic(Severity.INFO, "The game's scripts already contain mkxp-z-specific code paths.")
        val risky = dlls.filterKeys { it !in BENIGN_DLLS }
        risky.forEach { (dll, users) ->
            diags += Diagnostic(
                Severity.WARNING,
                "Scripts call the Windows library \"$dll.dll\" (${users.take(3).joinToString()}${if (users.size > 3) ", …" else ""}). " +
                    "Windows DLLs don't exist on Android; features using it will fail and may stop the game.",
            )
        }
        val benign = dlls.filterKeys { it in BENIGN_DLLS }
        if (benign.isNotEmpty()) diags += Diagnostic(
            Severity.INFO,
            "Scripts use Windows system calls (${benign.keys.joinToString { "$it.dll" }}), typically for window size, key state or INI files. " +
                "Common cases are handled by mkxp-z; unusual ones may not work.",
        )
        if (natives.isNotEmpty()) diags += Diagnostic(Severity.WARNING, "Scripts load native extensions (${natives.joinToString()}). These are Windows binaries and cannot load on Android.")
        if (usesFiddle) diags += Diagnostic(Severity.WARNING, "Scripts use Fiddle/DL to call native code directly; those parts won't work on Android.")
        return ScriptReport(
            scriptCount = scripts.size,
            totalChars = scripts.sumOf { it.code.length.toLong() },
            essentialsVersion = essentials,
            mkxpAware = mkxpAware,
            windowsDlls = dlls.mapValues { it.value.toList() },
            nativeExtensions = natives.toList(),
            diagnostics = diags,
        )
    }
}
