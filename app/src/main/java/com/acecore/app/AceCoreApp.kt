package com.acecore.app

import android.app.Application
import android.content.Context
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.database.AceDatabase
import com.acecore.app.core.datastore.SettingsRepository
import com.acecore.app.feature.launcher.LaunchCoordinator

/**
 * Manual dependency container. Everything is lazy so the isolated ":game" process (which also creates
 * this Application) never opens the database or DataStore — both are single-process only.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val database: AceDatabase by lazy { AceDatabase.create(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val games: GameRepository by lazy { GameRepository(app, database, settings) }
    val launcher: LaunchCoordinator by lazy { LaunchCoordinator(app, games, settings) }
}

class AceCoreApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        com.acecore.app.runtime.RuntimeRegistry.rgssNativeAvailable = com.acecore.app.runtime.rgss.RgssRuntime.isPresent(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as AceCoreApp).container
