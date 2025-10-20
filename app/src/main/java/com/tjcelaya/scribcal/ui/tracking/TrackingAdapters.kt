package com.tjcelaya.scribcal.ui.tracking

import android.text.format.DateFormat
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

data class EventTypeWithCount(
    val eventType: EventType,
    val ongoingCount: Int,
    val ongoingEvent: OngoingEvent? = null // The first ongoing event if any
)

class EventTypesTrackingAdapter(
    private val onStartEvent: (EventType) -> Unit,
    private val onRecordInstantEvent: (EventType) -> Unit,
    private val onStopEvent: (OngoingEvent) -> Unit
) : ListAdapter<EventTypeWithCount, EventTypesTrackingAdapter.ViewHolder>(EventTypeWithCountDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_type_tracking, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun refreshTimers() {
        // Force refresh all visible items to update elapsed time displays
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val eventTypeDescription: TextView = itemView.findViewById(R.id.eventTypeDescription)
        private val ongoingStatusLayout: View = itemView.findViewById(R.id.ongoingStatusLayout)
        private val elapsedTimeText: TextView = itemView.findViewById(R.id.elapsedTimeText)
        private val startedAtText: TextView = itemView.findViewById(R.id.startedAtText)
        private val instantEventButton: MaterialButton = itemView.findViewById(R.id.instantEventButton)
        private val startEventButton: MaterialButton = itemView.findViewById(R.id.startEventButton)
        private val stopEventButton: MaterialButton = itemView.findViewById(R.id.stopEventButton)

        fun bind(eventTypeWithCount: EventTypeWithCount) {
            val eventType = eventTypeWithCount.eventType
            val ongoingEvent = eventTypeWithCount.ongoingEvent

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
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.scribcal_blue)
                colorIndicator.background.setTint(defaultColor)
            }

            // Handle ongoing vs normal state
            if (ongoingEvent != null) {
                // Show ongoing state
                showOngoingState(ongoingEvent, eventType)
            } else {
                // Show normal buttons
                showNormalState(eventType)
            }
        }

        private fun showOngoingState(ongoingEvent: OngoingEvent, eventType: EventType) {
            // Show ongoing status
            ongoingStatusLayout.visibility = View.VISIBLE
            updateElapsedTime(ongoingEvent.startTime)

            // Format started time
            val startTime = Date(ongoingEvent.startTime)
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            startedAtText.text = "Started at ${timeFormat.format(startTime)}"

            // Hide normal buttons, show stop button
            instantEventButton.visibility = View.GONE
            startEventButton.visibility = View.GONE
            stopEventButton.visibility = View.VISIBLE

            // Set stop button click listener
            stopEventButton.setOnClickListener {
                onStopEvent(ongoingEvent)
            }
        }

        private fun showNormalState(eventType: EventType) {
            // Hide ongoing status
            ongoingStatusLayout.visibility = View.GONE

            // Show normal buttons, hide stop button
            instantEventButton.visibility = View.VISIBLE
            startEventButton.visibility = View.VISIBLE
            stopEventButton.visibility = View.GONE

            // Set normal button click listeners
            instantEventButton.setOnClickListener {
                onRecordInstantEvent(eventType)
            }
            startEventButton.setOnClickListener {
                onStartEvent(eventType)
            }
        }

        private fun updateElapsedTime(startTime: Long) {
            val now = System.currentTimeMillis()
            val elapsedMillis = now - startTime
            val elapsedSeconds = elapsedMillis / 1000

            val hours = elapsedSeconds / 3600
            val minutes = (elapsedSeconds % 3600) / 60
            val seconds = elapsedSeconds % 60

            elapsedTimeText.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}

class EventTypeWithCountDiffCallback : DiffUtil.ItemCallback<EventTypeWithCount>() {
    override fun areItemsTheSame(oldItem: EventTypeWithCount, newItem: EventTypeWithCount): Boolean {
        return oldItem.eventType.id == newItem.eventType.id
    }

    override fun areContentsTheSame(oldItem: EventTypeWithCount, newItem: EventTypeWithCount): Boolean {
        return oldItem == newItem
    }
}

data class OngoingEventWithType(
    val ongoingEvent: OngoingEvent,
    val eventType: EventType
)

/**
 * Represents a photo upload in progress
 */
data class PhotoUpload(
    val id: String, // Unique identifier for this upload
    val eventTypeId: Long,
    val fileName: String,
    val startTime: Long,
    val progress: Int, // 0-100
    val status: String, // "Uploading to Google Drive", "Uploading to Google Photos", etc.
    val notes: String? = null
)

