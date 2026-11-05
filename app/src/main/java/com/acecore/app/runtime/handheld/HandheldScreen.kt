package com.acecore.app.runtime.handheld

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import com.acecore.app.core.handheld.HandheldPlatform
import com.acecore.app.runtime.rgss.DisplayPrefs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * How the emulator's picture is enlarged on the GPU. These consoles draw at their own resolution (the bundled
 * emulators can't render internally at a higher one), so every mode interpolates the original pixels: none of them
 * adds real detail.
 */
enum class HandheldFilter(val id: String, val label: String, val about: String) {
    PIXEL("pixel", "Pixel perfect", "Nearest neighbour: every game pixel a sharp block (uneven sizes unless the scale is a whole number)."),
    SHARP("sharp", "Sharp", "Sharp bilinear: crisp pixels of even size at any scale, with only pixel edges softened."),
    SMOOTH("smooth", "Smooth", "Bilinear filtering: soft, no visible pixels."),
    BICUBIC("bicubic", "Smoother", "Bicubic (Catmull-Rom) filtering: smooth with less blur than bilinear."),
    SCALE2X("scale2x", "Enhanced pixel art", "Scale2x (EPX): rounds diagonal edges of sprites and text, then shows them crisply."),
    ;

    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: SHARP }
}

/**
 * Draws the emulator's picture with OpenGL ES 2 and the selected [filter]. Game Boy / Color / Advance: one picture,
 * placed by the Display settings. Nintendo DS: the emulator delivers both screens stacked (256x384); they are arranged
 * by [layout] with [gap] between them, and touches on the bottom screen become the DS touchscreen.
 */
class HandheldScreen(context: Context, private val platform: HandheldPlatform) : GLSurfaceView(context) {
    var prefs = DisplayPrefs()
        set(v) { field = v; requestRender() }
    var filter = HandheldFilter.SHARP
        set(v) { field = v; requestRender() }
    var layout = DsLayout.VERTICAL
        set(v) { field = v; requestRender() }
    /** Space between the DS screens, in DS pixels. */
    var gap = 0
        set(v) { field = v.coerceIn(0, 96); requestRender() }
    /** Single-screen layouts: show the bottom screen instead of the top (picture-in-picture: swap the screens). */
    var singleBottom = false
        set(v) { field = v; requestRender() }
    /** Touchscreen input: (libretro x, y, pressed). */
    var onTouch: ((Int, Int, Boolean) -> Unit)? = null

    private val lock = Any()
    private var pixels: ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    private var fw = 0
    private var fh = 0
    private var frameSerial = 0L
    @Volatile private var placements = emptyList<Placement>()

    init {
        setEGLContextClientVersion(2)
        setRenderer(Gl())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Called on the UI thread with the latest frame (RGBA bytes in [core.frame]). */
    fun showFrame(core: HandheldCore) {
        val f = core.copyFrame() ?: return
        val (_, w, h) = f
        synchronized(lock) {
            val n = w * h * 4
            if (pixels.capacity() < n) pixels = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
            core.frame.position(0).limit(n)
            pixels.clear(); pixels.put(core.frame); pixels.flip()
            core.frame.clear()
            fw = w; fh = h; frameSerial++
        }
        requestRender()
    }

    /** Copy of the current picture at its original size (screenshots, save-state thumbnails). */
    fun snapshot(): Bitmap? = synchronized(lock) {
        if (fw == 0) return null
        Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888).also { pixels.rewind(); it.copyPixelsFromBuffer(pixels); pixels.rewind() }
    }

    private data class Placement(val src: Rect, val dst: RectF, val bottom: Boolean)

    private val top = Rect(0, 0, 256, 192)
    private val bottom = Rect(0, 192, 256, 384)

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

