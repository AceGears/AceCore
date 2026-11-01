package com.acecore.app.runtime.rgss

import android.content.Context
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.model.Engine
import dalvik.system.BaseDexClassLoader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Everything the isolated ":rgss" process needs to start one game. */
@Serializable
data class RgssLaunchSpec(
    val gameId: Long,
    val title: String,
    val engine: String,
    /** Absolute path of the folder that contains Game.ini (the user's own folder in direct mode, or AceCore's copy). */
    val gamePath: String,
    /** Absolute path of the per-game private data directory (SDL pref path). */
    val dataPath: String,
    val config: Map<String, String>,
    val accentArgb: Long = 0xFFFF2A2AL,
    /** True when the game runs straight from the user's folder (no private copy). */
    val direct: Boolean = false,
    /** Whether AceCore's runtime helper (and therefore the in-game cheat menu) is loaded. */
    val helper: Boolean = true,
    val cheatsEnabled: Boolean = false,
    /** Wall-clock time of the Play tap, for launch-time measurement. */
    val launchRequestedAt: Long = 0,
    /** Name of the game's settings file without ".ini" (mkxp-z "execName"): "Game", or e.g. "Flux" for Flux.ini. */
    val execName: String = "Game",
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val EXTRA = "com.acecore.app.extra.RGSS_SPEC"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String): RgssLaunchSpec = json.decodeFromString(serializer(), s)
    }
}

object RgssRuntime {
    /** Native libraries, in load order, that make up the mkxp-z runtime (from mkxp-z-android-reworked). */
    val LIBRARIES = arrayOf("SDL2", "SDL2_image", "SDL2_ttf", "SDL2_sound", "openal", "ruby", "mkxp-z")

    private const val HELPER_ASSET = "acecore/rgss/acecore_runtime.rb"
    const val PRELOAD_FILE = "acecore_preload.rb"
    const val COMPAT_FILE = "acecore_compat.rb"

    private const val COMPAT_ASSET = "acecore/rgss/acecore_compat.rb"

    /**
     * Header for the always-on compatibility preload (acecore_compat.rb, also used with the game helper off): the
     * device screen in landscape pixels, which upstream mkxp-z reports through Graphics.display_width/height.
     */
    fun compatHeader(displayW: Int, displayH: Int, displayFile: File? = null, fit: String = DisplayPrefs.FIT, smooth: Boolean = true, sizePercent: Int = 100, dataDir: File? = null, display: DisplayPrefs? = null): String =
        "# encoding: utf-8\nmodule AceCoreCompat\n  DISPLAY_W = $displayW\n  DISPLAY_H = $displayH\n" +
            "  DATA_DIR = ${dataDir?.let { rubyString(it.absolutePath) } ?: "nil"}\n" +
            "  DISPLAY_FILE = ${displayFile?.let { rubyString(it.absolutePath) } ?: "nil"}\n" +
            "  FIT = ${rubyString(fit)}\n  SMOOTH = $smooth\n  SIZE = $sizePercent; LOCALE = ${rubyString(java.util.Locale.getDefault().toLanguageTag())}\n" +
            "  ZOOM = ${display?.zoomPercent ?: 100}; OFFSET_X = ${display?.offsetX ?: 0}; OFFSET_Y = ${display?.offsetY ?: 0}\nend\n"

