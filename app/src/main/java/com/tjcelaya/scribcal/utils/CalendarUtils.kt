package com.tjcelaya.scribcal.utils

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val isPrimary: Boolean
)

data class CalendarEventInfo(
    val id: Long,
    val title: String,
    val startTime: Long,
    val endTime: Long
)

object CalendarUtils {

    const val CALENDAR_READ_PERMISSION = Manifest.permission.READ_CALENDAR
    const val CALENDAR_WRITE_PERMISSION = Manifest.permission.WRITE_CALENDAR

    fun hasCalendarPermissions(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(context, CALENDAR_READ_PERMISSION) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, CALENDAR_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun getAvailableCalendars(context: Context): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions(context)) {
            return@withContext emptyList()
        }

        val calendars = mutableListOf<CalendarInfo>()
        val contentResolver: ContentResolver = context.contentResolver

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY
        )

        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = ?"
        val selectionArgs = arrayOf(
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString(),
            "1"
        )

        try {
            val cursor: Cursor? = contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                    val displayName = it.getString(it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)) ?: ""
                    val accountName = it.getString(it.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)) ?: ""
                    val accountType = it.getString(it.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)) ?: ""
                    val isPrimary = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)) == 1

                    calendars.add(CalendarInfo(id, displayName, accountName, accountType, isPrimary))
                }
            }
        } catch (e: SecurityException) {
            // Handle permission error
            return@withContext emptyList()
        }

        calendars
    }

    suspend fun getUpcomingEvents(context: Context, calendarId: Long, daysAhead: Int = 30): List<CalendarEventInfo> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions(context)) {
            return@withContext emptyList()
        }

        val events = mutableListOf<CalendarEventInfo>()
        val contentResolver: ContentResolver = context.contentResolver

        // Query for events starting from now to 30 days ahead
        val now = System.currentTimeMillis()
        val endTime = now + (daysAhead * 24 * 60 * 60 * 1000L)

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )

        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} != 1"
        val selectionArgs = arrayOf(
            calendarId.toString(),
            now.toString(),
            endTime.toString()
        )

        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        try {
            val cursor: Cursor? = contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events._ID))
                    val title = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) ?: "Untitled"
                    val startTime = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                    val endTime = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTEND))

                    events.add(CalendarEventInfo(id, title, startTime, endTime))
                }
            }
        } catch (e: SecurityException) {
            // Handle permission error
            return@withContext emptyList()
        }

        events
    }

    suspend fun insertEventToCalendar(
        context: Context,
        calendarId: Long,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        photoPath: String? = null,
        colorId: Int? = null,
        customColorHex: Int? = null,
        calendarAccountEmail: String? = null,
        calendarRepository: com.tjcelaya.scribcal.data.CalendarRepository? = null,
        eventType: com.tjcelaya.scribcal.data.database.EventType? = null
    ): Long? = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions(context)) {
            return@withContext null
        }

        // Debug logging to diagnose timezone issues
        val startDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault()).format(java.util.Date(startTime))
        val endDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault()).format(java.util.Date(endTime))
        android.util.Log.d("CalendarUtils", "Creating event '$title' from $startDate to $endDate")
        android.util.Log.d("CalendarUtils", "Timezone: ${java.util.TimeZone.getDefault().id}, startTime: $startTime, endTime: $endTime")

        val contentResolver = context.contentResolver

        // Format description with photo link if available
        val finalDescription = formatEventDescription(description, photoPath)

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, finalDescription)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            // For instant events (startTime == endTime), we want zero-duration events, not all-day
            // Only set as all-day if explicitly requested (which we don't do for now)
            put(CalendarContract.Events.ALL_DAY, 0)
            
            // Set event color
            when {
                GoogleCalendarColors.isCustomColor(colorId) && customColorHex != null -> {
                    // Use custom hex color directly
                    android.util.Log.d("CalendarUtils", "Setting custom color: #${Integer.toHexString(customColorHex)}")
                    put(CalendarContract.Events.EVENT_COLOR, customColorHex)
                }
                colorId != null && colorId in 1..11 -> {
                    // Use Google Calendar standard color hex value
                    val hexColor = GoogleCalendarColors.getHexColorById(colorId)
                    android.util.Log.d("CalendarUtils", "Setting Google Calendar color ID $colorId with hex: #${Integer.toHexString(hexColor ?: 0)}")
                    if (hexColor != null) {
                        put(CalendarContract.Events.EVENT_COLOR, hexColor)
                    }
                }
                else -> {
                    android.util.Log.d("CalendarUtils", "No color specified, colorId=$colorId, customColorHex=$customColorHex")
                }
            }
        }
        
        // Log the full ContentValues being sent
        android.util.Log.d("CalendarUtils", "========== INSERT REQUEST ==========")
        android.util.Log.d("CalendarUtils", "Full ContentValues:")
        for (key in values.keySet()) {
            val value = values.get(key)
            if (key == CalendarContract.Events.EVENT_COLOR) {
                android.util.Log.d("CalendarUtils", "  $key = ${if (value != null) "#${Integer.toHexString(value as Int)} (decimal: $value)" else "NULL"}")
            } else {
                android.util.Log.d("CalendarUtils", "  $key = $value")
            }
        }
        android.util.Log.d("CalendarUtils", "====================================")

        try {
            android.util.Log.d("CalendarUtils", "Calling contentResolver.insert()...")
            val uri: Uri? = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            
            android.util.Log.d("CalendarUtils", "========== INSERT RESPONSE ==========")
            android.util.Log.d("CalendarUtils", "Returned URI: $uri")
            
            val eventId = uri?.lastPathSegment?.toLongOrNull()
            if (eventId != null) {
                android.util.Log.d("CalendarUtils", "Successfully created calendar event with ID: $eventId")
                android.util.Log.d("CalendarUtils", "=====================================")
                
                // Verify what was actually stored by reading it back
                verifyEventColor(context, eventId)
                
                // If enhanced calendar is enabled, also sync via REST API
                if (calendarRepository != null && eventType != null && calendarAccountEmail != null) {
                    try {
                        val apiSuccess = calendarRepository.syncEventWithEnhancedApi(
                            calendarAccountEmail,
                            eventId.toString(),
                            eventType
                        )
                        if (apiSuccess) {
                            android.util.Log.d("CalendarUtils", "Successfully synced color via REST API")
                        } else {
                            android.util.Log.w("CalendarUtils", "Failed to sync color via REST API (may not be enabled)")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CalendarUtils", "Error syncing via REST API", e)
                    }
                }
            } else {
                android.util.Log.e("CalendarUtils", "Failed to create calendar event - no ID returned")
                android.util.Log.d("CalendarUtils", "=====================================")
            }
            return@withContext eventId
        } catch (e: SecurityException) {
            // Handle permission error
            android.util.Log.e("CalendarUtils", "SecurityException creating event", e)
            return@withContext null
        } catch (e: Exception) {
            // Handle other errors
            android.util.Log.e("CalendarUtils", "Exception creating event", e)
            return@withContext null
        }
    }

    suspend fun updateCalendarEvent(
        context: Context,
        eventId: Long,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        photoPath: String? = null,
        colorId: Int? = null,
        customColorHex: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions(context)) {
            return@withContext false
        }

        val contentResolver = context.contentResolver

        // Format description with photo link if available
        val finalDescription = formatEventDescription(description, photoPath)

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, finalDescription)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.DTEND, endTime)
            
            // Set event color
            when {
                GoogleCalendarColors.isCustomColor(colorId) && customColorHex != null -> {
                    // Use custom hex color directly
                    android.util.Log.d("CalendarUtils", "Updating with custom color: #${Integer.toHexString(customColorHex)}")
                    put(CalendarContract.Events.EVENT_COLOR, customColorHex)
                }
                colorId != null && colorId in 1..11 -> {
                    // Use Google Calendar standard color hex value
                    val hexColor = GoogleCalendarColors.getHexColorById(colorId)
                    android.util.Log.d("CalendarUtils", "Updating with Google Calendar color ID $colorId with hex: #${Integer.toHexString(hexColor ?: 0)}")
                    if (hexColor != null) {
                        put(CalendarContract.Events.EVENT_COLOR, hexColor)
                    }
                }
                else -> {
                    android.util.Log.d("CalendarUtils", "No color specified for update, colorId=$colorId, customColorHex=$customColorHex")
                }
            }
        }

        try {
            val uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString())
            val rowsUpdated = contentResolver.update(uri, values, null, null)
            return@withContext rowsUpdated > 0
        } catch (e: SecurityException) {
            return@withContext false
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun deleteCalendarEvent(context: Context, eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions(context)) {
            return@withContext false
        }

        val contentResolver = context.contentResolver

        try {
            val uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString())
            val rowsDeleted = contentResolver.delete(uri, null, null)
            return@withContext rowsDeleted > 0
        } catch (e: SecurityException) {
            return@withContext false
        } catch (e: Exception) {
            return@withContext false
        }
    }

    /**
     * Verify what color was actually stored in the calendar event
     */
    private fun verifyEventColor(context: Context, eventId: Long) {
        try {
            val contentResolver = context.contentResolver
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.EVENT_COLOR,
                CalendarContract.Events.DISPLAY_COLOR
            )
            
            val uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString())
            val cursor = contentResolver.query(uri, projection, null, null, null)
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events._ID))
                    val title = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE))
                    val eventColorIndex = it.getColumnIndex(CalendarContract.Events.EVENT_COLOR)
                    val displayColorIndex = it.getColumnIndex(CalendarContract.Events.DISPLAY_COLOR)
                    
                    val eventColor = if (!it.isNull(eventColorIndex)) {
                        it.getInt(eventColorIndex)
                    } else {
                        null
                    }
                    
                    val displayColor = if (!it.isNull(displayColorIndex)) {
                        it.getInt(displayColorIndex)
                    } else {
                        null
                    }
                    
                    android.util.Log.d("CalendarUtils", "========== VERIFICATION ==========")
                    android.util.Log.d("CalendarUtils", "Event ID: $id")
                    android.util.Log.d("CalendarUtils", "Event Title: $title")
                    android.util.Log.d("CalendarUtils", "EVENT_COLOR field: ${if (eventColor != null) "#${Integer.toHexString(eventColor)}" else "NULL"}")
                    android.util.Log.d("CalendarUtils", "DISPLAY_COLOR field: ${if (displayColor != null) "#${Integer.toHexString(displayColor)}" else "NULL"}")
                    android.util.Log.d("CalendarUtils", "=================================")
                } else {
                    android.util.Log.e("CalendarUtils", "Could not find event $eventId for verification")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CalendarUtils", "Error verifying event color", e)
        }
    }
    
    /**
     * Format event description with photo link if available
     */
    private fun formatEventDescription(description: String?, photoPath: String?): String {
        val baseDescription = description?.trim() ?: ""

        return when {
            photoPath.isNullOrEmpty() -> baseDescription
            isMultiplePhotoLinks(photoPath) -> {
                // Handle multiple photo links (formatted by EventRepository)
                val photoSection = "📷 $photoPath"  // photoPath already contains "Photos:" prefix
                if (baseDescription.isEmpty()) {
                    photoSection
                } else {
                    "$baseDescription\n\n$photoSection"
                }
            }
            isCloudStorageLink(photoPath) -> {
                // Format single cloud storage link as clickable URL
                val linkText = when {
                    isDriveLink(photoPath) -> "Google Drive"
                    isPhotosLink(photoPath) || isGoogleUserContentLink(photoPath) -> "Google Photos"
                    else -> "Cloud Storage"
                }
                val photoSection = "📷 Photo: $linkText\n$photoPath"
                if (baseDescription.isEmpty()) {
                    photoSection
                } else {
                    "$baseDescription\n\n$photoSection"
                }
            }
            else -> {
                // Local file path - indicate it's a local photo
                val photoSection = "📷 Local Photo: ${extractFileName(photoPath)}"
                if (baseDescription.isEmpty()) {
                    photoSection
                } else {
                    "$baseDescription\n\n$photoSection"
                }
            }
        }
    }

    /**
     * Check if the path contains multiple photo links (formatted by EventRepository)
     */
    private fun isMultiplePhotoLinks(path: String): Boolean {
        return path.startsWith("Photos:") && path.contains("\n")
    }

    /**
     * Check if a path is any kind of cloud storage link
     */
    private fun isCloudStorageLink(path: String): Boolean {
        return isDriveLink(path) || isPhotosLink(path) || isGoogleUserContentLink(path)
    }

    /**
     * Check if a path is a Google Drive link
     */
    private fun isDriveLink(path: String): Boolean {
        return path.startsWith("https://drive.google.com/")
    }

    /**
     * Check if a path is a Google Photos link
     */
    private fun isPhotosLink(path: String): Boolean {
        return path.startsWith("https://photos.google.com/")
    }

    /**
     * Check if a path is a Google Photos baseUrl (googleapis user content)
     */
    private fun isGoogleUserContentLink(path: String): Boolean {
        return path.startsWith("https://lh") && path.contains("googleusercontent.com")
    }

    /**
     * Extract filename from a file path
     */
    private fun extractFileName(path: String): String {
        return path.substringAfterLast('/')
    }
}
