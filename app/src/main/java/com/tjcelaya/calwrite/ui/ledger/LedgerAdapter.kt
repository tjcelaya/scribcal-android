package com.tjcelaya.calwrite.ui.ledger

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 */
class LedgerAdapter(
    private val onExtend: (LedgerRow) -> Unit,
    private val onAdjust: (LedgerRow) -> Unit
) : ListAdapter<LedgerRow, LedgerAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ledger_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun rowAt(position: Int): LedgerRow? = if (position in 0 until itemCount) getItem(position) else null

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val timeRangeText: TextView = itemView.findViewById(R.id.timeRangeText)
        private val durationText: TextView = itemView.findViewById(R.id.durationText)
        private val syncBadge: TextView = itemView.findViewById(R.id.syncBadge)
        private val notesText: TextView = itemView.findViewById(R.id.notesText)
        private val extendButton: MaterialButton = itemView.findViewById(R.id.extendButton)
        private val adjustButton: MaterialButton = itemView.findViewById(R.id.adjustButton)

        fun bind(row: LedgerRow) {
            val context = itemView.context
            val item = row.eventWithType
            val event = item.event

            eventTypeName.text = item.eventTypeName

            val displayColor = item.eventType.getDisplayColor()
                ?: ContextCompat.getColor(context, R.color.calwrite_blue)
            colorIndicator.background.setTint(displayColor)

            val endTime = event.endTime ?: event.startTime
            timeRangeText.text = LedgerFormatting.timeRange(context, event.startTime, endTime)

            durationText.text = if (item.isInstant) {
                context.getString(R.string.ledger_instant_label)
            } else {
                LedgerFormatting.duration(context, item.durationMs)
            }

            val synced = event.calendarEventId != null
            syncBadge.setText(if (synced) R.string.ledger_badge_synced else R.string.ledger_badge_unsynced)
            syncBadge.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (synced) android.R.color.holo_green_dark else android.R.color.holo_orange_dark
                )
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
