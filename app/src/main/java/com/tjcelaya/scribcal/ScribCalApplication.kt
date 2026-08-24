package com.tjcelaya.scribcal

import android.accounts.Account
import android.app.Application
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.ConfigBackupManager
import com.tjcelaya.scribcal.data.DriveRepository
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.PhotosRepository
import com.tjcelaya.scribcal.data.StoragePreferences
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.ui.notifications.NotificationService
import com.tjcelaya.scribcal.ui.voice.VoiceShortcutPublisher
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
    lateinit var notificationService: NotificationService
        private set
    lateinit var configBackupManager: ConfigBackupManager
        private set
    lateinit var voiceShortcutPublisher: VoiceShortcutPublisher
        private set

    companion object {
        private const val TAG = "ScribCalApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScribCal Application starting...")

        // Initialize repositories
        initializeRepositories()

        // Note: Google services initialization moved to on-demand in settings
        // This prevents UserRecoverableAuthException on app startup

        // Load persisted album configuration
        applicationScope.launch {
            try {
                photosRepository.initializeAsyncData()
                Log.d(TAG, "Photos repository async data initialized")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize photos repository async data", e)
            }
        }

        // Voice shortcuts mirror the user's event types, and those are edited from several
        // screens; observing the list is the single hook that catches every one of those paths
        // as well as the initial publish at startup.
        eventRepository.getAllEventTypes().observeForever {
            applicationScope.launch { voiceShortcutPublisher.publish() }
        }
    }

    private fun initializeRepositories() {
        database = ScribCalDatabase.getDatabase(this)
        calendarRepository = CalendarRepository(this)
        driveRepository = DriveRepository(this)
        photosRepository = PhotosRepository(this, database)
        storagePreferences = StoragePreferences(this)
        notificationService = NotificationService(this, storagePreferences)
        eventRepository = EventRepository(database, driveRepository, photosRepository, storagePreferences, notificationService)
        configBackupManager = ConfigBackupManager(database, storagePreferences, calendarRepository, driveRepository)
        voiceShortcutPublisher = VoiceShortcutPublisher(this, eventRepository)

        Log.d(TAG, "Repositories initialized")
    }

    // Removed automatic Google services initialization to prevent startup exceptions
    // Services are now initialized on-demand when users configure them in settings

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