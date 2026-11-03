package com.acecore.app.runtime.handheld

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.acecore.app.core.handheld.HandheldPlatform
import com.acecore.app.runtime.rgss.DisplayPrefs

/**
 * Draws the emulator's picture. Game Boy / Color / Advance: one picture, placed by the Display settings. Nintendo
 * DS: the emulator delivers both screens stacked (256x384); this view arranges them by [layout] (stacked, side by
 * side, one large and one small, one screen, picture-in-picture) with [gap] between them, and turns touches on the
 * bottom screen into the DS touchscreen (libretro pointer coordinates for melonDS DS's stacked layout).
 */
class HandheldScreen(context: Context, private val platform: HandheldPlatform) : View(context) {
    var prefs = DisplayPrefs()
        set(v) { field = v; invalidate() }
    var smooth = false
        set(v) { field = v; paint.isFilterBitmap = v; invalidate() }
    var layout = DsLayout.VERTICAL
        set(v) { field = v; invalidate() }
    /** Space between the DS screens, in DS pixels. */
    var gap = 0
        set(v) { field = v.coerceIn(0, 96); invalidate() }
    /** For single-screen layouts: show the bottom screen instead of the top. */
    var singleBottom = false
        set(v) { field = v; invalidate() }
    /** Touchscreen input: (libretro x, y, pressed). */
    var onTouch: ((Int, Int, Boolean) -> Unit)? = null

    private val paint = Paint().apply { isFilterBitmap = false }
    private val lock = Any()
    private var bitmap: Bitmap? = null

    /** Called on the UI thread with the latest frame (RGBA bytes in [core.frame]). */
    fun showFrame(core: HandheldCore) {
        val f = core.copyFrame() ?: return
        val (_, w, h) = f
        synchronized(lock) {
            var b = bitmap
            if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bitmap = b }
            core.frame.rewind()
            b.copyPixelsFromBuffer(core.frame)
        }
        invalidate()
    }

    /** Copy of the current picture (for screenshots and save-state thumbnails). */
    fun snapshot(): Bitmap? = synchronized(lock) { bitmap?.copy(Bitmap.Config.ARGB_8888, false) }

    private data class Placement(val src: Rect, val dst: RectF, val bottom: Boolean)

    private val top = Rect(0, 0, 256, 192)
    private val bottom = Rect(0, 192, 256, 384)
    private var placements = emptyList<Placement>()

    /** Placements in "virtual" units (DS pixels) and the virtual canvas size. */
    private fun virtualLayout(bw: Int, bh: Int): Pair<List<Placement>, Pair<Int, Int>> {
        if (platform != HandheldPlatform.NDS || bh < 384) {
            return listOf(Placement(Rect(0, 0, bw, bh), RectF(0f, 0f, bw.toFloat(), bh.toFloat()), false)) to (bw to bh)
        }
        val g = gap.toFloat()
        fun p(src: Rect, l: Float, t: Float, w: Float, h: Float, b: Boolean) = Placement(src, RectF(l, t, l + w, t + h), b)
        return when (layout) {
            DsLayout.VERTICAL -> listOf(p(top, 0f, 0f, 256f, 192f, false), p(bottom, 0f, 192f + g, 256f, 192f, true)) to (256 to (384 + gap))
            DsLayout.HORIZONTAL -> listOf(p(top, 0f, 0f, 256f, 192f, false), p(bottom, 256f + g, 0f, 256f, 192f, true)) to ((512 + gap) to 192)
            DsLayout.TOP_FOCUS -> listOf(p(top, 0f, 0f, 256f, 192f, false), p(bottom, 256f + g, 96f, 128f, 96f, true)) to ((384 + gap) to 192)
            DsLayout.BOTTOM_FOCUS -> listOf(p(top, 0f, 96f, 128f, 96f, false), p(bottom, 128f + g, 0f, 256f, 192f, true)) to ((384 + gap) to 192)
            DsLayout.TOP_ONLY, DsLayout.BOTTOM_ONLY -> {
                val showBottom = (layout == DsLayout.BOTTOM_ONLY) != singleBottom
                listOf(p(if (showBottom) bottom else top, 0f, 0f, 256f, 192f, showBottom)) to (256 to 192)
            }
            DsLayout.PICTURE_IN_PICTURE -> {
                val bigBottom = singleBottom
                listOf(p(if (bigBottom) bottom else top, 0f, 0f, 256f, 192f, bigBottom),
                    p(if (bigBottom) top else bottom, 256f - 96f - 4f, 192f - 72f - 4f, 96f, 72f, !bigBottom)) to (256 to 192)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(lock) {
            val b = bitmap ?: return
            val (virt, size) = virtualLayout(b.width, b.height)
            val (vw, vh) = size
            val (w, h) = prefs.surfaceSize(width, height, vw, vh)
            val left = (width - w) / 2f + prefs.offsetX / 100f * kotlin.math.abs(width - w) / 2f
            val topY = (height - h) / 2f + prefs.offsetY / 100f * kotlin.math.abs(height - h) / 2f
            val sx = w.toFloat() / vw; val sy = h.toFloat() / vh
            placements = virt.map { pl ->
                Placement(pl.src, RectF(left + pl.dst.left * sx, topY + pl.dst.top * sy, left + pl.dst.right * sx, topY + pl.dst.bottom * sy), pl.bottom)
            }
            for (pl in placements) canvas.drawBitmap(b, pl.src, pl.dst, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (platform != HandheldPlatform.NDS) return false
        val cb = onTouch ?: return false
        val pl = placements.firstOrNull { it.bottom } ?: return true
        val up = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        val inside = pl.dst.contains(e.x, e.y)
        if (up || !inside) { cb(0, 0, false); return true }
        // DS touch pixel, then melonDS DS's pointer coordinates for its stacked layout (x: -32767..32767 across the
        // width; y: 0..32767 = the bottom screen).
        val tx = ((e.x - pl.dst.left) / pl.dst.width() * 256f).coerceIn(0f, 255f)
        val ty = ((e.y - pl.dst.top) / pl.dst.height() * 192f).coerceIn(0f, 191f)
        cb(((tx - 128f) * 32767f / 128f).toInt(), (ty * 32767f / 192f).toInt(), true)
        return true
    }
}
