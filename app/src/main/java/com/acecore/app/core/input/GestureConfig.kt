package com.acecore.app.core.input

import com.acecore.app.core.config.ConfigKey
import org.json.JSONObject

/** The in-game gesture settings for one game (resolved from the layered config: game › engine › global › default). */
data class GestureConfig(
    val enabled: Boolean = true,
    val doubleTap: String = "pause",
    val twoFingerTap: String = "menu",
    val threeFingerTap: String = "cheats",
    val swipe: Boolean = true,
    val pinch: Boolean = true,
    val sensitivity: Int = 100,
    val doubleTapMs: Int = 300,
    val haptics: Boolean = true,
) {
    /** The action id a tap gesture is set to ("off" when gestures are off). */
    fun actionFor(g: Gesture): String = if (!enabled) "off" else when (g) {
        Gesture.DOUBLE_TAP -> doubleTap
        Gesture.TWO_FINGER_TAP -> twoFingerTap
        Gesture.THREE_FINGER_TAP -> threeFingerTap
        Gesture.TWO_FINGER_SWIPE_UP -> if (swipe) "quicksave" else "off"
        Gesture.TWO_FINGER_SWIPE_DOWN -> if (swipe) "quickload" else "off"
        Gesture.PINCH -> if (pinch) "zoom" else "off"
    }

    /** A tracker tuned to these settings; [density] = screen pixels per dp. */
    fun tracker(density: Float, onGesture: (Gesture) -> Unit, onPinch: (Float) -> Unit): GestureTracker {
        val k = 100f / sensitivity.coerceIn(50, 200)
        return GestureTracker(slopPx = 12f * density, swipePx = 64f * density * k, tapMs = 250, doubleTapMs = doubleTapMs.toLong(),
            onGesture = onGesture, onPinch = onPinch)
    }

    fun toJson(): String = JSONObject().put("enabled", enabled).put("doubleTap", doubleTap).put("two", twoFingerTap)
        .put("three", threeFingerTap).put("swipe", swipe).put("pinch", pinch).put("sensitivity", sensitivity)
        .put("doubleTapMs", doubleTapMs).put("haptics", haptics).toString()

    companion object {
        /** Readable names of the actions (also used by the gesture test area). */
        fun actionLabel(id: String): String = when (id) {
            "off" -> "nothing"
            "pause" -> "pause / resume"
            "menu" -> "open the menu"
            "cheats" -> "open cheats"
            "ff" -> "fast-forward on / off"
            "screenshot" -> "take a screenshot"
            "quicksave" -> "quick save"
            "quickload" -> "quick load"
            "zoom" -> "zoom the picture"
            else -> id
        }

        fun from(get: (ConfigKey) -> String): GestureConfig = GestureConfig(
            enabled = ConfigKey.GESTURES.bool(get(ConfigKey.GESTURES)),
            doubleTap = get(ConfigKey.GESTURE_DOUBLE_TAP),
            twoFingerTap = get(ConfigKey.GESTURE_TWO_FINGER_TAP),
            threeFingerTap = get(ConfigKey.GESTURE_THREE_FINGER_TAP),
            swipe = ConfigKey.GESTURE_SWIPE.bool(get(ConfigKey.GESTURE_SWIPE)),
            pinch = ConfigKey.GESTURE_PINCH.bool(get(ConfigKey.GESTURE_PINCH)),
            sensitivity = ConfigKey.GESTURE_SENSITIVITY.int(get(ConfigKey.GESTURE_SENSITIVITY)),
            doubleTapMs = ConfigKey.GESTURE_DOUBLE_TAP_MS.int(get(ConfigKey.GESTURE_DOUBLE_TAP_MS)),
            haptics = ConfigKey.GESTURE_HAPTICS.bool(get(ConfigKey.GESTURE_HAPTICS)),
        )

        fun fromJson(s: String?): GestureConfig {
            if (s.isNullOrBlank()) return GestureConfig()
            return runCatching {
                JSONObject(s).let {
                    GestureConfig(
                        it.optBoolean("enabled", true), it.optString("doubleTap", "pause"), it.optString("two", "menu"),
                        it.optString("three", "cheats"), it.optBoolean("swipe", true), it.optBoolean("pinch", true),
                        it.optInt("sensitivity", 100), it.optInt("doubleTapMs", 300), it.optBoolean("haptics", true),
                    )
                }
            }.getOrDefault(GestureConfig())
        }
    }
}
