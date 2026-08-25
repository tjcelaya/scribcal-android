package com.tjcelaya.calwrite.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import com.tjcelaya.calwrite.utils.GoogleCalendarColors

/**
 * How an event type can be recorded, which controls the action buttons shown on the main screen.
 * INSTANT = only the instant (record now) action, TIMED = only the start/stop stopwatch action,
 * BOTH = both actions.
 */
enum class Cadence {
    INSTANT,
    TIMED,
    BOTH;

    fun showsInstant(): Boolean = this == INSTANT || this == BOTH
    fun showsTimed(): Boolean = this == TIMED || this == BOTH

    companion object {
        fun fromName(value: String?): Cadence =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: BOTH
    }
}

@Entity(
    tableName = "event_types",
    indices = [Index(value = ["name"], unique = true)]
)
data class EventType(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val colorId: Int? = null, // Google Calendar color ID (1-11), null defaults to calendar color
    val shouldBubble: Boolean = false, // Whether this event type should show as bubbles when bubble mode is SELECTED
    val sortOrder: Int = 0, // User-defined sort order for display
    val createdAt: Long = System.currentTimeMillis(),
    val cadence: Cadence = Cadence.BOTH // Which record actions are available for this event type
) {
    /**
     * Get the display color for this event type
     */
    fun getDisplayColor(): Int? {
        return if (colorId != null) GoogleCalendarColors.getHexColorById(colorId) else null
    }
}
