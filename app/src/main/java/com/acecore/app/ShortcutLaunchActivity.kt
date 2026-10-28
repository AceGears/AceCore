package com.acecore.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Entry point for pinned home-screen shortcuts. Forwards to the library, which validates and launches the game. */
class ShortcutLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getLongExtra(MainActivity.EXTRA_LAUNCH_GAME_ID, -1L) ?: -1L
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_LAUNCH_GAME_ID, id),
        )
        finish()
    }
}
