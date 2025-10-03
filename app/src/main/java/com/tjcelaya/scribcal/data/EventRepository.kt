package com.tjcelaya.scribcal.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import com.tjcelaya.scribcal.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.*

class EventRepository(private val database: ScribCalDatabase) {
    
    private val eventTypeDao = database.eventTypeDao()
    private val eventDao = database.eventDao()
    
    // Flag to ensure default event types are only created once per app session
    private var defaultEventTypesEnsured = false
    
    // Event Type operations
    fun getAllEventTypes(): LiveData<List<EventType>> = eventTypeDao.getAllEventTypes()
    
    fun getFavoriteEventTypes(): Flow<List<EventType>> {
        // For now, return empty list. In future, can add favorite functionality
        return flowOf(emptyList())
    }
    
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
    
    // Event operations with EventWithType relations
    fun getTodaysEventsWithType(): Flow<List<EventWithType>> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        
        return eventDao.getTodaysEventsWithType(startOfDay, endOfDay)
    }
    
    fun getOngoingEventsWithType(): Flow<List<EventWithType>> = eventDao.getOngoingEventsWithType()
    
    // Event creation and management
    suspend fun createInstantEvent(eventTypeId: Long, notes: String = "", photoPath: String? = null): Long = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        val event = Event(
            eventTypeId = eventTypeId,
            startTime = currentTime,
            endTime = currentTime, // Same time for instant events
            notes = notes,
            photoPath = photoPath
        )
        eventDao.insertEvent(event)
    }
    
    suspend fun createInstantEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = ""): Long =
        createInstantEvent(eventTypeId, notes, photoPath)
    
    suspend fun startTimedEvent(eventTypeId: Long, notes: String = "", photoPath: String? = null): Long = withContext(Dispatchers.IO) {
        val event = Event(
            eventTypeId = eventTypeId,
            startTime = System.currentTimeMillis(),
            endTime = null, // Null indicates ongoing event
            notes = notes,
            photoPath = photoPath
        )
        eventDao.insertEvent(event)
    }
    
    suspend fun startTimedEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = ""): Long =
        startTimedEvent(eventTypeId, notes, photoPath)
    
    suspend fun completeOngoingEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        val event = eventDao.getEventById(eventId)
        if (event != null && event.isOngoing()) {
            eventDao.completeEvent(eventId, System.currentTimeMillis())
            true
        } else {
            false
        }
    }
    
    suspend fun deleteEventById(eventId: Long) = withContext(Dispatchers.IO) {
        eventDao.deleteEventById(eventId)
    }
    
    suspend fun ensureDefaultEventTypes() = withContext(Dispatchers.IO) {
        // Skip if we've already ensured default types in this app session
        if (defaultEventTypesEnsured) {
            android.util.Log.d("EventRepository", "Default event types already ensured in this session")
            return@withContext
        }
        
        try {
            // Use a direct database query instead of LiveData to check existing types
            val existingCount = eventTypeDao.getEventTypeCount()
            android.util.Log.d("EventRepository", "Found $existingCount existing event types")
            
            if (existingCount == 0) {
                android.util.Log.d("EventRepository", "No event types found, creating defaults")
                
                // Create a single default event type for testing
                val defaultTypes = listOf(
                    EventType(name = "TEST", description = "Test event type for debugging")
                )
                
                defaultTypes.forEach { eventType ->
                    try {
                        insertEventType(eventType)
                        android.util.Log.d("EventRepository", "Created default event type: ${eventType.name}")
                    } catch (e: Exception) {
                        android.util.Log.e("EventRepository", "Failed to create event type: ${eventType.name}", e)
                    }
                }
            } else {
                android.util.Log.d("EventRepository", "Event types already exist, skipping default creation")
            }
        } catch (e: Exception) {
            android.util.Log.e("EventRepository", "Error in ensureDefaultEventTypes", e)
        } finally {
            // Mark as ensured regardless of success/failure to prevent repeated attempts
            defaultEventTypesEnsured = true
        }
    }
    
    suspend fun removeDuplicateEventTypes() = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("EventRepository", "Checking for duplicate event types")
            
            // We need to add a synchronous method to get all event types
            // For now, let's just log that duplicates need to be manually cleaned up
            android.util.Log.d("EventRepository", "Duplicate cleanup - please clear app data if you see duplicates")
            
        } catch (e: Exception) {
            android.util.Log.e("EventRepository", "Error in duplicate cleanup", e)
        }
    }
    
    // Helper methods
    suspend fun getOngoingEventCountForType(eventTypeId: Long): Int = withContext(Dispatchers.IO) {
        eventDao.getOngoingEvents().count { it.eventTypeId == eventTypeId }
    }
    
    // Calendar sync methods - placeholder implementations
    suspend fun getUnsyncedEvents(): List<Event> = withContext(Dispatchers.IO) {
        // For now, return empty list since we don't have calendar sync flag in Event entity
        emptyList()
    }
    
    suspend fun markEventAsSynced(eventId: Long, calendarEventId: Long) = withContext(Dispatchers.IO) {
        // Placeholder - in the future we might add calendar sync fields to Event entity
        // For now, do nothing
    }
    
    private suspend fun syncEventToCalendar(eventId: Long, calendarRepository: CalendarRepository) = withContext(Dispatchers.IO) {
        try {
            val event = eventDao.getEventById(eventId)
            val eventType = event?.let { eventTypeDao.getEventTypeById(it.eventTypeId) }
            
            if (event != null && eventType != null) {
                val calendarEventId = calendarRepository.syncEventToCalendar(
                    eventId,
                    eventType,
                    event.startTime,
                    event.endTime ?: event.startTime, // Use startTime if null (ongoing event)
                    event.notes,
                    event.photoPath
                )
                
                if (calendarEventId != null) {
                    markEventAsSynced(eventId, calendarEventId)
                }
            }
        } catch (e: Exception) {
            // Log error but don't fail the event creation
            android.util.Log.e("EventRepository", "Failed to sync event $eventId to calendar", e)
        }
    }
    
    // Compatibility methods for old TrackingViewModel interface
    fun getAllOngoingEvents(): LiveData<List<OngoingEvent>> {
        // Convert Event entities to OngoingEvent entities for backward compatibility
        return androidx.lifecycle.liveData {
            try {
                val ongoingEvents = eventDao.getOngoingEvents()
                val ongoingEventsList = ongoingEvents.map { event ->
                    OngoingEvent(
                        id = event.id,
                        eventTypeId = event.eventTypeId,
                        startTime = event.startTime,
                        notes = event.notes ?: ""
                    )
                }
                emit(ongoingEventsList)
            } catch (e: Exception) {
                android.util.Log.e("EventRepository", "Error getting ongoing events", e)
                emit(emptyList())
            }
        }
    }
    
    suspend fun startEvent(eventTypeId: Long, notes: String? = null): Long {
        val eventId = startTimedEvent(eventTypeId, notes ?: "")
        // Note: Timed events will be synced to calendar when completed, not when started
        return eventId
    }
    
    suspend fun recordInstantaneousEvent(eventTypeId: Long, calendarRepository: CalendarRepository): Long {
        val eventId = createInstantEvent(eventTypeId)
        // Sync instant event to calendar immediately
        syncEventToCalendar(eventId, calendarRepository)
        return eventId
    }
    
    suspend fun stopEvent(ongoingEventId: Long, calendarRepository: CalendarRepository): Boolean {
        val success = completeOngoingEvent(ongoingEventId)
        if (success) {
            // Sync completed event to calendar
            syncEventToCalendar(ongoingEventId, calendarRepository)
        }
        return success
    }
}
