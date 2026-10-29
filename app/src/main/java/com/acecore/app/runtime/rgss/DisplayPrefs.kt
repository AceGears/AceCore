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
 * The in-game menu writes changes (display, button mapping, on-screen controls) to [file] straight away. The game's compatibility script (acecore_compat.rb)
 * watches that file to switch the engine's scaling live, and the library imports it into the game's own settings
 * before the next launch (so a change survives even if the game process is killed).
 */
data class DisplayPrefs(val fit: String = FIT, val sizePercent: Int = 100) {
    fun normalized() = copy(fit = if (fit in MODES) fit else FIT, sizePercent = sizePercent.coerceIn(MIN_SIZE, 100))

    fun toJson(): String = """{"${ConfigKey.SCREEN_FIT.id}":"${normalized().fit}","${ConfigKey.SCREEN_SIZE.id}":"${normalized().sizePercent}"}"""

    /** Values for the engine's own scaling switches (mkxp-z). */
    val fixedAspectRatio get() = fit != FILL
    val integerScaling get() = fit == INTEGER

    /**
     * Size of the game surface inside an [availW]×[availH] area for a game running at [gameW]×[gameH]:
     * Fit = largest size with the game's proportions, Pixel-perfect = largest whole-number multiple, Stretch = the
     * whole area, inside [sizePercent] of the screen. Same rules as the engine patch (display/graphics.cpp) uses, so the
     * Display panel can show the size the engine draws at. Returns the area itself when the resolution isn't known.
     */
    fun surfaceSize(availW: Int, availH: Int, gameW: Int, gameH: Int): Pair<Int, Int> {
        val p = normalized().sizePercent / 100.0
        val aw = (availW * p).toInt(); val ah = (availH * p).toInt()
        if (gameW <= 0 || gameH <= 0 || aw <= 0 || ah <= 0) return aw to ah
        val (w, h) = when (normalized().fit) {
            FILL -> aw to ah
            INTEGER -> {
                val k = maxOf(1, minOf(aw / gameW, ah / gameH))
                // Whole-number multiple if it fits; on a screen smaller than the game, fall back to Fit.
                if (gameW * k <= aw && gameH * k <= ah) (gameW * k) to (gameH * k) else fitIn(aw, ah, gameW, gameH)
            }
            else -> fitIn(aw, ah, gameW, gameH)
        }
        return w to h
    }

    private fun fitIn(aw: Int, ah: Int, gw: Int, gh: Int): Pair<Int, Int> {
        val scale = minOf(aw.toDouble() / gw, ah.toDouble() / gh)
        return (gw * scale).toInt() to (gh * scale).toInt()
    }

    companion object {
        const val FIT = "fit"
        const val FILL = "fill"
        const val INTEGER = "integer"
        val MODES = listOf(FIT, FILL, INTEGER)
        const val MIN_SIZE = 50
        const val FILE_NAME = "settings.json"

        /** Settings the in-game menu may change and save for the game. */
        val SAVED_IN_GAME = setOf(ConfigKey.SCREEN_FIT.id, ConfigKey.SCREEN_SIZE.id, ConfigKey.BUTTON_MAP.id, ConfigKey.GAMEPAD_MODE.id)

        fun file(dataDir: File) = File(RgssRuntime.helperDir(dataDir), FILE_NAME)

        fun from(config: Map<String, String>) = DisplayPrefs(
            config[ConfigKey.SCREEN_FIT.id] ?: FIT,
            config[ConfigKey.SCREEN_SIZE.id]?.toIntOrNull() ?: 100,
        ).normalized()

        /** Parses the small JSON object written by [toJson] (no JSON library needed on either side). */
        fun parse(text: String): Map<String, String> =
            Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
                .filterKeys { it in SAVED_IN_GAME }

        fun write(dataDir: File, prefs: DisplayPrefs) = writePending(dataDir,
            mapOf(ConfigKey.SCREEN_FIT.id to prefs.normalized().fit, ConfigKey.SCREEN_SIZE.id to prefs.normalized().sizePercent.toString()))

        private val lock = Any()

        /** Adds [values] to the pending in-game changes (merged with earlier ones in this session). */
        fun writePending(dataDir: File, values: Map<String, String>): Boolean = synchronized(lock) {
            runCatching {
                val f = file(dataDir)
                f.parentFile?.mkdirs()
                val merged = (if (f.exists()) parse(f.readText()) else emptyMap()) + values.filterKeys { it in SAVED_IN_GAME }
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(merged.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${v.replace("\"", "")}\"" })
                tmp.renameTo(f)
            }.isSuccess
        }

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
