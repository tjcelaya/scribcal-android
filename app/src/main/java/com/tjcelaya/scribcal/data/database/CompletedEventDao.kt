package com.tjcelaya.scribcal.data.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CompletedEventDao {
    
    @Query("SELECT * FROM completed_events ORDER BY startTime DESC")
    fun getAllCompletedEvents(): LiveData<List<CompletedEvent>>
    
    @Query("SELECT * FROM completed_events WHERE id = :id")
    suspend fun getCompletedEventById(id: Long): CompletedEvent?
    
    @Query("SELECT * FROM completed_events WHERE eventTypeId = :eventTypeId ORDER BY startTime DESC")
    fun getCompletedEventsByType(eventTypeId: Long): LiveData<List<CompletedEvent>>
    
    @Query("SELECT * FROM completed_events WHERE syncedToCalendar = 0")
    suspend fun getUnsyncedEvents(): List<CompletedEvent>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletedEvent(completedEvent: CompletedEvent): Long
    
    @Update
    suspend fun updateCompletedEvent(completedEvent: CompletedEvent)
    
    @Delete
    suspend fun deleteCompletedEvent(completedEvent: CompletedEvent)
    
    @Query("DELETE FROM completed_events WHERE id = :id")
    suspend fun deleteCompletedEventById(id: Long)
}
