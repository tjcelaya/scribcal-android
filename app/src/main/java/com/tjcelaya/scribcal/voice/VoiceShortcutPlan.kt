package com.tjcelaya.scribcal.voice

import com.tjcelaya.scribcal.data.database.EventType

/**
 * Decides which event types get a voice shortcut when there are more types than slots.
 *
 * The launcher caps dynamic shortcuts per activity (15 on most devices) and ScribCal already
 * spends some of that budget on the ongoing-event bubble shortcuts pushed from
 * NotificationService. Rather than let `pushDynamicShortcut` silently evict whatever it likes,
 * the choice is made here — pure Kotlin, so the ranking and the truncation are unit-testable —
 * and the caller logs what did not fit.
 */
object VoiceShortcutPlan {

    /** Shortcut id prefixes; must not collide with NotificationService's `event_ongoing_…`. */
    const val START_PREFIX = "voice_start_"
    const val STOP_PREFIX = "voice_stop_"
    const val RECORD_PREFIX = "voice_record_"

    data class Entry(val eventType: EventType, val action: VoiceActionType)

    data class Plan(
        val entries: List<Entry>,
        /** Types that had no slot left, in the order they lost out. */
        val dropped: List<EventType>
    )

    fun shortcutId(eventTypeId: Long, action: VoiceActionType): String = when (action) {
        VoiceActionType.STOP -> "$STOP_PREFIX$eventTypeId"
        VoiceActionType.RECORD -> "$RECORD_PREFIX$eventTypeId"
        else -> "$START_PREFIX$eventTypeId"
    }

    fun isVoiceShortcutId(id: String): Boolean =
        id.startsWith(START_PREFIX) || id.startsWith(STOP_PREFIX) || id.startsWith(RECORD_PREFIX)

    /**
     * User-chosen order first, then recency, so an untouched sortOrder still yields a sensible
     * list for someone who has never reordered their types.
     */
    fun rank(eventTypes: List<EventType>, lastUsed: Map<Long, Long?>): List<EventType> =
        eventTypes.sortedWith(
            compareBy<EventType> { it.sortOrder }
                .thenByDescending { lastUsed[it.id] ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() }
        )

    /**
     * Every ranked type gets one primary shortcut first — start, or record for a type that can
     * only be recorded instantly — and stop shortcuts fill whatever is left. Starting a type is
     * the action with no other hands-free route; stopping one is already an action on its
     * ongoing notification, so it is the right thing to lose when the budget runs out.
     */
    fun plan(ranked: List<EventType>, budget: Int): Plan {
        if (budget <= 0) return Plan(emptyList(), ranked)

        val primary = ranked.map { type ->
            val action =
                if (type.cadence.showsTimed()) VoiceActionType.START else VoiceActionType.RECORD
            Entry(type, action)
        }.take(budget)

        val stops = primary
            .filter { it.action == VoiceActionType.START }
            .take(budget - primary.size)
            .map { Entry(it.eventType, VoiceActionType.STOP) }

        return Plan(
            entries = primary + stops,
            dropped = ranked.drop(primary.size)
        )
    }
}
