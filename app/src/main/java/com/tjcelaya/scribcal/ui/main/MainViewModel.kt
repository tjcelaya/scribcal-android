package com.tjcelaya.scribcal.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.EventWithType
import kotlinx.coroutines.launch

/**
 * ViewModel for the main screen showing today's events
 */
class MainViewModel(
    private val repository: EventRepository
) : ViewModel() {

    // Live data for UI observation
    val todaysEvents: LiveData<List<EventWithType>> = repository.getTodaysEventsWithType().asLiveData()
    val ongoingEvents: LiveData<List<EventWithType>> = repository.getOngoingEventsWithType().asLiveData()
    val allEventTypes: LiveData<List<EventType>> = repository.getAllEventTypes()
    val favoriteEventTypes: LiveData<List<EventType>> = repository.getFavoriteEventTypes().asLiveData()

    /**
     * Creates an instant event for the given event type
     */
    fun createInstantEvent(eventTypeId: Long, notes: String = "") {
        viewModelScope.launch {
            repository.createInstantEvent(eventTypeId, notes = notes)
        }
    }

    /**
     * Creates an instant event with a photo attachment
     */
    fun createInstantEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = "") {
        viewModelScope.launch {
            repository.createInstantEventWithPhoto(eventTypeId, photoPath, notes = notes)
        }
    }

    /**
     * Starts a timed event for the given event type
     */
    fun startTimedEvent(eventTypeId: Long, notes: String = ""): Long? {
        var eventId: Long? = null
        viewModelScope.launch {
            eventId = repository.startTimedEvent(eventTypeId, notes = notes)
        }
        return eventId
    }

    /**
     * Starts a timed event with a photo attachment
     */
    fun startTimedEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = ""): Long? {
        var eventId: Long? = null
        viewModelScope.launch {
            eventId = repository.startTimedEventWithPhoto(eventTypeId, photoPath, notes = notes)
        }
        return eventId
    }

    /**
     * Completes an ongoing event
     */
    fun completeEvent(eventId: Long) {
        viewModelScope.launch {
            repository.completeOngoingEvent(eventId)
        }
    }

    /**
     * Deletes an event
     */
    fun deleteEvent(eventId: Long) {
        viewModelScope.launch {
            repository.deleteEventById(eventId)
        }
    }
}
