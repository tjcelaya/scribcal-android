package com.tjcelaya.scribcal.data.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface OngoingEventDao {

    @Query("SELECT * FROM ongoing_events ORDER BY startTime DESC")
    fun getAllOngoingEvents(): LiveData<List<OngoingEvent>>

    @Query("SELECT * FROM ongoing_events WHERE id = :id")
    suspend fun getOngoingEventById(id: Long): OngoingEvent?

    @Query("SELECT * FROM ongoing_events WHERE eventTypeId = :eventTypeId")
    suspend fun getOngoingEventsByType(eventTypeId: Long): List<OngoingEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOngoingEvent(ongoingEvent: OngoingEvent): Long

    @Update
    suspend fun updateOngoingEvent(ongoingEvent: OngoingEvent)

    @Delete
    suspend fun deleteOngoingEvent(ongoingEvent: OngoingEvent)

    @Query("DELETE FROM ongoing_events WHERE id = :id")
    suspend fun deleteOngoingEventById(id: Long)

    @Query("SELECT COUNT(*) FROM ongoing_events WHERE eventTypeId = :eventTypeId")
    suspend fun getOngoingEventCountForType(eventTypeId: Long): Int
}
