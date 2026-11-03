package com.acecore.app.runtime

import android.content.Context
import java.io.File

/**
 * Which handheld emulation cores this APK contains. The cores are optional at build time (CI job builds them), so
 * availability is read from the installed APK's native library folder at app start (see AceCoreApp).
 */
object HandheldPresence {
    const val FRONTEND = "libacehandheld.so"
    const val MGBA = "libmgba_libretro.so"
    const val MELONDS = "libmelondsds_libretro.so"
    const val VERSION_ASSET = "handheld/HANDHELD_VERSION.txt"

    private fun lib(context: Context, name: String) = File(context.applicationInfo.nativeLibraryDir, name)

    /** AceArcade's frontend and mGBA: Game Boy, Game Boy Color, Game Boy Advance. */
    fun gbaPresent(context: Context): Boolean = runCatching { lib(context, FRONTEND).isFile && lib(context, MGBA).isFile }.getOrDefault(false)

    /** AceArcade's frontend and melonDS DS: Nintendo DS. */
    fun dsPresent(context: Context): Boolean = runCatching { lib(context, FRONTEND).isFile && lib(context, MELONDS).isFile }.getOrDefault(false)

    fun bundledVersion(context: Context): String? =
        runCatching { context.assets.open(VERSION_ASSET).bufferedReader().readText().trim() }.getOrNull()?.ifEmpty { null }

    /** Sets [RuntimeRegistry]'s handheld availability from this APK. */
    fun apply(context: Context) {
        RuntimeRegistry.handheldGbaAvailable = gbaPresent(context)
        RuntimeRegistry.handheldDsAvailable = dsPresent(context)
        if (RuntimeRegistry.handheldGbaAvailable || RuntimeRegistry.handheldDsAvailable) {
            RuntimeRegistry.handheldVersion = bundledVersion(context)?.lines()?.filter { it.isNotBlank() }?.take(3)?.joinToString(" · ")
        }
    }
}
