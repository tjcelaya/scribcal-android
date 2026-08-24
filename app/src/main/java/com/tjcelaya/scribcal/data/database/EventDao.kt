package com.tjcelaya.scribcal.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Event operations
 */
@Dao
interface EventDao {

    @Transaction
    @Query("SELECT * FROM events ORDER BY startTime DESC")
    fun getAllEventsWithType(): Flow<List<EventWithType>>

    @Transaction
    @Query("SELECT * FROM events WHERE endTime IS NULL ORDER BY startTime DESC")
    fun getOngoingEventsWithType(): Flow<List<EventWithType>>

    @Transaction
    @Query("SELECT * FROM events WHERE startTime >= :startOfDay AND startTime < :endOfDay ORDER BY startTime DESC")
    fun getTodaysEventsWithType(startOfDay: Long, endOfDay: Long): Flow<List<EventWithType>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getEventById(id: Long): Event?

    @Query("SELECT * FROM events WHERE endTime IS NULL ORDER BY startTime DESC")
    suspend fun getOngoingEvents(): List<Event>

    @Insert
    suspend fun insertEvent(event: Event): Long

    @Update
    suspend fun updateEvent(event: Event)

    @Delete
    suspend fun deleteEvent(event: Event)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteEventById(id: Long)

    @Query("UPDATE events SET endTime = :endTime WHERE id = :id")
    suspend fun completeEvent(id: Long, endTime: Long)

    @Query("SELECT COUNT(*) FROM events WHERE eventTypeId = :eventTypeId AND endTime IS NULL")
    suspend fun getOngoingEventCountForType(eventTypeId: Long): Int

    @Query("SELECT MAX(endTime) FROM events WHERE eventTypeId = :eventTypeId AND endTime IS NOT NULL")
    suspend fun getLastCompletedEventTimeForType(eventTypeId: Long): Long?

    @Query("SELECT COUNT(*) FROM events WHERE eventTypeId = :eventTypeId AND endTime IS NOT NULL AND endTime >= :startTime")
    suspend fun getCompletedEventCountSince(eventTypeId: Long, startTime: Long): Int

    // === Calendar sync bookkeeping ===

    @Query("UPDATE events SET calendarEventId = :calendarEventId WHERE id = :id")
    suspend fun setCalendarEventId(id: Long, calendarEventId: Long?)

    /** Finished events that never made it to the calendar, oldest first so retries preserve order. */
    @Query("SELECT * FROM events WHERE endTime IS NOT NULL AND calendarEventId IS NULL ORDER BY startTime ASC")
    suspend fun getUnsyncedCompletedEvents(): List<Event>

    // === Ledger / extend ===

    /** Recent finished events, newest first, for the ledger screen. */
    @Transaction
    @Query("SELECT * FROM events WHERE endTime IS NOT NULL ORDER BY endTime DESC LIMIT :limit")
    fun getRecentCompletedEventsWithType(limit: Int): Flow<List<EventWithType>>

    @Query("SELECT * FROM events WHERE endTime IS NOT NULL AND (:eventTypeId IS NULL OR eventTypeId = :eventTypeId) ORDER BY endTime DESC LIMIT 1")
    suspend fun getLastCompletedEvent(eventTypeId: Long?): Event?

    /**
     * The extend target: the most recent genuinely *timed* event. The `endTime > startTime`
     * predicate is what excludes instant events, which are not extendable.
     */
    @Query("SELECT * FROM events WHERE endTime > startTime AND (:eventTypeId IS NULL OR eventTypeId = :eventTypeId) ORDER BY endTime DESC LIMIT 1")
    suspend fun getLastTimedEvent(eventTypeId: Long?): Event?
}
