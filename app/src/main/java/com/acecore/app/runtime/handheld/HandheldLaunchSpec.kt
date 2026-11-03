package com.acecore.app.runtime.handheld

import android.content.Context
import android.content.Intent
import com.acecore.app.core.handheld.HandheldPlatform
import org.json.JSONObject

/**
 * What the library passes to [HandheldActivity] (built by LaunchCoordinator.prepareHandheld).
 *
 * [romPath] is a file path the emulator can open directly: the ROM in the user's folder when AceArcade has direct
 * storage access, otherwise a copy in app storage (ZIP archives are always unpacked to app storage). [saveDir] holds
 * the game's battery save and save states (app storage, per game); the user's ROM files are never written to.
 */
data class HandheldLaunchSpec(
    val gameId: Long,
    val title: String,
    val platform: HandheldPlatform,
    val romPath: String,
    /** SHA-1 of the ROM (upper-case hex) when known; identifies the dump for cheat matching and verification. */
    val romSha1: String?,
    val helperDir: String,
    val saveDir: String,
    val accentArgb: Long,
    val buttonMap: String = "",
    val gamepadMode: String = "auto",
    val opacity: Int = 55,
    val scale: Int = 100,
    val haptics: Boolean = true,
    /** Screen fit etc. ([com.acecore.app.runtime.rgss.DisplayPrefs] JSON). */
    val display: String = "",
    val smooth: Boolean = false,
    val showFps: Boolean = false,
    /** DS screen layout id (see [DsLayout]). */
    val dsLayout: String = "vertical",
    /** Game Boy palette (mGBA's names, e.g. "Grayscale", "DMG Green"). */
    val gbPalette: String = "Grayscale",
    val fastForwardSpeed: Float = 2f,
    val volume: Int = 100,
    val vibration: Boolean = true,
    /** Automatic save backups kept per game (0 = off). */
    val backups: Int = 5,
) {
    fun toJson(): String = JSONObject().put("gameId", gameId).put("title", title).put("platform", platform.id).put("romPath", romPath)
        .put("romSha1", romSha1).put("helperDir", helperDir).put("saveDir", saveDir).put("accent", accentArgb)
        .put("buttonMap", buttonMap).put("gamepadMode", gamepadMode).put("opacity", opacity).put("scale", scale)
        .put("haptics", haptics).put("display", display).put("smooth", smooth).put("showFps", showFps)
        .put("dsLayout", dsLayout).put("gbPalette", gbPalette).put("ff", fastForwardSpeed.toDouble()).put("volume", volume)
        .put("vibration", vibration).put("backups", backups).toString()

    companion object {
        const val EXTRA = "com.acecore.app.extra.HANDHELD_SPEC"

        fun fromJson(s: String): HandheldLaunchSpec = JSONObject(s).let {
            HandheldLaunchSpec(
                it.getLong("gameId"), it.getString("title"), HandheldPlatform.byId(it.getString("platform")) ?: HandheldPlatform.GBA,
                it.getString("romPath"), it.optString("romSha1").ifEmpty { null }, it.getString("helperDir"), it.getString("saveDir"),
                it.optLong("accent", 0xFFFF2A2AL), it.optString("buttonMap"), it.optString("gamepadMode", "auto"),
                it.optInt("opacity", 55), it.optInt("scale", 100), it.optBoolean("haptics", true), it.optString("display"),
                it.optBoolean("smooth", false), it.optBoolean("showFps", false), it.optString("dsLayout", "vertical"),
                it.optString("gbPalette", "Grayscale"), it.optDouble("ff", 2.0).toFloat(), it.optInt("volume", 100),
                it.optBoolean("vibration", true), it.optInt("backups", 5),
            )
        }

        fun intent(context: Context, spec: HandheldLaunchSpec): Intent =
            Intent(context, HandheldActivity::class.java).putExtra(EXTRA, spec.toJson())
    }
}

/** Nintendo DS screen arrangements (AceArcade lays out the two screens itself; the emulator outputs them stacked). */
enum class DsLayout(val id: String, val label: String) {
    VERTICAL("vertical", "Stacked (top above bottom)"),
    HORIZONTAL("horizontal", "Side by side"),
    TOP_FOCUS("top-focus", "Large top screen, small bottom"),
    BOTTOM_FOCUS("bottom-focus", "Large bottom screen, small top"),
    TOP_ONLY("top", "Top screen only"),
    BOTTOM_ONLY("bottom", "Bottom screen only"),
    PICTURE_IN_PICTURE("pip", "Top screen with bottom inset"),
    ;

    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: VERTICAL }
}
