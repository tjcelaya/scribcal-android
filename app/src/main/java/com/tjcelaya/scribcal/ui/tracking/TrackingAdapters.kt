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
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent
import java.text.SimpleDateFormat
import java.util.*

data class EventTypeWithCount(
    val eventType: EventType,
    val ongoingCount: Int,
    val ongoingEvent: OngoingEvent? = null, // The first ongoing event if any
    val lastOccurrenceTime: Long? = null,
    val hourlyCount: Int = 0,
    val dailyCount: Int = 0,
    val weeklyCount: Int = 0,
    val monthlyCount: Int = 0,
    val isExpanded: Boolean = false
)

class EventTypesTrackingAdapter(
    private val onStartEvent: (EventType) -> Unit,
    private val onRecordInstantEvent: (EventType) -> Unit,
    private val onStopEvent: (OngoingEvent) -> Unit,
    private val onItemClick: ((EventTypeWithCount) -> Unit)? = null
) : ListAdapter<EventTypeWithCount, EventTypesTrackingAdapter.ViewHolder>(EventTypeWithCountDiffCallback()) {

    companion object {
        private const val PAYLOAD_UPDATE_TIMER = "update_timer"
        private const val PAYLOAD_UPDATE_TIME_SINCE = "update_time_since"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_type_tracking, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val item = getItem(position)
            
            if (payloads.contains(PAYLOAD_UPDATE_TIMER) && item.ongoingEvent != null) {
                // Only update the ongoing event timer
                holder.updateTimerOnly(item.ongoingEvent.startTime)
            }
            
            if (payloads.contains(PAYLOAD_UPDATE_TIME_SINCE)) {
                // Only update the time since last occurrence
                holder.updateTimeSinceOnly(item.lastOccurrenceTime)
            }
            
            // Don't call super if we handled all payloads
            if (payloads.all { it == PAYLOAD_UPDATE_TIMER || it == PAYLOAD_UPDATE_TIME_SINCE }) {
                return
            }
        }
        
        super.onBindViewHolder(holder, position, payloads)
    }

    fun refreshTimers() {
        // Refresh items with ongoing events and time-since displays
        for (position in 0 until itemCount) {
            val item = getItem(position)
            val payloads = mutableListOf<String>()
            
            // Update ongoing event timer if present
            if (item.ongoingEvent != null) {
                payloads.add(PAYLOAD_UPDATE_TIMER)
            }
            
            // Always update time since last occurrence (if there was a last occurrence)
            if (item.lastOccurrenceTime != null) {
                payloads.add(PAYLOAD_UPDATE_TIME_SINCE)
            }
            
            // Only notify if there's something to update
            if (payloads.isNotEmpty()) {
                notifyItemChanged(position, payloads)
            }
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val eventTypeDescription: TextView = itemView.findViewById(R.id.eventTypeDescription)
        private val ongoingStatusLayout: View = itemView.findViewById(R.id.ongoingStatusLayout)
        private val elapsedTimeText: TextView = itemView.findViewById(R.id.elapsedTimeText)
        private val startedAtText: TextView = itemView.findViewById(R.id.startedAtText)
        private val lastOccurrenceText: TextView = itemView.findViewById(R.id.lastOccurrenceText)
        private val frequencyStatsLayout: View = itemView.findViewById(R.id.frequencyStatsLayout)
        private val hourlyFrequency: TextView = itemView.findViewById(R.id.hourlyFrequency)
        private val dailyFrequency: TextView = itemView.findViewById(R.id.dailyFrequency)
        private val weeklyFrequency: TextView = itemView.findViewById(R.id.weeklyFrequency)
        private val monthlyFrequency: TextView = itemView.findViewById(R.id.monthlyFrequency)
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
            val displayColor = eventType.getDisplayColor()
            if (displayColor != null) {
                colorIndicator.background.setTint(displayColor)
            } else {
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.scribcal_blue)
                colorIndicator.background.setTint(defaultColor)
            }

            // Display time since last occurrence
            if (eventTypeWithCount.lastOccurrenceTime != null) {
                val timeSince = System.currentTimeMillis() - eventTypeWithCount.lastOccurrenceTime
                lastOccurrenceText.text = "Last: ${formatTimeSince(timeSince)}"
                lastOccurrenceText.visibility = View.VISIBLE
            } else {
                lastOccurrenceText.text = "Last: Never"
                lastOccurrenceText.visibility = View.VISIBLE
            }

            // Display frequency statistics
            hourlyFrequency.text = "Hourly: ${eventTypeWithCount.hourlyCount}"
            dailyFrequency.text = "Daily: ${eventTypeWithCount.dailyCount}"
            weeklyFrequency.text = "Weekly: ${eventTypeWithCount.weeklyCount}"
            monthlyFrequency.text = "Monthly: ${eventTypeWithCount.monthlyCount}"

            // Handle expanded/collapsed state
            frequencyStatsLayout.visibility = if (eventTypeWithCount.isExpanded) View.VISIBLE else View.GONE

            // Set click listener for the entire item (excluding buttons)
            itemView.setOnClickListener {
                onItemClick?.invoke(eventTypeWithCount)
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

        private fun formatTimeSince(millis: Long): String {
            val seconds = millis / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24
            val weeks = days / 7
            val months = days / 30

            return when {
                months > 0 -> "$months month${if (months > 1) "s" else ""} ago"
                weeks > 0 -> "$weeks week${if (weeks > 1) "s" else ""} ago"
                days > 0 -> "$days day${if (days > 1) "s" else ""} ago"
                hours > 0 -> "$hours hour${if (hours > 1) "s" else ""} ago"
                minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
                else -> "$seconds second${if (seconds != 1L) "s" else ""} ago"
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

            // Get the selected instant event icon from preferences
            val app = itemView.context.applicationContext as ScribCalApplication
            val iconResourceId = app.storagePreferences.getInstantEventIconResourceId(itemView.context)
            instantEventButton.setIconResource(iconResourceId)

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

        fun updateTimerOnly(startTime: Long) {
            // Only update the elapsed time without rebinding the entire view
            updateElapsedTime(startTime)
        }
        
        fun updateTimeSinceOnly(lastOccurrenceTime: Long?) {
            // Only update the time since display without rebinding the entire view
            if (lastOccurrenceTime != null) {
                val timeSince = System.currentTimeMillis() - lastOccurrenceTime
                lastOccurrenceText.text = "Last: ${formatTimeSince(timeSince)}"
            } else {
                lastOccurrenceText.text = "Last: Never"
            }
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
 * Represents a future event with countdown
 */
data class FutureEventWithType(
    val futureEvent: com.tjcelaya.scribcal.data.database.FutureEvent,
    val eventType: EventType
)

/**
 * Adapter for displaying future events with countdown timers
 */
class FutureEventsAdapter(
    private val onCompleteEarly: (com.tjcelaya.scribcal.data.database.FutureEvent, EventType) -> Unit,
    private val onCancelEvent: (com.tjcelaya.scribcal.data.database.FutureEvent) -> Unit
) : ListAdapter<FutureEventWithType, FutureEventsAdapter.ViewHolder>(FutureEventDiffCallback()) {

    companion object {
        private const val PAYLOAD_UPDATE_TIMER = "update_timer"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_future_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_UPDATE_TIMER)) {
            val item = getItem(position)
            holder.updateTimerOnly(item.futureEvent.targetTime)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    fun refreshTimers() {
        for (position in 0 until itemCount) {
            notifyItemChanged(position, PAYLOAD_UPDATE_TIMER)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val countdownText: TextView = itemView.findViewById(R.id.countdownText)
        private val targetTimeText: TextView = itemView.findViewById(R.id.targetTimeText)
        private val notesText: TextView = itemView.findViewById(R.id.notesText)
        private val completeEarlyButton: MaterialButton = itemView.findViewById(R.id.completeEarlyButton)
        private val cancelButton: MaterialButton = itemView.findViewById(R.id.cancelButton)

        fun bind(item: FutureEventWithType) {
            val eventType = item.eventType
            val futureEvent = item.futureEvent

            eventTypeName.text = eventType.name

            // Set color indicator
            val displayColor = eventType.getDisplayColor()
            if (displayColor != null) {
                colorIndicator.background.setTint(displayColor)
            } else {
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.scribcal_blue)
                colorIndicator.background.setTint(defaultColor)
            }

            // Update countdown
            updateCountdown(futureEvent.targetTime)

            // Format target time
            val targetTime = Date(futureEvent.targetTime)
            val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            targetTimeText.text = "Scheduled for ${dateFormat.format(targetTime)}"

            // Handle notes
            if (futureEvent.notes.isNullOrBlank()) {
                notesText.visibility = View.GONE
            } else {
                notesText.visibility = View.VISIBLE
                notesText.text = futureEvent.notes
            }

            // Set button click listeners
            completeEarlyButton.setOnClickListener {
                onCompleteEarly(futureEvent, eventType)
            }
            
            cancelButton.setOnClickListener {
                onCancelEvent(futureEvent)
            }
        }

        private fun updateCountdown(targetTime: Long) {
            val now = System.currentTimeMillis()
            val remainingMillis = targetTime - now

            if (remainingMillis <= 0) {
                countdownText.text = "00:00:00"
                return
            }

            val remainingSeconds = remainingMillis / 1000
            val days = remainingSeconds / 86400  // 86400 seconds in a day
            val hours = (remainingSeconds % 86400) / 3600
            val minutes = (remainingSeconds % 3600) / 60
            val seconds = remainingSeconds % 60

            val countdownString = if (days > 0) {
                String.format(Locale.getDefault(), "%dd %02d:%02d:%02d", days, hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            }

            countdownText.text = countdownString
        }

        fun updateTimerOnly(targetTime: Long) {
            updateCountdown(targetTime)
        }
    }
}

class FutureEventDiffCallback : DiffUtil.ItemCallback<FutureEventWithType>() {
    override fun areItemsTheSame(oldItem: FutureEventWithType, newItem: FutureEventWithType): Boolean {
        return oldItem.futureEvent.id == newItem.futureEvent.id
    }

    override fun areContentsTheSame(oldItem: FutureEventWithType, newItem: FutureEventWithType): Boolean {
        return oldItem == newItem
    }
}

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
