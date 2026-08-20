package com.tjcelaya.scribcal.ui.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.StoragePreferences
import com.tjcelaya.scribcal.data.database.OngoingEvent
import com.tjcelaya.scribcal.data.database.EventType
import java.text.SimpleDateFormat
import java.util.*

class NotificationService(
    private val context: Context,
    private val storagePreferences: StoragePreferences
) {
    
    companion object {
        // New channel ID so we can raise importance for bubbles without requiring users to change settings
        private const val CHANNEL_ID = "ongoing_events_v2"
        private const val CHANNEL_NAME = "Ongoing Events"
        private const val CHANNEL_DESCRIPTION = "Notifications for ongoing events"
        private const val NOTIFICATION_ID_BASE = 1000
    }
    
    private val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(context)
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Delete old channel if it exists to recreate with new settings
            try {
                notificationManager.deleteNotificationChannel(CHANNEL_ID)
            } catch (e: Exception) {
                // Ignore if doesn't exist
            }
            
            // Bubbles require at least IMPORTANCE_HIGH to work reliably
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Explicitly allow bubbles
                    setAllowBubbles(true)
                    Log.d("NotificationService", "Channel configured to allow bubbles")
                }
            }

            notificationManager.createNotificationChannel(channel)
            Log.d("NotificationService", "Notification channel created with importance=$importance")
        }
    }
    
    @SuppressLint("MissingPermission") // Permission checked via areNotificationsEnabled()
    fun showOngoingEventNotification(ongoingEvent: OngoingEvent, eventType: EventType) {
        val notificationId = getNotificationId(ongoingEvent.id)
        
        Log.d("NotificationService", "showOngoingEventNotification called for event ${ongoingEvent.id}, type: ${eventType.name}, notificationId: $notificationId")
        
        // Check if notifications are enabled
        if (!areNotificationsEnabled()) {
            Log.w("NotificationService", "Notifications are not enabled for this app")
            return
        }
        
        // Create intent to open the notification action activity when notification is tapped
        val intent = NotificationActionActivity.createIntent(context, ongoingEvent.id)
        
        // Bubble notifications require mutable PendingIntents
        val pendingIntentFlags = if (storagePreferences.areBubblesEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 
            notificationId, 
            intent, 
            pendingIntentFlags
        )
        
        // Format start time
        val startTime = Date(ongoingEvent.startTime)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val formattedTime = timeFormat.format(startTime)
        
        Log.d("NotificationService", "Creating notification: Title='${eventType.name}', Text='Started at $formattedTime'")
        
        // Determine if this notification should bubble based on mode and event type
        val bubbleMode = storagePreferences.getBubbleMode()
        val shouldBubble = when (bubbleMode) {
            com.tjcelaya.scribcal.data.BubbleMode.NEVER -> false
            com.tjcelaya.scribcal.data.BubbleMode.SELECTED -> eventType.shouldBubble
            com.tjcelaya.scribcal.data.BubbleMode.ALWAYS -> true
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(eventType.name)
            .setContentText("Started at $formattedTime")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setWhen(ongoingEvent.startTime)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        // Set priority and silence based on bubble mode
        if (shouldBubble) {
            // Bubbles need high priority and cannot be silent
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        } else {
            // Silent notifications for non-bubble mode
            builder
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
        }

        // Attach bubble metadata if should bubble and supported
        if (shouldBubble && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // Create a shortcut ID for this event type
                val shortcutId = "event_ongoing_${eventType.id}"
                
                // Create a person for the shortcut (required for bubbles)
                val person = Person.Builder()
                    .setName(eventType.name)
                    .setImportant(true)
                    .build()
                
                // Create the shortcut
                val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                    .setShortLabel(eventType.name)
                    .setLongLabel("Ongoing: ${eventType.name}")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_play))
                    .setIntent(intent) // Use the same intent as the notification
                    .setLongLived(true)
                    .setPerson(person)
                    .build()
                
                // Push the shortcut to the system
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                Log.d("NotificationService", "Created bubble shortcut: $shortcutId")
                
                // Now create the bubble metadata
                val bubbleIcon = IconCompat.createWithResource(context, R.drawable.ic_play)
                val bubble = NotificationCompat.BubbleMetadata.Builder(pendingIntent, bubbleIcon)
                    .setDesiredHeight(600)
                    .setAutoExpandBubble(false)
                    .setSuppressNotification(false)
                    .build()
                
                // Associate the shortcut with the notification
                builder
                    .setShortcutId(shortcutId)
                    .setBubbleMetadata(bubble)
                
                Log.d("NotificationService", "Bubble metadata configured with shortcut")
            } catch (e: Exception) {
                Log.e("NotificationService", "Failed to configure bubble metadata", e)
            }
        }

        val notification = builder.build()
        
        try {
            Log.d("NotificationService", "Attempting to show notification with ID: $notificationId")
            notificationManager.notify(notificationId, notification)
            Log.d("NotificationService", "Successfully posted notification")
        } catch (e: SecurityException) {
            // Handle case where notification permission is denied
            Log.w("NotificationService", "SecurityException when posting notification - permission likely denied", e)
        } catch (e: Exception) {
            Log.e("NotificationService", "Unexpected exception when posting notification", e)
        }
    }
    
    fun updateOngoingEventNotification(ongoingEvent: OngoingEvent, eventType: EventType) {
        // For ongoing notifications with chronometer, we don't need to manually update
        // The system handles the elapsed time display automatically
        // But we can call this if we want to update other content
        showOngoingEventNotification(ongoingEvent, eventType)
    }
    
    fun dismissOngoingEventNotification(ongoingEventId: Long) {
        val notificationId = getNotificationId(ongoingEventId)
        notificationManager.cancel(notificationId)
    }
    
    fun dismissAllOngoingEventNotifications() {
        // We could keep track of all notification IDs, but for now we'll just cancel a range
        for (i in 0..100) {
            notificationManager.cancel(NOTIFICATION_ID_BASE + i)
        }
    }
    
    private fun getNotificationId(ongoingEventId: Long): Int {
        // Convert ongoing event ID to a unique notification ID
        return NOTIFICATION_ID_BASE + (ongoingEventId % Int.MAX_VALUE).toInt()
    }
    
    fun areNotificationsEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
            val appNotificationsEnabled = notificationManager.areNotificationsEnabled()
            val channelEnabled = channel?.importance != NotificationManager.IMPORTANCE_NONE
            
            Log.d("NotificationService", "App notifications enabled: $appNotificationsEnabled, Channel enabled: $channelEnabled")
            appNotificationsEnabled && channelEnabled
        } else {
            val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            Log.d("NotificationService", "Notifications enabled (pre-O): $enabled")
            enabled
        }
    }
    
    /**
     * Test method to show a simple notification to verify notifications are working
     */
    @SuppressLint("MissingPermission")
    fun showTestNotification() {
        Log.d("NotificationService", "showTestNotification called")
        
        if (!areNotificationsEnabled()) {
            Log.w("NotificationService", "Cannot show test notification - notifications disabled")
            return
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("ScribCal Test")
            .setContentText("Notifications are working!")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
            
        try {
            Log.d("NotificationService", "Posting test notification")
            if (areNotificationsEnabled()) {
                notificationManager.notify(999, notification)
                Log.d("NotificationService", "Test notification posted successfully")
            } else {
                Log.w("NotificationService", "Cannot post test notification - notifications disabled")
            }
        } catch (e: Exception) {
            Log.e("NotificationService", "Failed to post test notification", e)
        }
    }
}