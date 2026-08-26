package com.tjcelaya.calwrite.appfunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.service.AppFunction
import com.tjcelaya.calwrite.CalWriteApplication
import com.tjcelaya.calwrite.voice.VoiceActionHandler
import com.tjcelaya.calwrite.voice.VoiceActionResult

/**
 * Exposes CalWrite's actions to Gemini and other on-device agents.
 *
 * This is the Gemini half of voice control, and a completely separate API from the Assistant half
 * (App Actions declared in `res/xml/shortcuts.xml`) — Gemini ignores Assistant shortcuts entirely.
 * Both funnel into the same [VoiceActionHandler] so the behaviour lives in one place.
 *
 * Built only when `-Pcalwrite.appfunctions=true`, and off by default: the API is alpha, needs
 * Android 16, and is in a private preview where Gemini cannot yet invoke third-party functions
 * outside adb testing. This exists so the wiring is ready, not because it works today.
 *
 * The KSP compiler generates the registration and the XML metadata; the library declares its own
 * service in its manifest, so nothing is needed in ours. The class is deliberately no-arg
 * constructible — every function receives an [AppFunctionContext], which is enough to reach the
 * application, so no `AppFunctionConfiguration.Provider` wiring is required.
 */
class CalWriteAppFunctions {

    /**
     * Start timing an event type, for example "start my exercise".
     *
     * Fails if that event type is already being tracked — CalWrite keeps one live event per type.
     *
     * @param eventType the event type to start, as the user said it.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun startEvent(
        appFunctionContext: AppFunctionContext,
        eventType: String
    ): ActionOutcome = appFunctionContext.handler().start(eventType).toOutcome()

    /**
     * Stop the event currently being tracked and write it to the calendar.
     *
     * With no event type given this stops the only thing running; if several are running it
     * reports the choices rather than picking one.
     *
     * @param eventType the event type to stop, or null to stop whatever is running.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun stopEvent(
        appFunctionContext: AppFunctionContext,
        eventType: String? = null
    ): ActionOutcome = appFunctionContext.handler().stop(eventType).toOutcome()

    /**
     * Record something that happened just now as a zero-duration event, for example "log a coffee".
     *
     * @param eventType the event type to record, as the user said it.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun recordEvent(
        appFunctionContext: AppFunctionContext,
        eventType: String
    ): ActionOutcome = appFunctionContext.handler().record(eventType).toOutcome()

    /**
     * Extend the most recently finished timed event so that it now ends at the current moment.
     *
     * Applies only to timed events. An instant event records a moment rather than a span, so it is
     * skipped rather than stretched into a duration.
     *
     * @param eventType the event type to extend, or null for the most recent timed event.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun extendEvent(
        appFunctionContext: AppFunctionContext,
        eventType: String? = null
    ): ActionOutcome = appFunctionContext.handler().extend(eventType).toOutcome()

    /**
     * Report which event types are currently being tracked, and for how long.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun whatAmITracking(
        appFunctionContext: AppFunctionContext
    ): ActionOutcome = appFunctionContext.handler().status().toOutcome()

    private fun AppFunctionContext.handler(): VoiceActionHandler {
        val app = context.applicationContext as CalWriteApplication
        return VoiceActionHandler(app, app.eventRepository, app.calendarRepository)
    }

    /**
     * Flattens a handler result into something an agent can read back.
     *
     * Ambiguity reports [succeeded] = false with [choices] populated: the agent should ask which
     * was meant rather than pick one, since guessing writes the wrong thing to a calendar.
     */
    private fun VoiceActionResult.toOutcome(): ActionOutcome = when (this) {
        is VoiceActionResult.Success -> ActionOutcome(succeeded = true, message = message)

        is VoiceActionResult.Failure -> ActionOutcome(succeeded = false, message = message)

        is VoiceActionResult.Ambiguous -> ActionOutcome(
            succeeded = false,
            message = "Several event types match \"${query.orEmpty()}\".",
            choices = candidates.map { it.name }
        )

        is VoiceActionResult.Status -> ActionOutcome(
            succeeded = true,
            message = if (entries.isEmpty()) {
                title
            } else {
                title + ": " + entries.joinToString(", ") { it.label }
            }
        )
    }
}

/**
 * The result of a CalWrite action.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ActionOutcome(
    /** Whether the action was carried out. False for refusals and for unresolved ambiguity. */
    val succeeded: Boolean,
    /** Human-readable description of what happened, suitable for reading aloud. */
    val message: String,
    /** Candidate event type names when the request matched more than one; empty otherwise. */
    val choices: List<String> = emptyList()
)
