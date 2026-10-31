package com.acecore.app.core.detection

import com.acecore.app.core.model.Engine

/**
 * Decides whether a folder the user picked is one game or a folder of games (a library such as "AceArcade/" with
 * "Pokémon Z/", "Pokémon Infinity/", ...). Pure logic, unit-tested.
 */
object CollectionRules {
    /** Below this, the root's own detection is too weak to stand for the whole folder. */
    const val CONFIDENT = 50

    enum class Pick { COLLECTION, ROOT, ONLY_CHILD, NOTHING }

    /**
     * True when the sub-folders must be checked too: nothing (or only a Windows program / a stray web page) was
     * recognised at the root, the root's match is weak, the game files sit in a sub-folder (a library of games
     * also matches that way, picking one of them), or the folder was too large to read completely.
     */
    fun mayBeCollection(root: DetectionResult, truncated: Boolean): Boolean =
        root.engine == Engine.UNKNOWN || root.engine == Engine.NATIVE_WINDOWS ||
            (root.engine == Engine.HTML5 && root.entry == null) ||
            root.confidence < CONFIDENT || truncated ||
            root.entry?.replace('\\', '/')?.contains('/') == true

    /** [found] = number of sub-folders in which a game was recognised. */
    fun pick(root: DetectionResult, found: Int): Pick = when {
        found >= 2 -> Pick.COLLECTION
        root.engine != Engine.UNKNOWN && root.engine != Engine.NATIVE_WINDOWS && root.confidence >= CONFIDENT -> Pick.ROOT
        found == 1 -> Pick.ONLY_CHILD
        root.engine != Engine.UNKNOWN -> Pick.ROOT
        else -> Pick.NOTHING
    }
}
