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
        listOf(
            "fit" to "Fit (keep proportions)", "cover" to "Fill screen (keep proportions, crop edges)",
            "fill" to "Stretch to fill", "integer" to "Pixel-perfect (whole-number scale)",
            "original" to "Original size (1 game pixel = 1 screen pixel)", "zoom" to "Custom zoom",
        ),
        section = "Display", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    SCREEN_FILTER(
        "screenFilter", "Upscaling filter",
        "How the game's pixels are enlarged. Smooth edges rounds off the jagged steps of pixel art (Scale4x), Smooth " +
            "gives a soft high-resolution look (bicubic), CRT imitates an old TV. Can also be changed in-game (☰ › Display).",
        ConfigType.CHOICE, "off",
        listOf("off" to "Off (sharp pixels)", "edges" to "Smooth edges (Scale4x)", "smooth" to "Smooth (bicubic)", "crt" to "CRT (scanlines)"),
        section = "Display", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    SCREEN_ZOOM(
        "screenZoom", "Custom zoom", "Size of the game with Screen fit = Custom zoom, as a percentage of the Fit size " +
            "(above 100% the edges are cut off). Can also be changed in-game (☰ › Display).",
        ConfigType.INT, "100", range = 25..300, section = "Display", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    SCREEN_OFFSET_X(
        "screenOffsetX", "Picture position (horizontal)", "Moves the game picture left (−100) or right (+100) inside the " +
            "free space; 0 = centred.", ConfigType.INT, "0", range = -100..100, section = "Display",
        appliesTo = { it.family == EngineFamily.RGSS },
    ),
    SCREEN_OFFSET_Y(
        "screenOffsetY", "Picture position (vertical)", "Moves the game picture up (−100) or down (+100) inside the " +
            "free space; 0 = centred.", ConfigType.INT, "0", range = -100..100, section = "Display",
        appliesTo = { it.family == EngineFamily.RGSS },
    ),
    SCREEN_SIZE(
        "screenSize", "Game size", "Size of the game area (%), centred. Lower it to keep the picture clear of the on-screen controls. " +
            "Can also be changed in-game (☰ › Display).",
        ConfigType.INT, "100", range = 50..100, section = "Display", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    GODOT_RENDERER(
        "godotRenderer", "Renderer",
        "Automatic uses Vulkan (Godot's Mobile renderer) on devices with a hardware Vulkan driver and OpenGL ES 3 " +
            "(Compatibility renderer) otherwise. Try OpenGL if a game shows a black screen or hangs at start-up.",
        ConfigType.CHOICE, "auto",
        listOf("auto" to "Automatic", "vulkan" to "Vulkan (Mobile)", "opengl" to "OpenGL ES 3 (Compatibility)"),
        section = "Display", appliesTo = { it.family == EngineFamily.GODOT },
    ),
    SHOW_FPS("showFps", "Performance overlay", "Show measured frames per second.", ConfigType.BOOL, "false", section = "Display"),
    FF_SPEED(
        "ffSpeed", "Fast-forward speed",
        "How much faster the game runs while fast-forward is on. Frame-based and time-based game logic both speed up; " +
            "the highest speeds depend on how fast the device can run the game's logic.",
        ConfigType.CHOICE, "2",
        listOf("1.25" to "1.25×", "1.5" to "1.5×", "2" to "2×", "3" to "3×", "4" to "4×", "6" to "6×", "8" to "8×"),
        section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    FF_MODE(
        "ffMode", "Fast-forward button",
        "Cycle: each tap moves to the next speed (1.25× … 8×, then off). Toggle: tap on / tap off at the chosen speed. " +
            "Hold: fast-forward only while the button is held.",
        ConfigType.CHOICE, "cycle",
        listOf("cycle" to "Cycle through speeds", "onoff" to "Toggle (tap on / tap off)", "hold" to "Hold"),
        section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    FF_AUDIO(
        "ffAudio", "Sound while fast-forwarding",
        "Quiet skips sound effects while fast-forwarding (music keeps playing); Normal plays everything.",
        ConfigType.CHOICE, "quiet", listOf("quiet" to "Quiet (no sound effects)", "normal" to "Normal"),
        section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    PAD_FF("padFf", "FF button", "Show the fast-forward button on the on-screen controller.", ConfigType.BOOL, "true", section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS }),
    PAD_QS("padQs", "QS button", "Show the quick save button on the on-screen controller.", ConfigType.BOOL, "true", section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS }),
    PAD_QL("padQl", "QL button", "Show the quick load button on the on-screen controller.", ConfigType.BOOL, "true", section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS }),
    TOOLS_SCALE("toolsScale", "FF / QS / QL size", "Size of the gameplay buttons (%).", ConfigType.INT, "100", range = 60..160, section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS }),
    TOOLS_OPACITY("toolsOpacity", "FF / QS / QL opacity", "Transparency of the gameplay buttons.", ConfigType.INT, "70", range = 15..100, section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS }),
    GAMEPAD_MODE(
        "gamepadMode", "On-screen controls", "When to show the virtual gamepad.", ConfigType.CHOICE, "auto",
        listOf("auto" to "Auto (hide when a controller is connected)", "always" to "Always", "never" to "Never"),
        section = "Controls", appliesTo = WITH_CONTROLS,
    ),
    GAMEPAD_OPACITY("gamepadOpacity", "Controls opacity", "Transparency of on-screen buttons.", ConfigType.INT, "55", range = 15..100, section = "Controls", appliesTo = WITH_CONTROLS),
    GAMEPAD_SCALE("gamepadScale", "Controls size", "Size of on-screen buttons (%).", ConfigType.INT, "100", range = 60..160, section = "Controls", appliesTo = WITH_CONTROLS),
    CHEAT_SHORTCUT(
        "cheatShortcut", "Cheats button", "An on-screen button next to FF / QS / QL that opens the cheat menu directly, " +
            "with the same look, size and opacity. Move it with ☰ › Move on-screen controls. Shown only when cheats are on for the game.",
        ConfigType.BOOL, "true", section = "Gameplay", appliesTo = { it.family == EngineFamily.RGSS },
    ),
    HAPTICS("haptics", "Haptic feedback", "Vibrate briefly when on-screen buttons are pressed.", ConfigType.BOOL, "true", section = "Controls", appliesTo = WITH_CONTROLS),
    DEADZONE("deadzone", "Stick dead zone", "Analog stick travel ignored before a direction registers (%).", ConfigType.INT, "30", range = 5..70, section = "Controls"),
    BUTTON_MAP("buttonMap", "Controller mapping", "Which keyboard key each controller button sends.", ConfigType.BUTTON_MAP, ButtonMap.DEFAULT_ENCODED, section = "Controls", appliesTo = WITH_CONTROLS),
    MASTER_VOLUME(
        "masterVolume", "Master volume", "Game volume (%). RPG Maker MV/MZ only – uses the engine's own audio mixer.", ConfigType.INT, "100",
        section = "Audio", appliesTo = { it == Engine.RPG_MAKER_MV || it == Engine.RPG_MAKER_MZ },
    ),
    PAUSE_AUDIO("pauseAudio", "Pause audio in background", "Suspend game audio when you leave AceArcade.", ConfigType.BOOL, "true", section = "Audio"),
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
