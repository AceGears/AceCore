package com.acecore.app.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTrackerTest {
    private val events = mutableListOf<Gesture>()
    private val pinches = mutableListOf<Float>()
    private val t = GestureTracker(onGesture = { events += it }, onPinch = { pinches += it })

    private fun tap(x: Float, y: Float, at: Long) { t.onDown(0, x, y, at); t.onUp(0, at + 60) }

    @Test fun doubleTap() {
        tap(100f, 100f, 0); tap(104f, 102f, 200)
        assertEquals(listOf(Gesture.DOUBLE_TAP), events)
    }

    @Test fun slowTapsAreNotADoubleTap() {
        tap(100f, 100f, 0); tap(100f, 100f, 2000)
        assertEquals(emptyList<Gesture>(), events)
    }

    @Test fun dragIsNotATap() {
        t.onDown(0, 100f, 100f, 0); t.onMove(0, 200f, 100f, 50); t.onUp(0, 80)
        t.onDown(0, 200f, 100f, 150); t.onUp(0, 200)
        assertEquals(emptyList<Gesture>(), events)
    }

    @Test fun twoAndThreeFingerTaps() {
        t.onDown(0, 100f, 100f, 0); t.onDown(1, 300f, 100f, 20); t.onUp(0, 120); t.onUp(1, 130)
        t.onDown(0, 100f, 100f, 1000); t.onDown(1, 300f, 100f, 1010); t.onDown(2, 500f, 100f, 1020)
        t.onUp(0, 1100); t.onUp(1, 1110); t.onUp(2, 1120)
        assertEquals(listOf(Gesture.TWO_FINGER_TAP, Gesture.THREE_FINGER_TAP), events)
    }

    @Test fun twoFingerSwipes() {
        t.onDown(0, 100f, 500f, 0); t.onDown(1, 300f, 500f, 10)
        for (i in 1..10) { t.onMove(0, 100f, 500f - i * 20f, 10L + i * 10); t.onMove(1, 300f, 500f - i * 20f, 10L + i * 10) }
        t.onUp(0, 200); t.onUp(1, 210)
        t.onDown(0, 100f, 100f, 1000); t.onDown(1, 300f, 100f, 1010)
        for (i in 1..10) { t.onMove(0, 100f, 100f + i * 20f, 1010L + i * 10); t.onMove(1, 300f, 100f + i * 20f, 1010L + i * 10) }
        t.onUp(0, 1200); t.onUp(1, 1210)
        assertEquals(listOf(Gesture.TWO_FINGER_SWIPE_UP, Gesture.TWO_FINGER_SWIPE_DOWN), events)
    }

    @Test fun pinchZoomsAndIsNotASwipeOrTap() {
        t.onDown(0, 200f, 300f, 0); t.onDown(1, 300f, 300f, 10)
        for (i in 1..10) { t.onMove(0, 200f - i * 10f, 300f, 10L + i * 10); t.onMove(1, 300f + i * 10f, 300f, 10L + i * 10) }
        t.onUp(0, 200); t.onUp(1, 210)
        assertTrue(events.isEmpty())
        val total = pinches.fold(1f) { a, b -> a * b }
        assertTrue("fingers moved apart: zoom in, got $total", total > 1.5f)
    }

    @Test fun edgeSwipeOnlyFromTheSides() {
        t.width = 1000f
        t.onDown(0, 990f, 300f, 0); for (i in 1..10) t.onMove(0, 990f - i * 20f, 300f, i * 10L); t.onUp(0, 120)
        t.onDown(0, 500f, 300f, 1000); for (i in 1..10) t.onMove(0, 500f - i * 20f, 300f, 1000 + i * 10L); t.onUp(0, 1120)
        t.onDown(0, 5f, 300f, 2000); for (i in 1..10) t.onMove(0, 5f + i * 20f, 300f, 2000 + i * 10L); t.onUp(0, 2120)
        assertEquals(listOf(Gesture.EDGE_SWIPE, Gesture.EDGE_SWIPE), events)
    }
}
