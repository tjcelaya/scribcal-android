package com.tjcelaya.calwrite.ui.ledger

/**
 * One ledger row as the timeline gutter sees it: when it happened, which day band it belongs to,
 * where it sits in that day's list, and what colour to draw it in.
 *
 * [LedgerAdapter] hangs one of these off each bound row so
 * [LedgerTimelineDecoration] - which draws across the whole RecyclerView, not inside a row - can
 * recover the timing of every child it is asked to draw.
 */
data class LedgerTimelineRow(
    val dayStartMillis: Long,
    val indexInDay: Int,
    val rowsInDay: Int,
    val startTime: Long,
    val endTime: Long,
    val isInstant: Boolean,
    val color: Int
)

/**
 * The geometry behind the ledger's timeline gutter, kept free of Android types so every
 * time -> pixel-offset decision is unit-testable (see `LedgerTimelineTest`).
 *
 * Two lanes share the gutter:
 *
 * - The **recorded lane** is true to scale. A day's rows occupy a pixel *band*, and that band is
 *   the day's clock: an event's offset and length come from its real start and end times, so a
 *   42m block draws longer than a 12m one. Rows are newest-first, so the axis is inverted -
 *   fraction 0 is the top of the band (the newest end of the day) and 1 the bottom (midnight).
 * - The **compacted lane** is pure recency order: row N's mark sits at the middle of row N as it
 *   was actually laid out. See [compactedCenter].
 *
 * Where the two agree the recorded mark already covers the row's own position and they render as
 * one line; where they diverge ([needsConnector]) the row is tied to its mark by a connector.
 */
object LedgerTimeline {

    const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    /**
     * The narrowest window a day band will ever represent. Just after midnight "the day so far"
     * is a couple of minutes wide, and without a floor two events a minute apart would be flung
     * to opposite ends of the band.
     */
    const val MIN_SPAN_MILLIS: Long = 15L * 60L * 1000L

    /**
     * How much of a day the band represents.
     *
     * A finished day is the whole 24 hours. The day in progress is only ever drawn up to [now],
     * so the top of the band means "just now" and nothing is spent on hours that have not
     * happened yet.
     */
    fun daySpanMillis(dayStartMillis: Long, now: Long): Long {
        val elapsed = now - dayStartMillis
        if (elapsed >= MILLIS_PER_DAY) return MILLIS_PER_DAY
        return elapsed.coerceAtLeast(MIN_SPAN_MILLIS)
    }

    /** Where [timestamp] falls in its day's span: 0f at the start of the day, 1f at its end. */
    fun dayProgress(
        timestamp: Long,
        dayStartMillis: Long,
        spanMillis: Long = MILLIS_PER_DAY
    ): Float {
        val span = spanMillis.coerceAtLeast(1L)
        val elapsed = timestamp - dayStartMillis
        if (elapsed <= 0L) return 0f
        if (elapsed >= span) return 1f
        return elapsed.toFloat() / span.toFloat()
    }

    /**
     * The same position measured down from the top of the band. The ledger lists rows newest
     * first, so the clock runs backwards: 0f is the newest edge of the day, 1f is midnight.
     */
    fun verticalFraction(
        timestamp: Long,
        dayStartMillis: Long,
        spanMillis: Long = MILLIS_PER_DAY
    ): Float = 1f - dayProgress(timestamp, dayStartMillis, spanMillis)

    /** The vertical pixel range a single day's rows occupy. */
    data class Band(val top: Float, val bottom: Float) {
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
    }

    /**
     * The band for a day, extrapolated from whichever of its rows are currently on screen.
     *
     * Only the visible children can be measured, but scaling to just those would make the axis
     * rescale itself on every scrolled pixel. Assuming the off-screen rows are about as tall as
     * the visible ones keeps the axis still while scrolling.
     */
    fun extrapolateBand(
        firstVisibleTop: Float,
        lastVisibleBottom: Float,
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        rowCount: Int
    ): Band {
        val spanned = (lastVisibleIndex - firstVisibleIndex + 1).coerceAtLeast(1)
        val rowHeight = (lastVisibleBottom - firstVisibleTop) / spanned
        val rowsAbove = firstVisibleIndex.coerceAtLeast(0)
        val rowsBelow = (rowCount - 1 - lastVisibleIndex).coerceAtLeast(0)
        return Band(
            top = firstVisibleTop - rowsAbove * rowHeight,
            bottom = lastVisibleBottom + rowsBelow * rowHeight
        )
    }

    /** An event's mark on the recorded lane. Instant events collapse to a zero-length tick. */
    data class Mark(val top: Float, val bottom: Float, val isInstant: Boolean) {
        val center: Float get() = (top + bottom) / 2f
        val length: Float get() = (bottom - top).coerceAtLeast(0f)
    }

    /**
     * The true-to-scale mark for one event inside [band].
     *
     * [minLengthPx] keeps a two-minute event from vanishing: a segment shorter than that is grown
     * about its centre and then *slid* - never shrunk - back inside the band.
     */
    fun recordedMark(
        startTime: Long,
        endTime: Long,
        isInstant: Boolean,
        dayStartMillis: Long,
        band: Band,
        spanMillis: Long = MILLIS_PER_DAY,
        minLengthPx: Float = 0f
    ): Mark {
        val height = band.height
        val safeEnd = maxOf(startTime, endTime)
        val rawTop = band.top + verticalFraction(safeEnd, dayStartMillis, spanMillis) * height
        val rawBottom = band.top + verticalFraction(startTime, dayStartMillis, spanMillis) * height

        if (isInstant || rawBottom <= rawTop) {
            val tick = rawTop.coerceIn(band.top, band.bottom)
            return Mark(tick, tick, isInstant = true)
        }

        var top = rawTop
        var bottom = rawBottom
        if (bottom - top < minLengthPx) {
            val center = (top + bottom) / 2f
            top = center - minLengthPx / 2f
            bottom = center + minLengthPx / 2f
        }
        if (top < band.top) {
            val shift = band.top - top
            top += shift
            bottom += shift
        }
        if (bottom > band.bottom) {
            val shift = bottom - band.bottom
            top -= shift
            bottom -= shift
        }
        return Mark(
            top = top.coerceAtLeast(band.top),
            bottom = bottom.coerceAtMost(band.bottom),
            isInstant = false
        )
    }

    /**
     * The compacted lane is pure recency order, so a row's mark is simply the middle of the row
     * as laid out - which is what makes it possible to tie a row to its mark by eye.
     */
    fun compactedCenter(rowTop: Float, rowBottom: Float): Float = (rowTop + rowBottom) / 2f

    /**
     * Whether the two lanes have drifted far enough apart to need a connector.
     *
     * While the row's own position still falls inside its recorded mark (give or take
     * [tolerancePx]) the two lanes are describing the same place and are drawn as one line.
     */
    fun needsConnector(mark: Mark, rowCenterY: Float, tolerancePx: Float): Boolean =
        rowCenterY < mark.top - tolerancePx || rowCenterY > mark.bottom + tolerancePx
}
