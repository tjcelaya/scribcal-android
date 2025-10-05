package com.tjcelaya.scribcal.data

import android.accounts.Account
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import com.tjcelaya.scribcal.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EventRepository(
    private val database: ScribCalDatabase,
    private val driveRepository: DriveRepository? = null,
    private val photosRepository: PhotosRepository? = null,
    private val storagePreferences: StoragePreferences? = null
) {
    
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
        
        // Debug logging
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault())
        val currentTimeString = dateFormat.format(java.util.Date(currentTime))
        android.util.Log.d("EventRepository", "Creating instant event at: $currentTimeString (timestamp: $currentTime)")
        
        val event = Event(
            eventTypeId = eventTypeId,
            startTime = currentTime,
            endTime = currentTime, // Same time for instant events
            notes = notes,
            photoPath = photoPath
        )
        val eventId = eventDao.insertEvent(event)
        android.util.Log.d("EventRepository", "Created event with ID: $eventId")
        eventId
    }
    
    
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
    
    suspend fun createInstantEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = ""): Long {
        val uploadedPhotoPath = uploadPhotoToSelectedStorage(photoPath)
            ?: throw RuntimeException("Failed to upload photo to selected storage. Photo path: $photoPath")
            
        return createInstantEvent(eventTypeId, notes, uploadedPhotoPath)
    }
    
    suspend fun startTimedEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = ""): Long {
        val uploadedPhotoPath = uploadPhotoToSelectedStorage(photoPath)
            ?: throw RuntimeException("Failed to upload photo to selected storage. Photo path: $photoPath")
            
        return startTimedEvent(eventTypeId, notes, uploadedPhotoPath)
    }
    
    // New methods with calendar sync and Google Drive integration
    suspend fun createInstantEventWithPhotoAndSync(eventTypeId: Long, photoPath: String, notes: String = "", calendarRepository: CalendarRepository): Long = withContext(Dispatchers.IO) {
        // Check if calendar is set up
        if (!calendarRepository.isCalendarSetupComplete()) {
            throw IllegalStateException("No calendar selected. Please select a calendar in settings first.")
        }
        
        Log.d("EventRepository", "Uploading photo to selected storage: $photoPath")
        val uploadedPhotoPath = uploadPhotoToSelectedStorage(photoPath)
            ?: throw RuntimeException("Failed to upload photo to selected storage during event sync. Photo path: $photoPath")
        
        val eventId = createInstantEvent(eventTypeId, notes, uploadedPhotoPath)
        syncEventToCalendar(eventId, calendarRepository)
        eventId
    }
    
    suspend fun startTimedEventWithPhotoAndSync(eventTypeId: Long, photoPath: String, notes: String = ""): Long = withContext(Dispatchers.IO) {
        // Check for existing ongoing events of this type
        val ongoingCount = getOngoingEventCountForType(eventTypeId)
        if (ongoingCount > 0) {
            throw IllegalStateException("An event of this type is already in progress. Please stop the existing event first.")
        }
        
        Log.d("EventRepository", "Uploading photo to selected storage: $photoPath")
        val uploadedPhotoPath = uploadPhotoToSelectedStorage(photoPath)
            ?: throw RuntimeException("Failed to upload photo to selected storage during timed event sync. Photo path: $photoPath")
        
        startTimedEvent(eventTypeId, notes, uploadedPhotoPath)
    }
    
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
        eventDao.getOngoingEventCountForType(eventTypeId)
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
        // We need to create a proper LiveData that observes the database
        // Since getOngoingEvents() returns a List, not LiveData, we need to observe changes differently
        
        // Use a MediatorLiveData that observes the ongoing events Flow
        return MediatorLiveData<List<OngoingEvent>>().apply {
            val source = eventDao.getOngoingEventsWithType().asLiveData()
            addSource(source) { eventsWithType ->
                try {
                    val ongoingEventsList = eventsWithType
                        .filter { it.event.isOngoing() }
                        .map { eventWithType ->
                            OngoingEvent(
                                id = eventWithType.event.id,
                                eventTypeId = eventWithType.event.eventTypeId,
                                startTime = eventWithType.event.startTime,
                                notes = eventWithType.event.notes ?: ""
                            )
                        }
                    value = ongoingEventsList
                    android.util.Log.d("EventRepository", "Updated ongoing events: ${ongoingEventsList.size}")
                } catch (e: Exception) {
                    android.util.Log.e("EventRepository", "Error updating ongoing events", e)
                    value = emptyList()
                }
            }
        }
    }
    
    suspend fun startEvent(eventTypeId: Long, notes: String? = null): Long {
        // Check if calendar is set up before creating event
        // (We check this even for timed events since they'll need to sync when completed)
        // TODO: We could make this check optional for timed events if needed
        
        // First check if there's already an ongoing event of this type
        val ongoingCount = getOngoingEventCountForType(eventTypeId)
        if (ongoingCount > 0) {
            throw IllegalStateException("An event of this type is already in progress. Please stop the existing event first.")
        }
        
        val eventId = startTimedEvent(eventTypeId, notes ?: "")
        // Note: Timed events will be synced to calendar when completed, not when started
        return eventId
    }
    
    suspend fun recordInstantaneousEvent(eventTypeId: Long, calendarRepository: CalendarRepository): Long {
        // Check if calendar is set up before creating event
        if (!calendarRepository.isCalendarSetupComplete()) {
            throw IllegalStateException("No calendar selected. Please select a calendar in settings before creating events.")
        }
        
        val eventId = createInstantEvent(eventTypeId)
        // Sync instant event to calendar immediately
        syncEventToCalendar(eventId, calendarRepository)
        return eventId
    }
    
    suspend fun stopEvent(ongoingEventId: Long, calendarRepository: CalendarRepository): Boolean {
        // Check if calendar is set up before completing event
        if (!calendarRepository.isCalendarSetupComplete()) {
            throw IllegalStateException("No calendar selected. Please select a calendar in settings before completing events.")
        }
        
        val success = completeOngoingEvent(ongoingEventId)
        if (success) {
            // Sync completed event to calendar
            syncEventToCalendar(ongoingEventId, calendarRepository)
        }
        return success
    }
    
    // Google Drive integration methods
    
    /**
     * Upload a photo to the selected storage (Google Drive or Google Photos) and return the shareable link
     */
    private suspend fun uploadPhotoToSelectedStorage(localPhotoPath: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val selectedStorage = storagePreferences?.getPhotoStorageType()
            
            when (selectedStorage) {
                StoragePreferences.STORAGE_TYPE_GOOGLE_DRIVE -> {
                    uploadPhotoToDrive(localPhotoPath)
                }
                StoragePreferences.STORAGE_TYPE_GOOGLE_PHOTOS -> {
                    uploadPhotoToPhotos(localPhotoPath)
                }
                null -> {
                    Log.e("EventRepository", "No photo storage option selected")
                    throw IllegalStateException("No photo storage option selected. Please select Google Drive or Google Photos in settings.")
                }
                else -> {
                    Log.e("EventRepository", "Unknown storage type: $selectedStorage")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("EventRepository", "Exception uploading photo to selected storage: $localPhotoPath", e)
            null
        }
    }
    
    /**
     * Upload a photo to Google Drive and return the shareable link
     */
    private suspend fun uploadPhotoToDrive(localPhotoPath: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = driveRepository ?: return@withContext null
            
            if (!drive.isDriveReady()) {
                Log.w("EventRepository", "Drive not ready, cannot upload photo")
                return@withContext null
            }
            
            val localFile = File(localPhotoPath)
            if (!localFile.exists()) {
                Log.e("EventRepository", "Local photo file does not exist: $localPhotoPath")
                return@withContext null
            }
            
            // Generate filename with timestamp to avoid conflicts
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "scribcal_photo_${timestamp}.jpg"
            
            Log.d("EventRepository", "Uploading photo $localPhotoPath as $fileName to Google Drive")
            
            val driveLink = drive.uploadPhotoAndGetLink(localPhotoPath, fileName)
            
            if (driveLink != null) {
                Log.d("EventRepository", "Photo uploaded successfully to Drive: $driveLink")
                
                // Optionally delete local file after successful upload
                try {
                    if (localFile.delete()) {
                        Log.d("EventRepository", "Deleted local photo file: $localPhotoPath")
                    } else {
                        Log.w("EventRepository", "Could not delete local photo file: $localPhotoPath")
                    }
                } catch (e: Exception) {
                    Log.w("EventRepository", "Error deleting local photo file: $localPhotoPath", e)
                }
            } else {
                Log.e("EventRepository", "Failed to upload photo to Drive: $localPhotoPath")
            }
            
            driveLink
        } catch (e: Exception) {
            Log.e("EventRepository", "Exception uploading photo to Drive: $localPhotoPath", e)
            null
        }
    }
    
    /**
     * Upload a photo to Google Photos and return the shareable link
     */
    private suspend fun uploadPhotoToPhotos(localPhotoPath: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val photos = photosRepository ?: return@withContext null
            
            if (!photos.isPhotosReady()) {
                Log.w("EventRepository", "Photos not ready, cannot upload photo")
                return@withContext null
            }
            
            val localFile = File(localPhotoPath)
            if (!localFile.exists()) {
                Log.e("EventRepository", "Local photo file does not exist: $localPhotoPath")
                return@withContext null
            }
            
            // Generate filename with timestamp to avoid conflicts
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "scribcal_photo_${timestamp}.jpg"
            
            Log.d("EventRepository", "Uploading photo $localPhotoPath as $fileName to Google Photos")
            
            val photosLink = photos.uploadPhotoAndGetLink(localPhotoPath, fileName)
            
            if (photosLink != null) {
                Log.d("EventRepository", "Photo uploaded successfully to Photos: $photosLink")
                
                // Optionally delete local file after successful upload
                try {
                    if (localFile.delete()) {
                        Log.d("EventRepository", "Deleted local photo file: $localPhotoPath")
                    } else {
                        Log.w("EventRepository", "Could not delete local photo file: $localPhotoPath")
                    }
                } catch (e: Exception) {
                    Log.w("EventRepository", "Error deleting local photo file: $localPhotoPath", e)
                }
            } else {
                Log.e("EventRepository", "Failed to upload photo to Photos: $localPhotoPath")
            }
            
            photosLink
        } catch (e: Exception) {
            Log.e("EventRepository", "Exception uploading photo to Photos: $localPhotoPath", e)
            null
        }
    }
    
    /**
     * Initialize Google Drive with the given account
     */
    suspend fun initializeDriveForPhotos(account: Account): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = driveRepository ?: return@withContext false
            
            Log.d("EventRepository", "Initializing Drive for photos with account: ${account.name}")
            val success = drive.initializeDrive(account)
            
            if (success) {
                Log.d("EventRepository", "Drive initialized successfully for photo uploads")
            } else {
                Log.e("EventRepository", "Failed to initialize Drive for photo uploads")
            }
            
            success
        } catch (e: Exception) {
            Log.e("EventRepository", "Exception initializing Drive for photos", e)
            false
        }
    }
    
    /**
     * Check if the selected storage is available and ready for photo uploads
     */
    fun isPhotoStorageAvailable(): Boolean {
        val selectedStorage = storagePreferences?.getPhotoStorageType()
        
        return when (selectedStorage) {
            StoragePreferences.STORAGE_TYPE_GOOGLE_DRIVE -> {
                driveRepository?.isDriveReady() == true
            }
            StoragePreferences.STORAGE_TYPE_GOOGLE_PHOTOS -> {
                photosRepository?.isPhotosReady() == true
            }
            else -> false
        }
    }
    
    /**
     * Check if Drive is available and initialized for photo uploads (legacy method)
     */
    @Deprecated("Use isPhotoStorageAvailable() instead")
    fun isDriveAvailableForPhotos(): Boolean {
        return driveRepository?.isDriveInitialized() == true
    }
}
