package com.tjcelaya.scribcal.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import com.tjcelaya.scribcal.utils.GoogleCalendarColors

@Entity(
    tableName = "event_types",
    indices = [Index(value = ["name"], unique = true)]
)
data class EventType(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val colorId: Int? = null, // Google Calendar color ID (1-11) or CUSTOM_COLOR_ID (-1), null defaults to calendar color
    val customColorHex: Int? = null, // Custom hex color value, only used when colorId == CUSTOM_COLOR_ID
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Get the display color for this event type
     * Returns the appropriate hex color based on whether it's a Google color or custom color
     */
    fun getDisplayColor(): Int? {
        return when {
            GoogleCalendarColors.isCustomColor(colorId) -> customColorHex
            colorId != null -> GoogleCalendarColors.getHexColorById(colorId)
            else -> null
        }
    }
    
    /**
     * Check if this event type uses a custom color
     */
    fun hasCustomColor(): Boolean {
        return GoogleCalendarColors.isCustomColor(colorId)
    }
}
