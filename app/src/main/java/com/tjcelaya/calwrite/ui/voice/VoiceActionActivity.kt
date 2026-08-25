package com.tjcelaya.calwrite.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tjcelaya.calwrite.MainActivity
import com.tjcelaya.calwrite.CalWriteApplication
import com.tjcelaya.calwrite.ui.dialogs.SaveEventDialog
import com.tjcelaya.calwrite.data.database.EventType
import com.tjcelaya.calwrite.voice.VoiceActionHandler
import com.tjcelaya.calwrite.voice.VoiceActionResult
import com.tjcelaya.calwrite.voice.VoiceActionType
import com.tjcelaya.calwrite.voice.VoiceFollowUp
import com.tjcelaya.calwrite.voice.VoiceIntentParser
import com.tjcelaya.calwrite.voice.VoiceRequest
import com.tjcelaya.calwrite.voice.VoiceUndo
import kotlinx.coroutines.launch

/**
 * Where every voice action lands: an Assistant capability intent, a launcher shortcut, or a
 * `calwrite://action/…` deep link from adb or an automation app.
 *
 * Headless by design — no layout, translucent theme, excluded from recents. It parses the
 * intent, runs it through [VoiceActionHandler], shows the result in a [VoiceResultSheet], and
 * finishes when that is dismissed. Same shape as NotificationActionActivity, which does this
 * for notification taps.
 */
class VoiceActionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VoiceActionActivity"

        /** Explicit intent for the shortcuts pushed by [VoiceShortcutPublisher]. */
        fun createIntent(context: Context, action: VoiceActionType, typeName: String?): Intent =
            Intent(context, VoiceActionActivity::class.java).apply {
                setAction(VoiceIntentParser.actionNameFor(action))
                typeName?.let { putExtra(VoiceIntentParser.EXTRA_TYPE_NAME, it) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
    }

    private lateinit var handler: VoiceActionHandler

    private var sheet: VoiceResultSheet? = null

    /** The type the current exchange is about, so a follow-up button knows what to act on. */
    private var subjectTypeName: String? = null
    private var pendingUndo: VoiceUndo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = requestFrom(intent)
        if (request == null) {
            Log.w(TAG, "Unrecognized voice intent: action=${intent.action} data=${intent.data}")
            finish()
            return
        }

        val app = application as CalWriteApplication
        handler = VoiceActionHandler(
            context = this,
            eventRepository = app.eventRepository,
            calendarRepository = app.calendarRepository,
            storagePreferences = app.storagePreferences,
            notificationService = app.notificationService
        )
        subjectTypeName = request.typeQuery

        Log.d(TAG, "Voice request: ${request.action} name=${request.typeQuery}")
        perform(request)
    }

    private fun requestFrom(intent: Intent): VoiceRequest? =
        VoiceIntentParser.fromDeepLink(intent.data?.toString())
            ?: VoiceIntentParser.fromAction(
                intent.action,
                intent.getStringExtra(VoiceIntentParser.EXTRA_TYPE_NAME)
            )

    private fun perform(request: VoiceRequest) {
        lifecycleScope.launch {
            val result = when (request.action) {
                VoiceActionType.START -> handler.start(request.typeQuery)
                VoiceActionType.STOP -> handler.stop(request.typeQuery)
                VoiceActionType.RECORD -> handler.record(request.typeQuery)
                VoiceActionType.EXTEND -> handler.extend(request.typeQuery)
                VoiceActionType.STATUS -> handler.status()
            }
            render(result, request.action)
        }
    }

    private fun render(result: VoiceActionResult, action: VoiceActionType) {
        if (isFinishing || isDestroyed) return

        // The user asked for voice stops to be confirmed, so hand off to the same dialog the
        // notification path uses rather than rendering a sheet over it.
        if (result is VoiceActionResult.NeedsStopConfirmation) {
            showStopConfirmation(result.eventId)
            return
        }

        if (result is VoiceActionResult.Success) {
            pendingUndo = result.undo
            result.eventTypeName?.let { subjectTypeName = it }
        }

        // Replacing rather than stacking: a follow-up answer supersedes what it came from.
        sheet?.dismissWithoutCallback()
        sheet = VoiceResultSheet(
            activity = this,
            result = result,
            onFollowUp = { followUp -> onFollowUp(followUp) },
            onCandidateChosen = { candidate -> onCandidateChosen(candidate, action) },
            onDismissed = { finish() }
        ).also { it.show() }
    }

    /**
     * Reuses SaveEventDialog so a voice stop and a notification stop offer exactly the same
     * choices; anything else would be a second, subtly different way to end an event.
     */
    private fun showStopConfirmation(eventId: Long) {
        val app = application as CalWriteApplication
        lifecycleScope.launch {
            val ongoing = app.eventRepository.getOngoingEventById(eventId)
            val eventType = ongoing?.let { app.eventRepository.getEventTypeById(it.eventTypeId) }
            if (ongoing == null || eventType == null) {
                Log.w(TAG, "Event $eventId is no longer running; nothing to confirm")
                finish()
                return@launch
            }

            SaveEventDialog.show(
                context = this@VoiceActionActivity,
                ongoingEvent = ongoing,
                eventType = eventType,
                onSave = {
                    lifecycleScope.launch {
                        runCatching { app.eventRepository.stopEvent(eventId, app.calendarRepository) }
                            .onFailure { Log.e(TAG, "Failed to save event $eventId", it) }
                        finish()
                    }
                },
                onDiscardWithoutSaving = {
                    lifecycleScope.launch {
                        runCatching { app.eventRepository.stopEventWithoutSaving(eventId) }
                            .onFailure { Log.e(TAG, "Failed to discard event $eventId", it) }
                        finish()
                    }
                },
                onCancel = { finish() }
            )
        }
    }

    private fun onFollowUp(followUp: VoiceFollowUp) {
        when (followUp) {
            VoiceFollowUp.STOP -> perform(VoiceRequest(VoiceActionType.STOP, subjectTypeName))
            VoiceFollowUp.EXTEND -> perform(VoiceRequest(VoiceActionType.EXTEND, subjectTypeName))
            VoiceFollowUp.UNDO -> undo()
            VoiceFollowUp.OPEN_APP -> openApp()
        }
    }

    /** The user picked one of the ambiguous candidates; rerun the original verb against it. */
    private fun onCandidateChosen(candidate: EventType, action: VoiceActionType) {
        subjectTypeName = candidate.name
        perform(VoiceRequest(action, candidate.name))
    }

    private fun undo() {
        val token = pendingUndo
        if (token == null) {
            Log.w(TAG, "Undo requested with nothing to revert")
            finish()
            return
        }
        pendingUndo = null
        lifecycleScope.launch {
            render(handler.undo(token), VoiceActionType.STATUS)
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    override fun onDestroy() {
        sheet?.dismissWithoutCallback()
        sheet = null
        super.onDestroy()
    }
}
