package com.tjcelaya.scribcal.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventType::class,
        OngoingEvent::class,
        CompletedEvent::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ScribCalDatabase : RoomDatabase() {
    
    abstract fun eventTypeDao(): EventTypeDao
    abstract fun ongoingEventDao(): OngoingEventDao
    abstract fun completedEventDao(): CompletedEventDao
    
    companion object {
        @Volatile
        private var INSTANCE: ScribCalDatabase? = null
        
        fun getDatabase(context: Context): ScribCalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScribCalDatabase::class.java,
                    "scribcal_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
