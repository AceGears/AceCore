package com.acecore.app.core.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.acecore.app.core.database.AceDatabase
import com.acecore.app.core.database.CategoryDirEntity
import com.acecore.app.core.database.CategoryEntity
import com.acecore.app.core.database.CategoryWithCount
import com.acecore.app.core.database.GameCategoryRef
import com.acecore.app.core.filesystem.SafTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class CategoryScanReport(val folders: Int, val found: Int, val added: Int, val alreadyInLibrary: Int, val failed: List<String>)

/**
 * Game categories. Everything here is library metadata: creating, moving, hiding or deleting categories never
 * moves, copies or deletes game files, and deleting a category never removes its games from the library.
 */
class CategoryRepository(
    private val context: Context,
    db: AceDatabase,
    private val games: GameRepository,
) {
    private val dao = db.categories()
    private val resolver = context.contentResolver

    val categories: Flow<List<CategoryWithCount>> = dao.observeWithCounts()
    val directories: Flow<List<CategoryDirEntity>> = dao.observeDirs()

    /** gameId -> ids of its categories. */
    val membership: Flow<Map<Long, Set<Long>>> = dao.observeRefs().map { refs ->
        refs.groupBy({ it.gameId }, { it.categoryId }).mapValues { it.value.toSet() }
    }

    suspend fun get(id: Long) = dao.get(id)

    suspend fun create(name: String, icon: String, color: Long): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Enter a name" }
        require(dao.findByName(clean) == null) { "A category called “$clean” already exists" }
        return dao.insert(CategoryEntity(name = clean, icon = icon, color = color, sortOrder = dao.maxSortOrder() + 1))
    }

    suspend fun update(category: CategoryEntity) {
        val clean = category.name.trim()
        require(clean.isNotEmpty()) { "Enter a name" }
        dao.findByName(clean)?.let { require(it.id == category.id) { "A category called “$clean” already exists" } }
        dao.update(category.copy(name = clean))
    }

    /** Deletes the category, its folder links and memberships. The games themselves stay in the library. */
    suspend fun delete(category: CategoryEntity) = withContext(Dispatchers.IO) {
        val dirs = dao.dirsOf(category.id)
        dao.delete(category.id)
        category.coverPath?.let { File(it).delete() }
        dirs.forEach { releaseIfUnused(Uri.parse(it.treeUri)) }
    }

    suspend fun setHidden(id: Long, hidden: Boolean) = dao.setHidden(id, hidden)
    suspend fun reorder(idsInOrder: List<Long>) = dao.reorder(idsInOrder)

    suspend fun setCover(category: CategoryEntity, image: Uri?) = withContext(Dispatchers.IO) {
        category.coverPath?.let { File(it).delete() }
        val path = image?.let {
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val out = File(dir, "category_${category.id}_${System.currentTimeMillis()}.img")
            resolver.openInputStream(it)?.use { input -> out.outputStream().use { o -> input.copyTo(o) } } ?: error("Could not read the selected image")
            out.absolutePath
        }
        dao.update(category.copy(coverPath = path))
    }

    // ------------------------------------------------------------------ membership

    suspend fun addGames(categoryId: Long, gameIds: Collection<Long>) = dao.addRefs(gameIds.map { GameCategoryRef(it, categoryId) })
    suspend fun removeGames(categoryId: Long, gameIds: Collection<Long>) = dao.removeRefs(gameIds.toList(), categoryId)
    suspend fun moveGames(categoryId: Long, gameIds: Collection<Long>) = dao.moveTo(gameIds.toList(), categoryId)

    /** Sets exactly which categories one game belongs to. */
    suspend fun setGameCategories(gameId: Long, categoryIds: Set<Long>) {
        val current = dao.categoryIdsOf(gameId).toSet()
        (current - categoryIds).forEach { dao.removeRefs(listOf(gameId), it) }
        dao.addRefs((categoryIds - current).map { GameCategoryRef(gameId, it) })
    }

    suspend fun categoriesOf(gameId: Long): Set<Long> = dao.categoryIdsOf(gameId).toSet()

    // ------------------------------------------------------------------ directories

    suspend fun dirsOf(categoryId: Long) = dao.dirsOf(categoryId)

    /** Links a folder (picked with the system folder picker) to the category and keeps read access to it. */
    suspend fun addDirectory(categoryId: Long, treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        games.takePermission(treeUri)
        dao.insertDir(CategoryDirEntity(categoryId = categoryId, treeUri = treeUri.toString(), label = labelOf(treeUri))) > 0
    }

    suspend fun changeDirectory(dir: CategoryDirEntity, treeUri: Uri) = withContext(Dispatchers.IO) {
        games.takePermission(treeUri)
        dao.changeDir(dir.id, treeUri.toString(), labelOf(treeUri))
        if (dir.treeUri != treeUri.toString()) releaseIfUnused(Uri.parse(dir.treeUri))
    }

    suspend fun removeDirectory(dir: CategoryDirEntity) = withContext(Dispatchers.IO) {
        dao.deleteDir(dir.id)
        releaseIfUnused(Uri.parse(dir.treeUri))
    }

    private fun labelOf(treeUri: Uri): String {
        val tree = SafTree(resolver, treeUri)
        return SafTree.displayName(resolver, tree.documentUri(tree.rootDocId))
            ?: Uri.decode(treeUri.lastPathSegment ?: treeUri.toString()).substringAfter(':')
    }

    /** Releases the folder grant when neither a game nor another category folder still needs it. */
    private suspend fun releaseIfUnused(treeUri: Uri) {
        val s = treeUri.toString()
        val usedByGame = games.allGames().any { it.treeUri == s }
        val usedByDir = dao.allDirs().any { it.treeUri == s }
        if (!usedByGame && !usedByDir) {
            runCatching { resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }

    /**
     * Scans every folder of the category: games found are added to the library (if new) and to the category.
     * Nothing is copied; games are referenced where they are.
     */
    suspend fun scan(categoryId: Long, progress: (String) -> Unit = {}): CategoryScanReport = withContext(Dispatchers.IO) {
        val dirs = dao.dirsOf(categoryId)
        var found = 0
        var added = 0
        var already = 0
        val failed = mutableListOf<String>()
        for (d in dirs) {
            val uri = Uri.parse(d.treeUri)
            if (!games.hasAccess(d.treeUri)) { failed += "${d.label}: folder access was lost; choose the folder again"; continue }
            val scanned = runCatching { games.scan(uri) { progress("${d.label}: $it") } }
            val outcome = scanned.getOrNull()
            if (outcome == null) { failed += "${d.label}: ${scanned.exceptionOrNull()?.message}"; continue }
            val candidates = when (outcome) {
                is ScanOutcome.Single -> listOf(outcome.candidate)
                is ScanOutcome.Collection -> outcome.candidates
                is ScanOutcome.NothingFound -> emptyList()
            }
            found += candidates.size
            val report = games.importGames(candidates)
            added += report.added.size
            already += report.existingIds.size
            failed += report.failed
            dao.addRefs((report.added + report.existingIds).map { GameCategoryRef(it, categoryId) })
        }
        dao.setLastScan(categoryId, System.currentTimeMillis())
        CategoryScanReport(dirs.size, found, added, already, failed)
    }

    /** Scans categories with automatic scanning enabled (at most every [minIntervalMs]). */
    suspend fun autoScan(minIntervalMs: Long = 6 * 60 * 60 * 1000L): Int {
        var n = 0
        for (c in dao.getAll()) {
            if (!c.autoScan) continue
            if (c.lastScanAt != null && System.currentTimeMillis() - c.lastScanAt < minIntervalMs) continue
            runCatching { scan(c.id) }.onSuccess { n += it.added }
        }
        return n
    }
}
