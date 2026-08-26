package com.tjcelaya.calwrite.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the 9 -> 10 migration, which adds `events.calendarEventId`.
 *
 * Room's MigrationTestHelper needs the schema JSON on the instrumentation classpath, so rather
 * than requiring a device this builds the v9 database directly from the DDL recorded in
 * `app/schemas/.../9.json` (including the identity hash Room checks on open), then lets the real
 * CalWriteDatabase.ALL_MIGRATIONS run against it.
 *
 * If this test starts failing after a schema change, regenerate against the newly exported JSON —
 * do not relax the assertions.
 */
@RunWith(RobolectricTestRunner::class)
class CalWriteDatabaseMigrationTest {

    private companion object {
        const val DB_NAME = "migration-test.db"

        /** identityHash from schemas/.../9.json — Room refuses to open the file without it. */
        const val V9_IDENTITY_HASH = "0fcdb2849d698f4f90e4463591de7003"

        val V9_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `event_types` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `colorId` INTEGER, `shouldBubble` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `cadence` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_types_name` ON `event_types` (`name`)",
            "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventTypeId` INTEGER NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `notes` TEXT NOT NULL, `photoPath` TEXT, FOREIGN KEY(`eventTypeId`) REFERENCES `event_types`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_events_eventTypeId` ON `events` (`eventTypeId`)",
            "CREATE TABLE IF NOT EXISTS `photo_upload_progress` (`eventId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `status` TEXT NOT NULL, `progressPercent` INTEGER NOT NULL, `startTime` INTEGER NOT NULL, `completedTime` INTEGER, `errorMessage` TEXT, `photoUrl` TEXT, PRIMARY KEY(`eventId`))",
            "CREATE TABLE IF NOT EXISTS `album_config` (`id` INTEGER NOT NULL, `googlePhotosAlbumId` TEXT, `googlePhotosAlbumName` TEXT, `createdAt` INTEGER NOT NULL, `lastVerified` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `future_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventTypeId` INTEGER NOT NULL, `targetTime` INTEGER NOT NULL, `notes` TEXT, FOREIGN KEY(`eventTypeId`) REFERENCES `event_types`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_future_events_eventTypeId` ON `future_events` (`eventTypeId`)"
        )
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var database: CalWriteDatabase? = null

    @Before
    fun deleteAnyLeftoverDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration9To10_preservesExistingRowsAndDefaultsCalendarEventIdToNull() {
        createV9DatabaseWith(
            eventTypeName = "Exercise",
            eventStartTime = 1_000L,
            eventEndTime = 5_000L,
            notes = "morning run"
        )

        val migrated = openWithMigrations()

        val event = runBlocking { migrated.eventDao().getEventById(1L) }
        assertNotNull("the v9 event row should survive the migration", event)
        assertEquals(1_000L, event!!.startTime)
        assertEquals(5_000L, event.endTime)
        assertEquals("morning run", event.notes)
        assertNull("pre-existing rows have no known calendar id", event.calendarEventId)
    }

    @Test
    fun migration9To10_leavesPreExistingRowsVisibleAsUnsynced() {
        createV9DatabaseWith(
            eventTypeName = "Exercise",
            eventStartTime = 1_000L,
            eventEndTime = 5_000L,
            notes = ""
        )

        val migrated = openWithMigrations()

        // calendarEventId IS NULL is what marks an event as never-synced, so rows carried over
        // from v9 are picked up by the retry path rather than silently ignored.
        val unsynced = runBlocking { migrated.eventDao().getUnsyncedCompletedEvents() }
        assertEquals(1, unsynced.size)
        assertEquals(1L, unsynced.first().id)
    }

    @Test
    fun migration9To10_acceptsWritesToTheNewColumn() {
        createV9DatabaseWith(
            eventTypeName = "Exercise",
            eventStartTime = 1_000L,
            eventEndTime = 5_000L,
            notes = ""
        )

        val migrated = openWithMigrations()

        runBlocking {
            migrated.eventDao().setCalendarEventId(1L, 42L)
            assertEquals(42L, migrated.eventDao().getEventById(1L)?.calendarEventId)
            assertEquals(
                "an event with a calendar id is no longer unsynced",
                0,
                migrated.eventDao().getUnsyncedCompletedEvents().size
            )
        }
    }

    /** Builds a database file that looks exactly like one written by schema version 9. */
    private fun createV9DatabaseWith(
        eventTypeName: String,
        eventStartTime: Long,
        eventEndTime: Long,
        notes: String
    ) {
        val callback = object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V9_DDL.forEach(db::execSQL)

                // Room verifies this table on open; without it the migration never runs.
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '$V9_IDENTITY_HASH')")

                db.execSQL(
                    "INSERT INTO event_types (id, name, description, colorId, shouldBubble, sortOrder, createdAt, cadence) " +
                        "VALUES (1, ?, NULL, NULL, 0, 0, 0, 'BOTH')",
                    arrayOf(eventTypeName)
                )
                db.execSQL(
                    "INSERT INTO events (id, eventTypeId, startTime, endTime, notes, photoPath) VALUES (1, 1, ?, ?, ?, NULL)",
                    arrayOf<Any>(eventStartTime, eventEndTime, notes)
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(callback)
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun openWithMigrations(): CalWriteDatabase =
        Room.databaseBuilder(context, CalWriteDatabase::class.java, DB_NAME)
            .addMigrations(*CalWriteDatabase.ALL_MIGRATIONS)
            .build()
            .also { database = it }
}
