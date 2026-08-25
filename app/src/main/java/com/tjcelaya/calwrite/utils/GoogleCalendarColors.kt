package com.tjcelaya.calwrite.utils

import android.graphics.Color

/**
 * Google Calendar API event colors
 * 
 * These match the standard colors available in Google Calendar's web and mobile interfaces.
 * Each color has an ID (1-11) that is used when creating/updating calendar events via CalendarContract.
 * 
 * Reference: https://developers.google.com/calendar/api/v3/reference/colors
 */
object GoogleCalendarColors {
    
    data class CalendarColor(
        val id: Int,
        val name: String,
        val hexColor: Int
    )
    
    // Standard Google Calendar event colors
    val LAVENDER = CalendarColor(1, "Lavender", Color.parseColor("#A4BDFC"))
    val SAGE = CalendarColor(2, "Sage", Color.parseColor("#7AE7BF"))
    val GRAPE = CalendarColor(3, "Grape", Color.parseColor("#DBADFF"))
    val FLAMINGO = CalendarColor(4, "Flamingo", Color.parseColor("#FF887C"))
    val BANANA = CalendarColor(5, "Banana", Color.parseColor("#FBD75B"))
    val TANGERINE = CalendarColor(6, "Tangerine", Color.parseColor("#FFB878"))
    val PEACOCK = CalendarColor(7, "Peacock", Color.parseColor("#46D6DB"))
    val GRAPHITE = CalendarColor(8, "Graphite", Color.parseColor("#E1E1E1"))
    val BLUEBERRY = CalendarColor(9, "Blueberry", Color.parseColor("#5484ED"))
    val BASIL = CalendarColor(10, "Basil", Color.parseColor("#51B749"))
    val TOMATO = CalendarColor(11, "Tomato", Color.parseColor("#DC2127"))
    
    /**
     * All available colors in order
     */
    val ALL_COLORS = listOf(
        LAVENDER,
        SAGE,
        GRAPE,
        FLAMINGO,
        BANANA,
        TANGERINE,
        PEACOCK,
        GRAPHITE,
        BLUEBERRY,
        BASIL,
        TOMATO
    )
    
    /**
     * Get color by ID (1-11)
     * Returns null if ID is not valid
     */
    fun getColorById(id: Int): CalendarColor? {
        return ALL_COLORS.find { it.id == id }
    }
    
    /**
     * Get hex color value by ID
     * Returns null if ID is not valid
     */
    fun getHexColorById(id: Int): Int? {
        return getColorById(id)?.hexColor
    }
    
}
