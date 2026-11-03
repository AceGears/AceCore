package com.acecore.app.core.handheld

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File
import java.util.zip.Inflater

/** A known ROM from the handheld database (No-Intro, hack and homebrew catalogues). */
data class KnownRelease(
    val id: Long, val platform: HandheldPlatform, val name: String, val region: String?, val revision: String?,
    val serial: String?, val kind: String, val sha1: String?, val description: String?,
)

/** How a cheat set relates to the ROM being played. */
enum class MatchKind {
    /** Written for exactly this ROM (its checksum identifies the release the set is named after). */
    EXACT,
    /** Same game, but not provably this ROM: another region/revision, older naming, or a hack of this game. */
    PROBABLE,
}

data class CheatSetRef(
    val id: Long, val platform: HandheldPlatform, val name: String, val region: String?, val revision: String?,
    val device: String?, val count: Int, val match: MatchKind, val source: String, val licence: String,
)

/** One cheat as stored (before the user's settings are applied). */
data class DbCheat(val description: String, val code: String, val group: String?, val master: Boolean, val enabledByDefault: Boolean)

/** Result of identifying a ROM: the exact release (by checksum) when known, and how sure that is. */
data class Identification(
    val release: KnownRelease?,
    /** The checksum matched a catalogued ROM. */
    val exact: Boolean,
    /** Releases with the same game code (header), when the checksum is unknown: e.g. the base game of a ROM hack. */
    val sameGameCode: List<KnownRelease>,
)

/**
 * Read-only access to the handheld database (assets/handheld/handheld.db, built by ci/handheld/build_cheat_db.py).
 * The bundled file is copied into app storage on first use or when the APK carries a newer one; an update downloaded
 * later (see [HandheldDbUpdates]) takes its place when it is newer than the bundled one.
 */
class HandheldDb private constructor(private val db: SQLiteDatabase, val version: String, val file: File) {

    fun close() = db.close()

    fun meta(key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    private fun release(c: android.database.Cursor) = KnownRelease(
        c.getLong(0), HandheldPlatform.byId(c.getString(1)) ?: HandheldPlatform.GBA, c.getString(2), c.getString(3),
        c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8),
    )

    private val releaseCols = "id, platform, name, region, revision, serial, kind, sha1, description"

    /** Identifies a ROM by SHA-1 (then CRC32 + size), falling back to releases sharing its header game code. */
    fun identify(platform: HandheldPlatform, hashes: RomHashes, header: RomHeader?): Identification {
        val platforms = if (platform == HandheldPlatform.GB || platform == HandheldPlatform.GBC) listOf("gb", "gbc") else listOf(platform.id)
        val inList = platforms.joinToString(",") { "'$it'" }
        val bySha = db.rawQuery("SELECT $releaseCols FROM release WHERE sha1 = ? AND platform IN ($inList) ORDER BY kind = 'retail' DESC LIMIT 1",
            arrayOf(hashes.sha1)).use { c -> if (c.moveToFirst()) release(c) else null }
        val exact = bySha ?: db.rawQuery("SELECT $releaseCols FROM release WHERE platform IN ($inList) AND crc32 = ? AND size = ? LIMIT 1",
            arrayOf(hashes.crc32, hashes.size.toString())).use { c -> if (c.moveToFirst()) release(c) else null }
        val code = header?.gameCode
        val same = if (exact == null && code != null) db.rawQuery(
            "SELECT $releaseCols FROM release WHERE platform IN ($inList) AND serial = ? AND kind = 'retail' ORDER BY name LIMIT 20",
            arrayOf(code)).use { c -> buildList { while (c.moveToNext()) add(release(c)) } } else emptyList()
        return Identification(exact, exact != null, same)
    }

    fun searchReleases(platform: HandheldPlatform, text: String, limit: Int = 50): List<KnownRelease> =
        db.rawQuery("SELECT $releaseCols FROM release WHERE platform = ? AND name LIKE ? ORDER BY kind = 'retail' DESC, name LIMIT ?",
            arrayOf(platform.id, "%${text.replace("%", "")}%", limit.toString())).use { c -> buildList { while (c.moveToNext()) add(release(c)) } }

