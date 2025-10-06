package com.tjcelaya.scribcal.data.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface EventTypeDao {

    @Query("SELECT * FROM event_types ORDER BY name ASC")
    fun getAllEventTypes(): LiveData<List<EventType>>

    @Query("SELECT * FROM event_types ORDER BY name ASC")
    suspend fun getAllEventTypesSync(): List<EventType>

    @Query("SELECT * FROM event_types WHERE id = :id")
    suspend fun getEventTypeById(id: Long): EventType?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventType(eventType: EventType): Long

    @Update
    suspend fun updateEventType(eventType: EventType)

    @Delete
    suspend fun deleteEventType(eventType: EventType)

    @Query("DELETE FROM event_types WHERE id = :id")
    suspend fun deleteEventTypeById(id: Long)

    @Query("SELECT COUNT(*) FROM event_types")
    suspend fun getEventTypeCount(): Int
}
