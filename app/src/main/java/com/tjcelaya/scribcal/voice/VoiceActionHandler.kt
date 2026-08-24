package com.tjcelaya.scribcal.voice

import android.content.Context
import android.util.Log
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.ExtendResult
import com.tjcelaya.scribcal.data.database.EventType

/**
 * The single place every voice surface funnels through.
 *
 * Google split assistant integration in two — Assistant consumes App Actions from
 * `shortcuts.xml`, while Gemini's replacement (AppFunctions) is a different API entirely and
 * ignores the former. Both, plus the in-app ledger and any deep link, call the methods here, so
 * adding a surface stays a matter of adapting arguments rather than duplicating behaviour.
 *
 * Every method returns a [VoiceActionResult] instead of throwing: these run from headless
 * activities where an uncaught exception is a silent crash with no feedback to the user.
 */
class VoiceActionHandler(
    private val context: Context,
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository
) {

    private companion object {
        const val TAG = "VoiceActionHandler"
    }

    suspend fun start(typeQuery: String?): VoiceActionResult = guarded {
        when (val resolution = resolve(typeQuery)) {
            is Resolved.Failed -> resolution.result
            is Resolved.Found -> {
                val type = resolution.eventType
                if (eventRepository.getOngoingEventCountForType(type.id) > 0) {
                    VoiceActionResult.Failure(string(R.string.voice_already_running, type.name))
                } else {
                    val eventId = eventRepository.startEvent(type.id)
                    VoiceActionResult.Success(
                        message = string(R.string.voice_started, type.name),
                        eventTypeName = type.name,
                        eventId = eventId,
                        offeredActions = listOf(VoiceFollowUp.STOP)
                    )
                }
            }
        }
    }

    suspend fun stop(typeQuery: String?): VoiceActionResult = guarded {
        if (!calendarRepository.isCalendarSetupComplete()) {
            return@guarded VoiceActionResult.Failure(string(R.string.voice_no_calendar))
        }

        // "stop" with no type is unambiguous when exactly one thing is running, which is the
        // common case; only ask when there is a genuine choice to make.
        val target = if (typeQuery.isNullOrBlank()) {
            val ongoing = eventRepository.getOngoingEventsSync()
            when (ongoing.size) {
                0 -> return@guarded VoiceActionResult.Failure(string(R.string.voice_nothing_running))
                1 -> ongoing.first()
                else -> return@guarded VoiceActionResult.Ambiguous(
                    query = null,
                    candidates = ongoing.mapNotNull { eventRepository.getEventTypeById(it.eventTypeId) }
                )
            }
        } else {
            when (val resolution = resolve(typeQuery)) {
                is Resolved.Failed -> return@guarded resolution.result
                is Resolved.Found -> eventRepository.getOngoingEventForType(resolution.eventType.id)
                    ?: return@guarded VoiceActionResult.Failure(
                        string(R.string.voice_not_running, resolution.eventType.name)
                    )
            }
        }

        val type = eventRepository.getEventTypeById(target.eventTypeId)
        val name = type?.name.orEmpty()
        val elapsed = DurationFormatter.format(context, System.currentTimeMillis() - target.startTime)

        eventRepository.stopEvent(target.id, calendarRepository)

        // stopEvent() reports whether the event was completed locally, not whether it reached the
        // calendar - it swallows sync failures. Voice has no other feedback channel, so claiming
        // "saved" when nothing was written is the one thing we must not do. A persisted
        // calendarEventId is the only honest evidence the write landed.
        val reachedCalendar = eventRepository.getEventByIdOrNull(target.id)?.calendarEventId != null
        val message = if (reachedCalendar) {
            string(R.string.voice_stopped, name, elapsed)
        } else {
            string(R.string.voice_stopped_unsynced, name, elapsed)
        }

        VoiceActionResult.Success(
            message = message,
            eventTypeName = name,
            eventId = target.id,
            undo = VoiceUndo.DeleteEvent(target.id),
            offeredActions = listOf(VoiceFollowUp.EXTEND, VoiceFollowUp.UNDO)
        )
    }

    suspend fun record(typeQuery: String?): VoiceActionResult = guarded {
        if (!calendarRepository.isCalendarSetupComplete()) {
            return@guarded VoiceActionResult.Failure(string(R.string.voice_no_calendar))
        }
        when (val resolution = resolve(typeQuery)) {
            is Resolved.Failed -> resolution.result
            is Resolved.Found -> {
                val type = resolution.eventType
                val eventId = eventRepository.recordInstantaneousEvent(type.id, calendarRepository)
                val reachedCalendar =
                    eventRepository.getEventByIdOrNull(eventId)?.calendarEventId != null
                VoiceActionResult.Success(
                    message = if (reachedCalendar) {
                        string(R.string.voice_recorded, type.name)
                    } else {
                        string(R.string.voice_recorded_unsynced, type.name)
                    },
                    eventTypeName = type.name,
                    eventId = eventId,
                    undo = VoiceUndo.DeleteEvent(eventId),
                    offeredActions = listOf(VoiceFollowUp.UNDO)
                )
            }
        }
    }

    /**
     * Move the last timed event's end time to now. Instant events are not extendable, so a type
     * whose most recent entry was instant reports that rather than silently rewriting it.
     */
    suspend fun extend(typeQuery: String?): VoiceActionResult = guarded {
        val type: EventType? = if (typeQuery.isNullOrBlank()) {
            null
        } else {
            when (val resolution = resolve(typeQuery)) {
                is Resolved.Failed -> return@guarded resolution.result
                is Resolved.Found -> resolution.eventType
            }
        }

        val result = eventRepository.extendLastEvent(type?.id, calendarRepository)
            ?: return@guarded VoiceActionResult.Failure(
                if (type != null) {
                    string(R.string.voice_extend_nothing_for_type, type.name)
                } else {
                    string(R.string.voice_extend_nothing)
                }
            )

        val name = eventRepository.getEventTypeById(result.eventTypeId)?.name.orEmpty()
        if (!result.extended) {
            return@guarded VoiceActionResult.Failure(string(R.string.voice_extend_already_current, name))
        }

        VoiceActionResult.Success(
            message = string(
                R.string.voice_extended,
                name,
                DurationFormatter.format(context, result.newDurationMs)
            ),
            eventTypeName = name,
            eventId = result.eventId,
            undo = VoiceUndo.RevertExtend(result),
            offeredActions = listOf(VoiceFollowUp.UNDO)
        )
    }

    /**
     * Answer "what am I tracking?". Assistant cannot speak a response back for a custom action,
     * so the caller renders this — see VoiceResultSheet.
     */
    suspend fun status(): VoiceActionResult = guarded {
        val ongoing = eventRepository.getOngoingEventsSync()
        if (ongoing.isEmpty()) {
            return@guarded VoiceActionResult.Status(
                title = string(R.string.voice_status_idle),
                entries = emptyList()
            )
        }

        val now = System.currentTimeMillis()
        val entries = ongoing.map { event ->
            val name = eventRepository.getEventTypeById(event.eventTypeId)?.name.orEmpty()
            VoiceActionResult.StatusEntry(
                eventId = event.id,
                eventTypeName = name,
                elapsedMs = now - event.startTime,
                label = string(
                    R.string.voice_status_entry,
                    name,
                    DurationFormatter.format(context, now - event.startTime)
                )
            )
        }
        VoiceActionResult.Status(title = string(R.string.voice_status_title), entries = entries)
    }

    suspend fun undo(token: VoiceUndo): VoiceActionResult = guarded {
        when (token) {
            is VoiceUndo.DeleteEvent -> {
                val event = eventRepository.getEventByIdOrNull(token.eventId)
                event?.calendarEventId?.let { calendarRepository.deleteCalendarEvent(it) }
                eventRepository.deleteEventById(token.eventId)
                VoiceActionResult.Success(string(R.string.voice_undone))
            }

            is VoiceUndo.RevertExtend -> {
                eventRepository.revertExtend(token.result, calendarRepository)
                VoiceActionResult.Success(string(R.string.voice_undone))
            }
        }
    }

    // === internals ===

    private sealed interface Resolved {
        data class Found(val eventType: EventType) : Resolved
        data class Failed(val result: VoiceActionResult) : Resolved
    }

    private suspend fun resolve(typeQuery: String?): Resolved {
        if (typeQuery.isNullOrBlank()) {
            return Resolved.Failed(VoiceActionResult.Failure(string(R.string.voice_no_type_given)))
        }
        val types = eventRepository.getAllEventTypesSync()
        return when (val resolution = EventTypeMatcher.resolve(typeQuery, types)) {
            is EventTypeMatcher.Resolution.Match -> Resolved.Found(resolution.eventType)
            is EventTypeMatcher.Resolution.Ambiguous -> Resolved.Failed(
                VoiceActionResult.Ambiguous(typeQuery, resolution.candidates)
            )
            EventTypeMatcher.Resolution.None -> Resolved.Failed(
                VoiceActionResult.Failure(string(R.string.voice_unknown_type, typeQuery))
            )
        }
    }

    /**
     * The repository signals "already running" and "no calendar selected" by throwing
     * IllegalStateException. Those are ordinary outcomes here, not crashes.
     */
    private inline fun guarded(block: () -> VoiceActionResult): VoiceActionResult = try {
        block()
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Voice action rejected", e)
        VoiceActionResult.Failure(e.message ?: string(R.string.voice_error_generic))
    } catch (e: Exception) {
        Log.e(TAG, "Voice action failed", e)
        VoiceActionResult.Failure(string(R.string.voice_error_generic))
    }

    private fun string(resId: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
}
