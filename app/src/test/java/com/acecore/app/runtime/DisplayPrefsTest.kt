package com.acecore.app.runtime

import com.acecore.app.core.config.ConfigKey
import com.acecore.app.runtime.rgss.DisplayPrefs
import com.acecore.app.runtime.rgss.RgssRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DisplayPrefsTest {
    @Test fun defaultsAndClamping() {
        assertEquals(DisplayPrefs("fit", 100), DisplayPrefs.from(emptyMap()))
        assertEquals(DisplayPrefs("fit", 50), DisplayPrefs.from(mapOf("screenFit" to "bogus", "screenSize" to "10")))
        assertEquals(DisplayPrefs("integer", 80), DisplayPrefs.from(mapOf("screenFit" to "integer", "screenSize" to "80")))
    }

    @Test fun engineSwitches() {
        assertTrue(DisplayPrefs("fit").fixedAspectRatio); assertFalse(DisplayPrefs("fit").integerScaling)
        assertFalse(DisplayPrefs("fill").fixedAspectRatio)
        assertTrue(DisplayPrefs("integer").integerScaling)
        val json = RgssRuntime.overrideConfigJson(mapOf(ConfigKey.SCREEN_FIT.id to "integer"))
        assertTrue(json, json.contains("\"fixedAspectRatio\":true") && json.contains("\"integerScalingActive\":true") &&
            json.contains("\"integerScalingLastMile\":false"))
        assertTrue(RgssRuntime.overrideConfigJson(mapOf(ConfigKey.SCREEN_FIT.id to "fill")).contains("\"fixedAspectRatio\":false"))
    }

    @Test fun pendingChangeRoundTrip() {
        val dir = Files.createTempDirectory("disp").toFile()
        assertNull(DisplayPrefs.takePending(dir))
        assertTrue(DisplayPrefs.write(dir, DisplayPrefs("fill", 75)))
        assertEquals(mapOf("screenFit" to "fill", "screenSize" to "75"), DisplayPrefs.takePending(dir))
        assertNull("consumed", DisplayPrefs.takePending(dir))
        DisplayPrefs.write(dir, DisplayPrefs("integer", 60))
        DisplayPrefs.writePending(dir, mapOf("buttonMap" to "A=ENTER;B=X", "gamepadMode" to "never", "evil" to "x"))
        assertEquals(mapOf("screenFit" to "integer", "screenSize" to "60", "buttonMap" to "A=ENTER;B=X", "gamepadMode" to "never"),
            DisplayPrefs.takePending(dir))
        dir.deleteRecursively()
    }

    @Test fun compatHeaderNamesDisplayFile() {
        val h = RgssRuntime.compatHeader(3120, 1440, java.io.File("/data/x/acecore/settings.json"))
        assertTrue(h, h.contains("DISPLAY_W = 3120") && h.contains("DISPLAY_FILE = \"/data/x/acecore/settings.json\""))
    }
}
