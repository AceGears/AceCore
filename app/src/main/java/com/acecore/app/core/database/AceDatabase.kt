package com.acecore.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema history is exported to app/schemas. Any future schema change MUST bump [version] and add a
 * Migration (never fallbackToDestructiveMigration) so users never lose their library on update.
 */
@Database(entities = [GameEntity::class], version = 1, exportSchema = true)
abstract class AceDatabase : RoomDatabase() {
    abstract fun games(): GameDao

    companion object {
        fun create(context: Context): AceDatabase =
            Room.databaseBuilder(context.applicationContext, AceDatabase::class.java, "acecore.db")
                // .addMigrations(MIGRATION_1_2, ...)
                .build()
    }
}
