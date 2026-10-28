package com.acecore.app.core.install

import android.content.Context
import android.net.Uri
import com.acecore.app.core.filesystem.SafTree
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** Reads a game folder the user granted through the Storage Access Framework. */
class SafInstallSource(context: Context, treeUri: Uri, private val rootDocId: String) : InstallSource {
    private val tree = SafTree(context.contentResolver, treeUri)
    private var ids: Map<String, String> = emptyMap()

    override fun list(): List<SourceFile> {
        val walk = tree.walk(rootDocId, maxDepth = 64, maxEntries = 300_000)
        if (walk.truncated) throw java.io.IOException("Game folder is too large to install (over 300,000 entries)")
        val files = walk.nodes.filter { !it.isDirectory }
        ids = files.associate { it.path to it.docId }
        return files.map { SourceFile(it.path, it.size, it.lastModified) }
    }

    override fun open(path: String): InputStream {
        val id = ids[path] ?: throw FileNotFoundException(path)
        return tree.open(id) ?: throw FileNotFoundException(path)
    }
}

/** Where AceCore keeps per-game data for native runtimes. */
object GameStorage {
    /** Working copy of the game's files (the game writes its own saves here too). */
    fun installDir(context: Context, gameId: Long): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "games/game_$gameId")

    /** Private per-game data directory (SDL "pref path": engine config overrides, Essentials-style saves). */
    fun dataDir(context: Context, gameId: Long): File = File(context.filesDir, "gamedata/game_$gameId")

    fun installRoot(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "games")
    fun dataRoot(context: Context): File = File(context.filesDir, "gamedata")

    fun deleteAll(context: Context, gameId: Long) {
        installDir(context, gameId).deleteRecursively()
        dataDir(context, gameId).deleteRecursively()
    }

    fun gameIdOf(dir: File): Long? = dir.name.removePrefix("game_").toLongOrNull()
}
