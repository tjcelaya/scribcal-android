package com.tjcelaya.scribcal.data.database

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Data class that combines Event with its EventType for UI display.
 * Used by Room queries with @Relation for automatic object mapping.
 */
data class EventWithType(
    @Embedded val event: Event,
    @Relation(
        parentColumn = "eventTypeId",
        entityColumn = "id"
    )
    val eventType: EventType
) {
    /**
     * Convenience accessors for common properties
     */
    val id: Long get() = event.id
    val eventTypeName: String get() = eventType.name
    val startTime: Long get() = event.startTime
    val endTime: Long? get() = event.endTime
    val notes: String get() = event.notes
    val photoPath: String? get() = event.photoPath
    val isOngoing: Boolean get() = event.isOngoing()
    val isInstant: Boolean get() = event.isInstant()
    val isCompleted: Boolean get() = event.isCompleted()
    val durationMs: Long get() = event.getDurationMs()

    /**
     * Helper to check if this event has a photo attachment
     */
    val hasPhoto: Boolean get() = !photoPath.isNullOrBlank()
}
