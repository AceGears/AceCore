package com.acecore.app.core.scripts

import com.acecore.app.core.detection.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fixtures were produced by real Ruby 3.3 (Marshal.dump + Zlib::Deflate), see docs/RGSS_RUNTIME.md. */
class ScriptAnalyzerTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    // Marshal.dump([0, 1, 4, 122, 123, 255, 256, -1, -123, -124, -256, -257, 65535, 2**30, -(2**30)])
    private val nums = hex(
            "04085b14690069066909697f69017b6901ff6902000169fa698069ff8469ff0069fefffe6902ffff6c2b070000004069" +
            "fc000000c0"
    )

    // [[12345678, "Main", deflate(code)], [-300, "Résumé", deflate("p 1")], [7, "", deflate("")]]
    private val scripts = hex(
            "04085b085b0869034e61bc4922094d61696e063a064554227a789c730d0e76f50bf174f4098e0f730d0af6f4f753b055" +
            "503232d43354e252f1f58e08e0aa50b05508cfcc3336720cf0d4cb4b2dd7502a2e494dcc8d4f2cc854d251500a06711c" +
            "033ce33df3324b4002209ca9a4c95589a1afb438b5c8d80824ef9e5ae29d5a195c9258920a510ed10300d09c26e85b08" +
            "69fed4fe49220d52c3a973756dc3a9063b00542210789c2b5030040001c400c25b08690c492200063b0054220d789c03" +
            "0000000001"
    )

    @Test fun packedIntegersMatchRuby() {
        val v = RubyMarshal.read(nums) as List<*>
        assertEquals(listOf(0L, 1L, 4L, 122L, 123L, 255L, 256L, -1L, -123L, -124L, -256L, -257L, 65535L, 1073741824L, -1073741824L), v)
    }

    @Test fun decodesScriptsContainer() {
        val list = ScriptAnalyzer.decode(scripts)
        assertEquals(3, list.size)
        assertEquals(12345678L, list[0].id)
        assertEquals("Main", list[0].name)
        assertEquals(-300L, list[1].id)
        assertEquals("Résumé", list[1].name)
        assertEquals("p 1", list[1].code)
        assertEquals("", list[2].code)
    }

    @Test fun findsEssentialsVersionMkxpAndRiskyDlls() {
        val r = ScriptAnalyzer.analyze(ScriptAnalyzer.decode(scripts))
        assertEquals("21.1", r.essentialsVersion)
        assertTrue(r.mkxpAware)
        assertEquals(setOf("steam_api", "user32"), r.windowsDlls.keys)
        assertTrue(r.diagnostics.any { it.severity == Severity.WARNING && it.message.contains("steam_api.dll") })
        // user32 is common and handled; it must not be reported as a warning.
        assertTrue(r.diagnostics.none { it.severity == Severity.WARNING && it.message.contains("user32") })
    }

    @Test fun rejectsNonMarshalData() {
        try { RubyMarshal.read("not marshal".toByteArray()); fail("expected exception") } catch (e: MarshalException) { assertTrue(e.message!!.isNotEmpty()) }
    }
}
