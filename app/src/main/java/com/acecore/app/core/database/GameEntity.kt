package com.acecore.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.acecore.app.core.model.CompatStatus
import com.acecore.app.core.model.Engine

@Entity(
    tableName = "games",
    indices = [Index(value = ["treeUri", "rootDocId"], unique = true), Index("lastPlayedAt"), Index("favorite")],
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Persisted SAF tree grant the game lives under. */
    val treeUri: String,
    /** Document id of the game's root folder inside [treeUri]. */
    val rootDocId: String,
    /** Human-readable folder name / path for display only. */
    val folderLabel: String,
    /** File the user picked (or AceCore detected) to start the game, relative to the game root. */
    val launchFile: String?,
    /** File the runtime actually opens, relative to the game root (e.g. Game.ini for RGSS, index.html for web). */
    val entry: String?,
    /** Web root for the runtime, relative to the game root. */
    val contentRoot: String,
    val engine: String,
    /** Optional manual override set by the user; null = use detected engine. */
    val engineOverride: String? = null,
    val engineVersion: String?,
    val confidence: Int,
    val status: String,
    /** JSON-serialised DetectionResult. */
    val detectionJson: String,
    /** Cover: a SAF document id inside the game folder, or an absolute app-private file path for custom covers. */
    val coverDocId: String? = null,
    val customCoverPath: String? = null,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    /** Legacy single category (schema v1). Migrated into the categories tables; no longer written. */
    val category: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val playtimeMs: Long = 0,
    /** Number of times a runtime was started (v1 name kept for compatibility). */
    val launchCount: Int = 0,
    val sortOrder: Int = 0,
    /** Per-game config overrides, JSON object of ConfigKey.id -> value. */
    @ColumnInfo(defaultValue = "{}") val overridesJson: String = "{}",
    val lastError: String? = null,

    // ---- schema v2 ----
    /** [LAUNCH_AUTO]: AceCore picks the entry from detection; [LAUNCH_MANUAL]: the user chose [launchFile]. */
    @ColumnInfo(defaultValue = "0") val launchMode: Int = LAUNCH_AUTO,
    val firstPlayedAt: Long? = null,
    /** Sessions in which the game actually ran (reached its first frame / page load). */
    @ColumnInfo(defaultValue = "0") val sessionCount: Int = 0,
    /** Every Play request, including ones blocked before a runtime started. */
    @ColumnInfo(defaultValue = "0") val launchAttempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val successfulLaunches: Int = 0,
    @ColumnInfo(defaultValue = "0") val failedLaunches: Int = 0,
    @ColumnInfo(defaultValue = "0") val longestSessionMs: Long = 0,
    /** Measured time from tapping Play to the game's first frame / page load, for the last successful launch. */
    val lastLaunchLatencyMs: Long? = null,
    /** null = never launched, true = last launch reached the game, false = last launch failed. */
    val lastLaunchOk: Boolean? = null,
    /** Size of the game folder in bytes when last measured (null = not measured). */
    val folderSizeBytes: Long? = null,
) {
    val effectiveEngine: Engine get() = Engine.fromName(engineOverride ?: engine)
    val compatStatus: CompatStatus get() = runCatching { CompatStatus.valueOf(status) }.getOrDefault(CompatStatus.UNSUPPORTED)
    val isManualLaunch: Boolean get() = launchMode == LAUNCH_MANUAL

    companion object {
        const val LAUNCH_AUTO = 0
        const val LAUNCH_MANUAL = 1
    }
}
