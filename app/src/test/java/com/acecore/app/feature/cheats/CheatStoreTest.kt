package com.acecore.app.feature.cheats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheatStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun cheatsAreOnByDefault_andOffStaysOff() {
        val store = CheatStore(context)
        store.reset(901)
        assertTrue("new game: on", store.load(901).enabled)

        // Settings saved before the default existed (no onByDefaultApplied): switched on once.
        store.file(901).apply { parentFile?.mkdirs(); writeText("""{"enabled":false,"autoBackup":true}""") }
        val migrated = store.load(901)
        assertTrue(migrated.enabled)

        // The user turns them off: that choice is kept.
        store.save(901, migrated.copy(enabled = false))
        assertFalse(store.load(901).enabled)
        store.reset(901)
    }
}
