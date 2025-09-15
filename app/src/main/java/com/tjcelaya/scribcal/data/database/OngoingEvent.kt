package com.tjcelaya.scribcal.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "ongoing_events",
    foreignKeys = [
        ForeignKey(
            entity = EventType::class,
            parentColumns = ["id"],
            childColumns = ["eventTypeId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class OngoingEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventTypeId: Long,
    val startTime: Long,
    val notes: String? = null
)
