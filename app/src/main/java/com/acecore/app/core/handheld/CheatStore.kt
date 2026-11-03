package com.acecore.app.core.handheld

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A game's cheat settings, kept in its AceArcade data folder (never in the ROM or its save file):
 *  - which cheats are on, and the value chosen for modifier codes ("000X" parts),
 *  - favourites, the user's own cheats, named presets,
 *  - which probable (not provably matching) cheat sets the user accepted,
 *  - verification results: a cheat confirmed working (or not) by the user on this ROM, identified by its SHA-1.
 *
 * A cheat is identified by a [key]: "db:<setId>:<index>" for database cheats, "custom:<id>" for the user's own.
 */
class CheatStore(private val file: File) {
    data class Custom(val id: String, val name: String, val code: String, val note: String = "")
    data class Verification(val key: String, val romSha1: String, val works: Boolean, val at: Long, val code: String)

    val enabled = linkedSetOf<String>()
    val values = mutableMapOf<String, String>()
    val favorites = linkedSetOf<String>()
    val custom = mutableListOf<Custom>()
    val presets = linkedMapOf<String, List<String>>()
    val acceptedSets = linkedSetOf<Long>()
    val verifications = mutableListOf<Verification>()
    /** Floating shortcut button: shown, size (%), opacity (%), position (fraction of the screen). */
    var shortcutShown = true
    var shortcutSize = 100
    var shortcutOpacity = 70
    var shortcutX = 0.93f
    var shortcutY = 0.42f
    /** The user saw the save-data warning for this game. */
    var warned = false

    init { load() }

    private fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        o.optJSONArray("enabled")?.let { a -> for (i in 0 until a.length()) enabled += a.getString(i) }
        o.optJSONObject("values")?.let { v -> v.keys().forEach { values[it] = v.getString(it) } }
        o.optJSONArray("favorites")?.let { a -> for (i in 0 until a.length()) favorites += a.getString(i) }
        o.optJSONArray("custom")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { c -> custom += Custom(c.getString("id"), c.getString("name"), c.getString("code"), c.optString("note")) }
        }
        o.optJSONObject("presets")?.let { p ->
            p.keys().forEach { k -> val a = p.getJSONArray(k); presets[k] = List(a.length()) { a.getString(it) } }
        }
        o.optJSONArray("acceptedSets")?.let { a -> for (i in 0 until a.length()) acceptedSets += a.getLong(i) }
        o.optJSONArray("verifications")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { v ->
                verifications += Verification(v.getString("key"), v.getString("sha1"), v.getBoolean("works"), v.getLong("at"), v.optString("code"))
            }
        }
        o.optJSONObject("shortcut")?.let { s ->
            shortcutShown = s.optBoolean("shown", true); shortcutSize = s.optInt("size", 100); shortcutOpacity = s.optInt("opacity", 70)
            shortcutX = s.optDouble("x", 0.93).toFloat(); shortcutY = s.optDouble("y", 0.42).toFloat()
        }
        warned = o.optBoolean("warned")
    }

    fun save() {
        val o = JSONObject()
            .put("enabled", JSONArray(enabled.toList()))
            .put("values", JSONObject(values.toMap()))
            .put("favorites", JSONArray(favorites.toList()))
            .put("custom", JSONArray(custom.map { JSONObject().put("id", it.id).put("name", it.name).put("code", it.code).put("note", it.note) }))
            .put("presets", JSONObject().apply { presets.forEach { (k, v) -> put(k, JSONArray(v)) } })
            .put("acceptedSets", JSONArray(acceptedSets.toList()))
            .put("verifications", JSONArray(verifications.map {
                JSONObject().put("key", it.key).put("sha1", it.romSha1).put("works", it.works).put("at", it.at).put("code", it.code)
            }))
            .put("shortcut", JSONObject().put("shown", shortcutShown).put("size", shortcutSize).put("opacity", shortcutOpacity)
                .put("x", shortcutX.toDouble()).put("y", shortcutY.toDouble()))
            .put("warned", warned)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString(1))
        tmp.renameTo(file)
    }

    /** The latest verdict for a cheat on this exact ROM (null = never checked on it). */
    fun verification(key: String, romSha1: String?, code: String): Verification? =
        if (romSha1 == null) null else verifications.lastOrNull { it.key == key && it.romSha1.equals(romSha1, true) && it.code == code }

    fun recordVerification(key: String, romSha1: String, works: Boolean, code: String, now: Long = System.currentTimeMillis()) {
        verifications.removeAll { it.key == key && it.romSha1.equals(romSha1, true) }
        verifications += Verification(key, romSha1.uppercase(), works, now, code)
    }

    fun newCustomId(): String = "c" + System.currentTimeMillis().toString(36) + (custom.size + 1)

    /** Export of the user's own cheats and presets (AceArcade's format; also understood by [importFrom]). */
    fun exportJson(gameTitle: String, platform: HandheldPlatform): String = JSONObject()
        .put("format", "acearcade-cheats").put("version", 1).put("game", gameTitle).put("platform", platform.id)
        .put("cheats", JSONArray(custom.map { JSONObject().put("name", it.name).put("code", it.code).put("note", it.note) }))
        .put("presets", JSONObject().apply { presets.forEach { (k, v) -> put(k, JSONArray(v)) } })
        .toString(1)

    /**
     * Adds cheats from an AceArcade export or a libretro/RetroArch .cht file as the user's own cheats (invalid codes
     * are skipped and counted). Returns (added, skipped).
     */
    fun importFrom(text: String, platform: HandheldPlatform): Pair<Int, Int> {
        val found = mutableListOf<Pair<String, String>>()
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            val o = JSONObject(trimmed)
            o.optJSONArray("cheats")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { found += it.getString("name") to it.getString("code") } }
        } else {
            val kv = mutableMapOf<String, String>()
            Regex("""^\s*(\w+)\s*=\s*"?(.*?)"?\s*$""", RegexOption.MULTILINE).findAll(text).forEach { kv[it.groupValues[1]] = it.groupValues[2] }
            val n = kv["cheats"]?.toIntOrNull() ?: 0
            for (i in 0 until n) {
                val code = kv["cheat${i}_code"].orEmpty()
                if (code.isNotBlank()) found += (kv["cheat${i}_desc"]?.ifBlank { null } ?: "Cheat ${i + 1}") to code
            }
        }
        var added = 0; var skipped = 0
        for ((name, code) in found) {
            if (CheatCodes.check(platform, code) is CheatCodes.Check.Valid && custom.none { it.code == code }) {
                custom += Custom(newCustomId(), name, code); added++
            } else skipped++
        }
        return added to skipped
    }

    companion object {
        fun file(dataDir: File) = File(dataDir, "handheld/cheats.json")
        fun dbKey(setId: Long, index: Int) = "db:$setId:$index"
        fun customKey(id: String) = "custom:$id"
    }
}
