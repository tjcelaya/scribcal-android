package com.tjcelaya.scribcal.ui.main

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.util.PhotoUtils

/**
 * Dialog for creating events with photo attachments
 */
object PhotoEventDialog {
    
    fun show(
        context: Context,
        photoPath: String,
        eventTypes: List<EventType>,
        onEventCreated: (EventType, String, Boolean) -> Unit // eventType, notes, isInstant
    ) {
        if (eventTypes.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("No Event Types")
                .setMessage("Please add some event types in settings first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        // Create custom layout
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_photo_event, null)
        
        // Setup views
        val photoPreview = view.findViewById<ImageView>(R.id.photoPreview)
        val eventTypeSpinner = view.findViewById<Spinner>(R.id.eventTypeSpinner)
        val notesEditText = view.findViewById<EditText>(R.id.notesEditText)
        val instantEventRadio = view.findViewById<RadioButton>(R.id.instantEventRadio)
        val timedEventRadio = view.findViewById<RadioButton>(R.id.timedEventRadio)
        
        // Load photo thumbnail
        val thumbnail = PhotoUtils.createThumbnail(photoPath)
        if (thumbnail != null) {
            photoPreview.setImageBitmap(thumbnail)
            photoPreview.visibility = View.VISIBLE
        } else {
            photoPreview.visibility = View.GONE
        }
        
        // Setup event type spinner
        val eventTypeNames = eventTypes.map { it.name }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, eventTypeNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        eventTypeSpinner.adapter = adapter
        
        // Pre-select first event type
        eventTypeSpinner.setSelection(0)
        
        // Default to instant event
        instantEventRadio.isChecked = true
        
        val dialog = AlertDialog.Builder(context)
            .setTitle("Create Event with Photo")
            .setView(view)
            .setPositiveButton("Create Event") { _, _ ->
                val selectedPosition = eventTypeSpinner.selectedItemPosition
                val selectedEventType = eventTypes[selectedPosition]
                val notes = notesEditText.text.toString().trim()
                val isInstant = instantEventRadio.isChecked
                
                onEventCreated(selectedEventType, notes, isInstant)
            }
            .setNegativeButton("Cancel", null)
            .create()
            
        dialog.show()
    }
}