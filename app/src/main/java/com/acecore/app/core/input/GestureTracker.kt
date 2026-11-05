package com.acecore.app.core.input

import kotlin.math.abs
import kotlin.math.hypot

/** Touch gestures AceArcade recognises in games and in the gesture test area. */
enum class Gesture(val label: String) {
    DOUBLE_TAP("Double tap"),
    TWO_FINGER_TAP("Two-finger tap"),
    THREE_FINGER_TAP("Three-finger tap"),
    TWO_FINGER_SWIPE_UP("Two-finger swipe up"),
    TWO_FINGER_SWIPE_DOWN("Two-finger swipe down"),
    PINCH("Pinch"),
}

/**
 * Recognises taps, multi-finger taps, two-finger swipes and pinches from raw pointer events (platform-independent, so
 * it can be unit-tested and shared by every game screen). Coordinates are pixels, times milliseconds.
 *
 * [slopPx]: how far a finger may move and still count as a tap; [swipePx]: two-finger travel for a swipe (both scaled
 * by the user's sensitivity setting); [tapMs]: longest press that is a tap; [doubleTapMs]: longest gap between the
 * taps of a double tap. [onPinch] gets the scale change since the previous call (1.0 = none).
 */
class GestureTracker(
    var slopPx: Float = 24f,
    var swipePx: Float = 120f,
    var tapMs: Long = 250,
    var doubleTapMs: Long = 300,
    private val onGesture: (Gesture) -> Unit,
    private val onPinch: (Float) -> Unit = {},
) {
    private data class P(val sx: Float, val sy: Float, var x: Float, var y: Float)

    private val down = linkedMapOf<Int, P>()
    private var gestureStart = 0L
    private var maxPointers = 0
    private var moved = false
    private var swiped = false
    private var pinching = false
    private var lastSpan = 0f
    private var lastTapAt = -1L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** True while two or more fingers are on the screen (a game should stop treating the touch as its own input). */
    val multiTouch get() = down.size >= 2 || maxPointers >= 2

    fun onDown(id: Int, x: Float, y: Float, t: Long) {
        if (down.isEmpty()) { gestureStart = t; maxPointers = 0; moved = false; swiped = false; pinching = false }
        down[id] = P(x, y, x, y)
        maxPointers = maxOf(maxPointers, down.size)
        if (down.size == 2) lastSpan = span()
    }

    fun onMove(id: Int, x: Float, y: Float, t: Long) {
        val p = down[id] ?: return
        p.x = x; p.y = y
        if (hypot(x - p.sx, y - p.sy) > slopPx) moved = true
        if (down.size == 2 && !swiped) {
            val s = span()
            val (dy, dx) = averageDelta()
            val spanChange = s - startSpan()
            // A pinch changes the finger distance; a swipe moves both fingers the same way.
            if (!pinching && abs(spanChange) > slopPx * 2 && abs(spanChange) > abs(dy)) pinching = true
            if (pinching) {
                if (lastSpan > 0f && s > 0f) onPinch(s / lastSpan)
                lastSpan = s
            } else if (abs(dy) > swipePx && abs(dy) > abs(dx) * 1.5f) {
                swiped = true
                onGesture(if (dy < 0) Gesture.TWO_FINGER_SWIPE_UP else Gesture.TWO_FINGER_SWIPE_DOWN)
            }
        }
    }

    fun onUp(id: Int, t: Long) {
        val p = down.remove(id) ?: return
        if (down.isNotEmpty()) return
        val duration = t - gestureStart
        val tap = !moved && !swiped && !pinching && duration <= tapMs * (if (maxPointers > 1) 2 else 1)
        when {
            tap && maxPointers == 3 -> { onGesture(Gesture.THREE_FINGER_TAP); lastTapAt = -1 }
            tap && maxPointers == 2 -> { onGesture(Gesture.TWO_FINGER_TAP); lastTapAt = -1 }
            tap && maxPointers == 1 -> {
                if (lastTapAt >= 0 && t - lastTapAt <= doubleTapMs + tapMs && hypot(p.x - lastTapX, p.y - lastTapY) <= slopPx * 3) {
                    onGesture(Gesture.DOUBLE_TAP); lastTapAt = -1
                } else { lastTapAt = t; lastTapX = p.x; lastTapY = p.y }
            }
            else -> lastTapAt = -1
        }
        maxPointers = 0
    }

    fun cancel() { down.clear(); maxPointers = 0; lastTapAt = -1 }

    private fun two() = down.values.take(2)
    private fun span(): Float = two().let { if (it.size < 2) 0f else hypot(it[0].x - it[1].x, it[0].y - it[1].y) }
    private fun startSpan(): Float = two().let { if (it.size < 2) 0f else hypot(it[0].sx - it[1].sx, it[0].sy - it[1].sy) }
    private fun averageDelta(): Pair<Float, Float> {
        val l = two()
        return l.map { it.y - it.sy }.average().toFloat() to l.map { it.x - it.sx }.average().toFloat()
    }
}
