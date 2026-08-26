package com.tjcelaya.calwrite.ui.ledger

import com.tjcelaya.calwrite.data.database.Event
import com.tjcelaya.calwrite.data.database.EventType
import com.tjcelaya.calwrite.data.database.EventWithType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Day grouping is the piece of the ledger that has no Android dependencies and the most room to
 * misbehave around midnight and around which rows may be extended, so it is tested directly.
 */
class LedgerGroupingTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        id: Long,
        typeId: Long,
        typeName: String,
        start: Long,
        end: Long
    ) = EventWithType(
        event = Event(id = id, eventTypeId = typeId, startTime = start, endTime = end),
        eventType = EventType(id = typeId, name = typeName)
    )

    @Test
    fun `empty input yields no days`() {
        assertTrue(LedgerGrouping.groupByDay(emptyList(), zone).isEmpty())
    }

    @Test
    fun `days come back newest first with rows newest first inside each day`() {
        val older = event(1, 1, "Exercise", at(2026, 8, 20, 9, 0), at(2026, 8, 20, 9, 30))
        val sameDayNewer = event(2, 2, "Coffee", at(2026, 8, 20, 15, 0), at(2026, 8, 20, 15, 5))
        val newestDay = event(3, 1, "Exercise", at(2026, 8, 22, 7, 0), at(2026, 8, 22, 7, 45))

        val days = LedgerGrouping.groupByDay(listOf(older, newestDay, sameDayNewer), zone)

        assertEquals(2, days.size)
        assertEquals(listOf(3L), days[0].rows.map { it.id })
        assertEquals(listOf(2L, 1L), days[1].rows.map { it.id })
    }

    @Test
    fun `an event running past midnight files under the day it started`() {
        val overnight = event(1, 1, "Sleep", at(2026, 8, 20, 23, 30), at(2026, 8, 21, 6, 30))

        val days = LedgerGrouping.groupByDay(listOf(overnight), zone)

        assertEquals(1, days.size)
        assertEquals(LedgerGrouping.startOfDayMillis(at(2026, 8, 20, 0, 0), zone), days[0].dayStartMillis)
    }

    @Test
    fun `instant events are never extendable`() {
        val instantMoment = at(2026, 8, 22, 12, 0)
        val instant = event(1, 1, "Coffee", instantMoment, instantMoment)

        val row = LedgerGrouping.groupByDay(listOf(instant), zone).single().rows.single()

        assertFalse(row.canExtend)
    }

    @Test
    fun `only the newest timed row of a type is extendable`() {
        // extendLastEvent is scoped to a type and always targets that type's most recent timed
        // event, so offering Extend on an older row would move a different event's end time.
        val olderExercise = event(1, 1, "Exercise", at(2026, 8, 20, 9, 0), at(2026, 8, 20, 9, 30))
        val newerExercise = event(2, 1, "Exercise", at(2026, 8, 22, 9, 0), at(2026, 8, 22, 9, 30))
        val otherType = event(3, 2, "Reading", at(2026, 8, 21, 9, 0), at(2026, 8, 21, 9, 30))

        val rows = LedgerGrouping.groupByDay(listOf(olderExercise, newerExercise, otherType), zone)
            .flatMap { it.rows }
            .associateBy { it.id }

        assertTrue(rows.getValue(2L).canExtend)
        assertFalse(rows.getValue(1L).canExtend)
        assertTrue(rows.getValue(3L).canExtend)
    }

    @Test
    fun `an instant entry does not shadow the type's newest timed entry`() {
        val instantMoment = at(2026, 8, 22, 18, 0)
        val instant = event(1, 1, "Exercise", instantMoment, instantMoment)
        val timed = event(2, 1, "Exercise", at(2026, 8, 22, 9, 0), at(2026, 8, 22, 9, 30))

        val rows = LedgerGrouping.groupByDay(listOf(instant, timed), zone)
            .flatMap { it.rows }
            .associateBy { it.id }

        assertFalse(rows.getValue(1L).canExtend)
        assertTrue(rows.getValue(2L).canExtend)
    }
}
