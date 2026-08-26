package com.tjcelaya.calwrite.data

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.tjcelaya.calwrite.utils.GoogleCalendarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Service for interacting with Google Calendar REST API
 * Provides enhanced features like setting event colors
 */
class GoogleCalendarApiService(private val context: Context) {

    private var calendarService: Calendar? = null
    private var credential: GoogleAccountCredential? = null

    companion object {
        private const val TAG = "GoogleCalendarAPI"
        private val SCOPES = listOf(CalendarScopes.CALENDAR)
    }

    /**
     * Initialize the service with a Google account
     */
    suspend fun initialize(accountName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Google Calendar API for account: $accountName")
            
            credential = GoogleAccountCredential.usingOAuth2(
                context,
                SCOPES
            ).apply {
                selectedAccountName = accountName
            }

            calendarService = Calendar.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName("CalWrite")
                .build()

            Log.d(TAG, "Google Calendar API initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Google Calendar API", e)
            false
        }
    }

    /**
     * Update an existing event's color using the REST API
     * @param calendarId The calendar ID (usually the email address)
     * @param eventId The local event ID from CalendarContract
     * @param colorId Google Calendar color ID (1-11)
     */
    suspend fun updateEventColor(
        calendarId: String,
        eventId: String,
        colorId: Int?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (calendarService == null) {
                Log.e(TAG, "Calendar service not initialized")
                return@withContext false
            }

            if (colorId == null || colorId !in 1..11) {
                Log.d(TAG, "No valid color ID provided, skipping API update")
                return@withContext true // Not an error, just skip
            }

            Log.d(TAG, "Updating event $eventId color to $colorId via REST API")

            // Fetch the existing event first
            val event = calendarService!!.events()
                .get(calendarId, eventId)
                .execute()

            // Update the color
            event.colorId = colorId.toString()

            // Patch the event (only updates specified fields)
            calendarService!!.events()
                .patch(calendarId, eventId, event)
                .execute()

            Log.d(TAG, "Successfully updated event color via REST API")
            true
        } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
            Log.w(TAG, "User authentication required for Calendar API")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update event color via REST API", e)
            false
        }
    }

    /**
     * Create a new event with color using REST API
     * This is an alternative to CalendarContract insertion
     */
    suspend fun createEvent(
        calendarId: String,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        colorId: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (calendarService == null) {
                Log.e(TAG, "Calendar service not initialized")
                return@withContext null
            }

            Log.d(TAG, "Creating event '$title' with color $colorId via REST API")

            val event = Event().apply {
                summary = title
                this.description = description
                
                // Set color if provided (only standard Google Calendar colors 1-11)
                if (colorId != null && colorId in 1..11) {
                    this.colorId = colorId.toString()
                }

                // Set start time
                start = EventDateTime().apply {
                    dateTime = com.google.api.client.util.DateTime(startTime)
                    timeZone = java.util.TimeZone.getDefault().id
                }

                // Set end time
                end = EventDateTime().apply {
                    dateTime = com.google.api.client.util.DateTime(endTime)
                    timeZone = java.util.TimeZone.getDefault().id
                }
            }

            val createdEvent = calendarService!!.events()
                .insert(calendarId, event)
                .execute()

            Log.d(TAG, "Successfully created event via REST API: ${createdEvent.id}")
            createdEvent.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create event via REST API", e)
            null
        }
    }

    /**
     * Check if the service is initialized and ready
     */
    fun isInitialized(): Boolean = calendarService != null
}
