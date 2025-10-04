package com.tjcelaya.scribcal.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.DriveRepository
import com.tjcelaya.scribcal.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var driveRepository: DriveRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        
        val app = requireActivity().application as ScribCalApplication
        calendarRepository = app.calendarRepository
        driveRepository = app.driveRepository
        
        setupUI()
        
        return binding.root
    }
    
    private fun setupUI() {
        setupCalendarSection()
        setupDriveSection()
    }
    
    private fun setupCalendarSection() {
        // Update calendar status
        if (calendarRepository.isCalendarSetupComplete()) {
            val calendarName = calendarRepository.getSelectedCalendarName()
            binding.calendarStatusText.text = calendarName ?: "Unknown calendar"
            binding.changeCalendarButton.text = "Change"
        } else {
            binding.calendarStatusText.text = "No calendar selected"
            binding.changeCalendarButton.text = "Setup"
        }
        
        // Set click listeners
        binding.calendarSettingItem.setOnClickListener {
            findNavController().navigate(R.id.calendarSetupFragment)
        }
        
        binding.changeCalendarButton.setOnClickListener {
            findNavController().navigate(R.id.calendarSetupFragment)
        }
    }
    
    private fun setupDriveSection() {
        // Update Drive information
        binding.driveFolderText.text = driveRepository.getCurrentFolderName()
        binding.driveStatusText.text = driveRepository.getDriveStatus()
        binding.driveLastTestText.text = "Not tested"
        
        // Set up test button
        binding.testDriveButton.setOnClickListener {
            testDriveConnection()
        }
    }
    
    private fun testDriveConnection() {
        lifecycleScope.launch {
            try {
                // Disable button and show testing state
                binding.testDriveButton.isEnabled = false
                binding.testDriveButton.text = "Testing..."
                binding.driveStatusText.text = "Testing connection..."
                
                // Test the connection
                val result = driveRepository.testDriveConnection()
                
                // Update UI with results
                binding.driveStatusText.text = result.status
                
                val lastTestTime = result.lastTestTime?.let { timestamp ->
                    val formatter = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
                    formatter.format(Date(timestamp))
                } ?: "Never"
                
                binding.driveLastTestText.text = lastTestTime
                
                // Show connection status with color
                if (result.isConnected) {
                    binding.driveStatusText.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                } else {
                    binding.driveStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                }
                
            } catch (e: Exception) {
                binding.driveStatusText.text = "Error: ${e.message}"
                binding.driveStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
            } finally {
                // Re-enable button
                binding.testDriveButton.isEnabled = true
                binding.testDriveButton.text = "Test Connection"
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
