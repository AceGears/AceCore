package com.acecore.app.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.detection.RomRules
import com.acecore.app.core.detection.ZipListing
import com.acecore.app.core.handheld.HandheldDb
import com.acecore.app.core.handheld.HandheldPlatform
import com.acecore.app.core.handheld.Identification
import com.acecore.app.core.handheld.KnownRelease
import com.acecore.app.core.handheld.RomHashes
import com.acecore.app.core.handheld.RomHeader
import com.acecore.app.core.handheld.RomInfo
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.model.EngineFamily
import com.acecore.app.core.storage.DirectStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream

/** A release from the handheld database, as remembered in [HandheldIdentity]. */
@Serializable
data class IdentityRelease(val id: Long, val name: String, val region: String? = null, val revision: String? = null, val serial: String? = null)

/**
 * What AceArcade knows about a handheld ROM: checksums, header and the database release it was identified as. Stored
 * per game in GameStorage.dataDir(gameId)/handheld.json ([HandheldRoms.identityFile]), written after import (hashing
 * runs in the background) and by "Identify game" on the game page.
 */
@Serializable
data class HandheldIdentity(
    /** HandheldPlatform id of the ROM ("gb", "gbc", "gba", "nds"). */
    val platform: String,
    /** Upper-case hex, as the database stores them. Null until hashing finished (or when it failed). */
    val sha1: String? = null,
    val crc32: String? = null,
    val sha256: String? = null,
    /** ROM size in bytes (of the ROM inside a zip for zipped ROMs). */
    val size: Long = -1,
    // ---- header (RomInfo.parseHeader)
    val headerTitle: String? = null,
    val gameCode: String? = null,
    val maker: String? = null,
    val version: Int? = null,
    val headerRegion: String? = null,
    val headerChecksumOk: Boolean? = null,
    val cartridgeType: Int? = null,
    // ---- identification (HandheldDb.identify or the user's choice)
    val releaseId: Long? = null,
    val releaseName: String? = null,
    val releaseRegion: String? = null,
    val releaseRevision: String? = null,
    val releaseSerial: String? = null,
    /** The checksum matched the catalogued release (this exact dump). */
    val exact: Boolean = false,
    /** The user picked the release by hand ("Identify game"); [exact] is then false. */
    val manual: Boolean = false,
    /** Releases with the same header game code when the checksum is unknown (e.g. the base game of a hack). */
    val sameGameCode: List<IdentityRelease> = emptyList(),
    /** Database version used, and when. */
    val dbVersion: String? = null,
    val identifiedAt: Long = 0,
    /** Size and modification time of the library file (the ROM or its zip) when hashed, to notice a replaced file. */
    val sourceSize: Long = -1,
    val sourceModified: Long = 0,
)

/**
 * Android-side helpers for handheld ROM library entries. A ROM entry's treeUri is either a folder grant (ROM found by a
 * folder scan; rootDocId = the file's document id) or the ROM file's own document URI (ROM picked as a file).
 */
object HandheldRoms {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun isHandheld(game: GameEntity) = game.effectiveEngine.family == EngineFamily.HANDHELD

    /** Document URI of the ROM file (or its zip). Works for both tree grants and single-document grants. */
    fun documentUri(treeUri: String, docId: String): Uri {
        val u = Uri.parse(treeUri)
        return if (runCatching { DocumentsContract.isTreeUri(u) }.getOrDefault(false)) DocumentsContract.buildDocumentUriUsingTree(u, docId) else u
    }

    fun documentUri(game: GameEntity): Uri = documentUri(game.treeUri, game.rootDocId)

    /** The ROM file through its real path, when the provider has one and AceArcade may read it directly. */
    fun directFile(context: Context, game: GameEntity): File? =
        DirectStorage.realPath(context, game.treeUri, game.rootDocId)
            ?.takeIf { DirectStorage.hasAccess(context) && runCatching { it.isFile && it.canRead() }.getOrDefault(false) }

    data class DocInfo(val name: String?, val size: Long, val lastModified: Long)

