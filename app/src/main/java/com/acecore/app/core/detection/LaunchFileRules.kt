package com.acecore.app.core.detection

import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily

/** Result of checking a file the user wants to start a game with. */
sealed interface LaunchEvaluation {
    /**
     * The file can start the game. [entry] is what the runtime really opens (relative to the game root);
     * [family] is the runtime family it implies; [configFile] is the configuration the runtime reads, if any.
     */
    data class Supported(
        val entry: String,
        val family: EngineFamily,
        val explanation: String,
        val configFile: String? = null,
    ) : LaunchEvaluation

    data class Unsupported(val reason: String) : LaunchEvaluation
}

/**
 * Decides whether a file chosen in the launch-file browser can start a game, and what the runtime actually opens.
 * Pure logic (no Android) so every rule is unit-tested.
 *
 * Key distinction for RPG Maker XP/VX/VX Ace: Game.exe is the Windows launcher and Game.ini is its configuration.
 * Neither is executed on Android. The RGSS runtime reads Game.ini (title, scripts file) and the Data/ folder next to it.
 */
object LaunchFileRules {

    /**
     * @param path file path relative to the game root ('/'-separated)
     * @param siblings names of the files and folders in the same folder (for .exe files)
     * @param runtimeAvailable whether this build can run a given family
     */
    fun evaluate(
        current: Engine,
        path: String,
        siblings: List<String>,
        runtimeAvailable: (EngineFamily) -> Boolean,
    ): LaunchEvaluation {
        val name = path.substringAfterLast('/')
        val dir = path.substringBeforeLast('/', "")
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val ext = name.substringAfterLast('.', "").lowercase()
        fun has(n: String) = siblings.any { it.equals(n, ignoreCase = true) }
        fun actual(n: String) = siblings.firstOrNull { it.equals(n, ignoreCase = true) } ?: n

        fun unavailable(what: String) =
            LaunchEvaluation.Unsupported("$what games can't be played in this version of AceArcade (no runtime is built in).")

        return when (ext) {
            "html", "htm" -> if (runtimeAvailable(EngineFamily.WEB)) LaunchEvaluation.Supported(
                path, EngineFamily.WEB, "Web page. AceArcade opens it with the web runtime.",
            ) else unavailable("HTML5")

            "swf" -> if (runtimeAvailable(EngineFamily.FLASH)) LaunchEvaluation.Supported(
                path, EngineFamily.FLASH, "Flash movie. AceArcade plays it with Ruffle.",
            ) else unavailable("Flash")

            "ini" -> when {
                name.equals("Game.ini", ignoreCase = true) && looksRgss(siblings) -> rgss(prefix + name, runtimeAvailable,
                    "Game.ini is the game's configuration file, not a program. Choosing it tells AceArcade which folder holds the game; " +
                        "the RGSS runtime reads it and starts the game's scripts.")
                name.equals("Game.ini", ignoreCase = true) -> LaunchEvaluation.Unsupported(
                    "There is no Data folder next to this Game.ini, so it doesn't belong to an RPG Maker XP/VX/VX Ace game.",
                )
                else -> LaunchEvaluation.Unsupported("“$name” is a configuration file, not something that starts a game.")
            }

            "exe" -> {
                val base = name.substringBeforeLast('.')
                when {
                    looksRgss(siblings) && has("Game.ini") -> rgss(prefix + actual("Game.ini"), runtimeAvailable,
                        "$name is the Windows launcher, which can't run on Android. AceArcade's RGSS runtime starts the game " +
                            "from ${actual("Game.ini")} and the Data folder instead.")
                    looksRgss(siblings) && has("$base.ini") -> LaunchEvaluation.Unsupported(
                        "This RPG Maker game keeps its settings in ${actual("$base.ini")}, but the RGSS runtime only reads Game.ini. " +
                            "Rename a copy of it to Game.ini, then choose it again.",
                    )
                    has("www") || has("index.html") || has("package.json") -> {
                        val entry = if (has("www")) "${prefix}${actual("www")}/index.html" else prefix + actual("index.html")
                        if (runtimeAvailable(EngineFamily.WEB)) LaunchEvaluation.Supported(
                            entry, EngineFamily.WEB,
                            "$name is the Windows (NW.js/Electron) launcher. AceArcade opens the game's web files ($entry) instead.",
                        ) else unavailable("HTML5")
                    }
                    has("RPG_RT.ldb") || has("RPG_RT.lmt") -> LaunchEvaluation.Unsupported(
                        "This is an RPG Maker 2000/2003 game. That engine isn't playable in this version of AceCore.",
                    )
                    else -> LaunchEvaluation.Unsupported(
                        "$name is a Windows program. Android can't run Windows programs, and AceArcade found no game files it " +
                            "knows how to start next to it.",
                    )
                }
            }

            "rxdata", "rvdata", "rvdata2" -> if (name.substringBeforeLast('.').equals("Scripts", ignoreCase = true) && dir.substringAfterLast('/').equals("Data", ignoreCase = true)) {
                val gameDir = dir.substringBeforeLast('/', "")
                rgss(if (gameDir.isEmpty()) "Game.ini" else "$gameDir/Game.ini", runtimeAvailable,
                    "The game's script file. AceArcade starts the game from the folder that contains Data/ (it must also contain Game.ini).")
            } else LaunchEvaluation.Unsupported("“$name” is RPG Maker game data (a map, save or database file), not a launch file.")

            "pck" -> unavailable("Godot")
            "rpy", "rpyc", "rpa" -> unavailable("Ren'Py")
            "ldb", "lmt" -> unavailable("RPG Maker 2000/2003")
            "" -> LaunchEvaluation.Unsupported("“$name” has no file extension, so AceArcade can't tell how to start it.")
            else -> LaunchEvaluation.Unsupported("AceArcade can't start games from .$ext files.")
        }
    }

    /** True when the folder looks like an RPG Maker XP/VX/VX Ace game (a Data folder or an encrypted archive). */
    fun looksRgss(siblings: List<String>): Boolean = siblings.any {
        it.equals("Data", ignoreCase = true) || it.lowercase().let { n -> n == "game.rgssad" || n == "game.rgss2a" || n == "game.rgss3a" }
    }

    private fun rgss(entry: String, runtimeAvailable: (EngineFamily) -> Boolean, explanation: String): LaunchEvaluation =
        if (runtimeAvailable(EngineFamily.RGSS)) LaunchEvaluation.Supported(entry, EngineFamily.RGSS, explanation, configFile = entry)
        else LaunchEvaluation.Unsupported("RPG Maker XP/VX/VX Ace games need the RGSS runtime, which isn't in this build.")

    /** Whether a file is worth highlighting in the browser before the user taps it. */
    fun isCandidateName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".exe") || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".swf") || n == "game.ini"
    }

    /** RGSS version from Game.ini's "Library=" line (RGSS1xx / RGSS2xx / RGSS3xx), when present. */
    fun rgssEngineFromIni(ini: String?): Engine? {
        val lib = ini?.lineSequence()?.map { it.trim() }?.firstOrNull { it.startsWith("Library", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.lowercase() ?: return null
        return when {
            "rgss3" in lib -> Engine.RPG_MAKER_VX_ACE
            "rgss2" in lib -> Engine.RPG_MAKER_VX
            "rgss1" in lib || "rgss10" in lib || "rgss" in lib -> Engine.RPG_MAKER_XP
            else -> null
        }
    }
}
