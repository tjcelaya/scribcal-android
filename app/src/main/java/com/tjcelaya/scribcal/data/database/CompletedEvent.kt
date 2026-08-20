package com.tjcelaya.scribcal.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "completed_events",
    foreignKeys = [
        ForeignKey(
            entity = EventType::class,
            parentColumns = ["id"],
            childColumns = ["eventTypeId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CompletedEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val eventTypeId: Long,
    val startTime: Long,
    val endTime: Long, // For instantaneous events, this equals startTime
    val notes: String? = null,
    val calendarEventId: Long? = null, // ID of the event in the system calendar
    val syncedToCalendar: Boolean = false
)
