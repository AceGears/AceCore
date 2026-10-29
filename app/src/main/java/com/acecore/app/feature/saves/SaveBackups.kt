package com.acecore.app.feature.saves

import android.content.Context
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.install.GameInstaller
import com.acecore.app.core.install.GameLocations
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.core.storage.DirectStorage
import com.acecore.app.runtime.rgss.RgssRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A save file found for a game. */
data class SaveFile(val file: File, val where: SaveLocation, val relative: String) {
    val size: Long get() = file.length()
    val modified: Long get() = file.lastModified()
}

enum class SaveLocation(val label: String, val folder: String) {
    /** AceCore's per-game data folder (SDL pref path; Pokémon Essentials v19+ saves here). */
    DATA("AceArcade game data", "data"),
    /** The folder the game runs from (your folder in direct mode, AceCore's copy otherwise). */
    GAME("Game folder", "game"),
}

data class SaveBackup(val dir: File, val createdAt: Long, val reason: String, val files: Int, val bytes: Long)

/**
 * Finds, backs up and restores RPG Maker XP/VX/VX Ace and Pokémon Essentials save files.
 *
 * Saves can live in two places: AceCore's per-game data folder (the engine's "data directory", used by Essentials
 * v19+) and the folder the game runs from (classic RPG Maker "Save1.rxdata" files). Backups are plain copies in
 * AceCore's private storage. Restoring always backs up the current saves first, so it can be undone.
 */
class SaveBackupManager(private val context: Context) {

    private val saveName = Regex("(?i).+\\.(rxdata|rvdata|rvdata2)")
    private val ownFiles = setOf("mkxp.json", RgssRuntime.PRELOAD_FILE, RgssRuntime.COMPAT_FILE)

    fun supports(game: GameEntity) = game.effectiveEngine.family == EngineFamily.RGSS

    private fun root(gameId: Long) = File(context.filesDir, "savebackups/game_$gameId")

    /**
     * Folder the game runs from right now, or null if it cannot be read. [direct] is the game's "How games are loaded"
     * setting: in copy mode this is AceCore's copy even when All files access is granted, so saves are never read
     * from, or restored into, the wrong folder.
     */
    fun gameDir(game: GameEntity, direct: Boolean): File? =
        if (direct) GameLocations.directGameDir(context, game)?.takeIf { DirectStorage.hasAccess(context) }
        else GameLocations.copyGameDir(context, game.id, game.entry).takeIf { GameInstaller(GameStorage.installDir(context, game.id)).isInstalled() }

    fun findSaves(game: GameEntity, direct: Boolean): List<SaveFile> = findSaves(game.id, gameDir(game, direct))

    /** Saves in AceCore's data folder for [gameId] and, when known, in the folder the game runs from. */
    fun findSaves(gameId: Long, gameDir: File?): List<SaveFile> {
        val out = mutableListOf<SaveFile>()
        val data = GameStorage.dataDir(context, gameId)
        if (data.isDirectory) {
            data.walkTopDown().onEnter { it == data || it.name != RgssRuntime.helperDir(data).name }
                .filter { it.isFile && it.name !in ownFiles && !it.name.endsWith(".tmp") }
                .forEach { out += SaveFile(it, SaveLocation.DATA, it.relativeTo(data).invariantSeparatorsPath) }
        }
        gameDir?.listFiles()?.filter { it.isFile && saveName.matches(it.name) }?.forEach {
            out += SaveFile(it, SaveLocation.GAME, it.name)
        }
        return out.sortedByDescending { it.modified }
    }

