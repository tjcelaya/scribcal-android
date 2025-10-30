package com.tjcelaya.scribcal.ui.tracking

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.map
import androidx.lifecycle.MediatorLiveData
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent
import com.tjcelaya.scribcal.data.database.FutureEvent
import kotlinx.coroutines.launch

class TrackingViewModel(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    val eventTypes: LiveData<List<EventType>> = eventRepository.getAllEventTypes()
    val ongoingEvents: LiveData<List<OngoingEvent>> = eventRepository.getAllOngoingEvents()
    val futureEvents: LiveData<List<FutureEvent>> = eventRepository.getAllFutureEvents()

    private val _calendarStatus = MutableLiveData<String>()
    val calendarStatus: LiveData<String> = _calendarStatus

    private val _needsCalendarSetup = MutableLiveData<Boolean>()
    val needsCalendarSetup: LiveData<Boolean> = _needsCalendarSetup

    private val _showStopConfirmation = MutableLiveData<OngoingEvent?>()
    val showStopConfirmation: LiveData<OngoingEvent?> = _showStopConfirmation

    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    // Combined LiveData for event types with ongoing counts
    val eventTypesWithCounts: LiveData<List<EventTypeWithCount>> = MediatorLiveData<List<EventTypeWithCount>>().apply {
        var eventTypesList: List<EventType> = emptyList()
        var ongoingEventsList: List<OngoingEvent> = emptyList()

        fun update() {
            viewModelScope.launch {
                val ongoingByEventType = ongoingEventsList.groupBy { it.eventTypeId }
                val currentTime = System.currentTimeMillis()
                
                val items = eventTypesList.map { eventType ->
                    val ongoingEvents = ongoingByEventType[eventType.id] ?: emptyList()
                    
                    // Fetch last occurrence time and frequency stats
                    val lastOccurrence = eventRepository.getLastCompletedEventTime(eventType.id)
                    val hourlyCount = eventRepository.getEventCountSince(eventType.id, currentTime - 3600_000L)
                    val dailyCount = eventRepository.getEventCountSince(eventType.id, currentTime - 86400_000L)
                    val weeklyCount = eventRepository.getEventCountSince(eventType.id, currentTime - 604800_000L)
                    val monthlyCount = eventRepository.getEventCountSince(eventType.id, currentTime - 2592000_000L)
                    
                    EventTypeWithCount(
                        eventType = eventType,
                        ongoingCount = ongoingEvents.size,
                        ongoingEvent = ongoingEvents.firstOrNull(), // Show the first ongoing event
                        lastOccurrenceTime = lastOccurrence,
                        hourlyCount = hourlyCount,
                        dailyCount = dailyCount,
                        weeklyCount = weeklyCount,
                        monthlyCount = monthlyCount
                    )
                }
                value = items
            }
        }

        addSource(eventTypes) { types ->
            eventTypesList = types
            update()
        }

        addSource(ongoingEvents) { events ->
            ongoingEventsList = events
            update()
        }
    }

    // Combined LiveData for ongoing events with their event types
    val ongoingEventsWithTypes: LiveData<List<OngoingEventWithType>> = MediatorLiveData<List<OngoingEventWithType>>().apply {
        var eventTypesList: List<EventType> = emptyList()
        var ongoingEventsList: List<OngoingEvent> = emptyList()

        fun update() {
            val eventTypesMap = eventTypesList.associateBy { it.id }
            value = ongoingEventsList.mapNotNull { ongoingEvent ->
                eventTypesMap[ongoingEvent.eventTypeId]?.let { eventType ->
                    OngoingEventWithType(ongoingEvent, eventType)
                }
            }
        }

        addSource(eventTypes) { types ->
            eventTypesList = types
            update()
        }

        addSource(ongoingEvents) { events ->
            ongoingEventsList = events
            update()
        }
    }

    // Combined LiveData for both ongoing events and photo uploads
    val displayableOngoingItems: LiveData<List<DisplayableOngoingItem>> = MediatorLiveData<List<DisplayableOngoingItem>>().apply {
        var eventTypesList: List<EventType> = emptyList()
        var ongoingEventsList: List<OngoingEvent> = emptyList()
        var photoUploadsList: List<PhotoUpload> = emptyList()

        fun update() {
            val eventTypesMap = eventTypesList.associateBy { it.id }
            val items = mutableListOf<DisplayableOngoingItem>()

            // Add regular ongoing events
            ongoingEventsList.forEach { ongoingEvent ->
                eventTypesMap[ongoingEvent.eventTypeId]?.let { eventType ->
                    items.add(
                        DisplayableOngoingItem(
                            id = "event_${ongoingEvent.id}",
                            eventType = eventType,
                            startTime = ongoingEvent.startTime,
                            notes = ongoingEvent.notes,
                            type = DisplayableOngoingItem.Type.REGULAR_EVENT,
                            ongoingEvent = ongoingEvent
                        )
                    )
                }
            }

            // Add photo uploads in progress
            photoUploadsList.forEach { photoUpload ->
                eventTypesMap[photoUpload.eventTypeId]?.let { eventType ->
                    items.add(
                        DisplayableOngoingItem(
                            id = "upload_${photoUpload.id}",
                            eventType = eventType,
                            startTime = photoUpload.startTime,
                            notes = photoUpload.notes,
                            type = DisplayableOngoingItem.Type.PHOTO_UPLOAD,
                            photoUpload = photoUpload
                        )
                    )
                }
            }

            // Sort by start time (newest first)
            value = items.sortedByDescending { it.startTime }
        }

        addSource(eventTypes) { types ->
            eventTypesList = types
            update()
        }

        addSource(ongoingEvents) { events ->
            ongoingEventsList = events
            update()
        }

        addSource(eventRepository.photoUploads) { uploads ->
            photoUploadsList = uploads
            update()
        }
    }

    // Combined LiveData for future events with their event types
    val futureEventsWithTypes: LiveData<List<com.tjcelaya.scribcal.ui.tracking.FutureEventWithType>> = MediatorLiveData<List<com.tjcelaya.scribcal.ui.tracking.FutureEventWithType>>().apply {
        var eventTypesList: List<EventType> = emptyList()
        var futureEventsList: List<FutureEvent> = emptyList()

        fun update() {
            val eventTypesMap = eventTypesList.associateBy { it.id }
            value = futureEventsList.mapNotNull { futureEvent ->
                eventTypesMap[futureEvent.eventTypeId]?.let { eventType ->
                    com.tjcelaya.scribcal.ui.tracking.FutureEventWithType(futureEvent, eventType)
                }
            }
        }

        addSource(eventTypes) { types ->
            eventTypesList = types
            update()
        }

        addSource(futureEvents) { events ->
            futureEventsList = events
            update()
        }
    }

    fun onCalendarPermissionsGranted() {
        updateCalendarStatus()
    }

    fun startEvent(eventTypeId: Long) {
        viewModelScope.launch {
            try {
                eventRepository.startEvent(eventTypeId)
                _message.value = "Event started"
            } catch (e: Exception) {
                _message.value = "Error starting event: ${e.message}"
            }
        }
    }

    fun recordInstantaneousEvent(eventTypeId: Long) {
        viewModelScope.launch {
            try {
                eventRepository.recordInstantaneousEvent(eventTypeId, calendarRepository = calendarRepository)
                _message.value = "Instant event recorded"
            } catch (e: Exception) {
                _message.value = "Error recording event: ${e.message}"
            }
        }
    }

    fun showStopEventConfirmation(ongoingEvent: OngoingEvent) {
        _showStopConfirmation.value = ongoingEvent
    }

    fun hideStopEventConfirmation() {
        _showStopConfirmation.value = null
    }

    fun getEventTypeById(eventTypeId: Long): EventType? {
        return eventTypes.value?.find { it.id == eventTypeId }
    }

    fun stopEvent(ongoingEvent: OngoingEvent) {
        viewModelScope.launch {
            try {
                val success = eventRepository.stopEvent(ongoingEvent.id, calendarRepository)
                if (success) {
                    _message.value = "Event stopped and saved to calendar"
                } else {
                    _message.value = "Error stopping event"
                }
                _showStopConfirmation.value = null
            } catch (e: Exception) {
                _message.value = "Error stopping event: ${e.message}"
            }
        }
    }

    fun stopEventWithoutSaving(ongoingEvent: OngoingEvent) {
        viewModelScope.launch {
            try {
                val success = eventRepository.stopEventWithoutSaving(ongoingEvent.id)
                if (success) {
                    _message.value = "Event stopped without saving"
                } else {
                    _message.value = "Error stopping event"
                }
                _showStopConfirmation.value = null
            } catch (e: Exception) {
                _message.value = "Error stopping event: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun recordInstantaneousEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = "") {
        viewModelScope.launch {
            try {
                eventRepository.createInstantEventWithPhotoAndSync(eventTypeId, photoPath, notes, calendarRepository)
                _message.value = "Instant event with photo created and synced to calendar"
            } catch (e: IllegalStateException) {
                // Handle access exceptions with specific message
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "Error creating event with photo: ${e.message}"
            }
        }
    }

    fun startTimedEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = "") {
        viewModelScope.launch {
            try {
                eventRepository.startTimedEventWithPhotoAndSync(eventTypeId, photoPath, notes)
                _message.value = "Timed event with photo started"
            } catch (e: IllegalStateException) {
                // Handle access exceptions with specific message
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "Error starting timed event with photo: ${e.message}"
            }
        }
    }

    fun createFutureEvent(eventTypeId: Long, targetTime: Long, notes: String? = null) {
        viewModelScope.launch {
            try {
                eventRepository.createFutureEvent(eventTypeId, targetTime, notes)
                _message.value = "Future event scheduled"
            } catch (e: Exception) {
                _message.value = "Error scheduling future event: ${e.message}"
            }
        }
    }

    suspend fun createEventTypeFromCalendarEvent(eventTitle: String): Long? {
        return try {
            // Check if an event type with this name already exists
            val existingEventType = eventRepository.getEventTypeByName(eventTitle)
            if (existingEventType != null) {
                Log.d("TrackingViewModel", "Reusing existing event type: ${existingEventType.name}")
                return existingEventType.id
            }
            
            // Create new event type if it doesn't exist
            val newEventType = EventType(
                name = eventTitle,
                description = "Created from calendar event",
                color = null
            )
            eventRepository.insertEventType(newEventType)
        } catch (e: Exception) {
            Log.e("TrackingViewModel", "Error creating event type from calendar event", e)
            null
        }
    }

    fun deleteFutureEvent(futureEventId: Long) {
        viewModelScope.launch {
            try {
                eventRepository.deleteFutureEvent(futureEventId)
                _message.value = "Future event deleted"
            } catch (e: Exception) {
                _message.value = "Error deleting future event: ${e.message}"
            }
        }
    }

    fun recordEarlyEvent(futureEventId: Long, eventTypeId: Long, eventTypeName: String) {
        viewModelScope.launch {
            try {
                // Create an instant event with "(early)" suffix
                val modifiedTitle = "$eventTypeName (early)"
                
                // Create a temporary event type with the modified name for this event
                val currentTime = System.currentTimeMillis()
                
                // Record the event directly to calendar with modified title
                val eventType = eventTypes.value?.find { it.id == eventTypeId }
                if (eventType != null) {
                    val modifiedEventType = eventType.copy(name = modifiedTitle)
                    val calendarEventId = calendarRepository.syncEventToCalendar(
                        eventId = 0L, // Temporary ID, not saved to DB
                        eventType = modifiedEventType,
                        startTime = currentTime,
                        endTime = currentTime,
                        notes = null
                    )
                    
                    // Delete the future event
                    eventRepository.deleteFutureEvent(futureEventId)
                    
                    if (calendarEventId != null) {
                        _message.value = "Event completed early and saved to calendar"
                    } else {
                        _message.value = "Event completed early but failed to save to calendar"
                    }
                } else {
                    _message.value = "Error: Event type not found"
                }
            } catch (e: Exception) {
                _message.value = "Error recording early event: ${e.message}"
            }
        }
    }

    fun checkExpiredFutureEvents() {
        viewModelScope.launch {
            try {
                val triggeredCount = eventRepository.triggerExpiredFutureEvents(calendarRepository)
                if (triggeredCount > 0) {
                    _message.value = "$triggeredCount scheduled event(s) triggered"
                }
            } catch (e: Exception) {
                Log.e("TrackingViewModel", "Error checking expired future events", e)
            }
        }
    }

    fun toggleItemExpanded(item: EventTypeWithCount) {
        val currentItems = eventTypesWithCounts.value ?: return
        val updatedItems = currentItems.map { currentItem ->
            if (currentItem.eventType.id == item.eventType.id) {
                currentItem.copy(isExpanded = !currentItem.isExpanded)
            } else {
                currentItem
            }
        }
        (eventTypesWithCounts as MediatorLiveData).value = updatedItems
    }

    private fun updateCalendarStatus() {
        if (calendarRepository.isCalendarSetupComplete()) {
            val calendarName = calendarRepository.getSelectedCalendarName()
            _calendarStatus.value = "Calendar: $calendarName"
            _needsCalendarSetup.value = false
        } else {
            _calendarStatus.value = "No calendar selected"
            _needsCalendarSetup.value = true
        }
    }

    init {
        updateCalendarStatus()
    }
}

class TrackingViewModelFactory(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackingViewModel(eventRepository, calendarRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
