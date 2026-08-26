package com.tjcelaya.calwrite.voice

import com.tjcelaya.calwrite.data.ExtendResult
import com.tjcelaya.calwrite.data.database.EventType

/**
 * What a voice surface should tell the user, and what it can offer next.
 *
 * Assistant cannot speak a custom response back, so every case carries display-ready text rather
 * than an error code — the caller renders it (see VoiceResultSheet) instead of interpreting it.
 */
sealed interface VoiceActionResult {

    data class Success(
        val message: String,
        val eventTypeName: String? = null,
        val eventId: Long? = null,
        val undo: VoiceUndo? = null,
        val offeredActions: List<VoiceFollowUp> = emptyList()
    ) : VoiceActionResult

    /** A question, not an error: more than one event type matched, so ask instead of guessing. */
    data class Ambiguous(
        val query: String?,
        val candidates: List<EventType>
    ) : VoiceActionResult

    data class Failure(val message: String) : VoiceActionResult

    /**
     * The user chose CONFIRM_DIALOG for voice stops, so the running event is left alone and the
     * surface is expected to show the same save/discard dialog the app uses in-app. The handler
     * has no UI of its own, so it reports the intent rather than acting on it.
     */
    data class NeedsStopConfirmation(
        val eventId: Long,
        val eventTypeName: String
    ) : VoiceActionResult

    /** Answer to "what am I tracking?" — [entries] is empty when nothing is running. */
    data class Status(
        val title: String,
        val entries: List<StatusEntry>
    ) : VoiceActionResult

    data class StatusEntry(
        val eventId: Long,
        val eventTypeName: String,
        val elapsedMs: Long,
        val label: String
    )
}

/** A reversible change, held so the result sheet or a notification can offer Undo. */
sealed interface VoiceUndo {
    /** Removes the event locally and from the calendar. */
    data class DeleteEvent(val eventId: Long) : VoiceUndo

    /** Restores the end time an extend moved. */
    data class RevertExtend(val result: ExtendResult) : VoiceUndo
}

/** Buttons a result sheet may offer alongside a confirmation. */
enum class VoiceFollowUp { STOP, EXTEND, UNDO, OPEN_APP }
