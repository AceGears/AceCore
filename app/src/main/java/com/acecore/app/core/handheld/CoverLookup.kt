package com.acecore.app.core.handheld

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Box art for an identified handheld ROM from the libretro thumbnail server (the artwork RetroArch shows), looked up
 * by the exact catalogue (No-Intro) name. Only used when the user asks ("Find cover online") or turned on automatic
 * lookups; the request is a plain image download (no account, nothing about the user is sent).
 */
object CoverLookup {
    private const val BASE = "https://thumbnails.libretro.com/"

    private fun system(p: HandheldPlatform) = when (p) {
        HandheldPlatform.GB -> "Nintendo - Game Boy"
        HandheldPlatform.GBC -> "Nintendo - Game Boy Color"
        HandheldPlatform.GBA -> "Nintendo - Game Boy Advance"
        HandheldPlatform.NDS -> "Nintendo - Nintendo DS"
    }

    /** The thumbnail server's file name: these characters are replaced by "_" in its names. */
    fun fileName(releaseName: String): String = releaseName.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun url(platform: HandheldPlatform, releaseName: String): String =
        BASE + enc(system(platform)) + "/Named_Boxarts/" + enc(fileName(releaseName)) + ".png"

    /** Downloads the box art; null when there is none for this name or the download failed. */
    fun download(platform: HandheldPlatform, releaseName: String): ByteArray? {
        val c = URL(url(platform, releaseName)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "AceArcade")
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }.takeIf { it.size in 100..(8 shl 20) }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }
}
