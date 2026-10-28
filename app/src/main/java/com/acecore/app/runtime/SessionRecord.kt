package com.acecore.app.runtime

import android.content.Context
import java.io.File

/**
 * Files shared between the launcher process and a game process for one session (a game process must not open
 * Room/DataStore). The game process writes play time and the moment the game became playable; the launcher
 * reads them when the game ends, even after a crash.
 *
 *   sessions/game_<id>.ms      written by the game process:  ms=<foreground play ms>, ready=<epoch ms>
 *   sessions/game_<id>.launch  written by the launcher:      requested=<epoch ms of the Play tap>
 */
object SessionRecord {
    data class Data(val ms: Long, val readyAt: Long?)

    fun file(context: Context, gameId: Long) = File(context.applicationContext.filesDir, "sessions/game_$gameId.ms")
    fun launchFile(context: Context, gameId: Long) = File(context.applicationContext.filesDir, "sessions/game_$gameId.launch")

    fun write(file: File, ms: Long, readyAt: Long?) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(buildString {
                append("ms=").append(ms).append('\n')
                if (readyAt != null) append("ready=").append(readyAt).append('\n')
            })
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    /** Also reads v0.3 files, which contain only the millisecond count. */
    fun read(file: File): Data? = runCatching {
        if (!file.exists()) return null
        val text = file.readText().trim()
        text.toLongOrNull()?.let { return Data(it, null) }
        val map = text.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        Data(map["ms"]?.toLongOrNull() ?: 0L, map["ready"]?.toLongOrNull())
    }.getOrNull()

    fun writeLaunch(context: Context, gameId: Long, requestedAt: Long) {
        runCatching { launchFile(context, gameId).apply { parentFile?.mkdirs() }.writeText("requested=$requestedAt\n") }
    }

    fun readLaunch(context: Context, gameId: Long): Long? = runCatching {
        launchFile(context, gameId).readText().trim().substringAfter("requested=").toLongOrNull()
    }.getOrNull()

    fun clear(context: Context, gameId: Long) {
        file(context, gameId).delete()
        launchFile(context, gameId).delete()
    }
}
