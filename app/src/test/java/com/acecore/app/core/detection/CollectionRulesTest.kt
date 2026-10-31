package com.acecore.app.core.detection

import com.acecore.app.core.detection.CollectionRules.Pick
import com.acecore.app.core.model.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionRulesTest {
    private fun d(engine: Engine, conf: Int, entry: String? = null) = DetectionResult(engine = engine, confidence = conf, entry = entry)

    @Test fun libraryFolderMisdetectedAsRenpyIsScannedAsACollection() {
        // The reported case: "AceArcade/" (7 Pokémon games) matched Ren'Py at 34 % from a partial sample.
        val root = d(Engine.RENPY, 34, "game/x.rpy")
        assertTrue(CollectionRules.mayBeCollection(root, truncated = true))
        assertEquals(Pick.COLLECTION, CollectionRules.pick(root, found = 7))
    }

    @Test fun nestedGameFilesAlsoTriggerTheSubFolderCheck() {
        val root = d(Engine.RPG_MAKER_XP, 90, "Pokémon Z V2.13/Game.ini")
        assertTrue(CollectionRules.mayBeCollection(root, truncated = false))
        assertEquals("a library of several games", Pick.COLLECTION, CollectionRules.pick(root, found = 5))
        assertEquals("one game nested in its own folder stays one game", Pick.ROOT, CollectionRules.pick(root, found = 1))
    }

    @Test fun aConfidentGameAtTheRootIsOneGame() {
        val root = d(Engine.RPG_MAKER_XP, 92, "Game.ini")
        assertFalse(CollectionRules.mayBeCollection(root, truncated = false))
    }

    @Test fun weakOrUnknownRoots() {
        assertEquals(Pick.ONLY_CHILD, CollectionRules.pick(d(Engine.UNKNOWN, 0), found = 1))
        assertEquals(Pick.ONLY_CHILD, CollectionRules.pick(d(Engine.RENPY, 34), found = 1))
        assertEquals(Pick.ROOT, CollectionRules.pick(d(Engine.RENPY, 34), found = 0))
        assertEquals(Pick.NOTHING, CollectionRules.pick(d(Engine.UNKNOWN, 0), found = 0))
    }
}
