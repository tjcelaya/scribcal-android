package com.tjcelaya.scribcal.ui.events

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import kotlinx.coroutines.launch

class AddEditEventTypeViewModel(
    private val eventRepository: EventRepository
) : ViewModel() {

    private val _saveResult = MutableLiveData<Boolean>()
    val saveResult: LiveData<Boolean> = _saveResult

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    fun saveEventType(eventType: EventType) {
        viewModelScope.launch {
            try {
                if (eventType.id == 0L) {
                    // Creating new event type
                    eventRepository.insertEventType(eventType)
                } else {
                    // Updating existing event type
                    eventRepository.updateEventType(eventType)
                }
                _saveResult.value = true
            } catch (e: Exception) {
                _errorMessage.value = "Error saving event type: ${e.message}"
                _saveResult.value = false
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }
}

class AddEditEventTypeViewModelFactory(
    private val eventRepository: EventRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AddEditEventTypeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AddEditEventTypeViewModel(eventRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
