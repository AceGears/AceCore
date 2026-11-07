package com.acecore.app.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockLayoutTest {
    private val phone = 1080 / 2.625f - 28f // 1080 px phone at 420 dpi, minus the dock's side padding
    private val corner = DockLayout.BAR_HEIGHT / 2

    /** The shape around the end tabs stays on the straight top edge, and the content fills the width exactly. */
    private fun checkFits(l: DockLayout) {
        assertFalse("must not scroll: $l", l.scrolls)
        assertEquals("fills the width: $l", l.width, l.contentWidth, 0.01f)
        assertTrue("first shape clear of the rounded end: $l", l.centre(0) - DockLayout.NOTCH_HALF >= corner - 0.01f)
        assertTrue("last shape clear of the rounded end: $l", l.centre(l.count - 1) + DockLayout.NOTCH_HALF <= l.width - corner + 0.01f)
        assertTrue("touch target: $l", l.slot >= 48f)
    }

    @Test fun phoneFitsUpToFiveTabs() { for (n in 1..5) checkFits(DockLayout.of(phone, n)) }

    @Test fun phoneKeepsTheStraightRunWhenThereIsRoom() {
        for (n in 2..4) { val l = DockLayout.of(phone, n); assertTrue("$l", l.centre(0) - DockLayout.NOTCH_HALF >= corner + DockLayout.END_FLAT - 0.01f) }
    }

    @Test fun fiveTabsOnAPhoneGiveUpTheStraightRunNotTheFit() {
        val l = DockLayout.of(phone, 5)
        assertEquals(corner + DockLayout.NOTCH_HALF, l.reach, 0.01f)
        assertTrue(l.slot >= DockLayout.SLOT_TIGHT)
    }

    @Test fun manyTabsScrollWithFullSlotsAndClearEnds() {
        val l = DockLayout.of(phone, 8)
        assertTrue(l.scrolls)
        assertEquals(DockLayout.SLOT_MIN, l.slot, 0.01f)
        assertTrue(l.centre(0) - DockLayout.NOTCH_HALF >= corner - 0.01f)
    }

    @Test fun tabletIsCappedAndFits() {
        val l = DockLayout.of(1280f, 3)
        assertTrue(l.width < 1280f)
        assertTrue(l.slot <= DockLayout.SLOT_MAX + 0.01f)
        checkFits(l)
    }

    @Test fun singleTab() { checkFits(DockLayout.of(phone, 1)); checkFits(DockLayout.of(300f, 1)) }
}
