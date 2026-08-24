package com.tjcelaya.scribcal.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which event `extend` acts on.
 *
 * Extending is only meaningful for timed events: an instant event records a moment, not a span,
 * so stretching one into a duration would rewrite what the user meant. The rule is enforced by
 * the `endTime > startTime` predicate in [EventDao.getLastTimedEvent], and these tests pin it —
 * including the case that motivated it, where a type's *most recent* entry is instant but an
 * older timed one exists.
 */
@RunWith(RobolectricTestRunner::class)
class ExtendTargetQueryTest {

    private lateinit var db: ScribCalDatabase
    private lateinit var events: EventDao
    private var exerciseId = 0L
    private var coffeeId = 0L

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ScribCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        events = db.eventDao()
        exerciseId = db.eventTypeDao().insertEventType(EventType(name = "Exercise"))
        coffeeId = db.eventTypeDao().insertEventType(EventType(name = "Coffee"))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun timed(typeId: Long, start: Long, end: Long) =
        events.insertEvent(Event(eventTypeId = typeId, startTime = start, endTime = end))

    private suspend fun instant(typeId: Long, at: Long) =
        events.insertEvent(Event(eventTypeId = typeId, startTime = at, endTime = at))

    private suspend fun ongoing(typeId: Long, start: Long) =
        events.insertEvent(Event(eventTypeId = typeId, startTime = start, endTime = null))

    @Test
    fun `picks the most recently finished timed event`() = runBlocking {
        timed(exerciseId, 1_000, 2_000)
        val newer = timed(exerciseId, 5_000, 6_000)

        assertEquals(newer, events.getLastTimedEvent(null)?.id)
    }

    @Test
    fun `skips instant events even when one is the most recent entry`() = runBlocking {
        val timedEvent = timed(exerciseId, 1_000, 2_000)
        instant(exerciseId, 9_000)

        // The instant event is newer, but it is not a span, so extend reaches past it.
        assertEquals(timedEvent, events.getLastTimedEvent(exerciseId)?.id)
    }

    @Test
    fun `finds nothing for a type that only has instant events`() = runBlocking {
        instant(coffeeId, 1_000)
        instant(coffeeId, 9_000)

        assertNull(events.getLastTimedEvent(coffeeId))
    }

    @Test
    fun `ignores events that are still running`() = runBlocking {
        val finished = timed(exerciseId, 1_000, 2_000)
        ongoing(exerciseId, 8_000)

        assertEquals(finished, events.getLastTimedEvent(exerciseId)?.id)
    }

    @Test
    fun `scopes to the requested event type`() = runBlocking {
        val exercise = timed(exerciseId, 1_000, 2_000)
        timed(coffeeId, 5_000, 6_000)

        assertEquals(exercise, events.getLastTimedEvent(exerciseId)?.id)
    }

    @Test
    fun `a null event type means any type`() = runBlocking {
        timed(exerciseId, 1_000, 2_000)
        val newest = timed(coffeeId, 5_000, 6_000)

        assertEquals(newest, events.getLastTimedEvent(null)?.id)
    }

    @Test
    fun `unsynced list covers finished events with no calendar id, instant included`() = runBlocking {
        val timedEvent = timed(exerciseId, 1_000, 2_000)
        val instantEvent = instant(coffeeId, 3_000)
        ongoing(exerciseId, 8_000)
        events.setCalendarEventId(timedEvent, 77L)

        // Unlike extend, sync retry cares about instant events too -- it just skips whatever is
        // still running or already has a calendar id.
        assertEquals(listOf(instantEvent), events.getUnsyncedCompletedEvents().map { it.id })
    }
}
