package com.tjcelaya.calwrite.ui.events

import com.tjcelaya.calwrite.data.database.EventType
import com.tjcelaya.calwrite.data.database.OngoingEvent

/**
 * Unified data model for displaying both event types and their ongoing instances
 */
data class EventDisplayItem(
    val eventType: EventType,
    val ongoingEvent: OngoingEvent? = null,
    val lastOccurrenceTime: Long? = null
) {
    val isOngoing: Boolean get() = ongoingEvent != null
    val displayId: String get() = if (isOngoing) "ongoing_${ongoingEvent!!.id}" else "type_${eventType.id}"
}
