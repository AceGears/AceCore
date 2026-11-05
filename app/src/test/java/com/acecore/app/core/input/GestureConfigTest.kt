package com.acecore.app.core.input

import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.model.Engine
import com.acecore.app.runtime.handheld.HandheldLaunchSpec
import com.acecore.app.core.handheld.HandheldPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GestureConfigTest {
    @Test fun builtInDefaultsMatchTheSettings() {
        val cfg = LayeredConfig(emptyMap(), emptyMap(), emptyMap(), Engine.GAME_BOY_ADVANCE)
        assertEquals(GestureConfig(), GestureConfig.from { cfg.string(it) })
    }

    @Test fun perGameOverrideWins() {
        val cfg = LayeredConfig(
            mapOf(ConfigKey.GESTURE_TWO_FINGER_TAP.id to "pause"), emptyMap(),
            mapOf(ConfigKey.GESTURE_TWO_FINGER_TAP.id to "cheats", ConfigKey.GESTURE_PINCH.id to "false"), Engine.NINTENDO_DS,
        )
        val g = GestureConfig.from { cfg.string(it) }
        assertEquals("cheats", g.twoFingerTap)
        assertFalse(g.pinch)
        assertEquals("off", g.actionFor(Gesture.PINCH))
    }

    @Test fun survivesTheLaunchSpec() {
        val g = GestureConfig(doubleTap = "menu", threeFingerTap = "ff", swipe = false, sensitivity = 150, edgeToolbar = false)
        val spec = HandheldLaunchSpec(1, "t", HandheldPlatform.GBA, "/r.gba", null, "/h", "/s", 0xFFFF0000, gestures = g.toJson())
        assertEquals(g, GestureConfig.fromJson(HandheldLaunchSpec.fromJson(spec.toJson()).gestures))
        assertEquals(GestureConfig(), GestureConfig.fromJson(""))
    }

    @Test fun offTurnsEverythingOff() {
        val g = GestureConfig(enabled = false)
        assertTrue(Gesture.entries.all { g.actionFor(it) == "off" })
    }

    @Test fun gestureKeysForRuntimesThatHaveGestures() {
        val keys = ConfigKey.entries.filter { it.section == "Gestures" }
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.all { it.appliesTo(Engine.NINTENDO_DS) && it.appliesTo(Engine.RPG_MAKER_XP) && it.appliesTo(Engine.POKEMON_ESSENTIALS) })
        assertTrue(keys.none { it.appliesTo(Engine.HTML5) || it.appliesTo(Engine.RENPY) })
    }
}
