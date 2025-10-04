package com.tjcelaya.scribcal.data

import android.content.Context
import android.content.SharedPreferences
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.utils.CalendarInfo
import com.tjcelaya.scribcal.utils.CalendarUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CalendarRepository(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("scribcal_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_SELECTED_CALENDAR_ID = "selected_calendar_id"
        private const val KEY_CALENDAR_SETUP_COMPLETE = "calendar_setup_complete"
        private const val KEY_SELECTED_CALENDAR_NAME = "selected_calendar_name"
    }
    
    fun hasCalendarPermissions(): Boolean {
        return CalendarUtils.hasCalendarPermissions(context)
    }
    
    suspend fun getAvailableCalendars(): List<CalendarInfo> {
        return CalendarUtils.getAvailableCalendars(context)
    }
    
    fun getSelectedCalendarId(): Long? {
        val calendarId = prefs.getLong(KEY_SELECTED_CALENDAR_ID, -1L)
        return if (calendarId == -1L) null else calendarId
    }
    
    fun getSelectedCalendarName(): String? {
        return prefs.getString(KEY_SELECTED_CALENDAR_NAME, null)
    }
    
    fun setSelectedCalendar(calendarInfo: CalendarInfo) {
        prefs.edit()
            .putLong(KEY_SELECTED_CALENDAR_ID, calendarInfo.id)
            .putString(KEY_SELECTED_CALENDAR_NAME, calendarInfo.displayName)
            .putBoolean(KEY_CALENDAR_SETUP_COMPLETE, true)
            .apply()
    }
    
    fun isCalendarSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_CALENDAR_SETUP_COMPLETE, false) && getSelectedCalendarId() != null
    }
    
    fun clearCalendarSelection() {
        prefs.edit()
            .remove(KEY_SELECTED_CALENDAR_ID)
            .remove(KEY_SELECTED_CALENDAR_NAME)
            .putBoolean(KEY_CALENDAR_SETUP_COMPLETE, false)
            .apply()
    }
    
    suspend fun syncEventToCalendar(
        eventId: Long,
        eventType: EventType,
        startTime: Long,
        endTime: Long,
        notes: String?,
        photoPath: String? = null
    ): Long? = withContext(Dispatchers.IO) {
        val calendarId = getSelectedCalendarId() ?: return@withContext null
        
        if (!hasCalendarPermissions()) {
            return@withContext null
        }
        
        val title = eventType.name
        val description = buildString {
            if (!notes.isNullOrBlank()) {
                append("Notes: $notes")
            }
            if (!eventType.description.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Event Type: ${eventType.description}")
            }
        }.takeIf { it.isNotBlank() }
        
        return@withContext CalendarUtils.insertEventToCalendar(
            context,
            calendarId,
            title,
            startTime,
            endTime,
            description,
            photoPath
        )
    }
    
    suspend fun updateCalendarEvent(
        calendarEventId: Long,
        eventType: EventType,
        startTime: Long,
        endTime: Long,
        notes: String?,
        photoPath: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions()) {
            return@withContext false
        }
        
        val title = eventType.name
        val description = buildString {
            if (!notes.isNullOrBlank()) {
                append("Notes: $notes")
            }
            if (!eventType.description.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Event Type: ${eventType.description}")
            }
        }.takeIf { it.isNotBlank() }
        
        return@withContext CalendarUtils.updateCalendarEvent(
            context,
            calendarEventId,
            title,
            startTime,
            endTime,
            description,
            photoPath
        )
    }
    
    suspend fun deleteCalendarEvent(calendarEventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions()) {
            return@withContext false
        }
        
        return@withContext CalendarUtils.deleteCalendarEvent(context, calendarEventId)
    }
    
    /**
     * Retry syncing events that failed to sync previously
     */
    suspend fun retrySyncingUnsyncedEvents(
        eventRepository: EventRepository
    ): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions() || getSelectedCalendarId() == null) {
            return@withContext 0
        }
        
        val unsyncedEvents = eventRepository.getUnsyncedEvents()
        var syncedCount = 0
        
        for (event in unsyncedEvents) {
            val eventType = eventRepository.getEventTypeById(event.eventTypeId)
            if (eventType != null) {
                val calendarEventId = syncEventToCalendar(
                    event.id,
                    eventType,
                    event.startTime,
                    event.endTime ?: event.startTime, // Use startTime if endTime is null (ongoing event)
                    event.notes,
                    event.photoPath
                )
                
                if (calendarEventId != null) {
                    eventRepository.markEventAsSynced(event.id, calendarEventId)
                    syncedCount++
                }
            }
        }
        
        return@withContext syncedCount
    }
}
