package com.acecore.app.runtime

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves in-game screenshots to Pictures/AceArcade (app storage before Android 10). */
object Screenshots {
    /** Writes [bitmap] as PNG; returns where it went. Call off the main thread. */
    fun save(context: Context, title: String, bitmap: Bitmap): Result<String> = runCatching {
        val name = "${title.replace(Regex("[^A-Za-z0-9 _-]"), "")}-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AceArcade")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("no media store")
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "Pictures/AceArcade"
        } else {
            val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dir.absolutePath
        }
    }
}
