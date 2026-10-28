package com.acecore.app.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Direct file access for native runtimes (mkxp-z) that open game files by path.
 *
 * A folder picked with the Storage Access Framework is only reachable through `content://` URIs, which native
 * code (PhysFS, Ruby's File/Dir) cannot use. With Android's "All files access" (API 30+) or the legacy storage
 * permission (API 26–29), the same folder is readable through its real path, e.g.
 * `primary:Games/Pokemon` → `/storage/emulated/0/Games/Pokemon`. That lets games run in place, with no copy.
 *
 * The permission is optional: without it AceCore falls back to a private copy (see GameInstaller).
 */
object DirectStorage {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    /** Permissions to request on API 26–29 (on 30+ "All files access" is granted in system settings instead). */
    val LEGACY_PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    val usesSettingsScreen: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else LEGACY_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Opens the system page where the user can allow "All files access" for AceCore (API 30+). */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }

    /** Fallback when a device has no per-app page (some vendor ROMs). */
    fun genericSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        else Intent(Settings.ACTION_SETTINGS)

    /**
     * Maps a SAF document (tree + document id) to its real filesystem path, or null when the provider is not
     * backed by shared storage (e.g. a cloud provider). Does not check that the path is readable.
     */
    fun realPath(context: Context, treeUri: Uri, docId: String): File? {
        when (treeUri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> {
                val colon = docId.indexOf(':')
                if (colon < 0) return null
                val volume = docId.substring(0, colon)
                val relative = docId.substring(colon + 1).trim('/')
                val base = volumeRoot(context, volume) ?: return null
                return if (relative.isEmpty()) base else File(base, relative)
            }
            DOWNLOADS_AUTHORITY -> {
                // Only "raw:" ids carry a path; numeric/msf ids do not.
                if (docId.startsWith("raw:")) return File(docId.removePrefix("raw:"))
                return null
            }
            else -> return null
        }
    }

    fun realPath(context: Context, treeUri: String, docId: String): File? = realPath(context, Uri.parse(treeUri), docId)

    private fun volumeRoot(context: Context, volume: String): File? {
        if (volume.equals("primary", ignoreCase = true)) return Environment.getExternalStorageDirectory()
        if (volume.equals("home", ignoreCase = true)) {
            return File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.directory?.let { return it }
        }
        return File("/storage/$volume")
    }

    /** True when [dir] is a directory this process can actually list (permission granted and path valid). */
    fun canList(dir: File): Boolean = runCatching { dir.isDirectory && dir.list() != null }.getOrDefault(false)

    /** Case-insensitive child lookup (Windows games reference "Game.ini" as "game.ini" and vice versa). */
    fun findChild(dir: File, name: String): File? {
        val exact = File(dir, name)
        if (exact.exists()) return exact
        return dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Resolves a '/'-separated relative path case-insensitively, segment by segment. */
    fun resolveRelative(root: File, relative: String): File? {
        var cur = root
        for (seg in relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            cur = findChild(cur, seg) ?: return null
        }
        return cur
    }

    fun documentUri(treeUri: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
}
