package com.tjcelaya.calwrite.ui.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The timeline gutter is drawn by an ItemDecoration and so cannot be asserted on without a
 * device - but every time -> pixel decision it makes lives in [LedgerTimeline], which is plain
 * Kotlin and is pinned down here.
 */
class LedgerTimelineTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val tolerance = 0.01f

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 8, 20, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Midnight local, i.e. what LedgerGrouping.startOfDayMillis hands the gutter. */
    private val dayStart = at(0, 0)

    /** A 1000px-tall band starting at the top of the list keeps the arithmetic readable. */
    private val band = LedgerTimeline.Band(0f, 1000f)

    @Test
    fun `day progress runs from midnight to midnight and clamps outside the day`() {
        assertEquals(0f, LedgerTimeline.dayProgress(dayStart, dayStart), tolerance)
        assertEquals(0.5f, LedgerTimeline.dayProgress(at(12), dayStart), tolerance)
        assertEquals(0.25f, LedgerTimeline.dayProgress(at(6), dayStart), tolerance)
        // An event that ran past midnight, and one that somehow predates the day.
        assertEquals(1f, LedgerTimeline.dayProgress(at(6) + LedgerTimeline.MILLIS_PER_DAY, dayStart), tolerance)
        assertEquals(0f, LedgerTimeline.dayProgress(dayStart - 1_000L, dayStart), tolerance)
    }

    @Test
    fun `rows are newest first so the axis is inverted`() {
        val evening = LedgerTimeline.verticalFraction(at(23), dayStart)
        val morning = LedgerTimeline.verticalFraction(at(9), dayStart)
        assertTrue("later in the day must sit higher up the band", evening < morning)
        assertEquals(0.5f, LedgerTimeline.verticalFraction(at(12), dayStart), tolerance)
    }

    @Test
    fun `a finished day spans 24h and the day in progress stops at now`() {
        val yesterdayStart = dayStart - LedgerTimeline.MILLIS_PER_DAY
        assertEquals(
            LedgerTimeline.MILLIS_PER_DAY,
            LedgerTimeline.daySpanMillis(yesterdayStart, now = at(9))
        )
        assertEquals(9L * 60L * 60L * 1000L, LedgerTimeline.daySpanMillis(dayStart, now = at(9)))
    }

    @Test
    fun `the day in progress never squeezes below the minimum span`() {
        // Just after midnight, "the day so far" is minutes wide; without a floor two events a
        // minute apart would be flung to opposite ends of the band.
        assertEquals(
            LedgerTimeline.MIN_SPAN_MILLIS,
            LedgerTimeline.daySpanMillis(dayStart, now = dayStart + 60_000L)
        )
    }

    @Test
    fun `now sits at the top of the band on the day in progress`() {
        val now = at(9)
        val span = LedgerTimeline.daySpanMillis(dayStart, now)
        assertEquals(0f, LedgerTimeline.verticalFraction(now, dayStart, span), tolerance)
        assertEquals(1f, LedgerTimeline.verticalFraction(dayStart, dayStart, span), tolerance)
    }

    @Test
    fun `a fully visible day is its own band`() {
        val result = LedgerTimeline.extrapolateBand(
            firstVisibleTop = 100f,
            lastVisibleBottom = 400f,
            firstVisibleIndex = 0,
            lastVisibleIndex = 2,
            rowCount = 3
        )
        assertEquals(100f, result.top, tolerance)
        assertEquals(400f, result.bottom, tolerance)
    }

    @Test
    fun `a partly scrolled day extrapolates its off-screen rows`() {
        // Rows 2..3 of six are on screen and 100px tall each, so two rows sit above and two below.
        val result = LedgerTimeline.extrapolateBand(
            firstVisibleTop = 0f,
            lastVisibleBottom = 200f,
            firstVisibleIndex = 2,
            lastVisibleIndex = 3,
            rowCount = 6
        )
        assertEquals(-200f, result.top, tolerance)
        assertEquals(400f, result.bottom, tolerance)
        assertEquals(600f, result.height, tolerance)
    }

    @Test
    fun `a single row day still produces a band`() {
        val result = LedgerTimeline.extrapolateBand(0f, 72f, 0, 0, 1)
        assertEquals(0f, result.top, tolerance)
        assertEquals(72f, result.bottom, tolerance)
    }

    @Test
    fun `segment length is proportional to the recorded duration`() {
        val short = LedgerTimeline.recordedMark(at(9), at(9, 12), false, dayStart, band)
        val long = LedgerTimeline.recordedMark(at(9), at(9, 42), false, dayStart, band)
        assertEquals(long.length, short.length * 3.5f, 0.5f)
    }

    @Test
    fun `an evening event is drawn above a morning one`() {
        val morning = LedgerTimeline.recordedMark(at(9), at(9, 30), false, dayStart, band)
        val evening = LedgerTimeline.recordedMark(at(23), at(23, 30), false, dayStart, band)
        assertTrue(evening.bottom < morning.top)
        assertEquals(1000f * (1f - 9.5f / 24f), morning.top, 0.5f)
    }

    @Test
    fun `a hairline event is grown to the minimum length without leaving the band`() {
        val atMidnight = LedgerTimeline.recordedMark(
            startTime = dayStart,
            endTime = dayStart + 60_000L,
            isInstant = false,
            dayStartMillis = dayStart,
            band = band,
            minLengthPx = 12f
        )
        assertEquals(12f, atMidnight.length, tolerance)
        assertTrue(atMidnight.bottom <= band.bottom + tolerance)
        assertTrue(atMidnight.top >= band.top - tolerance)
    }

    @Test
    fun `an event running past midnight is clamped to the top of its day`() {
        val overnight = LedgerTimeline.recordedMark(
            startTime = at(23, 30),
            endTime = at(23, 30) + 7L * 60L * 60L * 1000L,
            isInstant = false,
            dayStartMillis = dayStart,
            band = band
        )
        assertEquals(band.top, overnight.top, tolerance)
        assertTrue(overnight.length > 0f)
    }

    @Test
    fun `instant events collapse to a tick rather than a segment`() {
        val moment = at(18)
        val mark = LedgerTimeline.recordedMark(moment, moment, true, dayStart, band)
        assertTrue(mark.isInstant)
        assertEquals(0f, mark.length, tolerance)
        assertEquals(1000f * (1f - 18f / 24f), mark.center, 0.5f)
    }

    @Test
    fun `a zero length timed event is treated as a tick too`() {
        // Defensive: a timed event whose end never got written should not draw an invisible
        // segment that the connector logic then points at.
        val moment = at(18)
        val mark = LedgerTimeline.recordedMark(moment, moment, false, dayStart, band)
        assertTrue(mark.isInstant)
    }

    @Test
    fun `the compacted mark is the middle of the row as laid out`() {
        assertEquals(150f, LedgerTimeline.compactedCenter(100f, 200f), tolerance)
    }

    @Test
    fun `no connector while the row still sits on its own segment`() {
        val mark = LedgerTimeline.Mark(top = 100f, bottom = 200f, isInstant = false)
        assertFalse(LedgerTimeline.needsConnector(mark, rowCenterY = 150f, tolerancePx = 4f))
        // Just outside the segment but inside the slack still reads as one line.
        assertFalse(LedgerTimeline.needsConnector(mark, rowCenterY = 202f, tolerancePx = 4f))
    }

    @Test
    fun `a row pushed off its recorded position gets a connector`() {
        val mark = LedgerTimeline.Mark(top = 100f, bottom = 200f, isInstant = false)
        assertTrue(LedgerTimeline.needsConnector(mark, rowCenterY = 320f, tolerancePx = 4f))
        assertTrue(LedgerTimeline.needsConnector(mark, rowCenterY = 40f, tolerancePx = 4f))
    }
}
