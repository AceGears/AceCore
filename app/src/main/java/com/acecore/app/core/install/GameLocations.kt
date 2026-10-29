package com.acecore.app.core.install

import android.content.Context
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.storage.DirectStorage
import java.io.File

/** Where a native-runtime game's files are, in direct mode (user's folder) and copy mode (AceCore's copy). */
object GameLocations {

    private val SCRIPTS = Regex("(?i)(.*/)?data/scripts\\.(rxdata|rvdata|rvdata2)")

    /** Folder that contains the RGSS Game.ini, relative to the game root ("" = the root itself). */
    fun rgssSubdir(entry: String?): String {
        val e = entry?.replace('\\', '/')?.trim('/') ?: return ""
        return when {
            SCRIPTS.matches(e) -> e.substringBeforeLast('/', "").substringBeforeLast('/', "").let { if (it.equals("data", true)) "" else it }
            else -> e.substringBeforeLast('/', "")
        }
    }

    /**
     * The game's RGSS settings file. Normally Game.ini; renamed games keep it next to their renamed launcher
     * (Flux.exe + Flux.ini), and the entry then names it.
     */
    fun rgssIniName(entry: String?): String =
        entry?.replace('\\', '/')?.substringAfterLast('/')?.takeIf { it.endsWith(".ini", ignoreCase = true) } ?: "Game.ini"

    /** mkxp-z's "execName" for [entry]: the settings file's name without .ini ("Game" unless renamed). */
    fun rgssExecName(entry: String?): String = rgssIniName(entry).substringBeforeLast('.')

    /** The user's own folder for [game], through its real path (null when the provider has no real path). */
    fun directRoot(context: Context, game: GameEntity): File? = DirectStorage.realPath(context, game.treeUri, game.rootDocId)

    /** Direct-mode folder containing Game.ini, or null when it can't be resolved/read right now. */
    fun directGameDir(context: Context, game: GameEntity): File? {
        val root = directRoot(context, game) ?: return null
        if (!DirectStorage.canList(root)) return null
        val sub = rgssSubdir(game.entry)
        return if (sub.isEmpty()) root else DirectStorage.resolveRelative(root, sub)
    }

    /** Copy-mode folder containing Game.ini (exists only once the copy was made). */
    fun copyGameDir(context: Context, gameId: Long, entry: String?): File {
        val sub = rgssSubdir(entry)
        val base = GameStorage.installDir(context, gameId)
        return if (sub.isEmpty()) base else File(base, sub)
    }
}
