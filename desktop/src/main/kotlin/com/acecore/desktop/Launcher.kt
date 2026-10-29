package com.acecore.desktop

import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.runtime.godot.GodotPack
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files

/** How AceArcade will start a game on this computer. */
sealed class LaunchPlan(val summary: String) {
    /** Run a program (the game's own executable or a bundled engine). */
    class Process(summary: String, val command: List<String>, val workDir: File) : LaunchPlan(summary)
    /** Serve the folder over http://127.0.0.1 and open it in the default browser (HTML5 / RPG Maker MV/MZ…). */
    class Browser(summary: String, val root: File, val entry: String) : LaunchPlan(summary)
    /** Can't run here; [summary] says why, honestly. */
    class Unsupported(summary: String) : LaunchPlan(summary)
}

/**
 * Engines AceArcade can bundle with the desktop app (downloaded by CI into the app's resources folder):
 *   easyrpg  EasyRPG Player (GPL-3.0)   RPG Maker 2000/2003         Windows, macOS
 *   ruffle   Ruffle (MIT/Apache-2.0)   Flash (.swf)                 Windows, macOS
 *   godot    Godot 4 (MIT)             Godot 4 packs on macOS       macOS
 * A missing engine is reported, never faked.
 */
class Runtimes(private val root: File? = defaultRoot()) {
    fun easyRpg(): File? = find("easyrpg", when (Platform.current) {
        Platform.WINDOWS -> listOf("Player.exe", "EasyRPG Player.exe", "easyrpg-player.exe")
        Platform.MAC -> listOf("EasyRPG Player.app/Contents/MacOS/EasyRPG Player", "EasyRPG Player.app/Contents/MacOS/easyrpg-player")
        Platform.LINUX -> listOf("easyrpg-player")
    })

    fun ruffle(): File? = find("ruffle", when (Platform.current) {
        Platform.WINDOWS -> listOf("ruffle.exe")
        Platform.MAC -> listOf("Ruffle.app/Contents/MacOS/ruffle", "ruffle")
        Platform.LINUX -> listOf("ruffle")
    })

    fun godot(): File? = find("godot", when (Platform.current) {
        Platform.WINDOWS -> listOf("godot.exe")
        Platform.MAC -> listOf("Godot.app/Contents/MacOS/Godot")
        Platform.LINUX -> listOf("godot")
    })

    /** A runtime's executable: a listed path, else the first matching file found in its folder. */
    private fun find(name: String, candidates: List<String>): File? {
        val dir = root?.resolve("runtimes/$name")?.takeIf { it.isDirectory } ?: return null
        candidates.map { dir.resolve(it) }.firstOrNull { it.isFile }?.let { return it }
        val names = candidates.map { it.substringAfterLast('/').lowercase() }.toSet()
        return dir.walkTopDown().maxDepth(5).firstOrNull { it.isFile && it.name.lowercase() in names }
    }

    companion object {
        /** Set by the packaged app (Compose "appResourcesRootDir"); null when run from source. */
        fun defaultRoot(): File? = System.getProperty("compose.application.resources.dir")?.let(::File)
            ?: File("resources/${if (Platform.current == Platform.MAC) "macos" else "windows"}").takeIf { it.isDirectory }
    }
}

/** Decides how to start each game, per platform. Pure logic (unit-tested); [Launcher] runs the plan. */
class Planner(private val runtimes: Runtimes, private val platform: Platform = Platform.current) {

    fun plan(g: DesktopGame): LaunchPlan {
        val dir = g.folderFile
        if (!dir.isDirectory) return LaunchPlan.Unsupported("The game folder isn't there any more: ${g.folder}")
        val exe = g.exe?.let { dir.resolve(it) }?.takeIf { it.exists() }
        val nativeExe = exe?.takeIf { platform == Platform.WINDOWS && it.name.endsWith(".exe", true) }
        val macApp = exe?.takeIf { platform == Platform.MAC && it.name.endsWith(".app", true) }
        fun native(what: String) = nativeExe?.let { LaunchPlan.Process("$what: runs the game's own Windows program (${it.name})", listOf(it.absolutePath), it.parentFile) }
            ?: macApp?.let { LaunchPlan.Process("$what: opens the game's macOS app (${it.name})", listOf("open", "-W", it.absolutePath), dir) }

        return when (g.engine.family) {
            EngineFamily.RGSS -> native("RPG Maker XP/VX/VX Ace")
                ?: LaunchPlan.Unsupported(
                    if (platform == Platform.WINDOWS) "No Game.exe was found in this folder, so the game can't be started."
                    else "RPG Maker XP/VX/VX Ace games need mkxp-z on ${platform.label}, which isn't bundled in this version of AceArcade yet.",
                )
            EngineFamily.EASYRPG -> runtimes.easyRpg()?.let {
                LaunchPlan.Process("RPG Maker 2000/2003: EasyRPG Player (bundled)", listOf(it.absolutePath, "--project-path", gameDir(g).absolutePath), gameDir(g))
            } ?: native("RPG Maker 2000/2003") ?: LaunchPlan.Unsupported("EasyRPG Player isn't bundled in this build.")
            EngineFamily.WEB -> native(g.engine.displayName)
                ?: g.entry?.let { LaunchPlan.Browser("${g.engine.displayName}: runs in your web browser (served by AceArcade from the game folder)", dir.resolve(g.contentRoot), entryInRoot(g)) }
                ?: LaunchPlan.Unsupported("No HTML start page was found in this folder.")
            EngineFamily.FLASH -> runtimes.ruffle()?.let { r ->
                g.entry?.let { LaunchPlan.Process("Flash: Ruffle (bundled)", listOf(r.absolutePath, dir.resolve(it).absolutePath), dir) }
            } ?: LaunchPlan.Unsupported("Ruffle isn't bundled in this build.")
            EngineFamily.GODOT -> native("Godot") ?: godotPack(g, dir)
            EngineFamily.RENPY -> native("Ren'Py") ?: renpyMac(g, dir)
                ?: LaunchPlan.Unsupported("This Ren'Py build has no ${platform.label} launcher. Ren'Py's PC download runs on Windows (and Linux); its Mac download includes a .app.")
            EngineFamily.UNSUPPORTED_NATIVE -> native(g.engine.displayName)
                ?: LaunchPlan.Unsupported(
                    if (platform == Platform.WINDOWS) "No program to start was found in this folder."
                    else "This is a Windows program. ${platform.label} can't run Windows programs without a compatibility layer such as Wine or CrossOver.",
                )
        }
    }