    fun docInfo(resolver: ContentResolver, uri: Uri): DocInfo? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else DocInfo(c.getString(0), if (c.isNull(1)) -1L else c.getLong(1), if (c.isNull(2)) 0L else c.getLong(2))
        }
    }.getOrNull()

    /** True when the ROM file is still there. */
    fun exists(context: Context, game: GameEntity): Boolean =
        directFile(context, game) != null || docInfo(context.contentResolver, documentUri(game)) != null

    fun readHead(resolver: ContentResolver, uri: Uri, n: Int): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { readUpTo(it, n) }
    }.getOrNull()

    private fun readUpTo(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = input.read(buf, total, n - total)
            if (r <= 0) break
            total += r
        }
        return buf.copyOf(total)
    }

    /**
     * Entry names of a zip document: its central directory read with seeks when the provider allows it (local storage),
     * otherwise by streaming through archives up to 64 MB. Null when it can't be read as a zip.
     */
    fun zipEntries(resolver: ContentResolver, uri: Uri, knownSize: Long): List<String>? {
        val seekable = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val size = if (knownSize > 0) knownSize else ch.size()
                    ZipListing.entryNames(size) { pos, len ->
                        val bb = ByteBuffer.allocate(len)
                        var p = pos
                        while (bb.hasRemaining()) { val r = ch.read(bb, p); if (r <= 0) break; p += r }
                        bb.array().copyOf(bb.position())
                    }
                }
            }
        }.getOrNull()
        if (seekable != null) return seekable
        if (knownSize > 64L shl 20) return null
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { z -> buildList { while (true) add((z.nextEntry ?: break).name) } }
            }
        }.getOrNull()
    }

    /** Opens [entry] inside the zip [input]; the returned stream reads only that entry. */
    fun openZipEntry(input: InputStream, entry: String): InputStream? {
        val z = ZipInputStream(input.buffered())
        while (true) {
            val e = z.nextEntry ?: run { z.close(); return null }
            if (e.name == entry) return z
        }
    }

    fun zipEntryHead(resolver: ContentResolver, uri: Uri, entry: String, n: Int): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input -> openZipEntry(input, entry)?.use { readUpTo(it, n) } }
    }.getOrNull()

    // ------------------------------------------------------------------ identity sidecar

    /** GameStorage.dataDir(gameId)/handheld.json: the [HandheldIdentity] of a handheld game. */
    fun identityFile(context: Context, gameId: Long): File = File(GameStorage.dataDir(context, gameId), "handheld.json")

    fun readIdentity(context: Context, gameId: Long): HandheldIdentity? = runCatching {
        identityFile(context, gameId).takeIf { it.isFile }?.readText()?.let { json.decodeFromString<HandheldIdentity>(it) }
    }.getOrNull()

    fun writeIdentity(context: Context, gameId: Long, identity: HandheldIdentity) {
        val f = identityFile(context, gameId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "handheld.json.tmp")
        tmp.writeText(json.encodeToString(identity))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun KnownRelease.ref() = IdentityRelease(id, name, region, revision, serial)

    /** Opens the ROM's bytes (the ROM inside a zip for zipped ROMs). */
    fun openRom(context: Context, game: GameEntity, zipEntry: String?): InputStream? {
        val raw = directFile(context, game)?.inputStream() ?: context.contentResolver.openInputStream(documentUri(game)) ?: return null
        return if (zipEntry != null) openZipEntry(raw, zipEntry) ?: run { raw.close(); null } else raw
    }

    /**
     * Hashes the ROM, parses its header and looks it up in the handheld database (when the APK has one). Slow for big
     * ROMs (a DS ROM can be 512 MB): call on a background thread.
     */
    fun identify(context: Context, game: GameEntity, platform: HandheldPlatform, zipEntry: String?): HandheldIdentity {
        val previous = readIdentity(context, game.id)
        val info = docInfo(context.contentResolver, documentUri(game))
        val header: RomHeader? = openRom(context, game, zipEntry)?.use { RomInfo.parseHeader(readUpTo(it, RomInfo.HEADER_BYTES), platform) }
        val hashes: RomHashes? = openRom(context, game, zipEntry)?.use { RomInfo.hash(it) }
        val db = runCatching { HandheldDb.open(context) }.getOrNull()
        val id: Identification? = if (hashes != null && db != null) runCatching { db.identify(platform, hashes, header) }.getOrNull() else null
        val keepManual = previous?.manual == true && previous.sha1 != null && previous.sha1 == hashes?.sha1 && id?.exact != true
        val release = if (keepManual) null else id?.release
        return HandheldIdentity(
            platform = platform.id, sha1 = hashes?.sha1, crc32 = hashes?.crc32, sha256 = hashes?.sha256, size = hashes?.size ?: -1,
            headerTitle = header?.title, gameCode = header?.gameCode, maker = header?.maker, version = header?.version,
            headerRegion = header?.region, headerChecksumOk = header?.headerChecksumOk, cartridgeType = header?.cartridgeType,
            releaseId = if (keepManual) previous!!.releaseId else release?.id,
            releaseName = if (keepManual) previous!!.releaseName else release?.name,
            releaseRegion = if (keepManual) previous!!.releaseRegion else release?.region,
            releaseRevision = if (keepManual) previous!!.releaseRevision else release?.revision,
            releaseSerial = if (keepManual) previous!!.releaseSerial else release?.serial,
            exact = !keepManual && id?.exact == true,
            manual = keepManual,
            sameGameCode = id?.sameGameCode.orEmpty().map { it.ref() },
            dbVersion = db?.version, identifiedAt = System.currentTimeMillis(),
            sourceSize = info?.size ?: -1, sourceModified = info?.lastModified ?: 0,
        )
    }

    /** The identity after the user picked [release] by hand. */
    fun manualIdentity(current: HandheldIdentity?, platform: HandheldPlatform, release: KnownRelease, dbVersion: String?): HandheldIdentity =
        (current ?: HandheldIdentity(platform.id)).copy(
            releaseId = release.id, releaseName = release.name, releaseRegion = release.region, releaseRevision = release.revision,
            releaseSerial = release.serial, exact = false, manual = true, dbVersion = dbVersion, identifiedAt = System.currentTimeMillis(),
        )

    /** Platform and zip entry of a game's ROM, from its detection record. */
    fun romOf(repo: GameRepository, game: GameEntity): Pair<HandheldPlatform, String?>? {
        val rom = repo.decodeDetection(game)?.rom
        val platform = game.effectiveEngine.handheldPlatform ?: HandheldPlatform.byId(rom?.platform) ?: return null
        return platform to rom?.zipEntry
    }

    /** Whether [name] is a file AceArcade can add as a ROM entry (ROM extension or .zip). */
    fun isRomOrZip(name: String) = RomRules.isRomName(name) || RomRules.isZipName(name)
}
