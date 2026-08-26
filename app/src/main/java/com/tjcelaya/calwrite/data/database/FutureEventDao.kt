package com.tjcelaya.calwrite.data.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface FutureEventDao {

    @Query("SELECT * FROM future_events ORDER BY targetTime ASC")
    fun getAllFutureEvents(): LiveData<List<FutureEvent>>

    @Query("SELECT * FROM future_events ORDER BY targetTime ASC")
    suspend fun getAllFutureEventsSync(): List<FutureEvent>

    @Query("SELECT * FROM future_events WHERE id = :id")
    suspend fun getFutureEventById(id: Long): FutureEvent?

    @Query("SELECT * FROM future_events WHERE eventTypeId = :eventTypeId")
    suspend fun getFutureEventsByType(eventTypeId: Long): List<FutureEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFutureEvent(futureEvent: FutureEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFutureEvents(futureEvents: List<FutureEvent>)

    @Update
    suspend fun updateFutureEvent(futureEvent: FutureEvent)

    @Delete
    suspend fun deleteFutureEvent(futureEvent: FutureEvent)

    @Query("DELETE FROM future_events WHERE id = :id")
    suspend fun deleteFutureEventById(id: Long)

    @Query("SELECT COUNT(*) FROM future_events WHERE eventTypeId = :eventTypeId")
    suspend fun getFutureEventCountForType(eventTypeId: Long): Int

    @Query("SELECT * FROM future_events WHERE targetTime <= :currentTime")
    suspend fun getExpiredFutureEvents(currentTime: Long): List<FutureEvent>
}