    private fun gameDir(g: DesktopGame): File = g.entry?.let { File(g.folder, it).parentFile }?.takeIf { it.isDirectory } ?: g.folderFile

    private fun entryInRoot(g: DesktopGame): String =
        (if (g.contentRoot.isEmpty()) g.entry else g.entry?.removePrefix(g.contentRoot + "/")) ?: "index.html"

    private fun godotPack(g: DesktopGame, dir: File): LaunchPlan {
        val godot = runtimes.godot() ?: return LaunchPlan.Unsupported("Godot games exported for Windows need the Godot engine on ${platform.label}, which isn't bundled in this build.")
        return when (val check = GodotPack.check(dir, g.entry?.substringAfterLast('/'))) {
            is GodotPack.Check.Unsupported -> LaunchPlan.Unsupported(check.title + ": " + check.message)
            is GodotPack.Check.Ok -> LaunchPlan.Process("Godot ${check.pack.version}: bundled ${GodotPack.ENGINE_LABEL}",
                listOf(godot.absolutePath, "--main-pack", check.pack.file.absolutePath), dir)
        }
    }

    private fun renpyMac(g: DesktopGame, dir: File): LaunchPlan? {
        if (platform == Platform.WINDOWS) return null
        val app = dir.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.endsWith(".app") }
        if (app != null) return LaunchPlan.Process("Ren'Py: opens the game's macOS app (${app.name})", listOf("open", "-W", app.absolutePath), dir)
        val sh = dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.endsWith(".sh") }
        val hasMacLib = dir.resolve("lib").listFiles().orEmpty().any { it.name.contains("mac", true) || it.name.contains("darwin", true) }
        return if (sh != null && hasMacLib) LaunchPlan.Process("Ren'Py: runs the game's launcher script", listOf("/bin/sh", sh.absolutePath), dir) else null
    }
}

/** Starts plans, tracks play time while the game's process runs, and serves browser games. */
class Launcher(private val store: LibraryStore, private val planner: Planner) {
    private var server: HttpServer? = null

    /** Starts [game]; [onExit] gets the session length and an error message, if any. Returns the plan used. */
    fun launch(game: DesktopGame, onExit: (Long, String?) -> Unit): LaunchPlan {
        val plan = planner.plan(game)
        when (plan) {
            is LaunchPlan.Process -> {
                val started = System.currentTimeMillis()
                val process = runCatching {
                    ProcessBuilder(plan.command).directory(plan.workDir).redirectErrorStream(true)
                        .redirectOutput(Paths.dataDir().resolve("last-game.log")).start()
                }.getOrElse { e -> onExit(0, "Couldn't start: ${e.message}"); return plan }
                store.update(game.id) { it.copy(lastPlayedAt = started, launches = it.launches + 1, lastError = null) }
                Thread {
                    val code = process.waitFor()
                    val ms = System.currentTimeMillis() - started
                    val error = if (code != 0 && ms < 5_000) "The game closed right away (exit code $code). See ${Paths.dataDir().resolve("last-game.log")}" else null
                    store.update(game.id) { it.copy(playtimeMs = it.playtimeMs + ms, lastError = error) }
                    onExit(ms, error)
                }.apply { isDaemon = true }.start()
            }
            is LaunchPlan.Browser -> {
                val url = serve(plan.root, plan.entry)
                store.update(game.id) { it.copy(lastPlayedAt = System.currentTimeMillis(), launches = it.launches + 1, lastError = null) }
                runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure { onExit(0, "Couldn't open the browser: ${it.message}") }
            }
            is LaunchPlan.Unsupported -> onExit(0, plan.summary)
        }
        return plan
    }

    /** Serves [root] on 127.0.0.1 (a new port per game) and returns the URL of [entry]. Only local connections. */
    @Synchronized
    fun serve(root: File, entry: String): String {
        server?.stop(0)
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = root.canonicalFile
        s.createContext("/") { ex ->
            val rel = URLDecoder.decode(ex.requestURI.rawPath.removePrefix("/"), "UTF-8").ifEmpty { entry }
            val f = File(base, rel).canonicalFile
            val ok = f.path.startsWith(base.path) && f.isFile
            if (!ok) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.responseHeaders.add("Content-Type", mime(f.name))
            ex.responseHeaders.add("Cache-Control", "no-cache")
            ex.sendResponseHeaders(200, f.length())
            ex.responseBody.use { out -> Files.copy(f.toPath(), out) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/" + entry.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"
        "ogg" -> "audio/ogg"; "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "webm" -> "video/webm"; "mp4" -> "video/mp4"
        "wasm" -> "application/wasm"; "ttf" -> "font/ttf"; "otf" -> "font/otf"; "woff" -> "font/woff"; "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    fun stop() { server?.stop(0) }
}