    fun backups(gameId: Long): List<SaveBackup> = root(gameId).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        val meta = runCatching { File(dir, "backup.txt").readLines().associate { it.substringBefore('=') to it.substringAfter('=') } }.getOrNull()
            ?: return@mapNotNull null
        val files = dir.walkTopDown().filter { it.isFile && it.name != "backup.txt" }.toList()
        SaveBackup(dir, meta["created"]?.toLongOrNull() ?: dir.lastModified(), meta["reason"] ?: "", files.size, files.sumOf { it.length() })
    }.sortedByDescending { it.createdAt }

    /** Copies the current saves into a new backup. Returns null when there is nothing to back up. */
    fun backup(game: GameEntity, direct: Boolean, reason: String): SaveBackup? = backup(game.id, gameDir(game, direct), reason)

    /** Same, for callers that only know the paths (the game process, which can't open the library database). */
    fun backup(gameId: Long, gameDir: File?, reason: String, keep: File? = null): SaveBackup? {
        val saves = findSaves(gameId, gameDir)
        if (saves.isEmpty()) return null
        val now = System.currentTimeMillis()
        val dir = File(root(gameId), SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now)))
        for (s in saves) {
            val out = File(dir, "${s.where.folder}/${s.relative}")
            out.parentFile?.mkdirs()
            s.file.copyTo(out, overwrite = true)
            out.setLastModified(s.modified)
        }
        File(dir, "backup.txt").writeText("created=$now\nreason=${reason.replace('\n', ' ')}\n")
        prune(gameId, keep)
        return backups(gameId).firstOrNull { it.dir == dir }
    }

    /** Keeps the newest 20 backups (never deleting [keep], e.g. the backup that is about to be restored). */
    private fun prune(gameId: Long, keep: File?, max: Int = 20) {
        backups(gameId).drop(max).filter { it.dir != keep }.forEach { it.dir.deleteRecursively() }
    }

    data class RestoreResult(val restored: Int, val skipped: List<String>, val safetyBackup: SaveBackup?)

    /**
     * Restores [backup]. The current saves are backed up first ("Before restore"). Files for the game folder are
     * only written when that folder is available (direct access or AceCore's copy).
     */
    fun restore(game: GameEntity, direct: Boolean, backup: SaveBackup): RestoreResult {
        val gameDir = gameDir(game, direct)
        val safety = backup(game.id, gameDir, "Before restoring a backup", keep = backup.dir)
        var n = 0
        val skipped = mutableListOf<String>()
        val data = GameStorage.dataDir(context, game.id)
        File(backup.dir, SaveLocation.DATA.folder).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
            val rel = f.relativeTo(File(backup.dir, SaveLocation.DATA.folder)).invariantSeparatorsPath
            val out = File(data, rel)
            out.parentFile?.mkdirs()
            f.copyTo(out, overwrite = true); n++
        }
        File(backup.dir, SaveLocation.GAME.folder).takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile }?.forEach { f ->
            if (gameDir == null) { skipped += f.name; return@forEach }
            runCatching { f.copyTo(File(gameDir, f.name), overwrite = true); n++ }.onFailure { skipped += f.name }
        }
        // Files kept from a deleted private copy go back into the folder the game runs from, at their old paths.
        val copyRoot = File(backup.dir, "copy")
        copyRoot.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
            val rel = f.relativeTo(copyRoot).invariantSeparatorsPath
            if (gameDir == null) { skipped += rel; return@forEach }
            runCatching { File(gameDir, rel).also { it.parentFile?.mkdirs() }.let { f.copyTo(it, overwrite = true) }; n++ }.onFailure { skipped += rel }
        }
        return RestoreResult(n, skipped, safety)
    }

    fun delete(backup: SaveBackup) { backup.dir.deleteRecursively() }

    /**
     * Saves the game created inside AceCore's private copy (copy mode), which are not in the user's folder.
     * Used when switching a game to direct mode so no progress is left behind.
     */
    fun savesInPrivateCopy(game: GameEntity): List<File> {
        val copy = GameLocations.copyGameDir(context, game.id, game.entry)
        if (!copy.isDirectory) return emptyList()
        val installer = GameInstaller(GameStorage.installDir(context, game.id))
        val sub = GameLocations.rgssSubdir(game.entry)
        return installer.gameCreatedFiles()
            .map { if (sub.isEmpty()) it else it.removePrefix("$sub/") }
            .filter { !it.contains('/') && saveName.matches(it) }
            .map { File(copy, it) }
            .filter { it.isFile }
    }

    /**
     * Backs up every file the GAME created inside AceCore's private copy (saves in any folder, any extension), keeping
     * their relative paths, before the copy is deleted.
     */
    fun backupPrivateCopy(game: GameEntity, reason: String): SaveBackup? {
        val installDir = GameStorage.installDir(context, game.id)
        val created = GameInstaller(installDir).gameCreatedFiles()
        if (created.isEmpty()) return null
        val now = System.currentTimeMillis()
        val dir = File(root(game.id), SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now)))
        for (rel in created) {
            val f = File(installDir, rel)
            val out = File(dir, "copy/$rel")
            out.parentFile?.mkdirs()
            f.copyTo(out, overwrite = true)
            out.setLastModified(f.lastModified())
        }
        File(dir, "backup.txt").writeText("created=$now\nreason=${reason.replace('\n', ' ')}\n")
        return backups(game.id).firstOrNull { it.dir == dir }
    }

    data class MoveResult(val copied: List<String>, val conflicts: List<String>)

    /** Copies [files] from the private copy into the user's folder. Existing files there are never overwritten. */
    fun copySavesToDirectFolder(game: GameEntity, files: List<File>): MoveResult {
        val target = GameLocations.directGameDir(context, game) ?: error("AceArcade can't open your game folder directly yet.")
        val copied = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        for (f in files) {
            val out = File(target, f.name)
            if (out.exists()) { conflicts += f.name; continue }
            f.copyTo(out, overwrite = false)
            out.setLastModified(f.lastModified())
            copied += f.name
        }
        return MoveResult(copied, conflicts)
    }
}
