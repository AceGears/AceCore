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

    @Test fun gameScriptErrorsAreNotRetriedBlindly() {
        assertEquals(Fix.NONE, LaunchDiagnosis.classify("x", "NameError: uninitialized constant Foo", null, false, true, true).fix)
    }
}
