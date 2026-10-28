package com.acecore.app.core.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.acecore.app.core.database.AceDatabase
import com.acecore.app.core.database.GameEntity
import com.acecore.app.core.datastore.SettingsRepository
import com.acecore.app.core.detection.DetectionEngine
import com.acecore.app.core.detection.DetectionResult
import com.acecore.app.core.detection.LaunchCandidate
import com.acecore.app.core.filesystem.SafTree
import com.acecore.app.core.model.CompatStatus
import com.acecore.app.core.model.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class ScanCandidate(
    val treeUri: Uri,
    val rootDocId: String,
    val folderName: String,
    val detection: DetectionResult,
    /** Path → SAF document id for the cover candidates found during the scan. */
    val coverDocIds: Map<String, String>,
)

sealed interface ScanOutcome {
    data class Single(val candidate: ScanCandidate) : ScanOutcome
    data class Collection(val candidates: List<ScanCandidate>) : ScanOutcome
    data class NothingFound(val reason: String) : ScanOutcome
}

data class ImportReport(val added: List<Long>, val duplicates: List<String>, val failed: List<String>)

@Serializable
private data class LibraryBackup(
    val format: Int = 1,
    val exportedAt: Long,
    val settings: Map<String, String>,
    val games: List<BackupGame>,
)

@Serializable
private data class BackupGame(
    val title: String, val treeUri: String, val rootDocId: String, val folderLabel: String,
    val launchFile: String?, val entry: String?, val contentRoot: String, val engine: String, val engineOverride: String?,
    val engineVersion: String?, val confidence: Int, val status: String, val detectionJson: String,
    val coverDocId: String?, val favorite: Boolean, val hidden: Boolean, val category: String?,
    val addedAt: Long, val lastPlayedAt: Long?, val playtimeMs: Long, val launchCount: Int, val overridesJson: String,
)

