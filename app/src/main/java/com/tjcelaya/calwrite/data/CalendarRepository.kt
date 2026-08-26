package com.tjcelaya.calwrite.data

import android.content.Context
import android.content.SharedPreferences
import com.tjcelaya.calwrite.data.database.EventType
import com.tjcelaya.calwrite.utils.CalendarInfo
import com.tjcelaya.calwrite.utils.CalendarEventInfo
import com.tjcelaya.calwrite.utils.CalendarUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CalendarRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("calwrite_prefs", Context.MODE_PRIVATE)

    private val googleCalendarApiService = GoogleCalendarApiService(context)

    companion object {
        private const val KEY_SELECTED_CALENDAR_ID = "selected_calendar_id"
        private const val KEY_CALENDAR_SETUP_COMPLETE = "calendar_setup_complete"
        private const val KEY_SELECTED_CALENDAR_NAME = "selected_calendar_name"
        private const val KEY_SELECTED_CALENDAR_ACCOUNT = "selected_calendar_account"
        private const val KEY_ENHANCED_CALENDAR_ENABLED = "enhanced_calendar_enabled"
    }

    fun hasCalendarPermissions(): Boolean {
        return CalendarUtils.hasCalendarPermissions(context)
    }

    suspend fun getAvailableCalendars(): List<CalendarInfo> {
        return CalendarUtils.getAvailableCalendars(context)
    }

    suspend fun getUpcomingEvents(daysAhead: Int = 30): List<CalendarEventInfo> {
        val calendarId = getSelectedCalendarId() ?: return emptyList()
        return CalendarUtils.getUpcomingEvents(context, calendarId, daysAhead)
    }

    /**
     * Most recent past occurrence (DTSTART) of an event matching [title] in the selected
     * calendar, or null if none / no calendar selected. Used to seed last-occurrence on import.
     */
    suspend fun getLastOccurrence(title: String): Long? {
        val calendarId = getSelectedCalendarId() ?: return null
        return CalendarUtils.getLastOccurrence(context, calendarId, title)
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
            .putString(KEY_SELECTED_CALENDAR_ACCOUNT, calendarInfo.accountName)
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

    /**
     * Restore a previously-exported calendar selection from raw values (used by config import).
     * Calendar ids are device-specific, so this is best-effort.
     */
    fun restoreSelection(id: Long?, name: String?, account: String?, setupComplete: Boolean) {
        val editor = prefs.edit()
        if (id != null) {
            editor.putLong(KEY_SELECTED_CALENDAR_ID, id)
        } else {
            editor.remove(KEY_SELECTED_CALENDAR_ID)
        }
        editor.putString(KEY_SELECTED_CALENDAR_NAME, name)
        editor.putString(KEY_SELECTED_CALENDAR_ACCOUNT, account)
        editor.putBoolean(KEY_CALENDAR_SETUP_COMPLETE, setupComplete && id != null)
        editor.apply()
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
            photoPath,
            eventType.colorId,
            getSelectedCalendarAccount(),
            this@CalendarRepository,
            eventType
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
            photoPath,
            eventType.colorId
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
    
    // Enhanced Calendar Integration methods
    
    fun isEnhancedCalendarEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENHANCED_CALENDAR_ENABLED, false)
    }
    
    fun setEnhancedCalendarEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENHANCED_CALENDAR_ENABLED, enabled)
            .apply()
    }
    
    fun getSelectedCalendarAccount(): String? {
        return prefs.getString(KEY_SELECTED_CALENDAR_ACCOUNT, null)
    }
    
    suspend fun initializeEnhancedCalendar(): Boolean = withContext(Dispatchers.IO) {
        val accountName = getSelectedCalendarAccount()
        if (accountName == null) {
            android.util.Log.e("CalendarRepository", "No calendar account found")
            return@withContext false
        }
        
        return@withContext googleCalendarApiService.initialize(accountName)
    }
    
    suspend fun syncEventWithEnhancedApi(
        calendarAccountEmail: String,
        localEventId: String,
        eventType: EventType
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isEnhancedCalendarEnabled()) {
            return@withContext false
        }
        
        if (!googleCalendarApiService.isInitialized()) {
            val success = initializeEnhancedCalendar()
            if (!success) return@withContext false
        }
        
        // Only use REST API for standard Google Calendar colors (1-11)
        // Custom colors aren't supported by the colorId field
        return@withContext if (eventType.colorId != null && eventType.colorId in 1..11) {
            googleCalendarApiService.updateEventColor(
                calendarAccountEmail,
                localEventId,
                eventType.colorId
            )
        } else {
            true // Not an error, just skip API sync for custom colors
        }
    }
}
