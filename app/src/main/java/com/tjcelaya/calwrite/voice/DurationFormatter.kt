package com.tjcelaya.calwrite.voice

import android.content.Context
import com.tjcelaya.calwrite.R
import java.util.concurrent.TimeUnit

/**
 * Short, spoken-friendly durations ("1h 5m", "42m", "30s").
 *
 * Goes through string resources rather than concatenation so the unit order and spacing can
 * differ per locale — Spanish wants "1 h 5 min", not "1h 5m".
 */
object DurationFormatter {

    fun format(context: Context, durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(safeMs)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> context.getString(R.string.duration_hours_minutes, hours, minutes)
            totalMinutes > 0 -> context.getString(R.string.duration_minutes, totalMinutes)
            // Sub-minute: seconds, so a just-started or instantly-stopped event doesn't read "0m".
            else -> context.getString(
                R.string.duration_seconds,
                TimeUnit.MILLISECONDS.toSeconds(safeMs)
            )
        }
    }
}
