package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, FolderEntity::class, AutoSaveDraftEntity::class],
    version = 1,
    exportSchema = false
)
abstract class OfficeDatabase : RoomDatabase() {
    abstract fun officeDao(): OfficeDao

    companion object {
        @Volatile
        private var INSTANCE: OfficeDatabase? = null

        fun getInstance(context: Context): OfficeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfficeDatabase::class.java,
                    "office_pro_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
