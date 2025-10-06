package com.tjcelaya.scribcal.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.utils.CalendarInfo
import kotlinx.coroutines.launch

class CalendarSetupFragment : Fragment() {

    private lateinit var calendarRepository: CalendarRepository
    private lateinit var containerLayout: LinearLayout
    private lateinit var statusText: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val calendarReadGranted = permissions[Manifest.permission.READ_CALENDAR] ?: false
        val calendarWriteGranted = permissions[Manifest.permission.WRITE_CALENDAR] ?: false

        if (calendarReadGranted && calendarWriteGranted) {
            loadCalendars()
        } else {
            statusText.text = "Calendar permissions are required to select a calendar"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        calendarRepository = CalendarRepository(requireContext())

        containerLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusText = TextView(requireContext()).apply {
            text = "Loading calendars..."
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        containerLayout.addView(statusText)

        checkPermissionsAndLoadCalendars()

        return containerLayout
    }

    private fun checkPermissionsAndLoadCalendars() {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        val hasWritePermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasReadPermission || !hasWritePermission) {
            statusText.text = "Requesting calendar permissions..."
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            )
        } else {
            loadCalendars()
        }
    }

    private fun loadCalendars() {
        lifecycleScope.launch {
            try {
                val calendars = calendarRepository.getAvailableCalendars()

                if (calendars.isEmpty()) {
                    statusText.text = "No writable calendars found on this device"
                    return@launch
                }

                statusText.text = "Select a calendar to store your events:"

                calendars.forEach { calendar ->
                    val card = createCalendarCard(calendar)
                    containerLayout.addView(card)
                }

            } catch (e: Exception) {
                statusText.text = "Error loading calendars: ${e.message}"
            }
        }
    }

    private fun createCalendarCard(calendar: CalendarInfo): MaterialCardView {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            cardElevation = 4f
            radius = 8f
        }

        val cardContent = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val titleText = TextView(requireContext()).apply {
            text = calendar.displayName
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val subtitleText = TextView(requireContext()).apply {
            text = "${calendar.accountName} (${calendar.accountType})"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }

        val selectButton = MaterialButton(requireContext()).apply {
            text = "Select This Calendar"
            setOnClickListener {
                selectCalendar(calendar)
            }
        }

        cardContent.addView(titleText)
        cardContent.addView(subtitleText)
        cardContent.addView(selectButton)
        card.addView(cardContent)

        return card
    }

    private fun selectCalendar(calendar: CalendarInfo) {
        calendarRepository.setSelectedCalendar(calendar)
        Toast.makeText(
            requireContext(),
            "Selected calendar: ${calendar.displayName}",
            Toast.LENGTH_SHORT
        ).show()

        // Navigate back
        findNavController().navigateUp()
    }
}
