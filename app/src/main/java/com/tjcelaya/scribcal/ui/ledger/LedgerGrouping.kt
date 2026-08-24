package com.tjcelaya.scribcal.ui.ledger

import android.content.Context
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.database.EventWithType
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * One ledger row: the event plus whether the Extend action applies to it.
 *
 * [canExtend] is false for instant events (the repository's extend query filters on
 * `endTime > startTime`) and for older entries of a type, because `extendLastEvent` is scoped to a
 * type and always targets that type's *most recent* timed event - offering the button on an older
 * row would silently move a different event's end time.
 */
data class LedgerRow(
    val eventWithType: EventWithType,
    val canExtend: Boolean
) {
    val id: Long get() = eventWithType.id
}

/** A day's worth of ledger rows, newest day first, newest row first within the day. */
data class LedgerDay(
    val dayStartMillis: Long,
    val rows: List<LedgerRow>
)

object LedgerGrouping {

    /**
     * Group completed events into calendar days, newest first.
     *
     * Days are keyed off [EventWithType.startTime] rather than the end time so an event that runs
     * past midnight files under the day the user actually began it, and so a day header can never
     * appear twice.
     */
    fun groupByDay(
        events: List<EventWithType>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<LedgerDay> {
        if (events.isEmpty()) return emptyList()

        val newestFirst = events.sortedByDescending { it.startTime }

        // The type's newest timed entry is the only row extend can honestly act on.
        val extendableIds = mutableSetOf<Long>()
        val seenTypes = mutableSetOf<Long>()
        for (event in newestFirst) {
            if (!event.isCompleted) continue
            if (seenTypes.add(event.eventType.id)) extendableIds.add(event.id)
        }

        return newestFirst
            .groupBy { startOfDayMillis(it.startTime, zoneId) }
            .map { (dayStart, dayEvents) ->
                LedgerDay(
                    dayStartMillis = dayStart,
                    rows = dayEvents.map { LedgerRow(it, canExtend = it.id in extendableIds) }
                )
            }
            .sortedByDescending { it.dayStartMillis }
    }

    fun startOfDayMillis(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(timestamp)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

    /** "Today" / "Yesterday" / a written-out date, for the section header. */
    fun dayLabel(
        context: Context,
        dayStartMillis: Long,
        now: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val day = Instant.ofEpochMilli(dayStartMillis).atZone(zoneId).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        return when (day) {
            today -> context.getString(R.string.ledger_day_today)
            today.minusDays(1) -> context.getString(R.string.ledger_day_yesterday)
            else -> {
                val pattern = if (day.year == today.year) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy"
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date(dayStartMillis))
            }
        }
    }
}