class GameRepository(
    private val context: Context,
    private val db: AceDatabase,
    private val settings: SettingsRepository,
    private val detector: DetectionEngine = DetectionEngine(),
) {
    private val dao = db.games()
    private val resolver = context.contentResolver
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val games: Flow<List<GameEntity>> = dao.observeAll()
    fun game(id: Long): Flow<GameEntity?> = dao.observe(id)
    val categories: Flow<List<String>> = dao.observeCategories()
    suspend fun get(id: Long) = dao.get(id)

    fun decodeDetection(game: GameEntity): DetectionResult? =
        runCatching { json.decodeFromString<DetectionResult>(game.detectionJson) }.getOrNull()

    // ------------------------------------------------------------------ permissions

    fun takePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(treeUri, flags) }
            .onFailure { resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun hasAccess(treeUri: String): Boolean =
        resolver.persistedUriPermissions.any { it.uri.toString() == treeUri && it.isReadPermission }

    /** Folders the user has granted, for Settings > Library > Game directories. */
    fun grantedFolders(): List<Uri> = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }

    suspend fun revokeFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------ scanning

    suspend fun scan(treeUri: Uri, progress: (String) -> Unit = {}): ScanOutcome = withContext(Dispatchers.IO) {
        val tree = SafTree(resolver, treeUri)
        val rootId = tree.rootDocId
        val rootName = SafTree.displayName(resolver, tree.documentUri(rootId)) ?: "Game"
        progress("Analysing $rootName…")
        val root = scanFolder(tree, rootId, rootName, depth = 4)
        val rootIsCollectionCandidate = root.detection.engine == Engine.UNKNOWN ||
            root.detection.engine == Engine.NATIVE_WINDOWS ||
            (root.detection.engine == Engine.HTML5 && root.detection.entry == null)

        if (!rootIsCollectionCandidate) return@withContext ScanOutcome.Single(root)

        val dirs = tree.children(rootId, "").filter { it.isDirectory }.take(MAX_COLLECTION)
        val found = mutableListOf<ScanCandidate>()
        dirs.forEachIndexed { i, d ->
            progress("Scanning ${d.name} (${i + 1}/${dirs.size})…")
            val c = runCatching { scanFolder(tree, d.docId, d.name, depth = 3) }.getOrNull() ?: return@forEachIndexed
            if (c.detection.engine != Engine.UNKNOWN) found += c
        }
        when {
            found.isNotEmpty() -> ScanOutcome.Collection(found)
            root.detection.engine != Engine.UNKNOWN -> ScanOutcome.Single(root)
            else -> ScanOutcome.NothingFound("No recognisable game files were found in “$rootName” or its sub-folders.")
        }
    }

    private fun scanFolder(tree: SafTree, docId: String, name: String, depth: Int): ScanCandidate {
        val walk = tree.walk(docId, maxDepth = depth, maxEntries = 6000)
        val idByPath = walk.nodes.associate { it.path to it.docId }
        val snapshot = com.acecore.app.core.detection.GameSnapshot(
            walk.nodes.map { com.acecore.app.core.detection.SnapshotEntry(it.path, it.isDirectory, it.size) },
            reader = { p, max -> idByPath[p]?.let { tree.read(it, max) } },
            truncated = walk.truncated,
        )
        val detection = detector.detect(snapshot, name)
        val covers = detection.coverCandidates.mapNotNull { p -> idByPath[p]?.let { p to it } }.toMap()
        return ScanCandidate(tree.treeUri, docId, name, detection, covers)
    }

    // ------------------------------------------------------------------ import

    suspend fun importGames(candidates: List<ScanCandidate>, chosenLaunch: Map<String, LaunchCandidate?> = emptyMap()): ImportReport =
        withContext(Dispatchers.IO) {
            val added = mutableListOf<Long>()
            val dupes = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (c in candidates) {
                if (dao.findByLocation(c.treeUri.toString(), c.rootDocId) != null) { dupes += c.detection.title ?: c.folderName; continue }
                val launch = chosenLaunch[c.rootDocId] ?: c.detection.launchCandidates.firstOrNull { it.playable }
                val entity = toEntity(c, launch)
                runCatching { added += dao.insert(entity) }.onFailure { failed += "${c.folderName}: ${it.message}" }
            }
            ImportReport(added, dupes, failed)
        }

    private fun toEntity(c: ScanCandidate, launch: LaunchCandidate?): GameEntity {
        val d = c.detection
        val entry = launch?.resolvedEntry ?: d.entry
        return GameEntity(
            title = d.title ?: c.folderName,
            treeUri = c.treeUri.toString(),
            rootDocId = c.rootDocId,
            folderLabel = c.folderName,
            launchFile = launch?.path,
            entry = entry,
            contentRoot = contentRootFor(entry, d),
            engine = d.engine.name,
            engineVersion = d.engineVersion,
            confidence = d.confidence,
            status = d.status.name,
            detectionJson = json.encodeToString(d),
            coverDocId = d.coverCandidates.firstNotNullOfOrNull { c.coverDocIds[it] },
        )
    }

    /** For web games, the web root is the folder containing the chosen HTML entry. */
    private fun contentRootFor(entry: String?, d: DetectionResult): String =
        if (entry != null && entry.endsWith(".html", true)) entry.substringBeforeLast('/', "") else d.contentRoot

    suspend fun rescan(game: GameEntity): GameEntity = withContext(Dispatchers.IO) {
        val tree = SafTree(resolver, Uri.parse(game.treeUri))
        val c = scanFolder(tree, game.rootDocId, game.folderLabel, depth = 4)
        val keepLaunch = game.launchFile?.let { lf -> c.detection.launchCandidates.firstOrNull { it.path.equals(lf, true) } }
        val launch = keepLaunch ?: c.detection.launchCandidates.firstOrNull { it.playable }
        val entry = launch?.resolvedEntry ?: c.detection.entry
        val updated = game.copy(
            launchFile = launch?.path, entry = entry, contentRoot = contentRootFor(entry, c.detection),
            engine = c.detection.engine.name, engineVersion = c.detection.engineVersion, confidence = c.detection.confidence,
            status = c.detection.status.name, detectionJson = json.encodeToString(c.detection),
            coverDocId = game.coverDocId ?: c.detection.coverCandidates.firstNotNullOfOrNull { c.coverDocIds[it] },
            lastError = null,
        ).let { if (it.engineOverride != null) it.copy(status = runtimeStatus(it).name) else it }
        dao.update(updated)
        pathIndexFile(game.id).delete() // invalidate the asset path cache
        updated
    }

    suspend fun setLaunchFile(game: GameEntity, candidate: LaunchCandidate) {
        val d = decodeDetection(game)
        val entry = candidate.resolvedEntry ?: game.entry
        val root = if (entry != null && entry.endsWith(".html", true)) entry.substringBeforeLast('/', "") else (d?.contentRoot ?: game.contentRoot)
        dao.update(game.copy(launchFile = candidate.path, entry = entry, contentRoot = root))
        pathIndexFile(game.id).delete()
    }

    suspend fun update(game: GameEntity) = dao.update(game)
    suspend fun setFavorite(id: Long, fav: Boolean) = dao.setFavorite(id, fav)
    suspend fun setHidden(id: Long, hidden: Boolean) = dao.setHidden(id, hidden)
    suspend fun markLaunched(id: Long) = dao.markLaunched(id, System.currentTimeMillis())
    suspend fun addPlaytime(id: Long, ms: Long) { if (ms > 0) dao.addPlaytime(id, ms) }
    suspend fun setError(id: Long, error: String?) = dao.setError(id, error)

    suspend fun setEngineOverride(game: GameEntity, engine: Engine?) {
        val g = game.copy(engineOverride = engine?.name)
        dao.update(g.copy(status = runtimeStatus(g).name))
    }

    /**
     * Removes the game from the library only. The game's own files are never touched. Cached data that
     * AceCore created (path index, custom cover) is deleted, and the folder grant is released when no
     * other library entry still needs it.
     */
    /**
     * @param deleteAceCoreData also delete AceCore's private working copy and per-game data (which can contain saves
     * made inside AceCore). The user's original game folder is never touched either way.
     */
    suspend fun remove(game: GameEntity, deleteAceCoreData: Boolean = false) = withContext(Dispatchers.IO) {
        dao.delete(game.id)
        pathIndexFile(game.id).delete()
        game.customCoverPath?.let { File(it).delete() }
        if (deleteAceCoreData) com.acecore.app.core.install.GameStorage.deleteAll(context, game.id)
        val stillUsed = dao.getAll().any { it.treeUri == game.treeUri }
        if (!stillUsed) revokeFolder(Uri.parse(game.treeUri))
    }

    // ------------------------------------------------------------------ covers

    /**
     * Recomputes the stored status of every game against the runtimes in THIS build, so games added before a
     * runtime shipped (or with an engine override) become playable without a manual rescan, and vice versa.
     */
    suspend fun refreshRuntimeStatuses(): Int = withContext(Dispatchers.IO) {
        var changed = 0
        for (g in dao.getAll()) {
            val s = runtimeStatus(g)
            if (s != g.compatStatus) { dao.update(g.copy(status = s.name)); changed++ }
        }
        changed
    }

    private fun runtimeStatus(g: GameEntity): CompatStatus {
        val current = g.compatStatus
        // Folder-level findings (packed archives, missing entry) stay until the user rescans.
        if (current == CompatStatus.PACKED) return current
        val engine = g.effectiveEngine
        return when {
            engine.family == com.acecore.app.core.model.EngineFamily.UNSUPPORTED_NATIVE -> CompatStatus.UNSUPPORTED
            !com.acecore.app.runtime.RuntimeRegistry.canLaunch(engine) -> CompatStatus.RUNTIME_NOT_BUILT
            g.entry == null -> CompatStatus.MISSING_FILES
            current == CompatStatus.PLAYABLE -> CompatStatus.PLAYABLE
            else -> CompatStatus.PLAYABLE_UNTESTED
        }
    }

    suspend fun setCustomCover(game: GameEntity, image: Uri) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val out = File(dir, "game_${game.id}_${System.currentTimeMillis()}.img")
        resolver.openInputStream(image)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: error("Could not read the selected image")
        game.customCoverPath?.let { File(it).delete() }
        dao.update(game.copy(customCoverPath = out.absolutePath))
    }

    suspend fun clearCustomCover(game: GameEntity) = withContext(Dispatchers.IO) {
        game.customCoverPath?.let { File(it).delete() }
        dao.update(game.copy(customCoverPath = null))
    }

    /** Model for Coil: custom cover file, or the discovered cover inside the game folder. */
    fun coverModel(game: GameEntity): Any? = when {
        game.customCoverPath != null -> File(game.customCoverPath)
        game.coverDocId != null -> DocumentsContract.buildDocumentUriUsingTree(Uri.parse(game.treeUri), game.coverDocId)
        else -> null
    }

    // ------------------------------------------------------------------ config overrides

    fun overrides(game: GameEntity): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(game.overridesJson) }.getOrDefault(emptyMap())

    suspend fun setOverride(gameId: Long, key: String, value: String?) {
        val g = dao.get(gameId) ?: return
        val m = overrides(g).toMutableMap()
        if (value == null) m.remove(key) else m[key] = value
        dao.setOverrides(gameId, json.encodeToString(m))
    }

    suspend fun clearOverrides(gameId: Long) = dao.setOverrides(gameId, "{}")

    // ------------------------------------------------------------------ caches

    fun pathIndexFile(gameId: Long) = File(context.cacheDir, "pathindex/game_$gameId.idx")

    fun cacheStats(): Pair<Int, Long> {
        val files = File(context.cacheDir, "pathindex").listFiles().orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    fun clearPathCaches() { File(context.cacheDir, "pathindex").deleteRecursively() }

    /** Size of AceCore's private copy + data for a game (0 when it has never been installed). */
    fun installedSize(gameId: Long): Long {
        val dirs = listOf(com.acecore.app.core.install.GameStorage.installDir(context, gameId), com.acecore.app.core.install.GameStorage.dataDir(context, gameId))
        return dirs.filter { it.exists() }.sumOf { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
    }

    data class StorageUsage(val installedGames: Int, val bytes: Long, val orphanGames: Int, val orphanBytes: Long)

    /** Working copies on disk, including "orphans" left by games removed from the library without deleting data. */
    suspend fun storageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        val ids = dao.getAll().mapTo(HashSet()) { it.id }
        val roots = listOf(com.acecore.app.core.install.GameStorage.installRoot(context), com.acecore.app.core.install.GameStorage.dataRoot(context))
        val perGame = HashMap<Long, Long>()
        for (root in roots) root.listFiles().orEmpty().forEach { dir ->
            val id = com.acecore.app.core.install.GameStorage.gameIdOf(dir) ?: return@forEach
            perGame[id] = (perGame[id] ?: 0L) + dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }
        val orphans = perGame.filterKeys { it !in ids }
        StorageUsage(perGame.size - orphans.size, perGame.values.sum() - orphans.values.sum(), orphans.size, orphans.values.sum())
    }

    suspend fun deleteOrphanData(): Int = withContext(Dispatchers.IO) {
        val ids = dao.getAll().mapTo(HashSet()) { it.id }
        var n = 0
        listOf(com.acecore.app.core.install.GameStorage.installRoot(context), com.acecore.app.core.install.GameStorage.dataRoot(context)).forEach { root ->
            root.listFiles().orEmpty().forEach { dir ->
                val id = com.acecore.app.core.install.GameStorage.gameIdOf(dir) ?: return@forEach
                if (id !in ids && dir.deleteRecursively()) n++
            }
        }
        n
    }

    // ------------------------------------------------------------------ backup / restore (metadata only)

    suspend fun exportLibrary(target: Uri) = withContext(Dispatchers.IO) {
        val backup = LibraryBackup(
            exportedAt = System.currentTimeMillis(),
            settings = settings.exportRaw(),
            games = dao.getAll().map {
                BackupGame(it.title, it.treeUri, it.rootDocId, it.folderLabel, it.launchFile, it.entry, it.contentRoot, it.engine,
                    it.engineOverride, it.engineVersion, it.confidence, it.status, it.detectionJson, it.coverDocId, it.favorite,
                    it.hidden, it.category, it.addedAt, it.lastPlayedAt, it.playtimeMs, it.launchCount, it.overridesJson)
            },
        )
        resolver.openOutputStream(target, "wt")?.use { it.write(json.encodeToString(backup).toByteArray()) }
            ?: error("Could not write the backup file")
    }

    /** Returns (restored, needingFolderAccess). Folder grants cannot be transferred and must be re-picked. */
    suspend fun importLibrary(source: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val text = resolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Could not read the backup file")
        val backup = json.decodeFromString<LibraryBackup>(text)
        require(backup.format == 1) { "Unsupported backup format ${backup.format}" }
        settings.importRaw(backup.settings)
        var restored = 0
        var needsAccess = 0
        for (b in backup.games) {
            if (dao.findByLocation(b.treeUri, b.rootDocId) != null) continue
            dao.insert(GameEntity(title = b.title, treeUri = b.treeUri, rootDocId = b.rootDocId, folderLabel = b.folderLabel,
                launchFile = b.launchFile, entry = b.entry, contentRoot = b.contentRoot, engine = b.engine, engineOverride = b.engineOverride,
                engineVersion = b.engineVersion, confidence = b.confidence, status = b.status, detectionJson = b.detectionJson,
                coverDocId = b.coverDocId, favorite = b.favorite, hidden = b.hidden, category = b.category, addedAt = b.addedAt,
                lastPlayedAt = b.lastPlayedAt, playtimeMs = b.playtimeMs, launchCount = b.launchCount, overridesJson = b.overridesJson))
            restored++
            if (!hasAccess(b.treeUri)) needsAccess++
        }
        restored to needsAccess
    }

    companion object {
        const val MAX_COLLECTION = 300
        fun isPlayable(game: GameEntity): Boolean =
            game.compatStatus == CompatStatus.PLAYABLE || game.compatStatus == CompatStatus.PLAYABLE_UNTESTED
    }
}
