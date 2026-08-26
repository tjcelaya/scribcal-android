package com.tjcelaya.calwrite.ui.ledger

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tjcelaya.calwrite.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Short, spoken-friendly duration and time formatting shared by the ledger and its notification. */
object LedgerFormatting {

    fun duration(context: Context, durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val hours = TimeUnit.MILLISECONDS.toHours(safeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(safeMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(safeMs) % 60
        return when {
            hours > 0 -> context.getString(R.string.ledger_duration_hours_minutes, hours.toInt(), minutes.toInt())
            minutes > 0 -> context.getString(R.string.ledger_duration_minutes, minutes.toInt())
            else -> context.getString(R.string.ledger_duration_seconds, seconds.toInt())
        }
    }

    fun timeOfDay(timestamp: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))

    fun date(timestamp: Long): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))

    fun timeRange(context: Context, startTime: Long, endTime: Long): String =
        if (startTime == endTime) {
            timeOfDay(startTime)
        } else {
            context.getString(R.string.ledger_time_range, timeOfDay(startTime), timeOfDay(endTime))
        }
}

/**
 * Rows for a single day. One instance per day header sits inside the fragment's ConcatAdapter,
 * paired with a [com.tjcelaya.calwrite.ui.tracking.SectionHeaderAdapter].
 *
 * [dayStartMillis] is the day this adapter's rows were grouped into. Rows carry it (with their
 * position in the day) out to [LedgerTimelineDecoration], which draws the timeline gutter across
 * the whole list and otherwise has no way to know which day a given child belongs to.
 */
class LedgerAdapter(
    private val dayStartMillis: Long,
    private val onExtend: (LedgerRow) -> Unit,
    private val onAdjust: (LedgerRow) -> Unit
) : ListAdapter<LedgerRow, LedgerAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ledger_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position, itemCount)
    }

    fun rowAt(position: Int): LedgerRow? = if (position in 0 until itemCount) getItem(position) else null

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val timeRangeText: TextView = itemView.findViewById(R.id.timeRangeText)
        private val durationText: TextView = itemView.findViewById(R.id.durationText)
        private val syncBadge: ImageView = itemView.findViewById(R.id.syncBadge)
        private val notesText: TextView = itemView.findViewById(R.id.notesText)
        private val extendButton: MaterialButton = itemView.findViewById(R.id.extendButton)
        private val adjustButton: MaterialButton = itemView.findViewById(R.id.adjustButton)

        fun bind(row: LedgerRow, position: Int, rowsInDay: Int) {
            val context = itemView.context
            val item = row.eventWithType
            val event = item.event

            eventTypeName.text = item.eventTypeName

            val displayColor = item.eventType.getDisplayColor()
                ?: ContextCompat.getColor(context, R.color.calwrite_blue)
            val endTime = event.endTime ?: event.startTime

            // The gutter is drawn outside this row, so hand it everything it needs about this one.
            itemView.setTag(
                R.id.ledger_timeline_row,
                LedgerTimelineRow(
                    dayStartMillis = dayStartMillis,
                    indexInDay = position,
                    rowsInDay = rowsInDay,
                    startTime = event.startTime,
                    endTime = endTime,
                    isInstant = item.isInstant,
                    color = displayColor
                )
            )

            timeRangeText.text = LedgerFormatting.timeRange(context, event.startTime, endTime)

            durationText.text = if (item.isInstant) {
                context.getString(R.string.ledger_instant_label)
            } else {
                LedgerFormatting.duration(context, item.durationMs)
            }

            // An icon, not a text badge: the row's second line has to share its width with a
            // fixed action cluster, and "In calendar" was the piece that lost. The label survives
            // as the content description, so TalkBack still announces it.
            val synced = event.calendarEventId != null
            syncBadge.setImageResource(
                if (synced) R.drawable.ic_event_available else R.drawable.ic_calendar_today
            )
            syncBadge.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (synced) android.R.color.holo_green_dark else android.R.color.holo_orange_dark
                )
            )
            syncBadge.contentDescription = context.getString(
                if (synced) R.string.ledger_badge_synced else R.string.ledger_badge_unsynced
            )

            if (item.notes.isBlank()) {
                notesText.visibility = View.GONE
            } else {
                notesText.visibility = View.VISIBLE
                notesText.text = item.notes
            }

            // Instant events are deliberately not extendable, and neither are older entries of a
            // type - see LedgerRow.canExtend.
            extendButton.visibility = if (row.canExtend) View.VISIBLE else View.GONE
            extendButton.setOnClickListener { onExtend(row) }
            adjustButton.setOnClickListener { onAdjust(row) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<LedgerRow>() {
        override fun areItemsTheSame(oldItem: LedgerRow, newItem: LedgerRow): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LedgerRow, newItem: LedgerRow): Boolean =
            oldItem == newItem
    }
}
