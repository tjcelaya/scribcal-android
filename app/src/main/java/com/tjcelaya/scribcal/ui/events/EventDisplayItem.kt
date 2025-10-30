package com.tjcelaya.scribcal.ui.events

import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent

/**
 * Unified data model for displaying both event types and their ongoing instances
 */
data class EventDisplayItem(
    val eventType: EventType,
    val ongoingEvent: OngoingEvent? = null,
    val lastOccurrenceTime: Long? = null,
    val hourlyCount: Int = 0,
    val dailyCount: Int = 0,
    val weeklyCount: Int = 0,
    val monthlyCount: Int = 0,
    val isExpanded: Boolean = false
) {
    val isOngoing: Boolean get() = ongoingEvent != null
    val displayId: String get() = if (isOngoing) "ongoing_${ongoingEvent!!.id}" else "type_${eventType.id}"
}
