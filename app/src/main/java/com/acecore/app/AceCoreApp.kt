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
    val categories: com.acecore.app.core.data.CategoryRepository by lazy { com.acecore.app.core.data.CategoryRepository(app, database, games) }
    val cheats: com.acecore.app.feature.cheats.CheatStore by lazy { com.acecore.app.feature.cheats.CheatStore(app) }
    val saves: com.acecore.app.feature.saves.SaveBackupManager by lazy { com.acecore.app.feature.saves.SaveBackupManager(app) }
    val launcher: LaunchCoordinator by lazy { LaunchCoordinator(app, games, settings) }
}

class AceCoreApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        recordAppCrashes()
        com.acecore.app.runtime.RuntimeRegistry.rgssNativeAvailable = com.acecore.app.runtime.rgss.RgssRuntime.isPresent(this)
        com.acecore.app.runtime.RuntimeRegistry.easyRpgNativeAvailable = runCatching {
            val loader = classLoader as? dalvik.system.BaseDexClassLoader
            loader != null && loader.findLibrary("easyrpg_android") != null && loader.findLibrary("SDL3") != null
        }.getOrDefault(false)
    }
}

/**
 * Saves a report when AceArcade's own process (library, game details, launch-file browser…) crashes, so it can be shared
 * from Settings › Advanced › Crash reports, then lets Android handle the crash as usual. Game processes install their own.
 */
private fun Application.recordAppCrashes() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            val text = buildString {
                append(com.acecore.app.runtime.CrashReports.header("AceArcade app", "-", "AceArcade itself (not a game)"))
                appendLine("Cause: ${error.javaClass.name}: ${error.message}")
                appendLine("Thread: ${thread.name}")
                appendLine()
                appendLine("== Stack trace ==")
                append(android.util.Log.getStackTraceString(error).lineSequence().take(80).joinToString("\n"))
            }
            com.acecore.app.runtime.CrashReports.save(this, 0, text)
        }
        previous?.uncaughtException(thread, error)
    }
}

val Context.container: AppContainer get() = (applicationContext as AceCoreApp).container
