package com.tjcelaya.scribcal.data

import androidx.lifecycle.LiveData
import com.tjcelaya.scribcal.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EventRepository(private val database: ScribCalDatabase) {
    
    private val eventTypeDao = database.eventTypeDao()
    private val ongoingEventDao = database.ongoingEventDao()
    private val completedEventDao = database.completedEventDao()
    
    // Event Type operations
    fun getAllEventTypes(): LiveData<List<EventType>> = eventTypeDao.getAllEventTypes()
    
    suspend fun getEventTypeById(id: Long): EventType? = withContext(Dispatchers.IO) {
        eventTypeDao.getEventTypeById(id)
    }
    
    suspend fun insertEventType(eventType: EventType): Long = withContext(Dispatchers.IO) {
        eventTypeDao.insertEventType(eventType)
    }
    
    suspend fun updateEventType(eventType: EventType) = withContext(Dispatchers.IO) {
        eventTypeDao.updateEventType(eventType)
    }
    
    suspend fun deleteEventType(eventType: EventType) = withContext(Dispatchers.IO) {
        eventTypeDao.deleteEventType(eventType)
    }
    
    // Ongoing Event operations
    fun getAllOngoingEvents(): LiveData<List<OngoingEvent>> = ongoingEventDao.getAllOngoingEvents()
    
    suspend fun getOngoingEventById(id: Long): OngoingEvent? = withContext(Dispatchers.IO) {
        ongoingEventDao.getOngoingEventById(id)
    }
    
    suspend fun getOngoingEventsByType(eventTypeId: Long): List<OngoingEvent> = withContext(Dispatchers.IO) {
        ongoingEventDao.getOngoingEventsByType(eventTypeId)
    }
    
    suspend fun startEvent(eventTypeId: Long, notes: String? = null): Long = withContext(Dispatchers.IO) {
        val ongoingEvent = OngoingEvent(
            eventTypeId = eventTypeId,
            startTime = System.currentTimeMillis(),
            notes = notes
        )
        ongoingEventDao.insertOngoingEvent(ongoingEvent)
    }
    
    suspend fun stopEvent(ongoingEventId: Long, calendarRepository: CalendarRepository): Boolean = withContext(Dispatchers.IO) {
        val ongoingEvent = ongoingEventDao.getOngoingEventById(ongoingEventId)
        if (ongoingEvent != null) {
            val endTime = System.currentTimeMillis()
            val completedEvent = CompletedEvent(
                eventTypeId = ongoingEvent.eventTypeId,
                startTime = ongoingEvent.startTime,
                endTime = endTime,
                notes = ongoingEvent.notes
            )
            
            // Insert completed event
            val completedEventId = completedEventDao.insertCompletedEvent(completedEvent)
            
            // Remove ongoing event
            ongoingEventDao.deleteOngoingEvent(ongoingEvent)
            
            // Try to sync to calendar
            val eventType = eventTypeDao.getEventTypeById(ongoingEvent.eventTypeId)
            if (eventType != null) {
                calendarRepository.syncEventToCalendar(
                    completedEventId,
                    eventType,
                    ongoingEvent.startTime,
                    endTime,
                    ongoingEvent.notes
                )
            }
            
            true
        } else {
            false
        }
    }
    
    suspend fun recordInstantaneousEvent(eventTypeId: Long, notes: String? = null, calendarRepository: CalendarRepository): Long = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        val completedEvent = CompletedEvent(
            eventTypeId = eventTypeId,
            startTime = currentTime,
            endTime = currentTime, // Same time for instantaneous events
            notes = notes
        )
        
        val completedEventId = completedEventDao.insertCompletedEvent(completedEvent)
        
        // Try to sync to calendar
        val eventType = eventTypeDao.getEventTypeById(eventTypeId)
        if (eventType != null) {
            calendarRepository.syncEventToCalendar(
                completedEventId,
                eventType,
                currentTime,
                currentTime,
                notes
            )
        }
        
        completedEventId
    }
    
    suspend fun getOngoingEventCountForType(eventTypeId: Long): Int = withContext(Dispatchers.IO) {
        ongoingEventDao.getOngoingEventCountForType(eventTypeId)
    }
    
    // Completed Event operations
    fun getAllCompletedEvents(): LiveData<List<CompletedEvent>> = completedEventDao.getAllCompletedEvents()
    
    fun getCompletedEventsByType(eventTypeId: Long): LiveData<List<CompletedEvent>> = 
        completedEventDao.getCompletedEventsByType(eventTypeId)
    
    suspend fun getUnsyncedEvents(): List<CompletedEvent> = withContext(Dispatchers.IO) {
        completedEventDao.getUnsyncedEvents()
    }
    
    suspend fun markEventAsSynced(eventId: Long, calendarEventId: Long) = withContext(Dispatchers.IO) {
        val event = completedEventDao.getCompletedEventById(eventId)
        if (event != null) {
            completedEventDao.updateCompletedEvent(
                event.copy(
                    syncedToCalendar = true,
                    calendarEventId = calendarEventId
                )
            )
        }
    }
}
