package com.acecore.app.core

import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.config.ConfigSource
import com.acecore.app.core.config.LayeredConfig
import com.acecore.app.core.config.PadButton
import com.acecore.app.core.detection.BinaryHeaders
import com.acecore.app.core.filesystem.IndexedFile
import com.acecore.app.core.filesystem.PathIndex
import com.acecore.app.runtime.web.RangeHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter

class PathIndexTest {
    private val index = PathIndex(
        listOf(
            IndexedFile("www/img/faces/Actor1.png", "doc1", 100),
            IndexedFile("www/audio/bgm/Theme 1.ogg", "doc2", 2000),
            IndexedFile("www/index.html", "doc3", 10),
        ),
    )

    @Test fun exactAndCaseInsensitiveLookup() {
        assertEquals("doc1", index.lookup("www/img/faces/Actor1.png")?.docId)
        assertEquals("doc1", index.lookup("WWW/IMG/faces/actor1.PNG")?.docId)
        assertEquals(1L, index.caseFixups)
    }

    @Test fun windowsSeparatorsAndDotSegments() {
        assertEquals("doc1", index.lookup("www\\img\\.\\faces\\Actor1.png")?.docId)
    }

    @Test fun traversalNeverEscapesRoot() {
        assertEquals("", PathIndex.normalize("../../.."))
        assertEquals("etc/passwd", PathIndex.normalize("/../../etc/passwd"))
        assertNull(index.lookup("../../etc/passwd"))
    }

    @Test fun urlDecoding() {
        assertEquals("www/audio/bgm/Theme 1.ogg", PathIndex.fromUrlPath("/www/audio/bgm/Theme%201.ogg"))
        assertEquals("a+b.png", PathIndex.fromUrlPath("/a+b.png"))
        assertNotNull(index.lookup(PathIndex.fromUrlPath("/www/audio/bgm/Theme%201.ogg")))
    }

    @Test fun serialisationRoundTrip() {
        val w = StringWriter()
        index.writeTo(w)
        val back = PathIndex.readFrom(BufferedReader(StringReader(w.toString())))!!
        assertEquals(3, back.size)
        assertEquals("doc2", back.lookup("www/audio/bgm/Theme 1.ogg")?.docId)
        assertEquals(2000L, back.lookup("www/audio/bgm/Theme 1.ogg")?.size)
    }
}

class LayeredConfigTest {
    @Test fun priorityGameThenEngineThenGlobalThenDefault() {
        val k = ConfigKey.GAMEPAD_OPACITY
        assertEquals(Pair("55", ConfigSource.DEFAULT), LayeredConfig(emptyMap(), emptyMap(), emptyMap()).resolve(k).let { it.value to it.source })
        assertEquals(ConfigSource.GLOBAL, LayeredConfig(mapOf(k.id to "40"), emptyMap(), emptyMap()).resolve(k).source)
        val all = LayeredConfig(mapOf(k.id to "40"), mapOf(k.id to "60"), mapOf(k.id to "80"))
        assertEquals("80", all.string(k))
        assertEquals(ConfigSource.GAME, all.resolve(k).source)
        assertEquals("60", all.inherited(k, ConfigSource.GAME).value)
        assertEquals("40", all.inherited(k, ConfigSource.ENGINE).value)
    }

    @Test fun engineSpecificBuiltInDefaults() {
        val flash = LayeredConfig(emptyMap(), emptyMap(), emptyMap(), com.acecore.app.core.model.Engine.FLASH)
        assertEquals("SPACE", ButtonMap.decode(flash.string(ConfigKey.BUTTON_MAP))[PadButton.A])
        val mv = LayeredConfig(emptyMap(), emptyMap(), emptyMap(), com.acecore.app.core.model.Engine.RPG_MAKER_MV)
        assertEquals("Z", ButtonMap.decode(mv.string(ConfigKey.BUTTON_MAP))[PadButton.A])
        // A user's global mapping still beats the engine's built-in default.
        val custom = LayeredConfig(mapOf(ConfigKey.BUTTON_MAP.id to "A=ENTER"), emptyMap(), emptyMap(), com.acecore.app.core.model.Engine.FLASH)
        assertEquals("ENTER", ButtonMap.decode(custom.string(ConfigKey.BUTTON_MAP))[PadButton.A])
    }

    @Test fun intValuesAreClampedToRange() {
        val c = LayeredConfig(mapOf(ConfigKey.GAMEPAD_SCALE.id to "999"), emptyMap(), emptyMap())
        assertEquals(160, c.int(ConfigKey.GAMEPAD_SCALE))
    }
}

class ButtonMapTest {
    @Test fun roundTripAndInvalidValuesIgnored() {
        val m = ButtonMap.DEFAULT + (PadButton.A to "ENTER")
        assertEquals(m, ButtonMap.decode(ButtonMap.encode(m)))
        val decoded = ButtonMap.decode("A=NOT_A_KEY;B=SPACE;BOGUS=Z")
        assertEquals(ButtonMap.DEFAULT[PadButton.A], decoded[PadButton.A])
        assertEquals("SPACE", decoded[PadButton.B])
    }
}

class RangeHeaderTest {
    @Test fun parsesCommonForms() {
        assertEquals(RangeHeader.Range(0, 99), RangeHeader.parse("bytes=0-99", 1000))
        assertEquals(RangeHeader.Range(500, 999), RangeHeader.parse("bytes=500-", 1000))
        assertEquals(RangeHeader.Range(900, 999), RangeHeader.parse("bytes=-100", 1000))
        assertEquals(RangeHeader.Range(0, 999), RangeHeader.parse("bytes=0-5000", 1000))
    }

    @Test fun rejectsInvalid() {
        assertNull(RangeHeader.parse(null, 1000))
        assertNull(RangeHeader.parse("bytes=2000-", 1000))
        assertNull(RangeHeader.parse("items=0-1", 1000))
        assertNull(RangeHeader.parse("bytes=5-1", 1000))
    }
}

class BinaryHeadersTest {
    @Test fun rejectsNonPe() {
        assertNull(BinaryHeaders.parsePe("hello world, this is not an executable at all".toByteArray() + ByteArray(64)))
    }

    @Test fun swfHeaders() {
        val as3 = BinaryHeaders.parseSwf(com.acecore.app.core.detection.DetectionEngineTest.swfBytes(as3 = true, compressed = false))!!
        assertEquals(10, as3.version); assertEquals(true, as3.actionScript3); assertEquals("none", as3.compression)
        val as2z = BinaryHeaders.parseSwf(com.acecore.app.core.detection.DetectionEngineTest.swfBytes(as3 = false, compressed = true))!!
        assertEquals(false, as2z.actionScript3); assertEquals("zlib", as2z.compression)
        val lzma = BinaryHeaders.parseSwf(byteArrayOf('Z'.code.toByte(), 'W'.code.toByte(), 'S'.code.toByte(), 13, 0, 0, 0, 0, 0, 0))!!
        assertNull(lzma.actionScript3)
        assertNull(BinaryHeaders.parseSwf("not a swf".toByteArray()))
    }

    @Test fun rgssadVersion() {
        assertEquals(1, BinaryHeaders.rgssadVersion("RGSSAD".toByteArray() + byteArrayOf(0, 1)))
        assertNull(BinaryHeaders.rgssadVersion("RGSSXX".toByteArray() + byteArrayOf(0, 1)))
    }
}
