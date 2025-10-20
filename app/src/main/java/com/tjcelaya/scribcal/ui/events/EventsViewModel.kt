package com.tjcelaya.scribcal.ui.events

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.MediatorLiveData
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent
import kotlinx.coroutines.launch

class EventsViewModel(
    private val eventRepository: EventRepository
) : ViewModel() {

    val eventTypes: LiveData<List<EventType>> = eventRepository.getAllEventTypes()
    val ongoingEvents: LiveData<List<OngoingEvent>> = eventRepository.getAllOngoingEvents()

    // Combined list for unified display
    val displayItems: LiveData<List<EventDisplayItem>> = MediatorLiveData<List<EventDisplayItem>>().apply {
        var currentTypes: List<EventType> = emptyList()
        var currentOngoing: List<OngoingEvent> = emptyList()

        fun update() {
            // Map event types; if an ongoing exists for a type, include that item first
            val byTypeId = currentTypes.associateBy { it.id }
            val ongoingByTypeId = currentOngoing.groupBy { it.eventTypeId }

            val items = mutableListOf<EventDisplayItem>()
            for ((typeId, type) in byTypeId) {
                val ongoingForType = ongoingByTypeId[typeId]
                if (!ongoingForType.isNullOrEmpty()) {
                    // If multiple ongoing are possible, show the first; otherwise adapt as needed
                    items += EventDisplayItem(type, ongoingForType.first())
                } else {
                    items += EventDisplayItem(type, null)
                }
            }
            value = items
        }

        addSource(eventTypes) {
            currentTypes = it
            update()
        }
        addSource(ongoingEvents) {
            currentOngoing = it
            update()
        }
    }

    private val _deleteConfirmation = MutableLiveData<EventType?>()
    val deleteConfirmation: LiveData<EventType?> = _deleteConfirmation

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    fun showDeleteConfirmation(eventType: EventType) {
        _deleteConfirmation.value = eventType
    }

    fun hideDeleteConfirmation() {
        _deleteConfirmation.value = null
    }

    fun deleteEventType(eventType: EventType) {
        viewModelScope.launch {
            try {
                // Check if there are ongoing events for this type
                val ongoingCount = eventRepository.getOngoingEventCountForType(eventType.id)
                if (ongoingCount > 0) {
                    _errorMessage.value = "Cannot delete event type '${eventType.name}' because it has ongoing events. Stop all ongoing events first."
                    return@launch
                }

                eventRepository.deleteEventType(eventType)
                _deleteConfirmation.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Error deleting event type: ${e.message}"
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun stopOngoingEvent(ongoingEvent: OngoingEvent) {
        viewModelScope.launch {
            try {
                eventRepository.stopOngoingEvent(ongoingEvent.id)
            } catch (e: Exception) {
                _errorMessage.value = "Error stopping event: ${e.message}"
            }
        }
    }
}

class EventsViewModelFactory(
    private val eventRepository: EventRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EventsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EventsViewModel(eventRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
