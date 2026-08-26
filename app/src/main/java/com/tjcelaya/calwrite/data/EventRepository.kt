package com.tjcelaya.calwrite.data

import android.accounts.Account
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import com.tjcelaya.calwrite.data.database.*
import com.tjcelaya.calwrite.ui.tracking.PhotoUpload
import com.tjcelaya.calwrite.ui.notifications.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    private val database: CalWriteDatabase,
    private val driveRepository: DriveRepository? = null,
    private val photosRepository: PhotosRepository? = null,
    private val storagePreferences: StoragePreferences? = null,
    private val notificationService: NotificationService? = null
) {

    private val eventTypeDao = database.eventTypeDao()
    private val eventDao = database.eventDao()
    private val photoUploadProgressDao = database.photoUploadProgressDao()
    private val futureEventDao = database.futureEventDao()

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

    suspend fun getEventTypeByName(name: String): EventType? = withContext(Dispatchers.IO) {
        eventTypeDao.getEventTypeByName(name)
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

    suspend fun updateEventTypeSortOrders(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        orderedIds.forEachIndexed { index, id ->
            eventTypeDao.updateSortOrder(id, index)
        }
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

    // Enhanced workflow methods with coordinated calendar and storage integration

    /**
     * Create instant event with photo using enhanced coordinated workflow:
     * 1. Create calendar event
     * 2. Upload to drive and/or photos in parallel
     * 3. Update calendar event with storage links (parallel with step 4)
     * 4. Update storage files with calendar event links (parallel with step 3)
     */
    suspend fun createInstantEventWithPhotoAndSyncEnhanced(
        eventTypeId: Long,
        photoPath: String,
        notes: String = "",
        calendarRepository: CalendarRepository
    ): Long = withContext(Dispatchers.IO) {
        // Check if calendar is set up
        if (!calendarRepository.isCalendarSetupComplete()) {
            throw IllegalStateException("No calendar selected. Please select a calendar in settings first.")
        }

        Log.d("EventRepository", "Starting enhanced workflow for instant event with photo: $photoPath")

        // Step 1: Create calendar event first (without photo links)
        val eventId = createInstantEvent(eventTypeId, notes, null)
        val calendarEventId = syncEventToCalendarWithResult(eventId, calendarRepository)

        if (calendarEventId == null) {
            Log.e("EventRepository", "Failed to create calendar event, aborting photo upload")
            throw RuntimeException("Failed to create calendar event")
        }

        Log.d("EventRepository", "Created calendar event $calendarEventId, now uploading photos")

        // Step 2: Upload to storage services in parallel
        val uploadResults = uploadPhotoToSelectedStorageWithDetails(photoPath, eventId)
            ?: throw RuntimeException("Failed to upload photo to selected storage. Photo path: $photoPath")

        Log.d("EventRepository", "Photo uploads completed, now updating links")

        // Step 3 & 4: Update calendar event and storage files in parallel
        val updateCalendarJob = async {
            updateCalendarEventWithPhotoLinks(eventId, calendarEventId, uploadResults, calendarRepository)
        }

        val updateStorageJob = async {
            updateStorageFilesWithCalendarLinks(uploadResults, eventId, calendarEventId, calendarRepository)
        }

        // Wait for both update operations to complete
        updateCalendarJob.await()
        updateStorageJob.await()

        // Update the event in database with the combined photo path
        val combinedPhotoPath = formatMultiplePhotoLinks(uploadResults.results)
        val event = eventDao.getEventById(eventId)
        if (event != null) {
            val updatedEvent = event.copy(photoPath = combinedPhotoPath)
            eventDao.updateEvent(updatedEvent)
        }

        Log.d("EventRepository", "Enhanced workflow completed for event $eventId")
        eventId
    }

    // Legacy methods with calendar sync and Google Drive integration
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

    suspend fun getLastCompletedEventTime(eventTypeId: Long): Long? = withContext(Dispatchers.IO) {
        eventDao.getLastCompletedEventTimeForType(eventTypeId)
    }

    /**
     * Local retention: drop completed events older than [retentionMillis]. Google Calendar
     * remains the durable record; this only trims the local `events` table, which exists for
     * the recent-events ledger and sync bookkeeping, not long-range history.
     */
    suspend fun pruneOldEvents(retentionMillis: Long = RETENTION_MILLIS) = withContext(Dispatchers.IO) {
        eventDao.deleteCompletedEventsOlderThan(System.currentTimeMillis() - retentionMillis)
    }

    /** Finished events that never reached the calendar, oldest first. */
    suspend fun getUnsyncedEvents(): List<Event> = withContext(Dispatchers.IO) {
        eventDao.getUnsyncedCompletedEvents()
    }

    suspend fun markEventAsSynced(eventId: Long, calendarEventId: Long) = withContext(Dispatchers.IO) {
        eventDao.setCalendarEventId(eventId, calendarEventId)
    }

    suspend fun syncEventToCalendar(eventId: Long, calendarRepository: CalendarRepository) = withContext(Dispatchers.IO) {
        // Call the enhanced version that returns the calendar event ID, but discard the result for backward compatibility
        syncEventToCalendarWithResult(eventId, calendarRepository)
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
        
        // Show notification for ongoing event
        notificationService?.let { service ->
            try {
                Log.d("EventRepository", "Attempting to show notification for event $eventId")
                val eventType = getEventTypeById(eventTypeId)
                if (eventType != null) {
                    // Get the actual event to use the correct start time
                    val event = eventDao.getEventById(eventId)
                    if (event != null && event.isOngoing()) {
                        val ongoingEvent = OngoingEvent(
                            id = eventId,
                            eventTypeId = eventTypeId,
                            startTime = event.startTime,
                            notes = notes ?: ""
                        )
                        service.showOngoingEventNotification(ongoingEvent, eventType)
                        Log.d("EventRepository", "Successfully requested notification for event ${eventType.name}")
                    } else {
                        Log.w("EventRepository", "Event $eventId not found or not ongoing when trying to show notification")
                    }
                } else {
                    Log.w("EventRepository", "EventType $eventTypeId not found when trying to show notification")
                }
            } catch (e: Exception) {
                Log.e("EventRepository", "Failed to show notification for ongoing event", e)
            }
        } ?: run {
            Log.w("EventRepository", "NotificationService is null, cannot show notification")
        }
        
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
            // Dismiss notification for completed event
            notificationService?.dismissOngoingEventNotification(ongoingEventId)
            
            // Sync completed event to calendar
            syncEventToCalendar(ongoingEventId, calendarRepository)
        }
        return success
    }

    /** Recent finished events with their type, newest first, for the ledger. */
    fun getRecentCompletedEventsWithType(limit: Int = 200): Flow<List<EventWithType>> =
        eventDao.getRecentCompletedEventsWithType(limit)

    /** Any event by id, ongoing or finished. */
    suspend fun getEventByIdOrNull(eventId: Long): Event? = withContext(Dispatchers.IO) {
        eventDao.getEventById(eventId)
    }

    /** Currently-running events, newest first. */
    suspend fun getOngoingEventsSync(): List<Event> = withContext(Dispatchers.IO) {
        eventDao.getOngoingEvents()
    }

    /** The running event for a type, or null when that type isn't currently being tracked. */
    suspend fun getOngoingEventForType(eventTypeId: Long): Event? = withContext(Dispatchers.IO) {
        eventDao.getOngoingEvents().firstOrNull { it.eventTypeId == eventTypeId }
    }

    suspend fun getOngoingEventById(eventId: Long): OngoingEvent? = withContext(Dispatchers.IO) {
        val event = eventDao.getEventById(eventId)
        if (event != null && event.isOngoing()) {
            OngoingEvent(
                id = event.id,
                eventTypeId = event.eventTypeId,
                startTime = event.startTime,
                notes = event.notes ?: ""
            )
        } else {
            null
        }
    }

    suspend fun stopEventWithoutSaving(ongoingEventId: Long): Boolean {
        val success = withContext(Dispatchers.IO) {
            // Simply delete the ongoing event without saving to calendar
            val event = eventDao.getEventById(ongoingEventId)
            if (event != null && event.isOngoing()) {
                eventDao.deleteEventById(ongoingEventId)
                Log.d("EventRepository", "Deleted ongoing event $ongoingEventId without saving")
                true
            } else {
                Log.w("EventRepository", "Event $ongoingEventId not found or not ongoing")
                false
            }
        }
        
        if (success) {
            // Dismiss notification for deleted event
            notificationService?.dismissOngoingEventNotification(ongoingEventId)
        }
        
        return success
    }

    suspend fun stopOngoingEvent(ongoingEventId: Long): Boolean {
        return completeOngoingEvent(ongoingEventId)
    }

    /**
     * Extend the most recent *timed* event so it now ends at the current moment, pushing the new
     * end time to its calendar copy.
     *
     * Instant events are deliberately not extendable: an instant event records a moment, not a
     * span, so turning one into a duration would silently rewrite what the user meant. The
     * underlying query filters on `endTime > startTime`, which means this skips past any instant
     * events to the last genuinely timed one rather than refusing outright when an event type
     * mixes cadences.
     *
     * @param eventTypeId restrict to one event type, or null for the most recent timed event of
     *   any type.
     * @return the outcome, or null when there is no timed event to extend.
     */
    suspend fun extendLastEvent(
        eventTypeId: Long?,
        calendarRepository: CalendarRepository
    ): ExtendResult? = withContext(Dispatchers.IO) {
        val event = eventDao.getLastTimedEvent(eventTypeId) ?: return@withContext null
        val previousEndTime = event.endTime ?: return@withContext null
        val newEndTime = System.currentTimeMillis()

        // Nothing to do if the event already runs past now (clock skew, or a manual adjustment).
        if (newEndTime <= previousEndTime) {
            return@withContext ExtendResult(
                eventId = event.id,
                eventTypeId = event.eventTypeId,
                startTime = event.startTime,
                previousEndTime = previousEndTime,
                newEndTime = previousEndTime,
                extended = false,
                calendarUpdated = false
            )
        }

        eventDao.updateEvent(event.copy(endTime = newEndTime))

        val eventType = eventTypeDao.getEventTypeById(event.eventTypeId)
        var calendarUpdated = false
        if (eventType != null) {
            val calendarEventId = event.calendarEventId
            calendarUpdated = if (calendarEventId != null) {
                calendarRepository.updateCalendarEvent(
                    calendarEventId,
                    eventType,
                    event.startTime,
                    newEndTime,
                    event.notes,
                    event.photoPath
                )
            } else {
                // Never synced (or synced before calendarEventId was persisted) - sync now so the
                // extended event still lands on the calendar.
                syncEventToCalendarWithResult(event.id, calendarRepository) != null
            }
        }

        ExtendResult(
            eventId = event.id,
            eventTypeId = event.eventTypeId,
            startTime = event.startTime,
            previousEndTime = previousEndTime,
            newEndTime = newEndTime,
            extended = true,
            calendarUpdated = calendarUpdated
        )
    }

    /**
     * Rewrite a recorded event's times and notes, mirroring the change into the calendar copy.
     *
     * Used by the ledger's Adjust action. Refuses an end before its start rather than writing a
     * negative-duration event that the calendar would reject anyway.
     */
    suspend fun adjustEvent(
        eventId: Long,
        startTime: Long,
        endTime: Long,
        notes: String,
        calendarRepository: CalendarRepository
    ): Boolean = withContext(Dispatchers.IO) {
        val event = eventDao.getEventById(eventId) ?: return@withContext false
        if (endTime < startTime) {
            Log.w("EventRepository", "adjustEvent: refusing end before start for event $eventId")
            return@withContext false
        }
        val adjusted = event.copy(startTime = startTime, endTime = endTime, notes = notes)
        eventDao.updateEvent(adjusted)
        propagateToCalendar(adjusted, calendarRepository)
        true
    }

    /** Delete a recorded event along with the calendar entry it produced. */
    suspend fun deleteEventWithCalendarCopy(
        eventId: Long,
        calendarRepository: CalendarRepository
    ): Boolean = withContext(Dispatchers.IO) {
        val event = eventDao.getEventById(eventId) ?: return@withContext false
        event.calendarEventId?.let { calendarId ->
            try {
                val deleted = calendarRepository.deleteCalendarEvent(calendarId)
                if (!deleted) {
                    Log.w("EventRepository", "Calendar copy $calendarId of event $eventId was not deleted")
                }
            } catch (e: Exception) {
                Log.e("EventRepository", "Failed to delete calendar copy $calendarId of event $eventId", e)
            }
        }
        eventDao.deleteEventById(eventId)
        true
    }

    /**
     * Mirror [event] into the calendar: update in place when we know the calendar id, otherwise
     * sync fresh so an event that previously failed to reach the calendar still gets there.
     */
    private suspend fun propagateToCalendar(event: Event, calendarRepository: CalendarRepository) {
        val eventType = eventTypeDao.getEventTypeById(event.eventTypeId) ?: return
        val calendarEventId = event.calendarEventId
        try {
            if (calendarEventId != null) {
                val updated = calendarRepository.updateCalendarEvent(
                    calendarEventId,
                    eventType,
                    event.startTime,
                    event.endTime ?: event.startTime,
                    event.notes,
                    event.photoPath
                )
                if (!updated) Log.w("EventRepository", "Calendar update failed for event ${event.id}")
            } else {
                syncEventToCalendarWithResult(event.id, calendarRepository)
            }
        } catch (e: Exception) {
            Log.e("EventRepository", "Failed to propagate event ${event.id} to calendar", e)
        }
    }

    /**
     * Revert an extend, restoring the previous end time locally and on the calendar.
     */
    suspend fun revertExtend(result: ExtendResult, calendarRepository: CalendarRepository): Boolean =
        withContext(Dispatchers.IO) {
            val event = eventDao.getEventById(result.eventId) ?: return@withContext false
            eventDao.updateEvent(event.copy(endTime = result.previousEndTime))

            val eventType = eventTypeDao.getEventTypeById(event.eventTypeId)
            val calendarEventId = event.calendarEventId
            if (eventType != null && calendarEventId != null) {
                calendarRepository.updateCalendarEvent(
                    calendarEventId,
                    eventType,
                    event.startTime,
                    result.previousEndTime,
                    event.notes,
                    event.photoPath
                )
            }
            true
        }

    // Future event operations
    fun getAllFutureEvents(): LiveData<List<FutureEvent>> = futureEventDao.getAllFutureEvents()

    suspend fun createFutureEvent(eventTypeId: Long, targetTime: Long, notes: String? = null): Long = withContext(Dispatchers.IO) {
        val futureEvent = FutureEvent(
            eventTypeId = eventTypeId,
            targetTime = targetTime,
            notes = notes
        )
        futureEventDao.insertFutureEvent(futureEvent)
    }

    suspend fun deleteFutureEvent(futureEventId: Long) = withContext(Dispatchers.IO) {
        futureEventDao.deleteFutureEventById(futureEventId)
    }

    suspend fun getFutureEventById(futureEventId: Long): FutureEvent? = withContext(Dispatchers.IO) {
        futureEventDao.getFutureEventById(futureEventId)
    }

    /**
     * Check for expired future events and trigger instant events for them
     * Returns the number of future events that were triggered
     */
    suspend fun triggerExpiredFutureEvents(calendarRepository: CalendarRepository): Int = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        val expiredEvents = futureEventDao.getExpiredFutureEvents(currentTime)
        var triggeredCount = 0

        expiredEvents.forEach { futureEvent ->
            try {
                // Create an instantaneous event at the target time
                createInstantEvent(
                    eventTypeId = futureEvent.eventTypeId,
                    notes = futureEvent.notes ?: "Scheduled event",
                    timestamp = futureEvent.targetTime
                )
                
                // Sync to calendar
                val eventId = createInstantEvent(
                    eventTypeId = futureEvent.eventTypeId,
                    notes = futureEvent.notes ?: "Scheduled event",
                    timestamp = futureEvent.targetTime
                )
                syncEventToCalendar(eventId, calendarRepository)
                
                // Delete the future event
                futureEventDao.deleteFutureEventById(futureEvent.id)
                triggeredCount++
                
                Log.d("EventRepository", "Triggered future event ${futureEvent.id} for event type ${futureEvent.eventTypeId}")
            } catch (e: Exception) {
                Log.e("EventRepository", "Failed to trigger future event ${futureEvent.id}", e)
            }
        }

        triggeredCount
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

            // Delete local file after all uploads are complete (if any succeeded)
            if (combinedResult != null) {
                try {
                    val localFile = File(localPhotoPath)
                    if (localFile.exists() && localFile.delete()) {
                        Log.d("EventRepository", "Deleted local photo file after successful uploads: $localPhotoPath")
                    } else {
                        Log.w("EventRepository", "Could not delete local photo file: $localPhotoPath")
                    }
                } catch (e: Exception) {
                    Log.w("EventRepository", "Error deleting local photo file: $localPhotoPath", e)
                }
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
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val fileName = "${eventTypeName} ${timestamp}.jpg"

            Log.d("EventRepository", "Uploading photo $localPhotoPath as $fileName to Google Drive")

            val driveLink = drive.uploadPhotoAndGetLink(localPhotoPath, fileName)

            if (driveLink != null) {
                Log.d("EventRepository", "Photo uploaded successfully to Drive: $driveLink")
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
            val fileName = "calwrite_${eventTypeName}_${timestamp}.jpg"

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

            // Clean the name for use in filename: preserve readability but make it filesystem-safe
            val cleanName = eventType.name
                .replace(Regex("[<>:\"/\\|?*]"), "") // Remove filesystem-unsafe characters
                .trim()

            if (cleanName.isEmpty()) "unknown" else cleanName

        } catch (e: Exception) {
            Log.w("EventRepository", "Failed to get event type name for filename: ${e.message}")
            "unknown"
        }
    }

    /**
     * Upload photo to selected storage and return detailed results for enhanced workflow
     */
    private suspend fun uploadPhotoToSelectedStorageWithDetails(localPhotoPath: String, eventId: Long? = null): EnhancedUploadResults? = withContext(Dispatchers.IO) {
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
                val event = eventDao.getEventById(eventId)
                val eventTypeIdForUpload = event?.eventTypeId ?: 0L
                uploadId = startPhotoUploadTracking(eventTypeIdForUpload, fileName)
                startPhotoUpload(eventId, fileName)
                markPhotoUploadStarted(eventId)
            }

            val results = mutableListOf<EnhancedPhotoUploadResult>()

            // Upload to Google Drive if enabled
            if (isDriveEnabled) {
                uploadId?.let { updatePhotoUploadProgressTracking(it, 10, "Uploading to Google Drive...") }
                Log.d("EventRepository", "Uploading photo to Google Drive: $localPhotoPath")
                val driveResult = uploadPhotoToDriveWithDetails(localPhotoPath, eventId)
                if (driveResult != null) {
                    results.add(driveResult)
                    uploadId?.let { updatePhotoUploadProgressTracking(it, 50, "Drive upload complete") }
                    Log.d("EventRepository", "Successfully uploaded to Google Drive: ${driveResult.link}")
                } else {
                    Log.w("EventRepository", "Failed to upload photo to Google Drive")
                }
            }

            // Upload to Google Photos if enabled
            if (isPhotosEnabled) {
                val baseProgress = if (isDriveEnabled) 50 else 10
                uploadId?.let { updatePhotoUploadProgressTracking(it, baseProgress + 10, "Uploading to Google Photos...") }
                Log.d("EventRepository", "Uploading photo to Google Photos: $localPhotoPath")
                val photosResult = uploadPhotoToPhotosWithDetails(localPhotoPath, eventId)
                if (photosResult != null) {
                    results.add(photosResult)
                    uploadId?.let { updatePhotoUploadProgressTracking(it, baseProgress + 40, "Photos upload complete") }
                    Log.d("EventRepository", "Successfully uploaded to Google Photos: ${photosResult.link}")
                } else {
                    Log.w("EventRepository", "Failed to upload photo to Google Photos")
                }
            }

            // Create enhanced results
            val enhancedResults = if (results.isNotEmpty()) {
                val legacyResults = results.map { PhotoUploadResult(it.service, it.link) }
                EnhancedUploadResults(
                    results = legacyResults,
                    enhancedResults = results
                )
            } else {
                null
            }

            // Mark as completed or failed
            if (eventId != null) {
                if (enhancedResults != null) {
                    uploadId?.let {
                        updatePhotoUploadProgressTracking(it, 100, "Upload complete!")
                        completePhotoUploadTracking(it)
                    }
                    val combinedResult = formatMultiplePhotoLinks(enhancedResults.results)
                    markPhotoUploadCompleted(eventId, combinedResult)
                    Log.d("EventRepository", "Photo upload completed with ${results.size} successful uploads")
                } else {
                    uploadId?.let { completePhotoUploadTracking(it) }
                    markPhotoUploadFailed(eventId, "All photo uploads failed")
                }
            }

            // Delete local file after all uploads are complete (if any succeeded)
            if (enhancedResults != null) {
                try {
                    val localFile = File(localPhotoPath)
                    if (localFile.exists() && localFile.delete()) {
                        Log.d("EventRepository", "Deleted local photo file after successful uploads: $localPhotoPath")
                    } else {
                        Log.w("EventRepository", "Could not delete local photo file: $localPhotoPath")
                    }
                } catch (e: Exception) {
                    Log.w("EventRepository", "Error deleting local photo file: $localPhotoPath", e)
                }
            }

            enhancedResults
        } catch (e: Exception) {
            if (eventId != null) {
                markPhotoUploadFailed(eventId, "Exception: ${e.message}")
            }
            Log.e("EventRepository", "Exception uploading photo to selected storage: $localPhotoPath", e)
            throw e
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

    /**
     * Upload photo to Google Drive with detailed result information
     */
    private suspend fun uploadPhotoToDriveWithDetails(localPhotoPath: String, eventId: Long? = null): EnhancedPhotoUploadResult? = withContext(Dispatchers.IO) {
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
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val fileName = "${eventTypeName} ${timestamp}.jpg"

            Log.d("EventRepository", "Uploading photo $localPhotoPath as $fileName to Google Drive")

            val driveLink = drive.uploadPhotoAndGetLink(localPhotoPath, fileName)

            if (driveLink != null) {
                Log.d("EventRepository", "Photo uploaded successfully to Drive: $driveLink")
                return@withContext EnhancedPhotoUploadResult(
                    service = "Google Drive",
                    link = driveLink,
                    fileName = fileName,
                    fileId = extractFileIdFromDriveLink(driveLink)
                )
            } else {
                Log.e("EventRepository", "Failed to upload photo to Drive: $localPhotoPath")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("EventRepository", "Exception uploading photo to Drive: $localPhotoPath", e)
            null
        }
    }

    /**
     * Upload photo to Google Photos with detailed result information
     */
    private suspend fun uploadPhotoToPhotosWithDetails(localPhotoPath: String, eventId: Long? = null): EnhancedPhotoUploadResult? = withContext(Dispatchers.IO) {
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
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val fileName = "${eventTypeName} ${timestamp}.jpg"

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
                return@withContext EnhancedPhotoUploadResult(
                    service = "Google Photos",
                    link = photosLink,
                    fileName = fileName,
                    fileId = null // Google Photos doesn't have a simple file ID extraction
                )
            } else {
                Log.e("EventRepository", "Failed to upload photo to Photos: $localPhotoPath")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("EventRepository", "Exception uploading photo to Photos: $localPhotoPath", e)
            null
        }
    }

    /**
     * Update calendar event with photo links after upload completion
     */
    private suspend fun updateCalendarEventWithPhotoLinks(
        eventId: Long,
        calendarEventId: Long,
        uploadResults: EnhancedUploadResults,
        calendarRepository: CalendarRepository
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d("EventRepository", "Updating calendar event $calendarEventId with photo links")

            val event = eventDao.getEventById(eventId)
            val eventType = event?.let { eventTypeDao.getEventTypeById(it.eventTypeId) }

            if (event != null && eventType != null) {
                val combinedPhotoPath = formatMultiplePhotoLinks(uploadResults.results)
                val success = calendarRepository.updateCalendarEvent(
                    calendarEventId,
                    eventType,
                    event.startTime,
                    event.endTime ?: event.startTime,
                    event.notes,
                    combinedPhotoPath
                )

                if (success) {
                    Log.d("EventRepository", "Successfully updated calendar event with photo links")
                } else {
                    Log.w("EventRepository", "Failed to update calendar event with photo links")
                }
            }
        } catch (e: Exception) {
            Log.e("EventRepository", "Exception updating calendar event with photo links", e)
        }
    }

    /**
     * Update storage files with calendar event links
     */
    private suspend fun updateStorageFilesWithCalendarLinks(
        uploadResults: EnhancedUploadResults,
        eventId: Long,
        calendarEventId: Long,
        calendarRepository: CalendarRepository
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d("EventRepository", "Updating storage files with calendar event links")

            // Generate calendar event link (private to user)
            val calendarLink = generateCalendarEventLink(calendarEventId)
            val eventType = eventDao.getEventById(eventId)?.let { eventTypeDao.getEventTypeById(it.eventTypeId) }
            val eventTypeName = eventType?.name ?: "CalWrite Event"

            uploadResults.enhancedResults.forEach { result ->
                when (result.service) {
                    "Google Drive" -> {
                        if (result.fileId != null) {
                            updateDriveFileDescription(result.fileId, eventTypeName, calendarLink)
                        }
                    }
                    "Google Photos" -> {
                        // Google Photos doesn't support updating descriptions via API,
                        // but we could add metadata in other ways in the future
                        Log.d("EventRepository", "Google Photos file descriptions cannot be updated via API")
                    }
                }
            }

            Log.d("EventRepository", "Completed updating storage files with calendar links")
        } catch (e: Exception) {
            Log.e("EventRepository", "Exception updating storage files with calendar links", e)
        }
    }

    /**
     * Extract file ID from Google Drive link
     */
    private fun extractFileIdFromDriveLink(driveLink: String): String? {
        return try {
            // Google Drive links are usually in format:
            // https://drive.google.com/file/d/FILE_ID/view?usp=sharing
            val regex = Regex("/file/d/([a-zA-Z0-9_-]+)/")
            val matchResult = regex.find(driveLink)
            matchResult?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.w("EventRepository", "Failed to extract file ID from Drive link: $driveLink", e)
            null
        }
    }

    /**
     * Generate calendar event link (using content:// URI for Android calendar access)
     */
    private fun generateCalendarEventLink(calendarEventId: Long): String {
        return "content://com.android.calendar/events/$calendarEventId"
    }

    /**
     * Update Google Drive file description with calendar event link
     */
    private suspend fun updateDriveFileDescription(fileId: String, eventTypeName: String, calendarLink: String) {
        try {
            val drive = driveRepository
            if (drive != null) {
                val description = buildString {
                    append("CalWrite Event: $eventTypeName\n")
                    append("Created: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}\n")
                    append("Calendar Event: $calendarLink")
                }

                // Note: We'd need to add this method to DriveRepository
                // drive.updateFileDescription(fileId, description)
                Log.d("EventRepository", "Would update Drive file $fileId with description: $description")
            }
        } catch (e: Exception) {
            Log.e("EventRepository", "Failed to update Drive file description", e)
        }
    }

    /**
     * Enhanced version of syncEventToCalendar that returns the calendar event ID
     */
    private suspend fun syncEventToCalendarWithResult(eventId: Long, calendarRepository: CalendarRepository): Long? = withContext(Dispatchers.IO) {
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
                return@withContext calendarEventId
            }
            return@withContext null
        } catch (e: Exception) {
            // Log error but don't fail the event creation
            Log.e("EventRepository", "Failed to sync event $eventId to calendar", e)
            return@withContext null
        }
    }

    companion object {
        /** Default local retention window for [pruneOldEvents]. */
        const val RETENTION_MILLIS: Long = 90L * 24 * 60 * 60 * 1000
    }
}

/**
 * Outcome of [EventRepository.extendLastEvent].
 *
 * [extended] is false when a target existed but its end time was already at or past now, so
 * nothing changed. [previousEndTime] is retained so the change can be reverted.
 */
data class ExtendResult(
    val eventId: Long,
    val eventTypeId: Long,
    val startTime: Long,
    val previousEndTime: Long,
    val newEndTime: Long,
    val extended: Boolean,
    val calendarUpdated: Boolean
) {
    val newDurationMs: Long get() = newEndTime - startTime
    val addedMs: Long get() = newEndTime - previousEndTime
}

/**
 * Data class to hold photo upload results from different services
 */
data class PhotoUploadResult(
    val service: String,
    val link: String
)

/**
 * Enhanced photo upload result with additional metadata
 */
data class EnhancedPhotoUploadResult(
    val service: String,
    val link: String,
    val fileName: String,
    val fileId: String? // For services that provide file IDs (like Google Drive)
)

/**
 * Container for both legacy and enhanced upload results
 */
data class EnhancedUploadResults(
    val results: List<PhotoUploadResult>, // For backward compatibility
    val enhancedResults: List<EnhancedPhotoUploadResult> // For enhanced functionality
)
