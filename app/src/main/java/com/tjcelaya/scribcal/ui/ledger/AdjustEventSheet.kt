package com.tjcelaya.scribcal.ui.ledger

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.database.EventWithType
import java.util.Calendar
import java.util.TimeZone

/**
 * Bottom sheet for editing a recorded event's start, end and notes with Material pickers.
 *
 * Instant events hide the end row entirely and save with `end == start`, which is what keeps them
 * instant; letting the user give one an end time would quietly turn it into a timed event.
 */
object AdjustEventSheet {

    fun show(
        context: Context,
        fragmentManager: FragmentManager,
        item: EventWithType,
        onSave: (startTime: Long, endTime: Long, notes: String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_adjust_event, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(view)

        val isInstant = item.isInstant
        var startTime = item.startTime
        var endTime = item.endTime ?: item.startTime

        val title: TextView = view.findViewById(R.id.adjustTitle)
        val startDateButton: MaterialButton = view.findViewById(R.id.startDateButton)
        val startTimeButton: MaterialButton = view.findViewById(R.id.startTimeButton)
        val endRow: View = view.findViewById(R.id.endRow)
        val endDateButton: MaterialButton = view.findViewById(R.id.endDateButton)
        val endTimeButton: MaterialButton = view.findViewById(R.id.endTimeButton)
        val errorText: TextView = view.findViewById(R.id.adjustError)
        val notesInput: TextInputEditText = view.findViewById(R.id.notesInput)
        val cancelButton: MaterialButton = view.findViewById(R.id.cancelButton)
        val saveButton: MaterialButton = view.findViewById(R.id.saveButton)

        title.text = context.getString(R.string.ledger_adjust_title, item.eventTypeName)
        notesInput.setText(item.notes)
        endRow.visibility = if (isInstant) View.GONE else View.VISIBLE

        fun render() {
            startDateButton.text = LedgerFormatting.date(startTime)
            startTimeButton.text = LedgerFormatting.timeOfDay(startTime)
            endDateButton.text = LedgerFormatting.date(endTime)
            endTimeButton.text = LedgerFormatting.timeOfDay(endTime)
        }
        render()

        startDateButton.setOnClickListener {
            pickDate(fragmentManager, context.getString(R.string.ledger_pick_start_date), startTime) {
                startTime = it
                render()
            }
        }
        startTimeButton.setOnClickListener {
            pickTime(fragmentManager, context.getString(R.string.ledger_pick_start_time), startTime) {
                startTime = it
                render()
            }
        }
        endDateButton.setOnClickListener {
            pickDate(fragmentManager, context.getString(R.string.ledger_pick_end_date), endTime) {
                endTime = it
                render()
            }
        }
        endTimeButton.setOnClickListener {
            pickTime(fragmentManager, context.getString(R.string.ledger_pick_end_time), endTime) {
                endTime = it
                render()
            }
        }

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val resolvedEnd = if (isInstant) startTime else endTime
            if (resolvedEnd < startTime) {
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            errorText.visibility = View.GONE
            onSave(startTime, resolvedEnd, notesInput.text?.toString().orEmpty())
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun pickDate(
        fragmentManager: FragmentManager,
        title: String,
        current: Long,
        onPicked: (Long) -> Unit
    ) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(title)
            .setSelection(toUtcSelection(current))
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            onPicked(applyDate(current, utcMillis))
        }
        picker.show(fragmentManager, "adjust_date_$title")
    }

    private fun pickTime(
        fragmentManager: FragmentManager,
        title: String,
        current: Long,
        onPicked: (Long) -> Unit
    ) {
        val calendar = Calendar.getInstance().apply { timeInMillis = current }
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setTitleText(title)
            .setHour(calendar.get(Calendar.HOUR_OF_DAY))
            .setMinute(calendar.get(Calendar.MINUTE))
            .build()
        picker.addOnPositiveButtonClickListener {
            val updated = Calendar.getInstance().apply {
                timeInMillis = current
                set(Calendar.HOUR_OF_DAY, picker.hour)
                set(Calendar.MINUTE, picker.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onPicked(updated.timeInMillis)
        }
        picker.show(fragmentManager, "adjust_time_$title")
    }

    /** MaterialDatePicker works entirely in UTC, so shift the local day into UTC and back. */
    private fun toUtcSelection(localMillis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMillis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun applyDate(currentLocalMillis: Long, pickedUtcMillis: Long): Long {
        val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = pickedUtcMillis
        }
        return Calendar.getInstance().apply {
            timeInMillis = currentLocalMillis
            set(Calendar.YEAR, picked.get(Calendar.YEAR))
            set(Calendar.MONTH, picked.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, picked.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
}