    /** Real screen size in landscape orientation (games run landscape). */
    fun landscapeDisplaySize(context: Context): Pair<Int, Int> {
        val (w, h) = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val b = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val m = context.resources.displayMetrics
                m.widthPixels to m.heightPixels
            }
        }.getOrElse { 1920 to 1080 }
        return maxOf(w, h) to minOf(w, h)
    }

    /**
     * True when this APK actually contains the mkxp-z native library for the device's ABI. Uses the class loader's
     * library lookup, which also finds uncompressed libraries stored inside the APK (no extraction needed).
     */
    fun isPresent(context: Context): Boolean = runCatching {
        val loader = context.classLoader as? BaseDexClassLoader ?: return false
        LIBRARIES.all { loader.findLibrary(it) != null }
    }.getOrDefault(false)

    /** Kills a leftover ":rgss" process (e.g. still shutting down) so the next game gets a fresh Ruby VM. */
    fun killStaleProcess(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        val name = context.packageName + ":rgss"
        am.runningAppProcesses?.filter { it.processName == name }?.forEach { android.os.Process.killProcess(it.pid) }
    }

    /** Folder (inside the game's private data dir) used to talk to the runtime helper. */
    fun helperDir(dataDir: File): File = File(dataDir, "acecore")

    fun rgssVersion(engine: Engine): Int = when (engine) {
        Engine.RPG_MAKER_VX -> 2
        Engine.RPG_MAKER_VX_ACE -> 3
        else -> 1 // XP and Pokémon Essentials
    }

    /**
     * Writes the per-launch files into [dataDir]: the player's settings (mkxp.json, merged by mkxp-z over the game's
     * own mkxp.json, which is never modified) and, when [helper] is on, the runtime helper as a preload script.
     */
    fun writeLaunchFiles(
        context: Context,
        dataDir: File,
        engine: Engine,
        config: Map<String, String>,
        helper: Boolean,
        cheatsEnabled: Boolean,
        cheatFlags: Map<String, String>,
        launchRequestedAt: Long,
    ) {
        dataDir.mkdirs()
        val dir = helperDir(dataDir).apply { mkdirs() }
        // Stale signals from a previous session must never be mistaken for this one.
        listOf("ready", "error", "exit_called", "boot", "stage", "runtime.log", "java_crash.txt", "engine_output.txt", "cmd", "result", "state", "items", "natures", "species", "screen", "input_test", "input_state", "timeline", "heartbeat", "scenes", "dpad", "walk", "walk_done", "walk_ready").forEach { File(dir, it).delete() }
        val (dw, dh) = landscapeDisplaySize(context)
        val compatBody = context.assets.open(COMPAT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val compat = File(dataDir, COMPAT_FILE).apply { writeText(compatHeader(dw, dh, DisplayPrefs.file(dataDir), DisplayPrefs.from(config).fit,
            config[ConfigKey.FILTERING.id] != "sharp", DisplayPrefs.from(config).sizePercent, dataDir, DisplayPrefs.from(config)) + compatBody, Charsets.UTF_8) }
        val preload = File(dataDir, PRELOAD_FILE)
        if (helper) {
            val body = context.assets.open(HELPER_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            preload.writeText(helperHeader(dir, launchRequestedAt, rgssVersion(engine), cheatsEnabled, cheatFlags) + body, Charsets.UTF_8)
        } else {
            preload.delete()
        }
        val scripts = listOfNotNull(compat.absolutePath, if (helper) preload.absolutePath else null)
        File(dataDir, "mkxp.json").writeText(overrideConfigJson(config, scripts))
    }

    /** Ruby constants the helper reads. Values are escaped so a path can never break out of the string literal. */
    fun helperHeader(dir: File, launchAt: Long, rgss: Int, cheats: Boolean, flags: Map<String, String>): String = buildString {
        append("# encoding: utf-8\n")
        append("module AceCoreBoot\n")
        append("  DIR = ").append(rubyString(dir.absolutePath)).append('\n')
        append("  LAUNCH_AT = ").append(launchAt).append('\n')
        append("  RGSS = ").append(rgss).append('\n')
        append("  CHEATS = ").append(cheats).append('\n')
        append("  FLAGS = {")
        append(CheatFlags.KEYS.joinToString(", ") { k -> rubyString(k) + " => " + rubyString(flags[k] ?: "0") })
        append("}\n")
        append("end\n")
    }

    fun rubyString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '#' -> append("\\#")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\x%02X".format(c.code)) else append(c)
        }
        append('"')
    }

    fun overrideConfigJson(config: Map<String, String>, preloadScripts: List<String> = emptyList()): String {
        val smooth = config[ConfigKey.FILTERING.id] != "sharp"
        val display = DisplayPrefs.from(config)
        return Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                // Pixel-perfect: AceArcade sizes the surface to an exact multiple, so nearest-neighbour keeps it crisp.
                put("smoothScaling", smooth && !display.integerScaling)
                // Fit / Stretch / Pixel-perfect are done by the engine inside the full-screen surface.
                put("fixedAspectRatio", display.fixedAspectRatio)
                // Sharp scaling everywhere: whole-number nearest-neighbour step first; Fit/Stretch then smooth only the
                // last, fractional step (sharp-bilinear); Pixel-perfect stops at the whole number.
                put("integerScalingActive", true)
                put("integerScalingLastMile", !display.integerScaling)
                put("vsync", true)
                put("frameSkip", false)
                put("syncToRefreshrate", false)
                // mkxp-z's F1 settings window needs a keyboard and mouse; AceCore provides its own settings.
                put("enableSettings", false)
                // F12 soft-reset is exposed in AceCore's in-game menu as "Restart game".
                put("enableReset", true)
                if (preloadScripts.isNotEmpty()) put("preloadScript", JsonArray(preloadScripts.map { JsonPrimitive(it) }))
            },
        )
    }
}

/** Persistent cheat switches understood by the runtime helper (see acecore_runtime.rb). */
object CheatFlags {
    const val WALK_SPEED = "walk_speed"
    const val NO_ENCOUNTERS = "no_encounters"
    val KEYS = listOf(WALK_SPEED, NO_ENCOUNTERS)
}
