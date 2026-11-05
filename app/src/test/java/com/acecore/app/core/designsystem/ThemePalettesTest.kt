package com.acecore.app.core.designsystem

import com.acecore.app.core.datastore.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every theme has a dark and a light version, and both are readable (WCAG contrast). */
class ThemePalettesTest {
    private val themes = ThemePreset.entries.filter { it != ThemePreset.DYNAMIC && it != ThemePreset.CUSTOM && it != ThemePreset.SIGNATURE_LIGHT }

    @Test fun everyThemeHasADarkAndALightVersion() {
        for (p in themes) {
            assertTrue("$p dark", ThemePalettes.of(p, dark = true).dark)
            assertTrue("$p light", !ThemePalettes.of(p, dark = false).dark)
        }
    }

    @Test fun textAndAccentsAreReadableInBothModes() {
        for (p in themes) for (dark in listOf(true, false)) {
            val pal = ThemePalettes.of(p, dark)
            val bg = pal.background
            val on = Contrast.onColor(bg)
            assertTrue("$p dark=$dark text ${Contrast.ratio(on, bg)}", Contrast.ratio(on, bg) >= 7.0)
            // Text on cards (glass cards over the backdrop are checked on their solid colour).
            assertTrue("$p dark=$dark card text", Contrast.ratio(Contrast.onColor(pal.surface), pal.surface) >= 7.0)
            assertTrue("$p dark=$dark primary ${Contrast.ratio(pal.primary, bg)}", Contrast.ratio(pal.primary, bg) >= 3.0)
            val onPrimary = Contrast.onFill(pal.primary)
            assertTrue("$p dark=$dark onPrimary ${Contrast.ratio(onPrimary, pal.primary)}", Contrast.ratio(onPrimary, pal.primary) >= 4.5)
        }
    }

    @Test fun premiumThemesHaveBackdropsInBothModes() {
        for (p in ThemePreset.entries.filter { it.premium }) for (dark in listOf(true, false)) {
            val pal = ThemePalettes.of(p, dark)
            assertNotNull("$p dark=$dark", pal.backdrop)
            assertTrue(pal.glassAlpha in 0.5f..0.9f)
            // The backdrop's ends stay on the same side (dark/light) as the theme, so text over it keeps its contrast.
            assertEquals(dark, Contrast.luminance(pal.backdrop!!.top) < 0.4)
            assertEquals(dark, Contrast.luminance(pal.backdrop!!.bottom) < 0.4)
            // Text straight on the backdrop stays readable at the brightest point of every light orb.
            val on = Contrast.onColor(pal.background)
            for (end in listOf(pal.backdrop!!.top, pal.backdrop!!.bottom)) for (orb in pal.backdrop!!.orbs) {
                val spot = Contrast.mix(end, orb.color, if (dark) 0.55f else 0.7f)
                assertTrue("$p dark=$dark orb ${Contrast.ratio(on, spot)}", Contrast.ratio(on, spot) >= 4.5)
            }
        }
    }

    @Test fun customColoursGetTheOtherMode() {
        val lightCustom = Palette(0xFF1D4ED8, 0xFF334155, 0xFF0EA5E9, 0xFFF8FAFC, 0xFFFFFFFF, 0xFF3B82F6, 0xFF64748B)
        val d = ThemePalettes.custom(lightCustom, dark = true)
        assertTrue(d.dark)
        assertTrue(Contrast.ratio(d.primary, d.background) >= 4.5)
        assertEquals(lightCustom, ThemePalettes.custom(lightCustom, dark = false))
    }
}
