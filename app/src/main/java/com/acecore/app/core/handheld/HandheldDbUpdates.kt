package com.acecore.app.core.handheld

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Updates of the handheld database without a new APK.
 *
 * An update is a package (.acedb: a ZIP with `manifest.json` and `handheld.db`). The manifest carries
 * `{"db_version": "2026.11.20", "schema_version": 1, "sha256": "<of handheld.db>", "changelog": "..."}`. A package is
 * installed only when: the checksum matches, SQLite's integrity check passes, the schema version is one this app
 * reads, the database's own meta table agrees with the manifest, and it is newer than the database in use. The
 * previously installed update is kept, so [rollback] can return to it (or to the bundled database).
 *
 * Packages come from a file the user picks or, only when the user turns it on and enters an address, from an HTTPS
 * address (AceArcade sends nothing else; the request is a plain download).
 */
object HandheldDbUpdates {
    const val SUPPORTED_SCHEMA = 1

    data class Installed(val version: String, val changelog: String, val installedAt: Long)

    sealed interface Result {
        data class Ok(val version: String, val changelog: String) : Result
        data class Rejected(val reason: String) : Result
    }

    private fun dir(context: Context) = File(HandheldDb.dir(context), "updates")
    private fun currentFile(context: Context) = File(dir(context), "current.db")
    private fun currentInfo(context: Context) = File(dir(context), "current.json")
    private fun previousFile(context: Context) = File(dir(context), "previous.db")
    private fun previousInfo(context: Context) = File(dir(context), "previous.json")

    /** The installed update and its version, if any. */
    fun current(context: Context): Pair<File, String>? {
        val f = currentFile(context)
        val v = runCatching { JSONObject(currentInfo(context).readText()).getString("db_version") }.getOrNull()
        return if (f.isFile && v != null) f to v else null
    }

    fun installedInfo(context: Context): Installed? = runCatching {
        val o = JSONObject(currentInfo(context).readText())
        Installed(o.getString("db_version"), o.optString("changelog"), o.optLong("installed_at"))
    }.getOrNull()

    fun previousInfo(context: Context): Installed? = runCatching {
        val o = JSONObject(previousInfo(context).readText())
        Installed(o.getString("db_version"), o.optString("changelog"), o.optLong("installed_at"))
    }.getOrNull()

    /** Installs a package read from [input]. [inUse] = version of the database currently in use. */
    fun install(context: Context, input: InputStream, inUse: String?): Result {
        val work = File(dir(context), "incoming").apply { deleteRecursively(); mkdirs() }
        try {
            var manifest: JSONObject? = null
            val dbFile = File(work, "handheld.db")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when (e.name) {
                        "manifest.json" -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        "handheld.db" -> dbFile.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val m = manifest ?: return Result.Rejected("The package has no manifest.json.")
            if (!dbFile.isFile) return Result.Rejected("The package has no handheld.db.")
            val version = m.optString("db_version").ifEmpty { return Result.Rejected("The manifest has no db_version.") }
            if (m.optInt("schema_version") != SUPPORTED_SCHEMA)
                return Result.Rejected("This database uses schema ${m.optInt("schema_version")}; this version of AceArcade reads schema $SUPPORTED_SCHEMA. Update AceArcade first.")
            val sha = sha256(dbFile)
            if (!sha.equals(m.optString("sha256"), ignoreCase = true)) return Result.Rejected("The database doesn't match its checksum (damaged or altered download).")
            if (inUse != null && version <= inUse) return Result.Rejected("Version $version isn't newer than the database in use ($inUse).")
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val ok = db.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                if (!ok) return Result.Rejected("The database failed SQLite's integrity check.")
                val metaVersion = db.rawQuery("SELECT value FROM meta WHERE key = 'db_version'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val metaSchema = db.rawQuery("SELECT value FROM meta WHERE key = 'schema_version'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (metaVersion != version || metaSchema != SUPPORTED_SCHEMA.toString())
                    return Result.Rejected("The database's own version ($metaVersion, schema $metaSchema) doesn't match its manifest.")
                for (t in listOf("release", "cheat_set", "cheat_blob", "match", "source"))
                    db.rawQuery("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst() }
            }
            HandheldDb.reset()
            // Keep the update being replaced for rollback.
            if (currentFile(context).isFile) {
                currentFile(context).renameTo(previousFile(context).also { it.delete() })
                currentInfo(context).renameTo(previousInfo(context).also { it.delete() })
            }
            dbFile.renameTo(currentFile(context))
            val changelog = m.optString("changelog")
            currentInfo(context).writeText(JSONObject().put("db_version", version).put("changelog", changelog)
                .put("installed_at", System.currentTimeMillis()).put("sha256", sha).toString())
            return Result.Ok(version, changelog)
        } catch (e: Exception) {
            return Result.Rejected("The package couldn't be read: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            work.deleteRecursively()
        }
    }

    /** Returns to the previous update, or to the bundled database when there is none. */
    fun rollback(context: Context): String {
        HandheldDb.reset()
        return if (previousFile(context).isFile) {
            previousFile(context).renameTo(currentFile(context).also { it.delete() })
            previousInfo(context).renameTo(currentInfo(context).also { it.delete() })
            "Returned to database ${installedInfo(context)?.version}."
        } else {
            currentFile(context).delete(); currentInfo(context).delete()
            "Returned to the database included with the app."
        }
    }

    /**
     * Downloads a package from [address] (HTTPS only) and installs it. [progress] gets bytes read and the total (or
     * -1). Only called when the user asked for it.
     */
    fun download(context: Context, address: String, inUse: String?, progress: (Long, Long) -> Unit): Result {
        val url = runCatching { URL(address) }.getOrNull() ?: return Result.Rejected("That isn't a valid address.")
        if (url.protocol != "https") return Result.Rejected("Updates are only downloaded over HTTPS.")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "AceArcade")
        return try {
            if (conn.responseCode != 200) return Result.Rejected("The server answered ${conn.responseCode}.")
            val total = conn.contentLengthLong
            val tmp = File(dir(context).apply { mkdirs() }, "download.acedb")
            conn.inputStream.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16); var done = 0L
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress(done, total) }
                }
            }
            tmp.inputStream().use { install(context, it, inUse) }.also { tmp.delete() }
        } catch (e: Exception) {
            Result.Rejected("The download failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