/**
 * Combined type for displaying both real ongoing events and photo uploads
 */
data class DisplayableOngoingItem(
    val id: String,
    val eventType: EventType,
    val startTime: Long,
    val notes: String?,
    val type: Type,
    // For regular ongoing events
    val ongoingEvent: OngoingEvent? = null,
    // For photo uploads
    val photoUpload: PhotoUpload? = null
) {
    enum class Type {
        REGULAR_EVENT,
        PHOTO_UPLOAD
    }
}

class OngoingEventsAdapter(
    private val onStopEvent: (OngoingEvent) -> Unit
) : ListAdapter<DisplayableOngoingItem, OngoingEventsAdapter.ViewHolder>(DisplayableOngoingItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ongoing_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun refreshTimers() {
        // Force refresh all visible items to update elapsed time
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val elapsedTimeText: TextView = itemView.findViewById(R.id.elapsedTimeText)
        private val startedAtText: TextView = itemView.findViewById(R.id.startedAtText)
        private val stopEventButton: MaterialButton = itemView.findViewById(R.id.stopEventButton)

        fun bind(item: DisplayableOngoingItem) {
            eventTypeName.text = item.eventType.name

            when (item.type) {
                DisplayableOngoingItem.Type.REGULAR_EVENT -> {
                    bindRegularEvent(item)
                }
                DisplayableOngoingItem.Type.PHOTO_UPLOAD -> {
                    bindPhotoUpload(item)
                }
            }
        }

        private fun bindRegularEvent(item: DisplayableOngoingItem) {
            val ongoingEvent = item.ongoingEvent!!

            // Format started time
            val startTime = Date(ongoingEvent.startTime)
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            startedAtText.text = "Started at ${timeFormat.format(startTime)}"

            // Calculate and display elapsed time
            updateElapsedTime(ongoingEvent.startTime)

            // Enable stop button for regular events
            stopEventButton.isEnabled = true
            stopEventButton.text = "Stop"
            stopEventButton.setOnClickListener {
                onStopEvent(ongoingEvent)
            }
        }

        private fun bindPhotoUpload(item: DisplayableOngoingItem) {
            val photoUpload = item.photoUpload!!

            // Format started time
            val startTime = Date(photoUpload.startTime)
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            startedAtText.text = "Started at ${timeFormat.format(startTime)}"

            // Show upload progress instead of elapsed time
            elapsedTimeText.text = "${photoUpload.status} (${photoUpload.progress}%)"

            // Disable stop button for photo uploads (they can't be manually stopped)
            stopEventButton.isEnabled = false
            stopEventButton.text = "Uploading..."
            stopEventButton.setOnClickListener(null)
        }

        private fun updateElapsedTime(startTime: Long) {
            val now = System.currentTimeMillis()
            val elapsedMillis = now - startTime
            val elapsedSeconds = elapsedMillis / 1000

            val hours = elapsedSeconds / 3600
            val minutes = (elapsedSeconds % 3600) / 60
            val seconds = elapsedSeconds % 60

            elapsedTimeText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}

class DisplayableOngoingItemDiffCallback : DiffUtil.ItemCallback<DisplayableOngoingItem>() {
    override fun areItemsTheSame(oldItem: DisplayableOngoingItem, newItem: DisplayableOngoingItem): Boolean {
        return oldItem.id == newItem.id && oldItem.type == newItem.type
    }

    override fun areContentsTheSame(oldItem: DisplayableOngoingItem, newItem: DisplayableOngoingItem): Boolean {
        return oldItem == newItem
    }
}

class OngoingEventWithTypeDiffCallback : DiffUtil.ItemCallback<OngoingEventWithType>() {
    override fun areItemsTheSame(oldItem: OngoingEventWithType, newItem: OngoingEventWithType): Boolean {
        return oldItem.ongoingEvent.id == newItem.ongoingEvent.id
    }

    override fun areContentsTheSame(oldItem: OngoingEventWithType, newItem: OngoingEventWithType): Boolean {
        return oldItem == newItem
    }
}

class EventTypeDiffCallback : DiffUtil.ItemCallback<EventType>() {
    override fun areItemsTheSame(oldItem: EventType, newItem: EventType): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: EventType, newItem: EventType): Boolean {
        return oldItem == newItem
    }
}

class OngoingEventDiffCallback : DiffUtil.ItemCallback<OngoingEvent>() {
    override fun areItemsTheSame(oldItem: OngoingEvent, newItem: OngoingEvent): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: OngoingEvent, newItem: OngoingEvent): Boolean {
        return oldItem == newItem
    }
}
