package com.acecore.app.core.config

import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily

enum class ConfigType { BOOL, INT, CHOICE, BUTTON_MAP }

/** Engines whose runtime honours the on-screen-control, mapping, display-filter and screen settings. */
private val WEB_FLASH_RGSS: (Engine) -> Boolean =
    { it.family == EngineFamily.WEB || it.family == EngineFamily.FLASH || it.family == EngineFamily.RGSS }

/** Engines with AceArcade's on-screen controller and key mapping (adds Godot). */
private val WITH_CONTROLS: (Engine) -> Boolean = { WEB_FLASH_RGSS(it) || it.family == EngineFamily.GODOT || it.family == EngineFamily.EASYRPG }

/**
 * Settings that can be set globally, per engine, and per game. Each key declares which engines it actually
 * affects so the UI never shows an option that would do nothing for the selected game.
 */
enum class ConfigKey(
    val id: String,
    val title: String,
    val description: String,
    val type: ConfigType,
    val default: String,
    val choices: List<Pair<String, String>> = emptyList(),
    val range: IntRange = 0..100,
    val section: String,
    val appliesTo: (Engine) -> Boolean = { it.family == EngineFamily.WEB || it.family == EngineFamily.FLASH },
) {
    ORIENTATION(
        "orientation", "Orientation", "Screen orientation while playing.", ConfigType.CHOICE, "landscape",
        listOf("landscape" to "Landscape", "portrait" to "Portrait", "sensor" to "Auto-rotate", "system" to "Follow system"),
        section = "Display",
    ),
    IMMERSIVE("immersive", "Immersive fullscreen", "Hide the status and navigation bars.", ConfigType.BOOL, "true", section = "Display", appliesTo = WEB_FLASH_RGSS),
    KEEP_SCREEN_ON("keepScreenOn", "Keep screen on", "Prevent the screen from sleeping during play.", ConfigType.BOOL, "true", section = "Display", appliesTo = WEB_FLASH_RGSS),
    FILTERING(
        "filtering", "Scaling filter", "Sharp keeps pixel art crisp; Smooth blurs when scaled.", ConfigType.CHOICE, "smooth",
        listOf("smooth" to "Smooth (bilinear)", "sharp" to "Sharp (nearest-neighbour)"), section = "Display", appliesTo = WEB_FLASH_RGSS,
    ),
    SCREEN_FIT(
        "screenFit", "Screen fit",
        "How the game fills the screen. Fit keeps its proportions (black bars at the sides), Stretch fills the whole " +
            "screen, Pixel-perfect scales by whole numbers so pixel art stays crisp. Can also be changed in-game (☰ › Display).",
        ConfigType.CHOICE, "fit",
        listOf("fit" to "Fit (keep proportions)", "fill" to "Stretch to fill", "integer" to "Pixel-perfect (whole-number scale)"),
        section = "Display", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    SCREEN_SIZE(
        "screenSize", "Game size", "Size of the game area (%), centred. Lower it to keep the picture clear of the on-screen controls. " +
            "Can also be changed in-game (☰ › Display).",
        ConfigType.INT, "100", range = 50..100, section = "Display", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    SHOW_FPS("showFps", "Performance overlay", "Show measured frames per second.", ConfigType.BOOL, "false", section = "Display"),
    GAMEPAD_MODE(
        "gamepadMode", "On-screen controls", "When to show the virtual gamepad.", ConfigType.CHOICE, "auto",
        listOf("auto" to "Auto (hide when a controller is connected)", "always" to "Always", "never" to "Never"),
        section = "Controls", appliesTo = WITH_CONTROLS,
    ),
    GAMEPAD_OPACITY("gamepadOpacity", "Controls opacity", "Transparency of on-screen buttons.", ConfigType.INT, "55", range = 15..100, section = "Controls", appliesTo = WITH_CONTROLS),
    GAMEPAD_SCALE("gamepadScale", "Controls size", "Size of on-screen buttons (%).", ConfigType.INT, "100", range = 60..160, section = "Controls", appliesTo = WITH_CONTROLS),
    HAPTICS("haptics", "Haptic feedback", "Vibrate briefly when on-screen buttons are pressed.", ConfigType.BOOL, "true", section = "Controls", appliesTo = WITH_CONTROLS),
    DEADZONE("deadzone", "Stick dead zone", "Analog stick travel ignored before a direction registers (%).", ConfigType.INT, "30", range = 5..70, section = "Controls"),
    BUTTON_MAP("buttonMap", "Controller mapping", "Which keyboard key each controller button sends.", ConfigType.BUTTON_MAP, ButtonMap.DEFAULT_ENCODED, section = "Controls", appliesTo = WITH_CONTROLS),
    MASTER_VOLUME(
        "masterVolume", "Master volume", "Game volume (%). RPG Maker MV/MZ only – uses the engine's own audio mixer.", ConfigType.INT, "100",
        section = "Audio", appliesTo = { it == Engine.RPG_MAKER_MV || it == Engine.RPG_MAKER_MZ },
    ),
    PAUSE_AUDIO("pauseAudio", "Pause audio in background", "Suspend game audio when you leave AceCore.", ConfigType.BOOL, "true", section = "Audio"),
    GAME_FILES(
        "gameFiles", "How games are loaded",
        "Direct: AceArcade runs the game straight from your folder (needs Android's \"All files access\"; nothing is copied). " +
            "Private copy: AceArcade copies the game into its own storage once and runs that copy.",
        ConfigType.CHOICE, "direct",
        listOf("direct" to "Direct from your folder", "copy" to "Private copy"),
        section = "Advanced", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    RGSS_HELPER(
        "rgssHelper", "AceArcade game helper",
        "A small script AceArcade loads before the game's own scripts. It reports when the game has started (launch statistics) " +
            "and powers the in-game cheat menu. Turn it off if a game misbehaves.",
        ConfigType.BOOL, "true", section = "Advanced", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    ALLOW_NETWORK(
        "allowNetwork", "Allow internet access", "Let the game load resources from the internet. Off by default for privacy and offline play.",
        ConfigType.BOOL, "false", section = "Advanced",
    ),
    ;

    /** Built-in default, which can differ per engine (e.g. Flash games expect Space/arrows rather than Z/X). */
    fun defaultFor(engine: Engine?): String = when {
        this == BUTTON_MAP && engine == Engine.FLASH -> ButtonMap.FLASH_DEFAULT_ENCODED
        this == BUTTON_MAP && engine?.family == EngineFamily.RGSS -> ButtonMap.RGSS_DEFAULT_ENCODED
        this == BUTTON_MAP && engine?.family == EngineFamily.GODOT -> ButtonMap.GODOT_DEFAULT_ENCODED
        this == BUTTON_MAP && engine?.family == EngineFamily.EASYRPG -> ButtonMap.EASYRPG_DEFAULT_ENCODED
        else -> default
    }

    fun bool(v: String) = v.equals("true", ignoreCase = true)
    fun int(v: String) = (v.toIntOrNull() ?: default.toInt()).coerceIn(range)

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

enum class ConfigSource(val label: String) { GAME("This game"), ENGINE("Engine default"), GLOBAL("Global"), DEFAULT("Built-in default") }

data class Resolved(val value: String, val source: ConfigSource)

/**
 * Resolves a value with priority: per-game override → per-engine override → global → built-in default.
 * Global values that equal the built-in default are still reported as GLOBAL so the UI can show where they live.
 */
class LayeredConfig(
    private val global: Map<String, String>,
    private val engine: Map<String, String>,
    private val game: Map<String, String>,
    /** The engine whose built-in defaults apply (null = generic defaults). */
    private val forEngine: Engine? = null,
) {
    fun resolve(key: ConfigKey): Resolved = when {
        game[key.id] != null -> Resolved(game.getValue(key.id), ConfigSource.GAME)
        engine[key.id] != null -> Resolved(engine.getValue(key.id), ConfigSource.ENGINE)
        global[key.id] != null -> Resolved(global.getValue(key.id), ConfigSource.GLOBAL)
        else -> Resolved(key.defaultFor(forEngine), ConfigSource.DEFAULT)
    }

    /** Value inherited by [scope] if its own override were removed. */
    fun inherited(key: ConfigKey, scope: ConfigSource): Resolved = when (scope) {
        ConfigSource.GAME -> LayeredConfig(global, engine, emptyMap(), forEngine).resolve(key)
        ConfigSource.ENGINE -> LayeredConfig(global, emptyMap(), emptyMap(), forEngine).resolve(key)
        else -> Resolved(key.defaultFor(forEngine), ConfigSource.DEFAULT)
    }

    fun string(key: ConfigKey) = resolve(key).value
    fun bool(key: ConfigKey) = key.bool(resolve(key).value)
    fun int(key: ConfigKey) = key.int(resolve(key).value)

    fun snapshot(): Map<String, String> = ConfigKey.entries.associate { it.id to resolve(it).value }
}
