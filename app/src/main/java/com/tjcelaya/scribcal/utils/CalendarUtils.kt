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
    
    suspend fun insertEventToCalendar(
        context: Context,
        calendarId: Long,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null
    ): Long? = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions(context)) {
            return@withContext null
        }
        
        val contentResolver = context.contentResolver
        
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description ?: "")
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (startTime == endTime) 1 else 0)
        }
        
        try {
            val uri: Uri? = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            return@withContext uri?.lastPathSegment?.toLongOrNull()
        } catch (e: SecurityException) {
            // Handle permission error
            return@withContext null
        } catch (e: Exception) {
            // Handle other errors
            return@withContext null
        }
    }
    
    suspend fun updateCalendarEvent(
        context: Context,
        eventId: Long,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions(context)) {
            return@withContext false
        }
        
        val contentResolver = context.contentResolver
        
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description ?: "")
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
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
}
