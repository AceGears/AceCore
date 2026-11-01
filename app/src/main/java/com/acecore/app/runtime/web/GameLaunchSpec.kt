package com.acecore.app.runtime.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the isolated game process needs. Passed as JSON in the launch Intent because the game
 * process must not open the launcher's Room database or DataStore (neither supports multi-process access).
 */
@Serializable
data class GameLaunchSpec(
    val gameId: Long,
    val title: String,
    val engine: String,
    val treeUri: String,
    val rootDocId: String,
    /** Web root relative to the game root ("" = game root). */
    val contentRoot: String,
    /** Entry relative to the game root, e.g. "www/index.html". */
    val entry: String,
    /** Fully resolved layered config (ConfigKey.id -> value). */
    val config: Map<String, String>,
    val webDebugging: Boolean = false,
    val accentArgb: Long = 0xFFFF2A2AL,
    val forceReindex: Boolean = false,
    /** Wall-clock time of the Play tap, for launch-time measurement. */
    val launchRequestedAt: Long = 0,
) {
    /** Unique origin per game so browser storage (and therefore saves) never collide between games. */
    val host: String get() = "game-$gameId.acecore.invalid"
    val origin: String get() = "https://$host"

    /** Entry path relative to [contentRoot]. */
    val entryInRoot: String
        get() = if (contentRoot.isEmpty()) entry else entry.removePrefix("$contentRoot/")

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val EXTRA = "com.acecore.app.extra.LAUNCH_SPEC"
        const val RESULT_ERROR = "com.acecore.app.extra.ERROR"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String): GameLaunchSpec = json.decodeFromString(serializer(), s)
    }
}
