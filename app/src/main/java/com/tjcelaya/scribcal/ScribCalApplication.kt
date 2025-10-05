package com.tjcelaya.scribcal

import android.accounts.Account
import android.app.Application
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.DriveRepository
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.PhotosRepository
import com.tjcelaya.scribcal.data.StoragePreferences
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScribCalApplication : Application() {

    // Application-level coroutine scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Repositories
    lateinit var database: ScribCalDatabase
        private set
    lateinit var calendarRepository: CalendarRepository
        private set
    lateinit var driveRepository: DriveRepository
        private set
    lateinit var photosRepository: PhotosRepository
        private set
    lateinit var storagePreferences: StoragePreferences
        private set
    lateinit var eventRepository: EventRepository
        private set

    companion object {
        private const val TAG = "ScribCalApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScribCal Application starting...")

        // Initialize repositories
        initializeRepositories()

        // Initialize Google services on app startup
        initializeGoogleServicesAsync()
    }

    private fun initializeRepositories() {
        database = ScribCalDatabase.getDatabase(this)
        calendarRepository = CalendarRepository(this)
        driveRepository = DriveRepository(this)
        photosRepository = PhotosRepository(this, database)
        storagePreferences = StoragePreferences(this)
        eventRepository = EventRepository(database, driveRepository, photosRepository, storagePreferences)
        
        Log.d(TAG, "Repositories initialized")
    }

    private fun initializeGoogleServicesAsync() {
        applicationScope.launch {
            try {
                Log.d(TAG, "Starting Google services initialization...")
                
                // Get Google account from calendar setup
                val account = getGoogleAccountForServices()
                
                if (account != null) {
                    Log.d(TAG, "Found Google account: ${account.name}, initializing services...")
                    
                    // Initialize Drive
                    val driveSuccess = eventRepository.initializeDriveForPhotos(account)
                    if (driveSuccess) {
                        Log.d(TAG, "Google Drive initialized successfully")
                    } else {
                        Log.w(TAG, "Google Drive initialization failed")
                    }
                    
                    // Initialize Photos
                    val photosSuccess = photosRepository.initializePhotos(account)
                    if (photosSuccess) {
                        Log.d(TAG, "Google Photos initialized successfully")
                    } else {
                        Log.w(TAG, "Google Photos initialization failed")
                    }
                    
                } else {
                    Log.d(TAG, "No Google account found for services initialization")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception during Google services initialization", e)
            }
        }
    }

    private suspend fun getGoogleAccountForServices(): Account? {
        return try {
            val calendars = calendarRepository.getAvailableCalendars()
            val selectedCalendarId = calendarRepository.getSelectedCalendarId()
            
            Log.d(TAG, "Found ${calendars.size} calendars, selected ID: $selectedCalendarId")
            
            // Try to use the selected calendar's account first
            val selectedCalendar = calendars.find { it.id == selectedCalendarId }
            
            if (selectedCalendar != null && selectedCalendar.accountName.isNotEmpty()) {
                Log.d(TAG, "Using selected calendar account: ${selectedCalendar.accountName}")
                Account(selectedCalendar.accountName, selectedCalendar.accountType)
            } else {
                // Fallback to any Google account
                val googleCalendars = calendars.filter { 
                    it.accountType == "com.google" && it.accountName.isNotEmpty() 
                }
                
                if (googleCalendars.isNotEmpty()) {
                    Log.d(TAG, "Using first Google calendar account: ${googleCalendars[0].accountName}")
                    Account(googleCalendars[0].accountName, googleCalendars[0].accountType)
                } else {
                    Log.w(TAG, "No Google accounts found in calendars")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Google account for services", e)
            null
        }
    }

    /**
     * Check if Google Drive is ready for photo operations
     */
    fun isDriveReady(): Boolean {
        return driveRepository.isDriveInitialized()
    }

}