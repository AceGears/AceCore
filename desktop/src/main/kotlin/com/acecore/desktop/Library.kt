package com.acecore.desktop

import com.acecore.app.core.detection.DetectionEngine
import com.acecore.app.core.detection.DetectionResult
import com.acecore.app.core.detection.GameSnapshot
import com.acecore.app.core.detection.LaunchKind
import com.acecore.app.core.detection.SnapshotEntry
import com.acecore.app.core.model.Engine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A game in the desktop library. Paths are absolute; [entry] and [exe] are relative to [folder]. */
@Serializable
data class DesktopGame(
    val id: Long,
    val title: String,
    val folder: String,
    val engine: Engine,
    val engineVersion: String? = null,
    val entry: String? = null,
    val contentRoot: String = "",
    /** A Windows/macOS program in the folder that starts the game natively (Game.exe, nw.exe, *.app…). */
    val exe: String? = null,
    val cover: String? = null,
    val customCover: String? = null,
    val favorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val playtimeMs: Long = 0,
    val launches: Int = 0,
    val lastError: String? = null,
) {
    val folderFile: File get() = File(folder)
    val coverFile: File? get() = (customCover ?: cover)?.let { c -> File(c).takeIf { it.isAbsolute } ?: File(folder, c) }?.takeIf { it.isFile }
}

@Serializable
data class DesktopSettings(
    val coverWidth: Int = 170,
    val showTitles: Boolean = true,
    val sort: String = "recent",
)

@Serializable
private data class LibraryFile(val games: List<DesktopGame> = emptyList(), val settings: DesktopSettings = DesktopSettings())

/** The library and settings, stored as JSON in the user's application data folder. */
class LibraryStore(private val file: File = Paths.dataDir().resolve("library.json")) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    var games: List<DesktopGame> = emptyList()
        private set
    var settings: DesktopSettings = DesktopSettings()
        private set

    init { load() }

    private fun load() {
        val data = runCatching { json.decodeFromString<LibraryFile>(file.readText()) }.getOrNull() ?: return
        games = data.games
        settings = data.settings
    }

    @Synchronized
    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(LibraryFile(games, settings)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun upsert(game: DesktopGame) { games = games.filterNot { it.id == game.id } + game; save() }
    fun remove(id: Long) { games = games.filterNot { it.id == id }; save() }
    fun update(id: Long, change: (DesktopGame) -> DesktopGame) { games = games.map { if (it.id == id) change(it) else it }; save() }
    fun updateSettings(change: (DesktopSettings) -> DesktopSettings) { settings = change(settings); save() }
    fun nextId(): Long = (games.maxOfOrNull { it.id } ?: 0L) + 1
}

/** Builds a detection snapshot of a folder on disk (same rules as the Android app). */
object Scanner {
    private val detection = DetectionEngine(canLaunch = { true })

    fun snapshot(root: File, maxDepth: Int = 4, maxEntries: Int = 6000): GameSnapshot {
        val entries = ArrayList<SnapshotEntry>()
        var truncated = false
        fun walk(dir: File, prefix: String, depth: Int) {
            val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return
            for (f in kids) {
                if (entries.size >= maxEntries) { truncated = true; return }
                val path = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                entries += SnapshotEntry(path, f.isDirectory, if (f.isFile) f.length() else -1L)
                if (f.isDirectory) { if (depth < maxDepth) walk(f, path, depth + 1) else truncated = true }
            }
        }
        walk(root, "", 0)
        return GameSnapshot(entries, reader = { path, max ->
            runCatching {
                File(root, path).inputStream().use { input ->
                    val buf = ByteArray(max); var total = 0
                    while (total < max) { val n = input.read(buf, total, max - total); if (n <= 0) break; total += n }
                    buf.copyOf(total)
                }
            }.getOrNull()
        }, truncated = truncated)
    }

    fun detect(folder: File): DetectionResult = detection.detect(snapshot(folder), folder.name)

    /** A game for [folder], or null if the folder doesn't look like a game. */
    fun gameFor(folder: File, id: Long): DesktopGame? {
        val d = detect(folder)
        if (!d.isGame) return null
        return DesktopGame(
            id = id, title = d.title?.takeIf { it.isNotBlank() } ?: folder.name, folder = folder.absolutePath,
            engine = d.engine, engineVersion = d.engineVersion, entry = d.entry, contentRoot = d.contentRoot,
            exe = nativeProgram(folder, d), cover = d.coverCandidates.firstOrNull(),
        )
    }

    /** Folders inside [parent] that are games (for "Add a folder of games"). */
    fun scanChildren(parent: File, firstId: Long): List<DesktopGame> {
        var id = firstId
        return parent.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            .mapNotNull { gameFor(it, id)?.also { id++ } }
    }

    /** The game's own PC program: the detected .exe (Game.exe first), or a macOS .app bundle. */
    private fun nativeProgram(folder: File, d: DetectionResult): String? {
        val exes = d.launchCandidates.filter { it.kind == LaunchKind.EXE }.map { it.path }
        val preferred = exes.firstOrNull { it.equals("Game.exe", true) } ?: exes.firstOrNull { !it.contains("unins", true) && !it.contains("crash", true) }
        val app = folder.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.endsWith(".app", true) }?.name
        return preferred ?: app
    }
}

object Paths {
    /** %APPDATA%\AceArcade on Windows, ~/Library/Application Support/AceArcade on macOS, ~/.local/share/AceArcade elsewhere. */
    fun dataDir(): File {
        val home = File(System.getProperty("user.home"))
        return when (Platform.current) {
            Platform.WINDOWS -> File(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").path, "AceArcade")
            Platform.MAC -> home.resolve("Library/Application Support/AceArcade")
            Platform.LINUX -> home.resolve(".local/share/AceArcade")
        }.apply { mkdirs() }
    }
}

enum class Platform(val label: String) {
    WINDOWS("Windows"), MAC("macOS"), LINUX("Linux");

    companion object {
        val current: Platform = System.getProperty("os.name").orEmpty().lowercase().let {
            when {
                it.contains("win") -> WINDOWS
                it.contains("mac") || it.contains("darwin") -> MAC
                else -> LINUX
            }
        }
    }
}
