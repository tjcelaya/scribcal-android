package com.tjcelaya.scribcal.ui.voice

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.databinding.ItemVoiceActionBinding
import com.tjcelaya.scribcal.databinding.ItemVoiceOptionBinding
import com.tjcelaya.scribcal.databinding.SheetVoiceResultBinding
import com.tjcelaya.scribcal.voice.DurationFormatter
import com.tjcelaya.scribcal.voice.VoiceActionResult
import com.tjcelaya.scribcal.voice.VoiceFollowUp

/**
 * The popup a voice action lands in, hosted by the translucent [VoiceActionActivity].
 *
 * Assistant cannot speak a custom response back, so this sheet is the entire answer: what
 * happened, what is running, or which event type was meant. It is deliberately a dialog rather
 * than a screen — the user's hands are usually busy, and a full activity would push whatever
 * they were doing out of the way.
 */
class VoiceResultSheet(
    private val activity: Activity,
    private val result: VoiceActionResult,
    private val onFollowUp: (VoiceFollowUp) -> Unit,
    private val onCandidateChosen: (EventType) -> Unit,
    private val onDismissed: () -> Unit
) {

    private companion object {
        /** Long enough to read a confirmation, short enough to be hands-free. */
        const val AUTO_DISMISS_MS = 4_500L
        const val TICK_MS = 1_000L
    }

    private val dialog = BottomSheetDialog(activity)
    private val handler = Handler(Looper.getMainLooper())

    private var binding: SheetVoiceResultBinding? = null
    private var tickingEntries: List<Pair<ItemVoiceOptionBinding, VoiceActionResult.StatusEntry>> =
        emptyList()
    private var renderedAt = 0L

    fun show() {
        val binding = SheetVoiceResultBinding.inflate(LayoutInflater.from(activity))
        this.binding = binding
        renderedAt = SystemClock.elapsedRealtime()

        dialog.setContentView(binding.root)
        dialog.setOnDismissListener {
            teardown()
            onDismissed()
        }

        when (result) {
            is VoiceActionResult.Success -> renderMessage(
                binding,
                result.message,
                result.offeredActions + VoiceFollowUp.OPEN_APP
            )

            is VoiceActionResult.Failure -> renderMessage(
                binding,
                result.message,
                listOf(VoiceFollowUp.OPEN_APP)
            )

            is VoiceActionResult.Status -> renderStatus(binding, result)
            is VoiceActionResult.Ambiguous -> renderAmbiguous(binding, result)
        }

        dialog.show()
    }

    /** Replaces the sheet's content when a follow-up produced a new result. */
    fun dismissWithoutCallback() {
        dialog.setOnDismissListener(null)
        teardown()
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun renderMessage(
        binding: SheetVoiceResultBinding,
        message: String,
        actions: List<VoiceFollowUp>
    ) {
        binding.voiceResultMessage.text = message
        addActions(binding, actions.distinct())
        // Confirmations and errors are read, not operated: close on their own.
        handler.postDelayed({ dialog.dismiss() }, AUTO_DISMISS_MS)
    }

    private fun renderStatus(binding: SheetVoiceResultBinding, status: VoiceActionResult.Status) {
        if (status.entries.isEmpty()) {
            renderMessage(binding, status.title, listOf(VoiceFollowUp.OPEN_APP))
            return
        }

        binding.voiceResultTitle.visibility = View.VISIBLE
        binding.voiceResultTitle.text = status.title
        binding.voiceResultMessage.visibility = View.GONE

        tickingEntries = status.entries.map { entry ->
            val row = ItemVoiceOptionBinding.inflate(
                LayoutInflater.from(activity),
                binding.voiceResultRows,
                true
            )
            row.voiceOptionText.text = entry.label
            row to entry
        }
        handler.postDelayed(tick, TICK_MS)

        addActions(binding, listOf(VoiceFollowUp.OPEN_APP))
    }

    private fun renderAmbiguous(
        binding: SheetVoiceResultBinding,
        ambiguous: VoiceActionResult.Ambiguous
    ) {
        binding.voiceResultMessage.text = activity.getString(R.string.voice_ambiguous_title)

        ambiguous.candidates.forEach { candidate ->
            val row = ItemVoiceOptionBinding.inflate(
                LayoutInflater.from(activity),
                binding.voiceResultRows,
                true
            )
            row.voiceOptionText.text = candidate.name
            candidate.getDisplayColor()?.let {
                row.voiceOptionDot.backgroundTintList = ColorStateList.valueOf(it)
            }
            row.root.setOnClickListener { onCandidateChosen(candidate) }
        }
    }

    private fun addActions(binding: SheetVoiceResultBinding, actions: List<VoiceFollowUp>) {
        if (actions.isEmpty()) return
        binding.voiceResultActionsScroll.visibility = View.VISIBLE

        actions.forEach { action ->
            val button = ItemVoiceActionBinding.inflate(
                LayoutInflater.from(activity),
                binding.voiceResultActions,
                true
            )
            button.root.setText(labelFor(action))
            button.root.setOnClickListener {
                // A tap means the user is engaged; stop the hands-free countdown.
                handler.removeCallbacksAndMessages(null)
                onFollowUp(action)
            }
        }
    }

    private fun labelFor(action: VoiceFollowUp): Int = when (action) {
        VoiceFollowUp.STOP -> R.string.voice_action_stop
        VoiceFollowUp.EXTEND -> R.string.voice_action_extend
        VoiceFollowUp.UNDO -> R.string.voice_action_undo
        VoiceFollowUp.OPEN_APP -> R.string.voice_action_open
    }

    private val tick = object : Runnable {
        override fun run() {
            val sinceRender = SystemClock.elapsedRealtime() - renderedAt
            tickingEntries.forEach { (row, entry) ->
                row.voiceOptionText.text = activity.getString(
                    R.string.voice_status_entry,
                    entry.eventTypeName,
                    DurationFormatter.format(activity, entry.elapsedMs + sinceRender)
                )
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        tickingEntries = emptyList()
        binding = null
    }
}
