package com.acecore.app.runtime

import com.acecore.app.runtime.LaunchDiagnosis.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchDiagnosisTest {
    // Shape of a real Ruby 3.1 crash dump (as written to stderr by rb_bug), shortened.
    private val dump = """
        mkxp-z: engine set-up
        <main>: [BUG] Segmentation fault at 0x0000007ff10effe0
        ruby 3.1.3p185 (2022-11-24 revision 1a6b16756e) [aarch64-linux-android]

        -- Control frame information -----------------------------------------------
        c:0042 p:---- s:0311 e:000310 CFUNC  :load
        -- Ruby level backtrace information ----------------------------------------
        Scripts:12:in `load'
        * Loaded features:

            0 enumerator.so
            1 thread.rb
        * Process memory map:

        7ff10f0000-7ff18ef000 rw-p 00000000 00:00 0                              [stack]
    """.trimIndent()

    @Test fun rubyCrashKeepsTheCauseAndDropsTheMemoryMap() {
        val (head, body) = LaunchDiagnosis.rubyCrash(dump)!!
        assertTrue(head, head.contains("[BUG] Segmentation fault"))
        assertTrue(body.contains("Ruby level backtrace"))
        assertTrue(body.contains("Scripts:12"))
        assertFalse("memory map must not be shown", body.contains("[stack]"))
        assertFalse("loaded features list is omitted", body.contains("enumerator.so"))
    }

    @Test fun nativeBacktracePrintedBeforeTheDumpIsKept() {
        val out = "AceArcade native backtrace (signal 11, code 2, fault address 0x7f0):\n  #00 pc 0000001234  libruby.so (rb_foo+0x10)\nAceArcade native backtrace end\n" + dump
        val body = LaunchDiagnosis.rubyCrash(out)!!.second
        assertTrue(body, body.contains("libruby.so (rb_foo+0x10)"))
        assertTrue(body.contains("[BUG]"))
    }

    @Test fun noBugLineMeansNoRubyCrash() {
        assertNull(LaunchDiagnosis.rubyCrash("7ff10f0000-7ff18ef000 rw-p 00000000 00:00 0 [stack]\n"))
    }

    @Test fun helperErrorsRecommendRunningWithoutTheHelper() {
        val r = LaunchDiagnosis.classify("x", "NoMethodError in acecore_preload.rb:10", null, false, helperUsed = true, wasDirect = true)
        assertEquals(Fix.NO_HELPER, r.fix)
    }

    @Test fun fileErrorsInTheUsersFolderRecommendAPrivateCopy() {
        val r = LaunchDiagnosis.classify("x", "PHYSFS error: permission denied", null, false, helperUsed = true, wasDirect = true)
        assertEquals(Fix.PRIVATE_COPY, r.fix)
        // Already a private copy: no point suggesting one.
        assertEquals(Fix.NONE, LaunchDiagnosis.classify("x", "PHYSFS error", null, false, true, wasDirect = false).fix)
    }

    @Test fun engineCrashGetsPlainLanguageNotTheDump() {
        val body = LaunchDiagnosis.rubyCrash(dump)!!.second
        val r = LaunchDiagnosis.classify("Ruby crashed: Segmentation fault", null, body, false, true, true)
        assertEquals(Fix.RETRY, r.fix)
        assertFalse(r.issue.contains("rw-p"))
        assertFalse(r.issue.contains("[BUG]"))
    }

    @Test fun missingWindowsFunctionIsNamedWithTheGameScript() {
        val ruby = "RuntimeError: Failed loading gdi32: dlopen failed (Windows function gdi32!AddFontResource is not available on Android)\n" +
            "<app-data>/files/gamedata/game_23/acecore_compat.rb:1096:in `rescue in new'\n" +
            "119_PScreen_Load.rb:624:in `<module:FontInstaller>'\n-e:in `eval'"
        val r = LaunchDiagnosis.classify("x", ruby, null, false, true, true)
        assertTrue(r.issue, r.issue.contains("AddFontResource (gdi32)") && r.issue.contains("119_PScreen_Load.rb:624"))
        assertEquals(Fix.NONE, r.fix)
    }

    @Test fun theOriginalExceptionBehindAGameHandlerIsShown() {
        val ruby = "RuntimeError: Traceback is too big. Output in traceback.log\n000:Main:14:in `raise_traceback_error'\n" +
            "\nCaused by NoMethodError: undefined method `size' for nil\n120_Language.rb:33:in `pbLang'\n"
        val r = LaunchDiagnosis.classify("x", ruby, null, false, true, true)
        assertTrue(r.issue, r.issue.contains("undefined method `size' for nil") && r.issue.contains("120_Language.rb:33"))
    }

    @Test fun syntaxErrorsShowTheMessage() {
        val r = LaunchDiagnosis.classify("x", "SyntaxError: 126:PScreen_Load:544: Invalid retry\n:26:PScreen_Load", null, false, true, true)
        assertTrue(r.issue, r.issue.contains("126:PScreen_Load:544: Invalid retry"))
    }

    @Test fun gameScriptErrorsAreNotRetriedBlindly() {
        assertEquals(Fix.NONE, LaunchDiagnosis.classify("x", "NameError: uninitialized constant Foo", null, false, true, true).fix)
    }
}
