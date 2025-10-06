package com.tjcelaya.scribcal.ui.events

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import kotlinx.coroutines.launch

class AddEventViewModel(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    // LiveData for event types (for autocomplete)
    val eventTypes: LiveData<List<EventType>> = eventRepository.getAllEventTypes()

    // UI state
    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    private val _navigateBack = MutableLiveData<Boolean>()
    val navigateBack: LiveData<Boolean> = _navigateBack

    fun recordInstantEvent(eventTypeName: String, customTimestamp: Long? = null) {
        viewModelScope.launch {
            try {
                val eventTypeId = getOrCreateEventType(eventTypeName)
                val eventId = eventRepository.createInstantEvent(eventTypeId, timestamp = customTimestamp)

                // Sync to calendar if available
                try {
                    if (calendarRepository.isCalendarSetupComplete()) {
                        eventRepository.syncEventToCalendar(eventId, calendarRepository)
                    }
                } catch (e: Exception) {
                    Log.w("AddEventViewModel", "Failed to sync instant event to calendar", e)
                    // Don't fail the whole operation if calendar sync fails
                }

                _message.value = "SUCCESS_INSTANT_EVENT"
                _navigateBack.value = true
            } catch (e: Exception) {
                Log.e("AddEventViewModel", "Error recording instant event", e)
                _message.value = "ERROR_RECORDING_EVENT:${e.message}"
            }
        }
    }

    fun startTimedEvent(eventTypeName: String, customTimestamp: Long? = null) {
        viewModelScope.launch {
            try {
                val eventTypeId = getOrCreateEventType(eventTypeName)

                // Check if there's already an ongoing event of this type
                val existingOngoing = eventRepository.getOngoingEventCountForType(eventTypeId)
                if (existingOngoing > 0) {
                    _message.value = "ERROR_ONGOING_EVENT_EXISTS"
                    return@launch
                }

                eventRepository.startTimedEvent(eventTypeId, timestamp = customTimestamp)
                _message.value = "SUCCESS_TIMED_EVENT"
                _navigateBack.value = true
            } catch (e: Exception) {
                Log.e("AddEventViewModel", "Error starting timed event", e)
                _message.value = "ERROR_STARTING_EVENT:${e.message}"
            }
        }
    }

    private suspend fun getOrCreateEventType(eventTypeName: String): Long {
        // First, check if an event type with this name already exists
        val existingEventType = eventTypes.value?.find {
            it.name.equals(eventTypeName, ignoreCase = true)
        }

        return if (existingEventType != null) {
            Log.d("AddEventViewModel", "Using existing event type: ${existingEventType.name}")
            existingEventType.id
        } else {
            // Create a new event type
            Log.d("AddEventViewModel", "Creating new event type: $eventTypeName")
            val newEventType = EventType(name = eventTypeName)
            eventRepository.insertEventType(newEventType)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun onNavigatedBack() {
        _navigateBack.value = false
    }
}

class AddEventViewModelFactory(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AddEventViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AddEventViewModel(eventRepository, calendarRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}