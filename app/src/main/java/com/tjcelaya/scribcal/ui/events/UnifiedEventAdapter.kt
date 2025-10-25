package com.tjcelaya.scribcal.ui.events

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent
import java.text.SimpleDateFormat
import java.util.*

class UnifiedEventAdapter(
    private val onEditClick: (EventType) -> Unit,
    private val onDeleteClick: (EventType) -> Unit,
    private val onStopEventClick: (OngoingEvent) -> Unit,
    private val getCurrentTime: () -> Long = { System.currentTimeMillis() }
) : ListAdapter<EventDisplayItem, RecyclerView.ViewHolder>(EventDisplayItemDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_EVENT_TYPE = 1
        private const val VIEW_TYPE_ONGOING = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isOngoing) VIEW_TYPE_ONGOING else VIEW_TYPE_EVENT_TYPE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_ONGOING -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_ongoing_event, parent, false)
                OngoingEventViewHolder(view, getCurrentTime)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_event_type_management, parent, false)
                EventTypeViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is OngoingEventViewHolder -> holder.bind(item.eventType, item.ongoingEvent!!)
            is EventTypeViewHolder -> holder.bind(item.eventType)
        }
    }

    inner class EventTypeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val eventTypeDescription: TextView = itemView.findViewById(R.id.eventTypeDescription)
        private val editButton: MaterialButton = itemView.findViewById(R.id.editButton)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.deleteButton)

        fun bind(eventType: EventType) {
            eventTypeName.text = eventType.name

            // Handle description visibility
            if (eventType.description.isNullOrBlank()) {
                eventTypeDescription.visibility = View.GONE
            } else {
                eventTypeDescription.visibility = View.VISIBLE
                eventTypeDescription.text = eventType.description
            }

            // Set color indicator
            if (eventType.color != null) {
                colorIndicator.background.setTint(eventType.color)
            } else {
                // Use default primary color if no color is set
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.scribcal_blue)
                colorIndicator.background.setTint(defaultColor)
            }

            // Set click listeners
            editButton.setOnClickListener { onEditClick(eventType) }
            deleteButton.setOnClickListener { onDeleteClick(eventType) }
        }
    }

    inner class OngoingEventViewHolder(
        itemView: View,
        private val getCurrentTime: () -> Long
    ) : RecyclerView.ViewHolder(itemView) {
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val elapsedTimeText: TextView = itemView.findViewById(R.id.elapsedTimeText)
        private val startedAtText: TextView = itemView.findViewById(R.id.startedAtText)
        private val stopEventButton: MaterialButton = itemView.findViewById(R.id.stopEventButton)

        fun bind(eventType: EventType, ongoingEvent: OngoingEvent) {
            eventTypeName.text = eventType.name

            // Set color indicator to match event type
            if (eventType.color != null) {
                statusIndicator.background.setTint(eventType.color)
            } else {
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.scribcal_blue)
                statusIndicator.background.setTint(defaultColor)
            }

            // Format elapsed time
            val currentTime = getCurrentTime()
            val elapsedMillis = currentTime - ongoingEvent.startTime
            elapsedTimeText.text = formatElapsedTime(elapsedMillis)

            // Format start time
            val startTime = Calendar.getInstance().apply { timeInMillis = ongoingEvent.startTime }
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            startedAtText.text = "Started at ${timeFormat.format(startTime.time)}"

            // Set stop button click listener
            stopEventButton.setOnClickListener { onStopEventClick(ongoingEvent) }
        }

        private fun formatElapsedTime(elapsedMillis: Long): String {
            val seconds = (elapsedMillis / 1000) % 60
            val minutes = (elapsedMillis / (1000 * 60)) % 60
            val hours = elapsedMillis / (1000 * 60 * 60)

            return when {
                hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, seconds)
                else -> String.format("%02d:%02d", minutes, seconds)
            }
        }
    }
}

class EventDisplayItemDiffCallback : DiffUtil.ItemCallback<EventDisplayItem>() {
    override fun areItemsTheSame(oldItem: EventDisplayItem, newItem: EventDisplayItem): Boolean {
        return oldItem.displayId == newItem.displayId
    }

    override fun areContentsTheSame(oldItem: EventDisplayItem, newItem: EventDisplayItem): Boolean {
        return oldItem == newItem
    }
}