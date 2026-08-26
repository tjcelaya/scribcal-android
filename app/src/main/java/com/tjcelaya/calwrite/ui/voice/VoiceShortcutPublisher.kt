package com.tjcelaya.calwrite.ui.voice

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.tjcelaya.calwrite.R
import com.tjcelaya.calwrite.data.EventRepository
import com.tjcelaya.calwrite.data.database.EventType
import com.tjcelaya.calwrite.voice.VoiceActionType
import com.tjcelaya.calwrite.voice.VoiceShortcutPlan

/**
 * Publishes one dynamic shortcut per event type, each bound to an Assistant capability.
 *
 * This inline inventory is what makes *user-defined* type names resolve by voice: the BIIs in
 * `res/xml/shortcuts.xml` only cover exercise-shaped phrases, but a shortcut whose label is
 * "Start Coffee" lets Assistant match "start Coffee on CalWrite". Launcher long-press entries
 * come along for free.
 */
class VoiceShortcutPublisher(
    private val context: Context,
    private val eventRepository: EventRepository
) {

    private companion object {
        const val TAG = "VoiceShortcutPublisher"

        /**
         * Slots left for shortcuts this class does not own: the static "what am I tracking"
         * entry in shortcuts.xml and the `event_ongoing_…` bubble shortcuts NotificationService
         * pushes while events are running. Without this the launcher would evict those.
         */
        const val RESERVED_SLOTS = 5

        const val CAPABILITY_START = "actions.intent.START_EXERCISE"
        const val CAPABILITY_STOP = "actions.intent.STOP_EXERCISE"
        const val CAPABILITY_TRACK = "actions.intent.TRACK_EXERCISE"
        const val PARAMETER_NAME = "exercise.name"
    }

    /** Safe to call repeatedly; run it again whenever event types change. */
    suspend fun publish() {
        try {
            val types = eventRepository.getAllEventTypesSync()
            val lastUsed = types.associate { it.id to eventRepository.getLastCompletedEventTime(it.id) }
            val ranked = VoiceShortcutPlan.rank(types, lastUsed)

            val budget = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) -
                RESERVED_SLOTS).coerceAtLeast(0)
            val plan = VoiceShortcutPlan.plan(ranked, budget)

            val published = mutableSetOf<String>()
            plan.entries.forEach { entry ->
                val shortcut = build(entry.eventType, entry.action)
                if (ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)) {
                    published += shortcut.id
                } else {
                    Log.w(TAG, "Launcher refused shortcut ${shortcut.id}")
                }
            }

            removeStale(published)

            if (plan.dropped.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Shortcut budget $budget exhausted; no voice shortcut for: " +
                        plan.dropped.joinToString { it.name }
                )
            }
            Log.d(TAG, "Published ${published.size} voice shortcuts of budget $budget")
        } catch (e: Exception) {
            // Shortcuts are an enhancement; never let them take down application startup.
            Log.e(TAG, "Failed to publish voice shortcuts", e)
        }
    }

    private fun build(eventType: EventType, action: VoiceActionType): ShortcutInfoCompat {
        val label = context.getString(labelFor(action), eventType.name)
        return ShortcutInfoCompat.Builder(
            context,
            VoiceShortcutPlan.shortcutId(eventType.id, action)
        )
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, iconFor(action)))
            .setIntent(VoiceActionActivity.createIntent(context, action, eventType.name))
            .addCapabilityBinding(capabilityFor(action), PARAMETER_NAME, listOf(eventType.name))
            .setLongLived(true)
            .build()
    }

    /** Drops shortcuts for types that were renamed, deleted, or lost their slot. */
    private fun removeStale(published: Set<String>) {
        val stale = ShortcutManagerCompat.getDynamicShortcuts(context)
            .map { it.id }
            .filter { VoiceShortcutPlan.isVoiceShortcutId(it) && it !in published }

        if (stale.isNotEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
            Log.d(TAG, "Removed stale voice shortcuts: $stale")
        }
    }

    private fun capabilityFor(action: VoiceActionType): String = when (action) {
        VoiceActionType.STOP -> CAPABILITY_STOP
        VoiceActionType.RECORD -> CAPABILITY_TRACK
        else -> CAPABILITY_START
    }

    private fun labelFor(action: VoiceActionType): Int = when (action) {
        VoiceActionType.STOP -> R.string.voice_shortcut_stop
        VoiceActionType.RECORD -> R.string.voice_shortcut_record
        else -> R.string.voice_shortcut_start
    }

    private fun iconFor(action: VoiceActionType): Int = when (action) {
        VoiceActionType.STOP -> R.drawable.ic_stop
        VoiceActionType.RECORD -> R.drawable.ic_flash
        else -> R.drawable.ic_play
    }
}
