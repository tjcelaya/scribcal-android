package com.tjcelaya.scribcal.ui.tracking

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
import kotlinx.coroutines.launch

class TrackingViewModel(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    val eventTypes: LiveData<List<EventType>> = eventRepository.getAllEventTypes()
    val ongoingEvents: LiveData<List<OngoingEvent>> = eventRepository.getAllOngoingEvents()

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
            val ongoingByEventType = ongoingEventsList.groupBy { it.eventTypeId }
            value = eventTypesList.map { eventType ->
                val ongoingEvents = ongoingByEventType[eventType.id] ?: emptyList()
                EventTypeWithCount(
                    eventType = eventType,
                    ongoingCount = ongoingEvents.size,
                    ongoingEvent = ongoingEvents.firstOrNull() // Show the first ongoing event
                )
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
