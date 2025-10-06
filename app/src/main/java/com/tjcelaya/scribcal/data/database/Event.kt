package com.tjcelaya.scribcal.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Represents an individual event occurrence.
 *
 * Event States:
 * 1. Instant event: startTime == endTime (both set to same timestamp)
 * 2. Ongoing event: startTime set, endTime = null (user hasn't stopped it yet)
 * 3. Completed timed event: startTime set, endTime set and > startTime
 *
 * All timestamps must be within the current date (timezone aware).
 */
@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = EventType::class,
            parentColumns = ["id"],
            childColumns = ["eventTypeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["eventTypeId"])
    ]
)
data class Event(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventTypeId: Long, // Foreign key to EventType
    val startTime: Long, // Always required - when event started/occurred
    val endTime: Long? = null, // Null for ongoing events, same as startTime for instant events
    val notes: String = "", // Optional user notes for this specific event instance
    val photoPath: String? = null // Optional path to photo attachment
) {
    /**
     * @return true if this is an instant event (start == end)
     */
    fun isInstant(): Boolean = endTime != null && startTime == endTime

    /**
     * @return true if this event is currently ongoing (end time not set)
     */
    fun isOngoing(): Boolean = endTime == null

    /**
     * @return true if this is a completed timed event (end > start)
     */
    fun isCompleted(): Boolean = endTime != null && endTime > startTime

    /**
     * @return duration in milliseconds, or 0 for instant/ongoing events
     */
    fun getDurationMs(): Long = when {
        isInstant() -> 0L
        isOngoing() -> System.currentTimeMillis() - startTime // Current elapsed time
        isCompleted() -> endTime!! - startTime
        else -> 0L
    }
}
