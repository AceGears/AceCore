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
    /** File the user picked to launch (may be an .exe used only as a clue). */
    val launchFile: String?,
    /** Entry actually opened by the runtime, relative to the game root. */
    val entry: String?,
    /** Web root for the runtime, relative to the game root. */
    val contentRoot: String,
    val engine: String,
    /** Optional manual override set by the user; null = use detected engine. */
    val engineOverride: String? = null,
    val engineVersion: String?,
    val confidence: Int,
    val status: String,
    /** JSON-serialised DetectionResult for the Compatibility Center. */
    val detectionJson: String,
    /** Cover: a SAF document id inside the game folder, or an absolute app-private file path for custom covers. */
    val coverDocId: String? = null,
    val customCoverPath: String? = null,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val category: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val playtimeMs: Long = 0,
    val launchCount: Int = 0,
    val sortOrder: Int = 0,
    /** Per-game config overrides, JSON object of ConfigKey.id -> value. */
    @ColumnInfo(defaultValue = "{}") val overridesJson: String = "{}",
    val lastError: String? = null,
) {
    val effectiveEngine: Engine get() = Engine.fromName(engineOverride ?: engine)
    val compatStatus: CompatStatus get() = runCatching { CompatStatus.valueOf(status) }.getOrDefault(CompatStatus.UNSUPPORTED)
}
