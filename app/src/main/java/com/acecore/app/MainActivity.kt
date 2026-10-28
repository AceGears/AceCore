package com.acecore.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.acecore.app.feature.AceCoreRoot

class MainActivity : ComponentActivity() {

    /** Game id requested by a home-screen shortcut; consumed by [AceCoreRoot]. */
    var pendingLaunchId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readLaunchExtra(intent)
        setContent {
            AceCoreRoot(
                pendingLaunchId = pendingLaunchId,
                onPendingLaunchConsumed = { pendingLaunchId = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readLaunchExtra(intent)
    }

    private fun readLaunchExtra(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_LAUNCH_GAME_ID, -1L) ?: -1L
        if (id > 0) pendingLaunchId = id
    }

    companion object {
        const val EXTRA_LAUNCH_GAME_ID = "com.acecore.app.extra.LAUNCH_GAME_ID"
    }
}
