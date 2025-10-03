package com.tjcelaya.scribcal.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventType::class,
        Event::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ScribCalDatabase : RoomDatabase() {
    
    abstract fun eventTypeDao(): EventTypeDao
    abstract fun eventDao(): EventDao
    
    companion object {
        @Volatile
        private var INSTANCE: ScribCalDatabase? = null
        
        fun getDatabase(context: Context): ScribCalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScribCalDatabase::class.java,
                    "scribcal_database"
                )
                .fallbackToDestructiveMigration() // Allow destructive migrations during development
                .build()
                INSTANCE = instance
                instance
            }
        }
        
        fun getInstance(context: Context): ScribCalDatabase = getDatabase(context)
    }
}
