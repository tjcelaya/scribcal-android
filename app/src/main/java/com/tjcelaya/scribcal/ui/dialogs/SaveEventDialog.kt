package com.tjcelaya.scribcal.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shared dialog for saving an ongoing event.
 * Used both from in-app UI and notification taps.
 */
object SaveEventDialog {
    
    /**
     * Show the save event confirmation dialog
     * 
     * @param context The context to show the dialog in
     * @param ongoingEvent The event to save
     * @param eventType The type of the event
     * @param onSave Callback when user confirms to save the event
     * @param onDiscardWithoutSaving Callback when user chooses to discard without saving
     * @param onCancel Callback when user cancels
     */
    fun show(
        context: Context,
        ongoingEvent: OngoingEvent,
        eventType: EventType,
        onSave: () -> Unit,
        onDiscardWithoutSaving: () -> Unit,
        onCancel: () -> Unit
    ) {
        // Calculate elapsed time for display
        val elapsedMillis = System.currentTimeMillis() - ongoingEvent.startTime
        val elapsedSeconds = elapsedMillis / 1000
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        val elapsedTimeString = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)

        val startTime = Date(ongoingEvent.startTime)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        
        // Build message with consistent formatting
        val message = buildString {
            append("Elapsed time: $elapsedTimeString\n")
            append("Started at: ${timeFormat.format(startTime)}\n\n")
            append("The event will be saved to your calendar.")
        }
        
        AlertDialog.Builder(context)
            .setTitle("Save ${eventType.name}")
            .setMessage(message)
            .setPositiveButton("Save") { _, _ ->
                onSave()
            }
            .setNeutralButton("Discard without saving") { _, _ ->
                onDiscardWithoutSaving()
            }
            .setNegativeButton("Cancel") { _, _ ->
                onCancel()
            }
            .setOnCancelListener {
                onCancel()
            }
            .show()
    }
}
