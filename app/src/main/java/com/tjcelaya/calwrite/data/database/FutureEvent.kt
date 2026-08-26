package com.tjcelaya.calwrite.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "future_events",
    foreignKeys = [
        ForeignKey(
            entity = EventType::class,
            parentColumns = ["id"],
            childColumns = ["eventTypeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index(value = ["eventTypeId"])]
)
data class FutureEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventTypeId: Long,
    val targetTime: Long,
    val notes: String? = null
)
