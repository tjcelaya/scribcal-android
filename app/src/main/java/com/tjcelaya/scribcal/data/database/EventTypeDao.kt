package com.tjcelaya.scribcal.data.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface EventTypeDao {

    @Query("SELECT * FROM event_types ORDER BY sortOrder ASC, name ASC")
    fun getAllEventTypes(): LiveData<List<EventType>>

    @Query("SELECT * FROM event_types ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllEventTypesSync(): List<EventType>

    @Query("SELECT * FROM event_types WHERE id = :id")
    suspend fun getEventTypeById(id: Long): EventType?

    @Query("SELECT * FROM event_types WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getEventTypeByName(name: String): EventType?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventType(eventType: EventType): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventTypes(eventTypes: List<EventType>)

    @Query("DELETE FROM event_types")
    suspend fun deleteAllEventTypes()

    @Update
    suspend fun updateEventType(eventType: EventType)

    @Delete
    suspend fun deleteEventType(eventType: EventType)

    @Query("DELETE FROM event_types WHERE id = :id")
    suspend fun deleteEventTypeById(id: Long)

    @Query("SELECT COUNT(*) FROM event_types")
    suspend fun getEventTypeCount(): Int

    @Query("UPDATE event_types SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)
}
