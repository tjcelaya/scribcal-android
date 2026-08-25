package com.tjcelaya.calwrite.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EventType::class,
        Event::class,
        PhotoUploadProgress::class,
        AlbumConfig::class,
        FutureEvent::class
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CalWriteDatabase : RoomDatabase() {

    abstract fun eventTypeDao(): EventTypeDao
    abstract fun eventDao(): EventDao
    abstract fun photoUploadProgressDao(): PhotoUploadProgressDao
    abstract fun albumConfigDao(): AlbumConfigDao
    abstract fun futureEventDao(): FutureEventDao

    companion object {
        @Volatile
        private var INSTANCE: CalWriteDatabase? = null

        // === MIGRATIONS ===
        // Version 8 is the baseline (first version with exportSchema=true).
        // For any future schema change:
        //   1. Bump `version` above
        //   2. Add a Migration(old, new) here with ALTER TABLE / CREATE TABLE SQL
        //   3. Register it in .addMigrations() below
        //   4. Build to generate the new schema JSON in app/schemas/
        //   5. Commit the schema JSON
        //
        // Example:
        // val MIGRATION_8_9 = object : Migration(8, 9) {
        //     override fun migrate(db: SupportSQLiteDatabase) {
        //         db.execSQL("ALTER TABLE event_types ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        //     }
        // }

        // Adds the `cadence` column to event_types (Instant/Timed/Both), defaulting to BOTH
        // so existing event types keep showing both record actions.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE event_types ADD COLUMN cadence TEXT NOT NULL DEFAULT 'BOTH'")
            }
        }

        // Adds `calendarEventId` to events so a recorded event can be traced back to its
        // CalendarContract row. Without it the calendar copy can never be updated or deleted,
        // which blocks extend, undo, and adjust. Nullable: existing rows are unknown, and
        // getUnsyncedCompletedEvents() treats NULL as "never synced".
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN calendarEventId INTEGER")
            }
        }

        // internal so migration tests can apply the same set the app ships with.
        internal val ALL_MIGRATIONS = arrayOf<Migration>(
            MIGRATION_8_9,
            MIGRATION_9_10
        )

        fun getDatabase(context: Context): CalWriteDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CalWriteDatabase::class.java,
                    "calwrite_database"
                )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(context: Context): CalWriteDatabase = getDatabase(context)
    }
}
