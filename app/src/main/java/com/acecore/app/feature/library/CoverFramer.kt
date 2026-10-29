package com.acecore.app.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Lets the player frame a picture they chose as a game cover, so it fits the library's cover shape exactly:
 * Fill (drag / pinch to choose the part that shows) or Whole image (the full picture over a blurred copy of itself).
 * The result is rendered at the cover shape ([aspect] = width / height), so it is never cropped again later.
 */
@Composable
fun CoverFramer(uri: Uri, aspect: Float, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        val bmp = withContext(Dispatchers.IO) { runCatching { CoverImages.decode(ctx, uri, 2048) }.getOrNull() }
        if (bmp == null) error = "Couldn't read that image." else source = bmp
    }
    var whole by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }        // 1 = smallest scale that fills the frame
    var pan by remember { mutableStateOf(Offset.Zero) }      // in frame pixels
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF2080614)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Frame the cover", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                if (whole) "The whole picture is shown; the edges are filled with a blurred copy."
                else "Drag to move, pinch to zoom. What's inside the frame becomes the cover.",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                // Largest frame of the cover shape that fits here.
                val maxW = with(density) { maxWidth.toPx() } * 0.92f
                val maxH = with(density) { maxHeight.toPx() } * 0.96f
                val frameW = min(maxW, maxH * aspect)
                val frameH = frameW / aspect
                val src = source
                Box(
                    Modifier.size(with(density) { frameW.toDp() }, with(density) { frameH.toDp() })
                        .clip(RoundedCornerShape(14.dp)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .pointerInput(whole, src) {
                            if (src == null || whole) return@pointerInput
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                zoom = (zoom * zoomChange).coerceIn(1f, 6f)
                                pan = CoverImages.clampPan(pan + panChange, src.width, src.height, frameW, frameH, zoom)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        error != null -> Text(error!!, color = Color.White)
                        src == null -> CircularProgressIndicator()
                        else -> {
                            val preview = remember(src, whole) { if (whole) CoverImages.render(src, aspect, true, 1f, Offset.Zero, 600) else null }
                            val image = remember(src) { src.asImageBitmap() }
                            ComposeCanvas(Modifier.fillMaxSize()) {
                                if (preview != null) {
                                    drawImage(preview.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                                } else {
                                    val base = max(size.width / src.width, size.height / src.height)
                                    val s = base * zoom
                                    val left = (size.width - src.width * s) / 2 + pan.x
                                    val top = (size.height - src.height * s) / 2 + pan.y
                                    drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()),
                                        dstSize = IntSize((src.width * s).toInt(), (src.height * s).toInt()))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !whole, onClick = { whole = false }, label = { Text("Fill the cover") })
                FilterChip(selected = whole, onClick = { whole = true }, label = { Text("Whole image") })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                Button(enabled = source != null && !saving, onClick = {
                    val src = source ?: return@Button
                    saving = true
                    scope.launch {
                        // The pan was measured in on-screen frame pixels; express it as a share of the frame.
                        val bmp = withContext(Dispatchers.Default) {
                            CoverImages.renderFromFrame(src, aspect, whole, zoom, pan, frameSizeOnScreen = null)
                        }
                        onDone(bmp)
                    }
                }) { Text(if (saving) "Saving…" else "Use as cover") }
            }
        }
    }
}

/** Decoding and rendering of framed covers (pure Android graphics, no Compose). */
object CoverImages {
    /** Size of a saved cover: [WIDTH] pixels wide, height from the cover shape. */
    const val WIDTH = 900

    /** Set by the framer: the on-screen frame size the pan was measured in. */
    @Volatile var lastFrame: Pair<Float, Float>? = null

    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    /** Keeps the image covering the whole frame while panning. */
    fun clampPan(p: Offset, srcW: Int, srcH: Int, frameW: Float, frameH: Float, zoom: Float): Offset {
        val s = max(frameW / srcW, frameH / srcH) * zoom
        val maxX = max(0f, (srcW * s - frameW) / 2)
        val maxY = max(0f, (srcH * s - frameH) / 2)
        lastFrame = frameW to frameH
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }

    fun renderFromFrame(src: Bitmap, aspect: Float, whole: Boolean, zoom: Float, pan: Offset, frameSizeOnScreen: Pair<Float, Float>?): Bitmap {
        val frame = frameSizeOnScreen ?: lastFrame
        val rel = if (frame != null && frame.first > 0) Offset(pan.x / frame.first, pan.y / frame.second) else Offset.Zero
        return render(src, aspect, whole, zoom, rel, WIDTH)
    }

    /**
     * Renders the cover at [width] × width/[aspect].
     * Fill: the picture scaled to cover the frame × [zoom], moved by [relPan] (fractions of the frame size).
     * Whole: the picture fitted inside, over a blurred, darkened copy filling the frame.
     */
    fun render(src: Bitmap, aspect: Float, whole: Boolean, zoom: Float, relPan: Offset, width: Int): Bitmap {
        val w = width
        val h = (width / aspect).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val fill = max(w.toFloat() / src.width, h.toFloat() / src.height)
        if (whole) {
            // Blur by scaling down a lot and back up (smooth, cheap, no RenderScript).
            val tiny = Bitmap.createScaledBitmap(src, max(1, src.width / 24), max(1, src.height / 24), true)
            val bw = src.width * fill; val bh = src.height * fill
            c.drawBitmap(tiny, null, RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), paint)
            c.drawColor(0x66000000)
            val fit = min(w.toFloat() / src.width, h.toFloat() / src.height)
            val fw = src.width * fit; val fh = src.height * fit
            c.drawBitmap(src, null, RectF((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2), paint)
        } else {
            val s = fill * zoom
            val m = Matrix().apply {
                postScale(s, s)
                postTranslate((w - src.width * s) / 2 + relPan.x * w, (h - src.height * s) / 2 + relPan.y * h)
            }
            c.drawBitmap(src, m, paint)
        }
        return out
    }
}
