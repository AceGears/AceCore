package com.acecore.app.core.detection

import com.acecore.app.core.model.CompatStatus
import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.runtime.RuntimeRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class Severity { INFO, WARNING, ERROR }

@Serializable
data class Diagnostic(val severity: Severity, val message: String)

@Serializable
enum class LaunchKind { EXE, HTML, SWF, PCK, OTHER }

@Serializable
data class LaunchCandidate(
    /** Path relative to the game root, as the user sees it. */
    val path: String,
    val kind: LaunchKind,
    /** The real entry point the runtime will open (e.g. Game.exe -> www/index.html). */
    val resolvedEntry: String?,
    val playable: Boolean,
    val note: String,
)

@Serializable
data class EngineScore(val engine: Engine, val score: Int, val evidence: List<String>)

@Serializable
data class DetectionResult(
    val engine: Engine,
    val engineVersion: String? = null,
    /** 0..100 */
    val confidence: Int,
    /** Directory (relative to game root) that the runtime serves as its web root. "" = game root. */
    val contentRoot: String = "",
    /** Entry file relative to the game root; null when no runnable entry exists. */
    val entry: String? = null,
    val title: String? = null,
    val coverCandidates: List<String> = emptyList(),
    val launchCandidates: List<LaunchCandidate> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val scores: List<EngineScore> = emptyList(),
    val status: CompatStatus,
    /** Pokémon Essentials / plugin hints etc. */
    val tags: List<String> = emptyList(),
) {
    val isGame: Boolean get() = engine != Engine.UNKNOWN
}

/**
 * Multi-signal engine detector with confidence scoring. Each engine rule contributes weighted evidence; the
 * best score above [MIN_SCORE] wins and close runner-ups are kept for the manual override UI.
 */
