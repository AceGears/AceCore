package com.acecore.app.runtime.rgss

import com.acecore.app.core.config.ConfigKey
import java.io.File

/**
 * How an RPG Maker game is placed on screen.
 *
 * [fit]: "fit" keeps the game's aspect ratio (letterboxed), "fill" stretches it to the whole area, "integer" scales by
 * the largest whole number that fits (pixel-perfect, may leave borders).
 * [sizePercent]: the game area as a share of the screen (50–100), centred.
 *
 * The in-game Display panel writes changes to [file] straight away. The game's compatibility script (acecore_compat.rb)
 * watches that file to switch the engine's scaling live, and the library imports it into the game's own settings
 * before the next launch (so a change survives even if the game process is killed).
 */
data class DisplayPrefs(val fit: String = FIT, val sizePercent: Int = 100) {
    fun normalized() = copy(fit = if (fit in MODES) fit else FIT, sizePercent = sizePercent.coerceIn(MIN_SIZE, 100))

    fun toJson(): String = """{"${ConfigKey.SCREEN_FIT.id}":"${normalized().fit}","${ConfigKey.SCREEN_SIZE.id}":"${normalized().sizePercent}"}"""

    /** Values for the engine's own scaling switches (mkxp-z). */
    val fixedAspectRatio get() = fit != FILL
    val integerScaling get() = fit == INTEGER

    companion object {
        const val FIT = "fit"
        const val FILL = "fill"
        const val INTEGER = "integer"
        val MODES = listOf(FIT, FILL, INTEGER)
        const val MIN_SIZE = 50
        const val FILE_NAME = "display.json"

        fun file(dataDir: File) = File(RgssRuntime.helperDir(dataDir), FILE_NAME)

        fun from(config: Map<String, String>) = DisplayPrefs(
            config[ConfigKey.SCREEN_FIT.id] ?: FIT,
            config[ConfigKey.SCREEN_SIZE.id]?.toIntOrNull() ?: 100,
        ).normalized()

        /** Parses the small JSON object written by [toJson] (no JSON library needed on either side). */
        fun parse(text: String): Map<String, String> =
            Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
                .filterKeys { it == ConfigKey.SCREEN_FIT.id || it == ConfigKey.SCREEN_SIZE.id }

        fun write(dataDir: File, prefs: DisplayPrefs) = runCatching {
            val f = file(dataDir)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(prefs.toJson())
            tmp.renameTo(f)
        }.isSuccess

        /** Reads and removes a pending in-game change (null when there is none). */
        fun takePending(dataDir: File): Map<String, String>? {
            val f = file(dataDir)
            if (!f.exists()) return null
            val values = runCatching { parse(f.readText()) }.getOrNull()
            f.delete()
            return values?.takeIf { it.isNotEmpty() }
        }
    }
}
