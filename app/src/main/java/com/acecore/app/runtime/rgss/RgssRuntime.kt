package com.acecore.app.runtime.rgss

import android.content.Context
import com.acecore.app.core.config.ConfigKey
import dalvik.system.BaseDexClassLoader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Everything the isolated ":rgss" process needs to start one game. */
@Serializable
data class RgssLaunchSpec(
    val gameId: Long,
    val title: String,
    val engine: String,
    /** Absolute path of the private working copy of the game. */
    val gamePath: String,
    /** Absolute path of the per-game private data directory (SDL pref path). */
    val dataPath: String,
    val config: Map<String, String>,
    val accentArgb: Long = 0xFFE3132BL,
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

    /**
     * mkxp-z merges `<pref path>/mkxp.json` over the game's own mkxp.json. AceCore writes the player's settings there,
     * so a game's shipped configuration (e.g. Pokémon Essentials v19+) is never modified.
     */
    fun overrideConfigJson(config: Map<String, String>): String {
        val smooth = config[ConfigKey.FILTERING.id] != "sharp"
        return Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("smoothScaling", smooth)
                put("fixedAspectRatio", true)
                put("vsync", true)
                put("frameSkip", false)
                put("syncToRefreshrate", false)
                // mkxp-z's F1 settings window needs a keyboard and mouse; AceCore provides its own settings.
                put("enableSettings", false)
                // F12 soft-reset is exposed in AceCore's in-game menu as "Restart game".
                put("enableReset", true)
            },
        )
    }
}
