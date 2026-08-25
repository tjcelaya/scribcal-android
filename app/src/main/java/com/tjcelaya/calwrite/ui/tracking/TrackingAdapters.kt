package com.tjcelaya.calwrite.ui.tracking

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tjcelaya.calwrite.R
import com.tjcelaya.calwrite.CalWriteApplication
import com.tjcelaya.calwrite.data.CardColorStyle
import com.tjcelaya.calwrite.data.EventViewMode
import com.tjcelaya.calwrite.data.database.EventType
import com.tjcelaya.calwrite.data.database.OngoingEvent
import java.text.SimpleDateFormat
import java.util.*

data class EventTypeWithCount(
    val eventType: EventType,
    val ongoingCount: Int,
    val ongoingEvent: OngoingEvent? = null, // The first ongoing event if any
    val lastOccurrenceTime: Long? = null
)

class EventTypesTrackingAdapter(
    private val onStartEvent: (EventType) -> Unit,
    private val onRecordInstantEvent: (EventType) -> Unit,
    private val onStopEvent: (OngoingEvent) -> Unit
) : ListAdapter<EventTypeWithCount, RecyclerView.ViewHolder>(EventTypeWithCountDiffCallback()) {

    companion object {
        private const val PAYLOAD_UPDATE_TIMER = "update_timer"
        private const val PAYLOAD_UPDATE_TIME_SINCE = "update_time_since"
        private const val VIEW_TYPE_LIST = 0
        private const val VIEW_TYPE_CARD = 1
    }

    private var viewMode: EventViewMode = EventViewMode.LIST
    private var cellWidthPx: Int = 0
    private var cardColorStyle: CardColorStyle = CardColorStyle.LINE

    fun setViewMode(mode: EventViewMode) {
        if (viewMode != mode) {
            viewMode = mode
            notifyDataSetChanged()
        }
    }

    fun setCellWidthPx(px: Int) {
        if (cellWidthPx != px) {
            cellWidthPx = px
            if (viewMode == EventViewMode.CARD) notifyDataSetChanged()
        }
    }

    fun setCardColorStyle(style: CardColorStyle) {
        if (cardColorStyle != style) {
            cardColorStyle = style
            if (viewMode == EventViewMode.CARD) notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (viewMode == EventViewMode.CARD) VIEW_TYPE_CARD else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_CARD) {
            CardViewHolder(inflater.inflate(R.layout.item_event_type_card, parent, false))
        } else {
            ListViewHolder(inflater.inflate(R.layout.item_event_type_tracking, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as EventTypeBindable).bind(getItem(position))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val item = getItem(position)
            val bindable = holder as EventTypeBindable

            if (payloads.contains(PAYLOAD_UPDATE_TIMER) && item.ongoingEvent != null) {
                bindable.updateTimerOnly(item.ongoingEvent.startTime)
            }

            if (payloads.contains(PAYLOAD_UPDATE_TIME_SINCE)) {
                bindable.updateTimeSinceOnly(item.lastOccurrenceTime)
            }

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

    private interface EventTypeBindable {
        fun bind(item: EventTypeWithCount)
        fun updateTimerOnly(startTime: Long)
        fun updateTimeSinceOnly(lastOccurrenceTime: Long?)
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

    private fun formatElapsed(startTime: Long): String {
        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    inner class ListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), EventTypeBindable {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val eventTypeDescription: TextView = itemView.findViewById(R.id.eventTypeDescription)
        private val ongoingStatusLayout: View = itemView.findViewById(R.id.ongoingStatusLayout)
        private val elapsedTimeText: TextView = itemView.findViewById(R.id.elapsedTimeText)
        private val startedAtText: TextView = itemView.findViewById(R.id.startedAtText)
        private val lastOccurrenceText: TextView = itemView.findViewById(R.id.lastOccurrenceText)
        private val instantEventButton: MaterialButton = itemView.findViewById(R.id.instantEventButton)
        private val startEventButton: MaterialButton = itemView.findViewById(R.id.startEventButton)
        private val stopEventButton: MaterialButton = itemView.findViewById(R.id.stopEventButton)

        override fun bind(eventTypeWithCount: EventTypeWithCount) {
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
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.calwrite_blue)
                colorIndicator.background.setTint(defaultColor)
            }

            // Display time since last occurrence
            if (eventTypeWithCount.lastOccurrenceTime != null) {
                val timeSince = System.currentTimeMillis() - eventTypeWithCount.lastOccurrenceTime
                lastOccurrenceText.text = formatTimeSince(timeSince)
                lastOccurrenceText.visibility = View.VISIBLE
            } else {
                lastOccurrenceText.text = "Never"
                lastOccurrenceText.visibility = View.VISIBLE
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

            // Show buttons according to the event type's cadence
            instantEventButton.visibility = if (eventType.cadence.showsInstant()) View.VISIBLE else View.GONE
            startEventButton.visibility = if (eventType.cadence.showsTimed()) View.VISIBLE else View.GONE
            stopEventButton.visibility = View.GONE

            // Get the selected instant event icon from preferences
            val app = itemView.context.applicationContext as CalWriteApplication
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

        override fun updateTimerOnly(startTime: Long) {
            // Only update the elapsed time without rebinding the entire view
            updateElapsedTime(startTime)
        }

        override fun updateTimeSinceOnly(lastOccurrenceTime: Long?) {
            // Only update the time since display without rebinding the entire view
            if (lastOccurrenceTime != null) {
                val timeSince = System.currentTimeMillis() - lastOccurrenceTime
                lastOccurrenceText.text = formatTimeSince(timeSince)
            } else {
                lastOccurrenceText.text = "Never"
            }
        }
    }

    /**
     * Card/grid presentation of an event type.
     */
    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), EventTypeBindable {
        private val cardView = itemView as MaterialCardView
        private val colorStrip: View = itemView.findViewById(R.id.colorStrip)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val lastOccurrenceText: TextView = itemView.findViewById(R.id.lastOccurrenceText)
        private val instantEventButton: MaterialButton = itemView.findViewById(R.id.instantEventButton)
        private val startEventButton: MaterialButton = itemView.findViewById(R.id.startEventButton)
        private val stopEventButton: MaterialButton = itemView.findViewById(R.id.stopEventButton)

        // Defaults captured before any per-style override, so LINE mode can reset them.
        private val defaultCardColor: ColorStateList? = cardView.cardBackgroundColor
        private val defaultNameColors: ColorStateList = eventTypeName.textColors
        private val defaultSubColors: ColorStateList = lastOccurrenceText.textColors

        private var boundOngoingStart: Long? = null

        override fun bind(item: EventTypeWithCount) {
            val eventType = item.eventType
            val ongoingEvent = item.ongoingEvent
            boundOngoingStart = ongoingEvent?.startTime

            // Square the card to the current cell width
            if (cellWidthPx > 0) {
                val lp = itemView.layoutParams
                if (lp != null && lp.height != cellWidthPx) {
                    lp.height = cellWidthPx
                    itemView.layoutParams = lp
                }
            }

            eventTypeName.text = eventType.name

            // Status line: live elapsed when ongoing, otherwise time-since-last
            if (ongoingEvent != null) {
                lastOccurrenceText.text = formatElapsed(ongoingEvent.startTime)
            } else if (item.lastOccurrenceTime != null) {
                lastOccurrenceText.text = formatTimeSince(System.currentTimeMillis() - item.lastOccurrenceTime)
            } else {
                lastOccurrenceText.text = "Never"
            }

            applyColorStyle(eventType.getDisplayColor())
            applyCadenceButtons(eventType, ongoingEvent)
        }

        private fun applyColorStyle(displayColor: Int?) {
            val color = displayColor ?: ContextCompat.getColor(itemView.context, R.color.calwrite_blue)
            when (cardColorStyle) {
                CardColorStyle.LINE -> {
                    colorStrip.visibility = View.VISIBLE
                    colorStrip.setBackgroundColor(color)
                    cardView.setCardBackgroundColor(defaultCardColor)
                    eventTypeName.setTextColor(defaultNameColors)
                    lastOccurrenceText.setTextColor(defaultSubColors)
                }
                CardColorStyle.BACKGROUND -> {
                    colorStrip.visibility = View.GONE
                    cardView.setCardBackgroundColor(color)
                    val onColor = if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
                    eventTypeName.setTextColor(onColor)
                    lastOccurrenceText.setTextColor(onColor)
                }
            }
        }

        private fun applyCadenceButtons(eventType: EventType, ongoingEvent: OngoingEvent?) {
            instantEventButton.visibility = if (eventType.cadence.showsInstant()) View.VISIBLE else View.GONE

            val app = itemView.context.applicationContext as CalWriteApplication
            instantEventButton.setIconResource(app.storagePreferences.getInstantEventIconResourceId(itemView.context))
            instantEventButton.setOnClickListener { onRecordInstantEvent(eventType) }

            if (ongoingEvent != null) {
                startEventButton.visibility = View.GONE
                stopEventButton.visibility = View.VISIBLE
                stopEventButton.setOnClickListener { onStopEvent(ongoingEvent) }
            } else {
                stopEventButton.visibility = View.GONE
                startEventButton.visibility = if (eventType.cadence.showsTimed()) View.VISIBLE else View.GONE
                startEventButton.setOnClickListener { onStartEvent(eventType) }
            }
        }

        override fun updateTimerOnly(startTime: Long) {
            lastOccurrenceText.text = formatElapsed(startTime)
        }

        override fun updateTimeSinceOnly(lastOccurrenceTime: Long?) {
            // While a timer is running the status line shows live elapsed time; don't overwrite it.
            if (boundOngoingStart != null) return
            lastOccurrenceText.text = if (lastOccurrenceTime != null) {
                formatTimeSince(System.currentTimeMillis() - lastOccurrenceTime)
            } else {
                "Never"
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
    val futureEvent: com.tjcelaya.calwrite.data.database.FutureEvent,
    val eventType: EventType
)

/**
 * Adapter for displaying future events with countdown timers
 */
class FutureEventsAdapter(
    private val onCompleteEarly: (com.tjcelaya.calwrite.data.database.FutureEvent, EventType) -> Unit,
    private val onCancelEvent: (com.tjcelaya.calwrite.data.database.FutureEvent) -> Unit
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
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.calwrite_blue)
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
