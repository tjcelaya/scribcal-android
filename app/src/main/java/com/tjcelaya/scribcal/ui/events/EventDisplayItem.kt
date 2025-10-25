package com.tjcelaya.scribcal.ui.events

import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent

/**
 * Unified data model for displaying both event types and their ongoing instances
 */
data class EventDisplayItem(
    val eventType: EventType,
    val ongoingEvent: OngoingEvent? = null
) {
    val isOngoing: Boolean get() = ongoingEvent != null
    val displayId: String get() = if (isOngoing) "ongoing_${ongoingEvent!!.id}" else "type_${eventType.id}"
}