class DetectionEngine(
    private val canLaunch: (Engine) -> Boolean = RuntimeRegistry::canLaunch,
) {
    companion object {
        const val MIN_SCORE = 40
        private const val MAX_SCRIPTS_BYTES = 24 * 1024 * 1024
        private val WEB_PREFIXES = listOf("", "www/", "resources/app/", "package.nw/", "app/", "html/", "game/")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private class Acc(val engine: Engine) {
        var score = 0
        val evidence = mutableListOf<String>()
        var contentRoot = ""
        var entry: String? = null
        var version: String? = null
        fun add(points: Int, why: String) { score += points; evidence += "+$points $why" }
    }

    fun detect(snap: GameSnapshot, folderName: String): DetectionResult {
        val accs = listOf(
            detectMv(snap), detectMz(snap), detectConstruct2(snap), detectConstruct3(snap), detectTyrano(snap),
            detectRgss(snap, Engine.RPG_MAKER_XP), detectRgss(snap, Engine.RPG_MAKER_VX),
            detectRgss(snap, Engine.RPG_MAKER_VX_ACE), detectRm2k(snap), detectRenpy(snap), detectGodot(snap),
            detectFlash(snap), detectUnity(snap), detectUnreal(snap), detectGameMaker(snap), detectWolf(snap),
            detectGenericHtml(snap),
        ).filter { it.score > 0 }.sortedByDescending { it.score }

        val diagnostics = mutableListOf<Diagnostic>()
        val tags = mutableListOf<String>()
        val top: Acc? = accs.firstOrNull()?.takeIf { it.score >= MIN_SCORE }

        // Pokémon Essentials is an RGSS (usually XP) project with a recognisable data layout.
        val essentials = if (top != null && top.engine in setOf(Engine.RPG_MAKER_XP, Engine.RPG_MAKER_VX_ACE)) {
            essentialsEvidence(snap).takeIf { it.first >= 30 }
        } else null
        val best: Acc? = if (top != null && essentials != null) {
            tags += "base:${top.engine.name}"
            Acc(Engine.POKEMON_ESSENTIALS).also { e ->
                e.score = top.score + essentials.first
                e.evidence += top.evidence
                e.evidence += essentials.second
                e.contentRoot = top.contentRoot
                e.entry = top.entry
                e.version = essentialsVersionHint(snap)
            }
        } else top
        if (snap.exists("mkxp.json")) {
            tags += "mkxp-z-config"
            diagnostics += Diagnostic(Severity.INFO, "Game ships an mkxp.json, so it already targets the mkxp-z runtime.")
        }

        val exeCandidates = exeCandidates(snap)

        if (best == null) {
            // Packed Electron / NW.js builds hide the web game inside an archive.
            if (snap.exists("resources/app.asar")) {
                diagnostics += Diagnostic(
                    Severity.WARNING,
                    "This is an Electron build with its game packed inside resources/app.asar. Extract the archive " +
                        "(e.g. with the 'asar' tool on a PC) into resources/app/ and rescan.",
                )
                return result(Engine.HTML5, 30, snap, folderName, null, accs, diagnostics, tags, exeCandidates, CompatStatus.PACKED)
            }
            if (snap.exists("package.nw") && snap.isFile("package.nw")) {
                diagnostics += Diagnostic(Severity.WARNING, "This NW.js build stores its game in a packed 'package.nw' file. Unzip it into a 'package.nw' folder and rescan.")
                return result(Engine.HTML5, 30, snap, folderName, null, accs, diagnostics, tags, exeCandidates, CompatStatus.PACKED)
            }
            if (exeCandidates.isNotEmpty()) {
                diagnostics += Diagnostic(
                    Severity.ERROR,
                    "Only native Windows executables were found and no supported engine data was recognised. " +
                        "Native Windows programs cannot run on Android.",
                )
                return result(Engine.NATIVE_WINDOWS, 50, snap, folderName, null, accs, diagnostics, tags, exeCandidates, CompatStatus.UNSUPPORTED)
            }
            return result(Engine.UNKNOWN, 0, snap, folderName, null, accs, diagnostics, tags, exeCandidates, CompatStatus.UNSUPPORTED)
        }

        val runnerUp = accs.firstOrNull { it.engine != best.engine && it.engine != Engine.HTML5 }
        if (runnerUp != null && runnerUp.score >= best.score - 15 && runnerUp.score >= MIN_SCORE) {
            diagnostics += Diagnostic(
                Severity.WARNING,
                "Detection is ambiguous between ${best.engine.displayName} and ${runnerUp.engine.displayName}. " +
                    "You can override the engine in Game Settings.",
            )
        }
        if (snap.truncated) {
            diagnostics += Diagnostic(Severity.INFO, "Very large folder: detection looked at a limited number of files.")
        }

        val status = when {
            best.engine.family == EngineFamily.UNSUPPORTED_NATIVE -> CompatStatus.UNSUPPORTED
            !canLaunch(best.engine) -> CompatStatus.RUNTIME_NOT_BUILT
            best.entry == null -> CompatStatus.MISSING_FILES
            else -> CompatStatus.PLAYABLE_UNTESTED
        }
        engineSpecificDiagnostics(best, snap, diagnostics)
        val confidence = (best.score.coerceAtMost(130) * 100 / 130).coerceIn(1, 100)
        return result(best.engine, confidence, snap, folderName, best, accs, diagnostics, tags, exeCandidates, status)
    }

    // ---------------------------------------------------------------- web engines

    private fun firstPrefix(snap: GameSnapshot, marker: String): String? =
        WEB_PREFIXES.firstOrNull { snap.isFile(it + marker) }

    private fun detectMv(snap: GameSnapshot) = Acc(Engine.RPG_MAKER_MV).apply {
        val p = firstPrefix(snap, "js/rpg_core.js") ?: return@apply
        add(70, "${p}js/rpg_core.js")
        if (snap.isFile("${p}js/rpg_managers.js")) add(15, "rpg_managers.js")
        if (snap.isFile("${p}data/System.json")) add(15, "data/System.json")
        if (snap.isFile("${p}js/plugins.js")) add(5, "plugins.js")
        contentRoot = p.trimEnd('/')
        entry = "${p}index.html".takeIf { snap.isFile(it) }
        version = snap.readText("${p}js/rpg_core.js", 64 * 1024)?.let { versionFrom(it) }
    }

    private fun detectMz(snap: GameSnapshot) = Acc(Engine.RPG_MAKER_MZ).apply {
        val p = firstPrefix(snap, "js/rmmz_core.js") ?: return@apply
        add(70, "${p}js/rmmz_core.js")
        if (snap.isFile("${p}js/rmmz_managers.js")) add(15, "rmmz_managers.js")
        if (snap.isFile("${p}data/System.json")) add(15, "data/System.json")
        if (snap.isDir("${p}effects")) add(5, "effects/ (Effekseer)")
        contentRoot = p.trimEnd('/')
        entry = "${p}index.html".takeIf { snap.isFile(it) }
        version = snap.readText("${p}js/rmmz_core.js", 64 * 1024)?.let { versionFrom(it) }
    }

    private fun versionFrom(js: String): String? =
        Regex("""RPGMAKER_VERSION\s*=\s*["']([0-9.]+)["']""").find(js)?.groupValues?.get(1)

    private fun detectConstruct2(snap: GameSnapshot) = Acc(Engine.CONSTRUCT_2).apply {
        val p = firstPrefix(snap, "c2runtime.js") ?: return@apply
        add(75, "${p}c2runtime.js")
        if (snap.isFile("${p}data.js")) add(15, "data.js")
        contentRoot = p.trimEnd('/')
        entry = "${p}index.html".takeIf { snap.isFile(it) }
    }

    private fun detectConstruct3(snap: GameSnapshot) = Acc(Engine.CONSTRUCT_3).apply {
        val p = firstPrefix(snap, "scripts/c3runtime.js") ?: firstPrefix(snap, "c3runtime.js")
            ?: firstPrefix(snap, "scripts/c3main.js") ?: return@apply
        add(75, "${p}c3runtime/c3main script")
        if (snap.isFile("${p}data.json")) add(15, "data.json")
        contentRoot = p.trimEnd('/')
        entry = "${p}index.html".takeIf { snap.isFile(it) }
    }

    private fun detectTyrano(snap: GameSnapshot) = Acc(Engine.TYRANO).apply {
        val p = WEB_PREFIXES.firstOrNull { snap.isDir("${it}tyrano") && snap.isDir("${it}data/scenario") }
            ?: return@apply
        add(60, "${p}tyrano/")
        add(30, "${p}data/scenario/")
        contentRoot = p.trimEnd('/')
        entry = "${p}index.html".takeIf { snap.isFile(it) }
    }

    private fun detectGenericHtml(snap: GameSnapshot) = Acc(Engine.HTML5).apply {
        val p = WEB_PREFIXES.firstOrNull { snap.isFile("${it}index.html") } ?: return@apply
        add(if (p.isEmpty()) 45 else 40, "${p}index.html")
        val hasCanvasEngine = snap.filesWhere { it.endsWith(".js") }.isNotEmpty()
        if (hasCanvasEngine) add(5, "JavaScript files")
        contentRoot = p.trimEnd('/')
        entry = "${p}index.html"
    }

    // ---------------------------------------------------------------- RGSS (XP / VX / VX Ace)

    private fun detectRgss(snap: GameSnapshot, engine: Engine) = Acc(engine).apply {
        val (proj, ext, archive, dllPrefix, rgssLabel, archiveVersion) = when (engine) {
            Engine.RPG_MAKER_XP -> RgssSpec("game.rxproj", "rxdata", "game.rgssad", "rgss1", "RGSS1", 1)
            Engine.RPG_MAKER_VX -> RgssSpec("game.rvproj", "rvdata", "game.rgss2a", "rgss2", "RGSS2", 1)
            else -> RgssSpec("game.rvproj2", "rvdata2", "game.rgss3a", "rgss3", "RGSS3", 3)
        }
        if (snap.isFile(proj)) add(40, proj)
        if (snap.isFile("data/scripts.$ext")) add(50, "Data/Scripts.$ext")
        else if (snap.filesWhere { it.startsWith("data/") && it.endsWith(".$ext") }.isNotEmpty()) add(35, "Data/*.$ext")
        if (snap.isFile(archive)) {
            val v = snap.readBytes(archive, 8)?.let { BinaryHeaders.rgssadVersion(it) }
            add(if (v == archiveVersion) 55 else 45, "${archive} (encrypted archive${if (v != null) " v$v" else ""})")
        }
        if (snap.filesWhere { it.startsWith(dllPrefix) && it.endsWith(".dll") && !it.contains('/') }.isNotEmpty() ||
            snap.filesWhere { it.startsWith("system/$dllPrefix") && it.endsWith(".dll") }.isNotEmpty()
        ) add(20, "$rgssLabel library DLL")
        val ini = snap.readText("game.ini", 4096)
        if (ini != null && ini.contains(rgssLabel, ignoreCase = true)) add(20, "Game.ini Library=$rgssLabel")
        if (score > 0) {
            version = rgssLabel
            entry = "game.ini".takeIf { snap.isFile(it) } ?: "Data/Scripts.$ext"
        }
    }

    private data class RgssSpec(
        val proj: String, val ext: String, val archive: String, val dll: String, val label: String, val archiveVersion: Int,
    )

    private fun essentialsEvidence(snap: GameSnapshot): Pair<Int, List<String>> {
        var s = 0
        val ev = mutableListOf<String>()
        fun hit(p: Int, w: String) { s += p; ev += "+$p $w" }
        if (snap.isDir("pbs")) hit(30, "PBS/ data folder")
        if (snap.isFile("data/pluginscripts.rxdata")) hit(20, "Data/PluginScripts.rxdata")
        if (snap.isDir("plugins")) hit(10, "Plugins/ folder")
        if (snap.isDir("graphics/pokemon")) hit(20, "Graphics/Pokemon/")
        if (snap.isDir("graphics/battlers")) hit(10, "Graphics/Battlers/")
        if (snap.isDir("audio/se/cries")) hit(15, "Audio/SE/Cries/")
        if (snap.filesWhere { it.startsWith("data/") && it.endsWith(".dat") && (it.contains("species") || it.contains("pokemon") || it.contains("moves")) }.isNotEmpty()) hit(15, "compiled species/moves .dat")
        if (snap.isFile("data/messages.dat") || snap.isFile("data/messages_core.dat")) hit(10, "messages .dat")
        return s to ev
    }

    private fun essentialsVersionHint(snap: GameSnapshot): String? = when {
        snap.isDir("graphics/pokemon/front") && snap.isFile("data/pluginscripts.rxdata") -> "v20+ (estimated)"
        snap.isFile("data/pluginscripts.rxdata") -> "v19 (estimated)"
        snap.isDir("graphics/battlers") -> "v18 or older (estimated)"
        else -> null
    }

    // ---------------------------------------------------------------- other engines

    private fun detectRm2k(snap: GameSnapshot) = Acc(Engine.RPG_MAKER_2K).apply {
        if (snap.isFile("rpg_rt.ldb")) add(60, "RPG_RT.ldb")
        if (snap.isFile("rpg_rt.lmt")) add(30, "RPG_RT.lmt")
        if (snap.isFile("rpg_rt.ini")) add(10, "RPG_RT.ini")
        if (snap.filesWhere { it.matches(Regex("map\\d{4}\\.lmu")) }.isNotEmpty()) add(15, "Map####.lmu")
        if (score > 0) entry = "RPG_RT.ldb"
        if (snap.isFile("ultimate_rt_eb.dll") || snap.isFile("rpg_rt.exe") && snap.isFile("harmony.dll")) version = "2000/2003"
    }

    private fun detectRenpy(snap: GameSnapshot) = Acc(Engine.RENPY).apply {
        if (snap.isDir("renpy")) add(45, "renpy/ engine folder")
        val gameFiles = snap.filesWhere { it.startsWith("game/") && (it.endsWith(".rpa") || it.endsWith(".rpyc") || it.endsWith(".rpy")) }
        if (gameFiles.isNotEmpty()) add(45, "game/*.rpa|.rpyc|.rpy")
        if (snap.directories.any { it.path.lowercase().startsWith("lib/py3-") }) { add(10, "lib/py3-*"); version = "8.x (Python 3)" }
        else if (snap.directories.any { it.path.lowercase().startsWith("lib/py2-") }) { add(10, "lib/py2-*"); version = "7.x (Python 2)" }
        snap.readText("renpy/vc_version.py", 4096)?.let { vc ->
            Regex("""version\s*=\s*['"]([0-9][0-9.]*)""").find(vc)?.groupValues?.get(1)?.let { version = it }
        }
        if (score > 0) entry = gameFiles.firstOrNull()?.path
    }

    private fun detectGodot(snap: GameSnapshot) = Acc(Engine.GODOT).apply {
        if (snap.isFile("project.godot")) { add(60, "project.godot (source project)"); entry = "project.godot" }
        val pcks = snap.files.filter { !it.path.contains('/') && it.path.endsWith(".pck", ignoreCase = true) }
        for (pck in pcks.take(2)) {
            val h = snap.readBytes(pck.path, 20)?.let { BinaryHeaders.parseGodotPck(it) }
            if (h != null) {
                add(70, "${pck.path} (Godot pack)"); version = "${h.major}.${h.minor}.${h.patch}"; entry = pck.path
            } else add(25, "${pck.path}")
        }
        // Godot can also embed the pck inside the exe; that cannot be detected from the folder alone.
    }

    private fun detectFlash(snap: GameSnapshot) = Acc(Engine.FLASH).apply {
        val swfs = snap.files.filter { it.path.count { c -> c == '/' } <= 1 && it.path.endsWith(".swf", ignoreCase = true) }
        if (swfs.isNotEmpty()) {
            add(65, "${swfs.size} .swf file(s)")
            entry = swfs.maxByOrNull { it.size }?.path
            entry?.let { e ->
                snap.readBytes(e, 8192)?.let { BinaryHeaders.parseSwf(it) }?.let { info ->
                    add(10, "valid SWF header (v${info.version}, ${info.compression})")
                    version = "SWF ${info.version}" + when (info.actionScript3) { true -> " · ActionScript 3"; false -> " · ActionScript 1/2"; null -> "" }
                }
            }
        }
    }

    private fun detectUnity(snap: GameSnapshot) = Acc(Engine.UNITY).apply {
        if (snap.isFile("unityplayer.dll")) add(60, "UnityPlayer.dll")
        if (snap.directories.any { it.path.lowercase().endsWith("_data") && !it.path.contains('/') }) add(20, "*_Data/")
        if (snap.files.any { it.path.lowercase().endsWith("_data/globalgamemanagers") }) add(30, "globalgamemanagers")
    }

    private fun detectUnreal(snap: GameSnapshot) = Acc(Engine.UNREAL).apply {
        if (snap.isDir("engine/binaries")) add(60, "Engine/Binaries/")
        if (snap.files.any { it.path.lowercase().endsWith(".pak") && it.path.lowercase().contains("/content/paks/") }) add(40, "Content/Paks/*.pak")
    }

    private fun detectGameMaker(snap: GameSnapshot) = Acc(Engine.GAMEMAKER).apply {
        if (snap.isFile("data.win")) add(70, "data.win")
        if (snap.isFile("options.ini") && snap.isFile("data.win")) add(10, "options.ini")
    }

    private fun detectWolf(snap: GameSnapshot) = Acc(Engine.WOLF_RPG).apply {
        if (snap.isFile("data.wolf")) add(70, "Data.wolf")
        if (snap.isDir("data/basicdata")) add(50, "Data/BasicData/")
        if (snap.isFile("config.exe") && snap.isFile("game.exe") && snap.files.any { it.path.lowercase().endsWith(".wolf") }) add(20, "*.wolf archives")
    }

    // ---------------------------------------------------------------- results

    private fun exeCandidates(snap: GameSnapshot): List<SnapshotEntry> =
        snap.files.filter { it.path.count { c -> c == '/' } <= 1 && it.path.endsWith(".exe", ignoreCase = true) }

    private fun analyzeRgssScripts(best: Acc, snap: GameSnapshot, out: MutableList<Diagnostic>) {
        val scripts = listOf("data/scripts.rxdata", "data/scripts.rvdata", "data/scripts.rvdata2").firstOrNull { snap.isFile(it) }
        if (scripts == null) {
            if (snap.files.any { it.path.lowercase().let { p -> p == "game.rgssad" || p == "game.rgss2a" || p == "game.rgss3a" } }) out += Diagnostic(
                Severity.INFO, "The game's scripts are inside its encrypted archive, so script compatibility analysis was skipped. The runtime reads the archive directly.",
            )
            return
        }
        val bytes = snap.readBytes(scripts, MAX_SCRIPTS_BYTES) ?: return
        val report = runCatching { com.acecore.app.core.scripts.ScriptAnalyzer.analyze(com.acecore.app.core.scripts.ScriptAnalyzer.decode(bytes)) }
            .getOrElse { e ->
                out += Diagnostic(Severity.INFO, "Script analysis wasn't possible (${e.message}).")
                return
            }
        out += report.diagnostics
        if (report.essentialsVersion != null && best.engine == Engine.POKEMON_ESSENTIALS) best.version = "v" + report.essentialsVersion.removePrefix("v")
    }

    private fun engineSpecificDiagnostics(best: Acc, snap: GameSnapshot, out: MutableList<Diagnostic>) {
        if (best.engine.family == EngineFamily.RGSS) analyzeRgssScripts(best, snap, out)
        when (best.engine) {
            Engine.RPG_MAKER_MV, Engine.RPG_MAKER_MZ -> {
                val root = best.contentRoot.let { if (it.isEmpty()) "" else "$it/" }
                val plugins = snap.readText("${root}js/plugins.js", 512 * 1024)
                if (plugins != null) {
                    val nodeHeavy = listOf("Steamworks", "Greenworks", "NWJS", "electron", "child_process")
                        .filter { plugins.contains(it, ignoreCase = true) }
                    if (nodeHeavy.isNotEmpty()) out += Diagnostic(
                        Severity.WARNING,
                        "Plugins reference desktop-only features (${nodeHeavy.joinToString()}). These are unavailable in the Web Runtime; the game may fail at those points.",
                    )
                }
                if (snap.filesWhere { it.endsWith(".rpgmvp") || it.endsWith(".png_") }.isNotEmpty()) out += Diagnostic(
                    Severity.INFO, "Encrypted image assets detected. They are decrypted by the game's own scripts at runtime.",
                )
                out += Diagnostic(Severity.INFO, "Saves are stored per game inside AceCore (browser storage), not in the game's save/ folder.")
            }
            Engine.POKEMON_ESSENTIALS -> out += Diagnostic(
                Severity.INFO,
                if (canLaunch(Engine.POKEMON_ESSENTIALS))
                    "Pokémon Essentials ${best.version ?: ""} detected. v19 and newer are built for mkxp-z and run natively; older versions run in RGSS1 mode, where plugins that call Windows DLLs will not work.".replace("  ", " ")
                else
                    "Pokémon Essentials ${best.version ?: ""} detected. This build doesn't include the RGSS runtime's native libraries, so it can't be played yet.".replace("  ", " "),
            )
            Engine.FLASH -> {
                if (best.version?.contains("ActionScript 3") == true) out += Diagnostic(
                    Severity.WARNING,
                    "This is an ActionScript 3 Flash game. Ruffle's AS3 support is still incomplete, so it may not run or may have glitches.",
                )
                if (snap.files.count { it.path.endsWith(".swf", true) } > 1) out += Diagnostic(
                    Severity.INFO, "Several .swf files were found; the largest was chosen. Use “Change launch file” if the game starts from a different one.",
                )
            }
            Engine.GODOT -> if (best.entry == "project.godot") out += Diagnostic(
                Severity.WARNING, "This is a Godot source project, not an exported game. It must be exported before it can run.",
            )
            else -> Unit
        }
        if (best.entry == null && canLaunch(best.engine)) out += Diagnostic(
            Severity.ERROR, "Engine recognised but its entry file (index.html) is missing.",
        )
    }

    private fun result(
        engine: Engine, confidence: Int, snap: GameSnapshot, folderName: String, best: Acc?,
        accs: List<Acc>, diagnostics: List<Diagnostic>, tags: List<String>, exes: List<SnapshotEntry>, status: CompatStatus,
    ): DetectionResult {
        val contentRoot = best?.contentRoot ?: ""
        val entry = best?.entry
        val runnable = canLaunch(engine) && entry != null && status == CompatStatus.PLAYABLE_UNTESTED
        val candidates = buildList {
            for (exe in exes) {
                val pe = snap.readBytes(exe.path, 4096)?.let { BinaryHeaders.parsePe(it) }
                val note = when {
                    pe == null -> "Not a valid Windows executable"
                    runnable -> "Launches ${engine.displayName} via ${entry}"
                    else -> "Windows ${pe.machine.label} program – not runnable on Android"
                }
                add(LaunchCandidate(exe.path, LaunchKind.EXE, if (runnable && pe != null) entry else null, runnable && pe != null, note))
            }
            if (canLaunch(Engine.HTML5)) {
                snap.files.filter { it.path.endsWith(".html", true) || it.path.endsWith(".htm", true) }
                    .filter { it.path.count { c -> c == '/' } <= 2 }
                    .filter { !it.path.contains("/js/") && !it.path.contains("node_modules") }
                    .sortedBy { it.path.count { c -> c == '/' } }
                    .take(8)
                    .forEach { add(LaunchCandidate(it.path, LaunchKind.HTML, it.path, true, if (it.path.equals(entry, true)) "Detected entry point" else "HTML page")) }
            }
            snap.files.filter { it.path.endsWith(".swf", true) }.take(4).forEach {
                add(LaunchCandidate(it.path, LaunchKind.SWF, it.path, canLaunch(Engine.FLASH), "Flash movie – needs Ruffle"))
            }
            snap.files.filter { it.path.endsWith(".pck", true) && !it.path.contains('/') }.forEach {
                add(LaunchCandidate(it.path, LaunchKind.PCK, it.path, canLaunch(Engine.GODOT), "Godot pack – needs Godot runtime"))
            }
        }.distinctBy { it.path.lowercase() }
            .sortedWith(compareByDescending<LaunchCandidate> { it.playable }.thenByDescending { it.resolvedEntry.equals(entry, true) && it.kind == LaunchKind.EXE }.thenBy { it.path.length })

        return DetectionResult(
            engine = engine,
            engineVersion = best?.version,
            confidence = confidence,
            contentRoot = contentRoot,
            entry = entry,
            title = discoverTitle(engine, snap, contentRoot) ?: prettifyFolderName(folderName),
            coverCandidates = discoverCovers(engine, snap, contentRoot),
            launchCandidates = candidates,
            diagnostics = diagnostics,
            scores = accs.take(5).map { EngineScore(it.engine, it.score, it.evidence) },
            status = status,
            tags = tags,
        )
    }

    // ---------------------------------------------------------------- metadata

    private fun discoverTitle(engine: Engine, snap: GameSnapshot, contentRoot: String): String? {
        val root = if (contentRoot.isEmpty()) "" else "$contentRoot/"
        runCatching {
            when (engine) {
                Engine.RPG_MAKER_MV, Engine.RPG_MAKER_MZ -> snap.readText("${root}data/System.json", 2 * 1024 * 1024)
                    ?.let { (json.parseToJsonElement(it) as? JsonObject)?.get("gameTitle")?.jsonPrimitive?.content }
                    ?.takeIf { it.isNotBlank() }?.let { return it }
                Engine.RPG_MAKER_XP, Engine.RPG_MAKER_VX, Engine.RPG_MAKER_VX_ACE, Engine.POKEMON_ESSENTIALS ->
                    snap.readText("game.ini", 4096)?.lineSequence()
                        ?.firstOrNull { it.trim().startsWith("Title=", ignoreCase = true) }
                        ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                Engine.RPG_MAKER_2K -> snap.readText("rpg_rt.ini", 4096)?.lineSequence()
                    ?.firstOrNull { it.trim().startsWith("GameTitle=", ignoreCase = true) }
                    ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                else -> Unit
            }
            snap.readText("${root}package.json", 64 * 1024)?.let { pkg ->
                val obj = json.parseToJsonElement(pkg) as? JsonObject
                val window = obj?.get("window") as? JsonObject
                (window?.get("title")?.jsonPrimitive?.content ?: obj?.get("productName")?.jsonPrimitive?.content)
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
            snap.readText("${root}index.html", 64 * 1024)?.let { html ->
                Regex("<title>([^<]{1,80})</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("index", true) }?.let { return it }
            }
        }
        return null
    }

    private fun discoverCovers(engine: Engine, snap: GameSnapshot, contentRoot: String): List<String> {
        val root = if (contentRoot.isEmpty()) "" else "$contentRoot/"
        val out = mutableListOf<String>()
        fun addIf(path: String) { snap.entry(path)?.takeIf { !it.isDirectory }?.let { out += it.path } }
        when (engine) {
            Engine.RPG_MAKER_MV, Engine.RPG_MAKER_MZ -> {
                runCatching {
                    snap.readText("${root}data/System.json", 2 * 1024 * 1024)?.let { s ->
                        val t1 = (json.parseToJsonElement(s) as? JsonObject)?.get("title1Name")?.jsonPrimitive?.content
                        if (!t1.isNullOrBlank()) addIf("${root}img/titles1/$t1.png")
                    }
                }
                addIf("${root}icon/icon.png")
            }
            Engine.RENPY -> { addIf("game/gui/main_menu.png"); addIf("game/gui/window_icon.png") }
            Engine.RPG_MAKER_XP, Engine.RPG_MAKER_VX, Engine.RPG_MAKER_VX_ACE, Engine.POKEMON_ESSENTIALS ->
                snap.filesWhere { it.startsWith("graphics/titles/") && it.endsWith(".png") }.firstOrNull()?.let { out += it.path }
            else -> Unit
        }
        listOf("cover.png", "cover.jpg", "icon.png", "${root}icon.png", "${root}favicon.png", "${root}icons/icon-512.png", "${root}icons/icon-256.png")
            .forEach { addIf(it) }
        return out.distinct()
    }

    private fun prettifyFolderName(name: String): String =
        name.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { "Untitled game" }
}
