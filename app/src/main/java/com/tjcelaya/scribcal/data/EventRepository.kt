package com.tjcelaya.scribcal.data

import android.accounts.Account
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import com.tjcelaya.scribcal.data.database.*
import com.tjcelaya.scribcal.ui.tracking.PhotoUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class EventRepository(
    private val database: ScribCalDatabase,
    private val driveRepository: DriveRepository? = null,
    private val photosRepository: PhotosRepository? = null,
    private val storagePreferences: StoragePreferences? = null
) {
    
    private val eventTypeDao = database.eventTypeDao()
    private val eventDao = database.eventDao()
    private val photoUploadProgressDao = database.photoUploadProgressDao()
    
    // Flag to ensure default event types are only created once per app session
    private var defaultEventTypesEnsured = false
    
    // Photo upload tracking
    private val activeUploads = ConcurrentHashMap<String, PhotoUpload>()
    private val _photoUploads = MutableLiveData<List<PhotoUpload>>(emptyList())
    val photoUploads: LiveData<List<PhotoUpload>> = _photoUploads
    
    // Photo upload tracking methods for UI display
    private fun startPhotoUploadTracking(eventTypeId: Long, fileName: String): String {
        val uploadId = UUID.randomUUID().toString()
        val photoUpload = PhotoUpload(
            id = uploadId,
            eventTypeId = eventTypeId,
            fileName = fileName,
            startTime = System.currentTimeMillis(),
            progress = 0,
            status = "Preparing upload..."
        )
        
        activeUploads[uploadId] = photoUpload
        updatePhotoUploadsLiveData()
        
        return uploadId
    }
    
    private fun updatePhotoUploadProgressTracking(uploadId: String, progress: Int, status: String) {
        activeUploads[uploadId]?.let { upload ->
            val updatedUpload = upload.copy(progress = progress, status = status)
            activeUploads[uploadId] = updatedUpload
            updatePhotoUploadsLiveData()
        }
    }
    
    private fun completePhotoUploadTracking(uploadId: String) {
        activeUploads.remove(uploadId)
        updatePhotoUploadsLiveData()
    }
    
    private fun updatePhotoUploadsLiveData() {
        _photoUploads.postValue(activeUploads.values.toList())
    }
    
    // Event Type operations
    fun getAllEventTypes(): LiveData<List<EventType>> = eventTypeDao.getAllEventTypes()
    
    fun getFavoriteEventTypes(): Flow<List<EventType>> {
        // For now, return empty list. In future, can add favorite functionality
        return flowOf(emptyList())
    }
    
    suspend fun getEventTypeById(id: Long): EventType? = withContext(Dispatchers.IO) {
        eventTypeDao.getEventTypeById(id)
    }
    
    suspend fun getAllEventTypesSync(): List<EventType> = withContext(Dispatchers.IO) {
        // Get all event types synchronously by converting LiveData to a one-time fetch
        // This is a simple solution - in a real app you might want to use Flow instead
        eventTypeDao.getAllEventTypesSync()
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
    suspend fun createInstantEvent(eventTypeId: Long, notes: String = "", photoPath: String? = null, timestamp: Long? = null): Long = withContext(Dispatchers.IO) {
        val eventTime = timestamp ?: System.currentTimeMillis()
        
        // Debug logging
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        val eventTimeString = dateFormat.format(Date(eventTime))
        Log.d("EventRepository", "Creating instant event at: $eventTimeString (timestamp: $eventTime)")
        
        val event = Event(
            eventTypeId = eventTypeId,
            startTime = eventTime,
            endTime = eventTime, // Same time for instant events
            notes = notes,
            photoPath = photoPath
        )
        val eventId = eventDao.insertEvent(event)
        Log.d("EventRepository", "Created event with ID: $eventId")
        eventId
    }
    
    
    suspend fun startTimedEvent(eventTypeId: Long, notes: String = "", photoPath: String? = null, timestamp: Long? = null): Long = withContext(Dispatchers.IO) {
        val startTime = timestamp ?: System.currentTimeMillis()
        val event = Event(
            eventTypeId = eventTypeId,
            startTime = startTime,
            endTime = null, // Null indicates ongoing event
            notes = notes,
            photoPath = photoPath
        )
        eventDao.insertEvent(event)
    }
    
    suspend fun createInstantEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String = ""): Long {
        // Create the event first so we have an ID for progress tracking
        val eventId = createInstantEvent(eventTypeId, notes, null) // Start without photo
        
        // Now upload the photo with progress tracking
        val uploadedPhotoPath = uploadPhotoToSelectedStorage(photoPath, eventId)
            ?: throw RuntimeException("Failed to upload photo to selected storage. Photo path: $photoPath")
        
        // Update the event with the photo path
        val event = eventDao.getEventById(eventId)
        if (event != null) {
            val updatedEvent = event.copy(photoPath = uploadedPhotoPath)
            eventDao.updateEvent(updatedEvent)
        }
            
        return eventId
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
            Log.d("EventRepository", "Default event types check already completed in this session")
            return@withContext
        }
        
        try {
            // Use a direct database query instead of LiveData to check existing types
            val existingCount = eventTypeDao.getEventTypeCount()
            Log.d("EventRepository", "Found $existingCount existing event types")
            
            // No longer creating default event types - users will create their own via the UI
            if (existingCount == 0) {
                Log.d("EventRepository", "No event types found - users can create them via the Add Event form")
            } else {
                Log.d("EventRepository", "Event types already exist")
            }
        } catch (e: Exception) {
            Log.e("EventRepository", "Error in ensureDefaultEventTypes", e)
        } finally {
            // Mark as ensured regardless of success/failure to prevent repeated attempts
            defaultEventTypesEnsured = true
        }
    }
    
    suspend fun removeDuplicateEventTypes() = withContext(Dispatchers.IO) {
        try {
            Log.d("EventRepository", "Checking for duplicate event types")
            
            // We need to add a synchronous method to get all event types
            // For now, let's just log that duplicates need to be manually cleaned up
            Log.d("EventRepository", "Duplicate cleanup - please clear app data if you see duplicates")
            
        } catch (e: Exception) {
            Log.e("EventRepository", "Error in duplicate cleanup", e)
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
    
    suspend fun syncEventToCalendar(eventId: Long, calendarRepository: CalendarRepository) = withContext(Dispatchers.IO) {
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
            Log.e("EventRepository", "Failed to sync event $eventId to calendar", e)
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
                    Log.d("EventRepository", "Updated ongoing events: ${ongoingEventsList.size}")
                } catch (e: Exception) {
                    Log.e("EventRepository", "Error updating ongoing events", e)
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
     * Upload a photo to the enabled storage options (Google Drive and/or Google Photos) and return a combined link
     */
    private suspend fun uploadPhotoToSelectedStorage(localPhotoPath: String, eventId: Long? = null): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val isDriveEnabled = storagePreferences?.isGoogleDriveEnabled() ?: false
            val isPhotosEnabled = storagePreferences?.isGooglePhotosEnabled() ?: false
            
            if (!isDriveEnabled && !isPhotosEnabled) {
                val errorMsg = "No photo storage option selected. Please select at least one storage option in settings."
                if (eventId != null) {
                    markPhotoUploadFailed(eventId, errorMsg)
                }
                Log.e("EventRepository", errorMsg)
                throw IllegalStateException(errorMsg)
            }
            
            // Extract filename for progress tracking
            val fileName = File(localPhotoPath).name
            var uploadId: String? = null
            
            // Start progress tracking if eventId is provided
            if (eventId != null) {
                // We need to get the eventTypeId from the eventId
                val event = eventDao.getEventById(eventId)
                val eventTypeIdForUpload = event?.eventTypeId ?: 0L
                uploadId = startPhotoUploadTracking(eventTypeIdForUpload, fileName)
                startPhotoUpload(eventId, fileName) // For database tracking
                markPhotoUploadStarted(eventId)
            }
            
            val results = mutableListOf<PhotoUploadResult>()
            
            // Upload to Google Drive if enabled
            if (isDriveEnabled) {
                uploadId?.let { updatePhotoUploadProgressTracking(it, 10, "Uploading to Google Drive...") }
                Log.d("EventRepository", "Uploading photo to Google Drive: $localPhotoPath")
                val driveLink = uploadPhotoToDrive(localPhotoPath, eventId)
                if (driveLink != null) {
                    results.add(PhotoUploadResult(service = "Google Drive", link = driveLink))
                    uploadId?.let { updatePhotoUploadProgressTracking(it, 50, "Drive upload complete") }
                    Log.d("EventRepository", "Successfully uploaded to Google Drive: $driveLink")
                } else {
                    Log.w("EventRepository", "Failed to upload photo to Google Drive")
                }
            }
            
            // Upload to Google Photos if enabled
            if (isPhotosEnabled) {
                val baseProgress = if (isDriveEnabled) 50 else 10
                uploadId?.let { updatePhotoUploadProgressTracking(it, baseProgress + 10, "Uploading to Google Photos...") }
                Log.d("EventRepository", "Uploading photo to Google Photos: $localPhotoPath")
                val photosLink = uploadPhotoToPhotos(localPhotoPath, eventId)
                if (photosLink != null) {
                    results.add(PhotoUploadResult(service = "Google Photos", link = photosLink))
                    uploadId?.let { updatePhotoUploadProgressTracking(it, baseProgress + 40, "Photos upload complete") }
                    Log.d("EventRepository", "Successfully uploaded to Google Photos: $photosLink")
                } else {
                    Log.w("EventRepository", "Failed to upload photo to Google Photos")
                    
                    // Check if this was due to album access issues
                    val photosHealthy = photosRepository?.isHealthy() ?: false
                    if (!photosHealthy) {
                        Log.e("EventRepository", "Google Photos upload failed - service is no longer healthy (likely album access lost)")
                        // Don't throw here, let the combined result handling decide what to do
                        if (eventId != null) {
                            markPhotoUploadFailed(eventId, "Google Photos access lost. Please reconnect in Settings.")
                        }
                    }
                }
            }
            
            // Generate combined result
            val combinedResult = if (results.isNotEmpty()) {
                formatMultiplePhotoLinks(results)
            } else {
                null
            }
            
            // Mark as completed or failed based on result
            if (eventId != null) {
                if (combinedResult != null) {
                    uploadId?.let { 
                        updatePhotoUploadProgressTracking(it, 100, "Upload complete!")
                        completePhotoUploadTracking(it)
                    }
                    markPhotoUploadCompleted(eventId, combinedResult)
                    Log.d("EventRepository", "Photo upload completed with ${results.size} successful uploads")
                } else {
                    // Provide more specific error message based on what services were enabled vs healthy
                    val errorMsg = when {
                        isDriveEnabled && isPhotosEnabled -> {
                            val driveHealthy = driveRepository?.isHealthy() ?: false
                            val photosHealthy = photosRepository?.isHealthy() ?: false
                            when {
                                !driveHealthy && !photosHealthy -> "Both Google Drive and Photos access lost. Please reconnect both in Settings."
                                !driveHealthy -> "Google Drive access lost. Google Photos may also have issues. Please check Settings."
                                !photosHealthy -> "Google Photos access lost. Google Drive may also have issues. Please check Settings."
                                else -> "All photo uploads failed due to unknown error"
                            }
                        }
                        isDriveEnabled -> "Google Drive upload failed. Please check connection in Settings."
                        isPhotosEnabled -> "Google Photos upload failed. Please check connection in Settings."
                        else -> "No photo storage configured"
                    }
                    uploadId?.let { completePhotoUploadTracking(it) }
                    markPhotoUploadFailed(eventId, errorMsg)
                }
            }
            
            // If no uploads succeeded and it's due to access issues, throw informative exception
            if (combinedResult == null && (isDriveEnabled || isPhotosEnabled)) {
                val driveHealthy = if (isDriveEnabled) driveRepository?.isHealthy() ?: false else true
                val photosHealthy = if (isPhotosEnabled) photosRepository?.isHealthy() ?: false else true
                
                if (!driveHealthy || !photosHealthy) {
                    val accessErrorMsg = when {
                        isDriveEnabled && isPhotosEnabled && !driveHealthy && !photosHealthy -> 
                            "Both Google Drive and Google Photos access lost. Please go to Settings and reconnect both services."
                        isDriveEnabled && !driveHealthy -> 
                            "Google Drive access lost. Please go to Settings and reconnect Google Drive."
                        isPhotosEnabled && !photosHealthy -> 
                            "Google Photos access lost. Please go to Settings and reconnect Google Photos."
                        else -> 
                            "Photo storage access lost. Please check Settings."
                    }
                    throw IllegalStateException(accessErrorMsg)
                }
            }
            
            combinedResult
        } catch (e: Exception) {
            if (eventId != null) {
                markPhotoUploadFailed(eventId, "Exception: ${e.message}")
            }
            Log.e("EventRepository", "Exception uploading photo to selected storage: $localPhotoPath", e)
            throw e // Re-throw to preserve the exception for the caller
        }
    }
    
    /**
     * Upload a photo to Google Drive and return the shareable link
     */
    private suspend fun uploadPhotoToDrive(localPhotoPath: String, eventId: Long? = null): String? = withContext(Dispatchers.IO) {
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
            
            // Generate filename with event type name and timestamp
            val eventTypeName = getEventTypeNameForFilename(eventId)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "scribcal_${eventTypeName}_${timestamp}.jpg"
            
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
    private suspend fun uploadPhotoToPhotos(localPhotoPath: String, eventId: Long? = null): String? = withContext(Dispatchers.IO) {
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
            
            // Generate filename with event type name and timestamp
            val eventTypeName = getEventTypeNameForFilename(eventId)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "scribcal_${eventTypeName}_${timestamp}.jpg"
            
            Log.d("EventRepository", "Uploading photo $localPhotoPath as $fileName to Google Photos")
            
            // Create progress callback
            val progressCallback: ((Int) -> Unit)? = if (eventId != null) {
                { percent -> 
                    kotlinx.coroutines.runBlocking {
                        updatePhotoUploadProgress(eventId, percent)
                    }
                }
            } else null
            
            val photosLink = photos.uploadPhotoAndGetLink(localPhotoPath, fileName, progressCallback)
            
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
     * Check if any photo storage is available and healthy for photo uploads
     */
    fun isPhotoStorageAvailable(): Boolean {
        val isDriveEnabled = storagePreferences?.isGoogleDriveEnabled() ?: false
        val isPhotosEnabled = storagePreferences?.isGooglePhotosEnabled() ?: false
        
        // Use unified health check methods
        val isDriveHealthy = driveRepository?.isHealthy() == true
        val isPhotosHealthy = photosRepository?.isHealthy() == true
        
        return (isDriveEnabled && isDriveHealthy) || (isPhotosEnabled && isPhotosHealthy)
    }
    
    /**
     * Check if Drive is available and initialized for photo uploads (legacy method)
     */
    @Deprecated("Use isPhotoStorageAvailable() instead")
    fun isDriveAvailableForPhotos(): Boolean {
        return driveRepository?.isDriveInitialized() == true
    }
    
    // Photo Upload Progress Methods
    
    /**
     * Start tracking photo upload progress for an event
     */
    suspend fun startPhotoUpload(eventId: Long, fileName: String) = withContext(Dispatchers.IO) {
        val progress = PhotoUploadProgress(
            eventId = eventId,
            fileName = fileName,
            status = PhotoUploadStatus.PREPARING,
            progressPercent = 0
        )
        photoUploadProgressDao.insertOrUpdateUploadProgress(progress)
        Log.d("EventRepository", "Started tracking photo upload for event $eventId: $fileName")
    }
    
    /**
     * Update photo upload progress percentage
     */
    suspend fun updatePhotoUploadProgress(eventId: Long, percent: Int) = withContext(Dispatchers.IO) {
        photoUploadProgressDao.updateUploadPercentage(eventId, percent)
        Log.d("EventRepository", "Updated photo upload progress for event $eventId: $percent%")
    }
    
    /**
     * Mark photo upload as uploading
     */
    suspend fun markPhotoUploadStarted(eventId: Long) = withContext(Dispatchers.IO) {
        val existing = photoUploadProgressDao.getUploadProgressForEvent(eventId)
        if (existing != null) {
            val updated = existing.copy(status = PhotoUploadStatus.UPLOADING, progressPercent = 5)
            photoUploadProgressDao.updateUploadProgress(updated)
            Log.d("EventRepository", "Marked photo upload as started for event $eventId")
        }
    }
    
    /**
     * Mark photo upload as completed successfully
     */
    suspend fun markPhotoUploadCompleted(eventId: Long, photoUrl: String) = withContext(Dispatchers.IO) {
        val completedTime = System.currentTimeMillis()
        photoUploadProgressDao.markUploadCompleted(
            eventId = eventId,
            status = PhotoUploadStatus.COMPLETED,
            completedTime = completedTime,
            photoUrl = photoUrl
        )
        Log.d("EventRepository", "Marked photo upload as completed for event $eventId")
    }
    
    /**
     * Mark photo upload as failed
     */
    suspend fun markPhotoUploadFailed(eventId: Long, errorMessage: String) = withContext(Dispatchers.IO) {
        val completedTime = System.currentTimeMillis()
        photoUploadProgressDao.markUploadFailed(
            eventId = eventId,
            status = PhotoUploadStatus.FAILED,
            completedTime = completedTime,
            errorMessage = errorMessage
        )
        Log.d("EventRepository", "Marked photo upload as failed for event $eventId: $errorMessage")
    }
    
    /**
     * Get ongoing events with photo upload progress
     */
    fun getOngoingEventsWithUploadProgress(): Flow<List<OngoingEventWithProgress>> {
        return database.eventDao().getOngoingEventsWithType().map { eventsWithType ->
            val ongoingEvents = eventsWithType.filter { it.event.isOngoing() }
            
            // Get upload progress for all events
            val uploadProgressList = photoUploadProgressDao.getAllUploadProgress().first()
            val uploadProgressMap = uploadProgressList.associateBy { it.eventId }
            
            // Combine ongoing events with their upload progress
            val ongoingWithProgress = ongoingEvents.map { eventWithType ->
                OngoingEventWithProgress.fromEvent(
                    event = eventWithType.event,
                    uploadProgress = uploadProgressMap[eventWithType.event.id]
                )
            }
            
            // Add completed/failed uploads that should still be shown (not auto-removed yet)
            val recentCompletedUploads = uploadProgressList
                .filter { !it.shouldAutoRemove() && it.isCompleted() || it.isFailed() }
                .filter { progress -> ongoingEvents.none { it.event.id == progress.eventId } }
                .mapNotNull { progress ->
                    // Get the completed event
                    val event = eventDao.getEventById(progress.eventId)
                    if (event != null) {
                        OngoingEventWithProgress.fromEvent(event, progress)
                    } else null
                }
            
            // Combine and sort by start time (most recent first)
            (ongoingWithProgress + recentCompletedUploads)
                .sortedByDescending { it.startTime }
        }
    }
    
    /**
     * Clean up expired upload progress entries
     */
    suspend fun cleanupExpiredUploads() = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - PhotoUploadProgress.AUTO_REMOVE_DELAY_MS
        photoUploadProgressDao.deleteExpiredCompletedUploads(cutoffTime)
        Log.d("EventRepository", "Cleaned up expired upload progress entries")
    }
    
    /**
     * Get event type name for use in filename, with safe formatting
     */
    private suspend fun getEventTypeNameForFilename(eventId: Long?): String = withContext(Dispatchers.IO) {
        try {
            if (eventId == null) {
                return@withContext "unknown"
            }
            
            val event = eventDao.getEventById(eventId)
            if (event == null) {
                return@withContext "unknown"
            }
            
            val eventType = eventTypeDao.getEventTypeById(event.eventTypeId)
            if (eventType == null) {
                return@withContext "unknown"
            }
            
            // Clean the name for use in filename: remove special characters and convert to lowercase
            val cleanName = eventType.name
                .lowercase()
                .replace(Regex("[^a-z0-9_-]"), "_")
                .replace(Regex("_{2,}"), "_") // Replace multiple underscores with single
                .trim('_') // Remove leading/trailing underscores
            
            if (cleanName.isEmpty()) "unknown" else cleanName
            
        } catch (e: Exception) {
            Log.w("EventRepository", "Failed to get event type name for filename: ${e.message}")
            "unknown"
        }
    }
    
    /**
     * Format multiple photo upload results into a combined string for calendar events
     */
    private fun formatMultiplePhotoLinks(results: List<PhotoUploadResult>): String {
        return when (results.size) {
            0 -> ""
            1 -> results[0].link
            else -> {
                // Create a formatted string with links to both services
                val linkText = results.joinToString(separator = "\n") { result ->
                    "${result.service}: ${result.link}"
                }
                "Photos:\n$linkText"
            }
        }
    }
}

/**
 * Data class to hold photo upload results from different services
 */
data class PhotoUploadResult(
    val service: String,
    val link: String
)