    /** Where each screen goes in view pixels (same rules as the RPG Maker screen: DisplayPrefs.surfaceSize + offsets). */
    private fun place(viewW: Int, viewH: Int, bw: Int, bh: Int): List<Placement> {
        val (virt, size) = virtualLayout(bw, bh)
        val (vw, vh) = size
        val (w, h) = prefs.surfaceSize(viewW, viewH, vw, vh)
        val left = (viewW - w) / 2f + prefs.offsetX / 100f * kotlin.math.abs(viewW - w) / 2f
        val topY = (viewH - h) / 2f + prefs.offsetY / 100f * kotlin.math.abs(viewH - h) / 2f
        val sx = w.toFloat() / vw; val sy = h.toFloat() / vh
        return virt.map { pl -> Placement(pl.src, RectF(left + pl.dst.left * sx, topY + pl.dst.top * sy, left + pl.dst.right * sx, topY + pl.dst.bottom * sy), pl.bottom) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (platform != HandheldPlatform.NDS) return false
        val cb = onTouch ?: return false
        val pl = placements.firstOrNull { it.bottom } ?: return true
        val up = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        if (up || !pl.dst.contains(e.x, e.y)) { cb(0, 0, false); return true }
        // DS touch pixel, then melonDS DS's pointer coordinates for its stacked layout (x: -32767..32767 across the
        // width; y: 0..32767 = the bottom screen).
        val tx = ((e.x - pl.dst.left) / pl.dst.width() * 256f).coerceIn(0f, 255f)
        val ty = ((e.y - pl.dst.top) / pl.dst.height() * 192f).coerceIn(0f, 191f)
        cb(((tx - 128f) * 32767f / 128f).toInt(), (ty * 32767f / 192f).toInt(), true)
        return true
    }

    // ------------------------------------------------------------------ OpenGL

    private inner class Gl : Renderer {
        private var programs = IntArray(HandheldFilter.entries.size)
        private var tex = 0
        private var texW = 0
        private var texH = 0
        private var uploaded = -1L
        private var viewW = 1
        private var viewH = 1
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            programs = IntArray(HandheldFilter.entries.size) { i -> link(VERTEX, fragment(HandheldFilter.entries[i])) }
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texW = 0; texH = 0; uploaded = -1
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1); viewH = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val w: Int; val h: Int
            synchronized(lock) {
                w = fw; h = fh
                if (w == 0) return
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                if (uploaded != frameSerial) {
                    pixels.rewind()
                    if (w != texW || h != texH) {
                        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                        texW = w; texH = h
                    } else {
                        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                    }
                    pixels.rewind()
                    uploaded = frameSerial
                }
            }
            val f = filter
            // Linear sampling for the modes that compute their own sample positions or blend; nearest otherwise.
            val linear = f == HandheldFilter.SMOOTH || f == HandheldFilter.SHARP || f == HandheldFilter.BICUBIC
            val mode = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, mode)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, mode)
            val prog = programs[f.ordinal]
            GLES20.glUseProgram(prog)
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val aUv = GLES20.glGetAttribLocation(prog, "aUv")
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTexSize"), w.toFloat(), h.toFloat())
            val pls = place(viewW, viewH, w, h)
            placements = pls
            for (pl in pls) {
                // Output pixels per source pixel, for the sharp-bilinear prescale.
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uScale"), pl.dst.width() / pl.src.width(), pl.dst.height() / pl.src.height())
                val x0 = pl.dst.left / viewW * 2f - 1f; val x1 = pl.dst.right / viewW * 2f - 1f
                val y0 = 1f - pl.dst.top / viewH * 2f; val y1 = 1f - pl.dst.bottom / viewH * 2f
                val u0 = pl.src.left.toFloat() / w; val u1 = pl.src.right.toFloat() / w
                val v0 = pl.src.top.toFloat() / h; val v1 = pl.src.bottom.toFloat() / h
                quad.clear()
                quad.put(floatArrayOf(x0, y0, u0, v0, x1, y0, u1, v0, x0, y1, u0, v1, x1, y1, u1, v1)).flip()
                quad.position(0)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(aPos)
                quad.position(2)
                GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(aUv)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "program: " + GLES20.glGetProgramInfoLog(p))
            return p
        }
    }

    companion object {
        private const val TAG = "AceArcadeHandheld"

        private const val VERTEX = """
attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv;
void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
        private const val HEAD = """
precision highp float;
uniform sampler2D uTex; uniform vec2 uTexSize; uniform vec2 uScale; varying vec2 vUv;
"""

        fun fragment(f: HandheldFilter): String = HEAD + when (f) {
            HandheldFilter.PIXEL, HandheldFilter.SMOOTH -> "void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }"
            // Sharp bilinear: nearest-neighbour to the largest whole-number scale, bilinear for the rest.
            HandheldFilter.SHARP -> """
void main() {
  vec2 texel = vUv * uTexSize;
  vec2 prescale = max(floor(uScale), vec2(1.0));
  vec2 region = 0.5 - 0.5 / prescale;
  vec2 d = fract(texel) - 0.5;
  vec2 f = (d - clamp(d, -region, region)) * prescale + 0.5;
  gl_FragColor = vec4(texture2D(uTex, (floor(texel) + f) / uTexSize).rgb, 1.0);
}"""
            // Catmull-Rom bicubic with 9 bilinear taps.
            HandheldFilter.BICUBIC -> """
void main() {
  vec2 samplePos = vUv * uTexSize;
  vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
  vec2 f = samplePos - texPos1;
  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
  vec2 w3 = f * f * (-0.5 + 0.5 * f);
  vec2 w12 = w1 + w2;
  vec2 offset12 = w2 / w12;
  vec2 texPos0 = (texPos1 - 1.0) / uTexSize;
  vec2 texPos3 = (texPos1 + 2.0) / uTexSize;
  vec2 texPos12 = (texPos1 + offset12) / uTexSize;
  vec3 r = vec3(0.0);
  r += texture2D(uTex, vec2(texPos0.x, texPos0.y)).rgb * w0.x * w0.y;
  r += texture2D(uTex, vec2(texPos12.x, texPos0.y)).rgb * w12.x * w0.y;
  r += texture2D(uTex, vec2(texPos3.x, texPos0.y)).rgb * w3.x * w0.y;
  r += texture2D(uTex, vec2(texPos0.x, texPos12.y)).rgb * w0.x * w12.y;
  r += texture2D(uTex, vec2(texPos12.x, texPos12.y)).rgb * w12.x * w12.y;
  r += texture2D(uTex, vec2(texPos3.x, texPos12.y)).rgb * w3.x * w12.y;
  r += texture2D(uTex, vec2(texPos0.x, texPos3.y)).rgb * w0.x * w3.y;
  r += texture2D(uTex, vec2(texPos12.x, texPos3.y)).rgb * w12.x * w3.y;
  r += texture2D(uTex, vec2(texPos3.x, texPos3.y)).rgb * w3.x * w3.y;
  gl_FragColor = vec4(clamp(r, 0.0, 1.0), 1.0);
}"""
            // Scale2x (EPX): each source pixel becomes 2x2, corners taken from matching neighbours.
            HandheldFilter.SCALE2X -> """
bool eq(vec3 a, vec3 b) { return all(lessThan(abs(a - b), vec3(0.004))); }
void main() {
  vec2 p = vUv * uTexSize;
  vec2 c = (floor(p) + 0.5) / uTexSize;
  vec2 dx = vec2(1.0 / uTexSize.x, 0.0); vec2 dy = vec2(0.0, 1.0 / uTexSize.y);
  vec3 E = texture2D(uTex, c).rgb;
  vec3 B = texture2D(uTex, c - dy).rgb; vec3 H = texture2D(uTex, c + dy).rgb;
  vec3 D = texture2D(uTex, c - dx).rgb; vec3 F = texture2D(uTex, c + dx).rgb;
  vec2 q = fract(p);
  vec3 o = E;
  if (!eq(B, H) && !eq(D, F)) {
    if (q.x < 0.5 && q.y < 0.5) o = eq(D, B) ? D : E;
    else if (q.x >= 0.5 && q.y < 0.5) o = eq(B, F) ? F : E;
    else if (q.x < 0.5) o = eq(D, H) ? D : E;
    else o = eq(H, F) ? F : E;
  }
  gl_FragColor = vec4(o, 1.0);
}"""
        }
    }
}
