package com.tjcelaya.scribcal.ui.dialogs

import android.content.Context
import androidx.appcompat.app.AlertDialog

object NotificationPermissionDialog {
    
    fun show(context: Context, onPermissionRequested: () -> Unit, onSkipped: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Optional Notifications")
            .setMessage(
                """Notifications are optional but can be helpful for ongoing events.

Benefits:
• See ongoing event timers in your notification panel
• Quickly return to the app when an event is running
• Tap notification to quickly stop an event

You can change this setting later in your device's app settings."""
            )
            .setPositiveButton("Allow Notifications") { _, _ ->
                onPermissionRequested()
            }
            .setNegativeButton("Skip") { _, _ ->
                onSkipped()
            }
            .setCancelable(false)
            .show()
    }
}