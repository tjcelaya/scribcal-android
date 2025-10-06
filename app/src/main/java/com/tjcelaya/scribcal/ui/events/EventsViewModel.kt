package com.tjcelaya.scribcal.ui.events

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import kotlinx.coroutines.launch

class EventsViewModel(
    private val eventRepository: EventRepository
) : ViewModel() {

    val eventTypes: LiveData<List<EventType>> = eventRepository.getAllEventTypes()

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
