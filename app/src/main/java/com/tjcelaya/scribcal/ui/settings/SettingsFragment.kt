package com.tjcelaya.scribcal.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.CalendarRepository

class SettingsFragment : Fragment() {
    
    private lateinit var calendarRepository: CalendarRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        calendarRepository = CalendarRepository(requireContext())
        
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
}