    fun releaseById(id: Long): KnownRelease? =
        db.rawQuery("SELECT $releaseCols FROM release WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) release(c) else null }

    /** Cheat sets for a release: exact ones first. */
    fun cheatSetsFor(releaseId: Long): List<CheatSetRef> = db.rawQuery(
        """SELECT s.id, s.platform, s.name, s.region, s.revision, s.device, s.count, m.kind, src.name, src.licence
           FROM match m JOIN cheat_set s ON s.id = m.set_id JOIN source src ON src.id = s.source_id
           WHERE m.release_id = ? ORDER BY m.kind = 'exact' DESC, s.name""", arrayOf(releaseId.toString())).use { c ->
        buildList { while (c.moveToNext()) add(setRef(c, if (c.getString(7) == "exact") MatchKind.EXACT else MatchKind.PROBABLE)) }
    }

    /** Sets named after a game's title (for ROMs the catalogue doesn't know, e.g. a hack): always probable. */
    fun cheatSetsByTitle(platform: HandheldPlatform, normalizedTitle: String): List<CheatSetRef> = db.rawQuery(
        """SELECT s.id, s.platform, s.name, s.region, s.revision, s.device, s.count, 'probable', src.name, src.licence
           FROM cheat_set s JOIN source src ON src.id = s.source_id WHERE s.platform = ? AND s.title = ? ORDER BY s.name""",
        arrayOf(platform.id, normalizedTitle)).use { c -> buildList { while (c.moveToNext()) add(setRef(c, MatchKind.PROBABLE)) } }

    fun searchCheatSets(platform: HandheldPlatform, text: String, limit: Int = 60): List<CheatSetRef> = db.rawQuery(
        """SELECT s.id, s.platform, s.name, s.region, s.revision, s.device, s.count, 'probable', src.name, src.licence
           FROM cheat_set s JOIN source src ON src.id = s.source_id WHERE s.platform IN (?, ?) AND s.name LIKE ? ORDER BY s.name LIMIT ?""",
        arrayOf(platform.id, if (platform == HandheldPlatform.GB) "gbc" else if (platform == HandheldPlatform.GBC) "gb" else platform.id,
            "%${text.replace("%", "")}%", limit.toString())).use { c -> buildList { while (c.moveToNext()) add(setRef(c, MatchKind.PROBABLE)) } }

    private fun setRef(c: android.database.Cursor, kind: MatchKind) = CheatSetRef(
        c.getLong(0), HandheldPlatform.byId(c.getString(1)) ?: HandheldPlatform.GBA, c.getString(2), c.getString(3), c.getString(4),
        c.getString(5), c.getInt(6), kind, c.getString(8), c.getString(9),
    )

    /** The cheats of a set, in the source's order. */
    fun cheats(setId: Long): List<DbCheat> {
        val blob = db.rawQuery("SELECT b.data FROM cheat_set s JOIN cheat_blob b ON b.id = s.blob_id WHERE s.id = ?", arrayOf(setId.toString()))
            .use { c -> if (c.moveToFirst()) c.getBlob(0) else null } ?: return emptyList()
        val arr = JSONArray(String(inflate(blob), Charsets.UTF_8))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DbCheat(o.getString("d"), o.getString("c"), o.optString("g").ifEmpty { null }, o.optInt("m") == 1, o.optInt("e") == 1)
        }
    }

    companion object {
        const val ASSET = "handheld/handheld.db"
        const val VERSION_ASSET = "handheld/HANDHELD_DB_VERSION.txt"

        @Volatile private var instance: HandheldDb? = null

        fun dir(context: Context) = File(context.filesDir, "handheld/db")

        /** True when this APK carries the database. */
        fun isBundled(context: Context): Boolean = runCatching { context.assets.open(VERSION_ASSET).use { true } }.getOrDefault(false)

        fun bundledVersion(context: Context): String? =
            runCatching { context.assets.open(VERSION_ASSET).bufferedReader().readText().trim() }.getOrNull()

        /** Opens the newest available database (installing the bundled one when needed), or null if there is none. */
        @Synchronized
        fun open(context: Context): HandheldDb? {
            instance?.let { return it }
            val d = dir(context).apply { mkdirs() }
            val bundled = bundledVersion(context)
            val installed = File(d, "bundled.db")
            val installedVersion = File(d, "bundled.version").takeIf { it.isFile }?.readText()?.trim()
            if (bundled != null && (bundled != installedVersion || !installed.isFile)) {
                val tmp = File(d, "bundled.db.tmp")
                context.assets.open(ASSET).use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(installed)
                File(d, "bundled.version").writeText(bundled)
            }
            // A downloaded update wins when it is newer than the bundled database (versions are yyyy.mm.dd[.n]).
            val update = HandheldDbUpdates.current(context)
            val (file, version) = when {
                update != null && bundled != null && update.second > bundled -> update
                installed.isFile -> installed to (bundled ?: installedVersion ?: "?")
                update != null -> update
                else -> return null
            }
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            return HandheldDb(db, version, file).also { instance = it }
        }

        /** Forgets the open database (after an update or a rollback) so the next [open] picks the right file. */
        @Synchronized
        fun reset() { instance?.close(); instance = null }

        internal fun inflate(data: ByteArray): ByteArray {
            val inf = Inflater()
            inf.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(1 shl 15)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            return out.toByteArray()
        }

        /** Title as the database's "title" columns store it (see norm_title in build_cheat_db.py). */
        fun normalizeTitle(name: String): String {
            var t = name.replace(Regex("\\s*\\[[^\\]]*\\]"), "")
            // Nested parentheses (hack names inside a release name) are removed with their contents.
            var prev: String
            do { prev = t; t = t.replace(Regex("\\([^()]*\\)"), "") } while (t != prev)
            return t.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), " ").trim()
        }
    }
}
