package com.localai.runtime.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Single Room database holding models, runtime configs, downloads, API logs and paired devices.
 * Version 1; destructive migration is acceptable for the pre-release data model.
 */
@Database(
    entities = [
        ModelEntity::class,
        RuntimeConfigEntity::class,
        DownloadEntity::class,
        ApiLogEntity::class,
        PairedDeviceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LocalAiDatabase : RoomDatabase() {

    abstract fun modelDao(): ModelDao

    abstract fun runtimeConfigDao(): RuntimeConfigDao

    abstract fun downloadDao(): DownloadDao

    abstract fun apiLogDao(): ApiLogDao

    abstract fun pairedDeviceDao(): PairedDeviceDao

    companion object {
        private const val NAME = "localai.db"

        fun build(context: Context): LocalAiDatabase =
            Room.databaseBuilder(context.applicationContext, LocalAiDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
