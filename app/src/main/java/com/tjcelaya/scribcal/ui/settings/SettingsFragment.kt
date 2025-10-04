package com.tjcelaya.scribcal.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.DriveConnectionResult
import com.tjcelaya.scribcal.data.DriveRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SettingsFragment : Fragment() {
    
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var driveRepository: DriveRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val app = requireActivity().application as ScribCalApplication
        calendarRepository = app.calendarRepository
        driveRepository = app.driveRepository
        
        val containerLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        // Title
        val titleText = TextView(requireContext()).apply {
            text = "Settings"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        containerLayout.addView(titleText)
        
        // Calendar Settings Card
        val calendarCard = createCalendarSettingsCard()
        containerLayout.addView(calendarCard)
        
        // Google Drive Settings Card
        val driveCard = createDriveSettingsCard()
        containerLayout.addView(driveCard)
        
        return containerLayout
    }
    
    private fun createCalendarSettingsCard(): MaterialCardView {
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
        
        val cardTitle = TextView(requireContext()).apply {
            text = "Calendar Integration"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        
        val statusText = TextView(requireContext()).apply {
            if (calendarRepository.isCalendarSetupComplete()) {
                val calendarName = calendarRepository.getSelectedCalendarName()
                text = "Currently using: $calendarName"
            } else {
                text = "No calendar selected"
            }
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        
        val changeCalendarButton = MaterialButton(requireContext()).apply {
            text = if (calendarRepository.isCalendarSetupComplete()) "Change Calendar" else "Setup Calendar"
            setOnClickListener {
                findNavController().navigate(R.id.calendarSetupFragment)
            }
        }
        
        cardContent.addView(cardTitle)
        cardContent.addView(statusText)
        cardContent.addView(changeCalendarButton)
        card.addView(cardContent)
        
        return card
    }
    
    private fun createDriveSettingsCard(): MaterialCardView {
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
        
        val cardTitle = TextView(requireContext()).apply {
            text = "Google Drive Integration"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        
        val folderText = TextView(requireContext()).apply {
            text = "Storage folder: ${driveRepository.getCurrentFolderName()}"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        
        val statusText = TextView(requireContext()).apply {
            text = "Status: ${driveRepository.getDriveStatus()}"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        
        val lastTestText = TextView(requireContext()).apply {
            text = "Last connection test: Not tested"
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        
        val testConnectionButton = MaterialButton(requireContext()).apply {
            text = "Test Drive Connection"
            setOnClickListener {
                testDriveConnection(statusText, lastTestText, this)
            }
        }
        
        cardContent.addView(cardTitle)
        cardContent.addView(folderText)
        cardContent.addView(statusText)
        cardContent.addView(lastTestText)
        cardContent.addView(testConnectionButton)
        card.addView(cardContent)
        
        return card
    }
    
    private fun testDriveConnection(
        statusText: TextView, 
        lastTestText: TextView, 
        testButton: MaterialButton
    ) {
        lifecycleScope.launch {
            try {
                // Disable button and show testing state
                testButton.isEnabled = false
                testButton.text = "Testing..."
                statusText.text = "Status: Testing connection..."
                
                // Test the connection
                val result = driveRepository.testDriveConnection()
                
                // Update UI with results
                statusText.text = "Status: ${result.status}"
                
                val lastTestTime = result.lastTestTime?.let { timestamp ->
                    val formatter = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm:ss", Locale.getDefault())
                    formatter.format(Date(timestamp))
                } ?: "Never"
                
                lastTestText.text = "Last connection test: $lastTestTime"
                
                // Show connection status with color
                if (result.isConnected) {
                    statusText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                } else {
                    statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                }
                
            } catch (e: Exception) {
                statusText.text = "Status: Error testing connection: ${e.message}"
                statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            } finally {
                // Re-enable button
                testButton.isEnabled = true
                testButton.text = "Test Drive Connection"
            }
        }
    }
}
