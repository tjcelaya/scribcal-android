package com.tjcelaya.scribcal.ui.main

import android.app.AlertDialog
import android.content.Context
import com.tjcelaya.scribcal.data.database.EventType

/**
 * Simple event type picker dialog
 */
object EventTypePicker {
    
    fun show(
        context: Context,
        eventTypes: List<EventType>,
        onEventTypeSelected: (EventType) -> Unit
    ) {
        if (eventTypes.isEmpty()) {
            // Show message about no event types
            AlertDialog.Builder(context)
                .setTitle("No Event Types")
                .setMessage("Please add some event types in settings first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        val eventTypeNames = eventTypes.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(context)
            .setTitle("Select Event Type")
            .setItems(eventTypeNames) { dialog, which ->
                val selectedEventType = eventTypes[which]
                onEventTypeSelected(selectedEventType)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
