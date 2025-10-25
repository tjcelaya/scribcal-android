package com.tjcelaya.scribcal.ui.notifications

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent
import com.tjcelaya.scribcal.ui.dialogs.SaveEventDialog
import kotlinx.coroutines.launch

/**
 * Activity that's launched when a user taps on an ongoing event notification.
 * This shows the stop dialog with options (same as in the app).
 * When closed, the activity finishes but can optionally launch MainActivity.
 */
class NotificationActionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NotificationAction"
        const val EXTRA_EVENT_ID = "event_id"
        
        /**
         * Create an intent for opening this activity from a notification tap
         */
        fun createIntent(context: Context, eventId: Long): Intent {
            return Intent(context, NotificationActionActivity::class.java).apply {
                putExtra(EXTRA_EVENT_ID, eventId)
                // These flags ensure the activity opens properly from a notification
                // FLAG_ACTIVITY_NEW_TASK is needed to start activity from non-activity context
                // FLAG_ACTIVITY_SINGLE_TOP prevents creating duplicate activities
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // This activity has no layout - it just shows a dialog
        // When the dialog is dismissed, the activity finishes
        
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1)
        
        if (eventId == -1L) {
            Log.e(TAG, "No valid event ID provided, finishing activity")
            finish()
            return
        }
        
        // Retrieve the ongoing event and show dialog
        retrieveEventAndShowDialog(eventId)
    }
    
    private fun retrieveEventAndShowDialog(eventId: Long) {
        val app = application as ScribCalApplication
        val eventRepository = app.eventRepository
        
        lifecycleScope.launch {
            try {
                // Get event and its event type
                val ongoingEvent = eventRepository.getOngoingEventById(eventId)
                
                if (ongoingEvent == null) {
                    Log.w(TAG, "Event $eventId no longer exists, finishing")
                    finish()
                    return@launch
                }
                
                val eventType = eventRepository.getEventTypeById(ongoingEvent.eventTypeId)
                
                if (eventType == null) {
                    Log.w(TAG, "Event type ${ongoingEvent.eventTypeId} not found, finishing")
                    finish()
                    return@launch
                }
                
                // Show save confirmation dialog
                showSaveEventDialog(ongoingEvent, eventType)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving event data", e)
                finish()
            }
        }
    }
    
    private fun showSaveEventDialog(ongoingEvent: OngoingEvent, eventType: EventType) {
        SaveEventDialog.show(
            context = this,
            ongoingEvent = ongoingEvent,
            eventType = eventType,
            onSave = { saveEvent(ongoingEvent) },
            onDiscardWithoutSaving = { discardEvent(ongoingEvent) },
            onCancel = { finish() }
        )
    }
    
    private fun saveEvent(ongoingEvent: OngoingEvent) {
        val app = application as ScribCalApplication
        val eventRepository = app.eventRepository
        val calendarRepository = app.calendarRepository
        
        lifecycleScope.launch {
            try {
                val success = eventRepository.stopEvent(ongoingEvent.id, calendarRepository)
                if (success) {
                    Log.d(TAG, "Event saved to calendar")
                } else {
                    Log.e(TAG, "Error saving event")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving event", e)
            } finally {
                // Launch main activity and finish this one
                launchMainAndFinish()
            }
        }
    }
    
    private fun discardEvent(ongoingEvent: OngoingEvent) {
        val app = application as ScribCalApplication
        val eventRepository = app.eventRepository
        
        lifecycleScope.launch {
            try {
                val success = eventRepository.stopEventWithoutSaving(ongoingEvent.id)
                if (success) {
                    Log.d(TAG, "Event discarded without saving")
                } else {
                    Log.e(TAG, "Error discarding event")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error discarding event", e)
            } finally {
                // Launch main activity and finish this one
                launchMainAndFinish()
            }
        }
    }
    
    private fun launchMainAndFinish() {
        // Create an intent to launch MainActivity
        val mainIntent = Intent(this, com.tjcelaya.scribcal.MainActivity::class.java).apply {
            // FLAG_ACTIVITY_CLEAR_TOP brings existing MainActivity to front if it exists
            // FLAG_ACTIVITY_SINGLE_TOP prevents creating duplicate MainActivity instances
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        finish()
    }
}