package com.tjcelaya.scribcal.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.DriveRepository
import com.tjcelaya.scribcal.data.PhotosRepository
import com.tjcelaya.scribcal.data.StoragePreferences
import com.tjcelaya.scribcal.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var driveRepository: DriveRepository
    private lateinit var photosRepository: PhotosRepository
    private lateinit var storagePreferences: StoragePreferences
    
    // Activity result launcher for Google Photos consent screen
    private val photosConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // After consent screen, re-test the connection
        testPhotosConnection()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        
        val app = requireActivity().application as ScribCalApplication
        calendarRepository = app.calendarRepository
        driveRepository = app.driveRepository
        photosRepository = app.photosRepository
        storagePreferences = app.storagePreferences
        
        setupUI()
        
        return binding.root
    }
    
    private fun setupUI() {
        setupCalendarSection()
        setupDriveSection()
        setupPhotosSection()
        setupStorageSelection()
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
        updateDriveStatus()
        
        // Show last test time if available
        val lastTestTime = driveRepository.getLastTestTime()?.let { timestamp ->
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            formatter.format(Date(timestamp))
        } ?: "Not tested"
        binding.driveLastTestText.text = lastTestTime
        
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
                binding.driveStatusText.text = "Testing..."
                
                // Test the connection
                val result = driveRepository.testDriveConnection()
                
                // Update UI with results - use the short status from DriveRepository
                updateDriveStatus()
                
                val lastTestTime = result.lastTestTime?.let { timestamp ->
                    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
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
                binding.driveStatusText.text = "Error"
                binding.driveStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
            } finally {
                // Re-enable button
                binding.testDriveButton.isEnabled = true
                binding.testDriveButton.text = "Test Connection"
                
                // Update storage selection status
                updateStorageSelectionStatus()
            }
        }
    }
    
    private fun updateDriveStatus() {
        // Get the short status message from DriveRepository
        binding.driveStatusText.text = driveRepository.getDriveStatus()
        
        // Reset color to default
        binding.driveStatusText.setTextColor(requireContext().getColor(android.R.color.tab_indicator_text))
    }
    
    private fun setupPhotosSection() {
        // Update Photos information
        binding.photosAlbumText.text = photosRepository.getCurrentAlbumName()
        updatePhotosStatus()
        
        // Show last test time if available
        val lastTestTime = photosRepository.getLastTestTime()?.let { timestamp ->
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            formatter.format(Date(timestamp))
        } ?: "Not tested"
        binding.photosLastTestText.text = lastTestTime
        
        // Set up test button
        binding.testPhotosButton.setOnClickListener {
            testPhotosConnection()
        }
    }
    
    private fun testPhotosConnection() {
        lifecycleScope.launch {
            try {
                // Disable button and show testing state
                binding.testPhotosButton.isEnabled = false
                binding.testPhotosButton.text = "Testing..."
                binding.photosStatusText.text = "Testing..."
                
                // Test the connection
                val result = photosRepository.testPhotosConnection()
                
                // Check if user consent is required
                if (result.status == "Setup required") {
                    // Clear cached tokens first to ensure fresh consent
                    photosRepository.clearCachedTokens()
                    
                    // Get the consent intent and launch it
                    val consentException = photosRepository.getUserConsentException()
                    if (consentException != null) {
                        photosConsentLauncher.launch(consentException.intent)
                        return@launch // Exit early, don't update UI yet
                    }
                }
                
                // Update UI with results
                updatePhotosStatus()
                
                val lastTestTime = result.lastTestTime?.let { timestamp ->
                    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    formatter.format(Date(timestamp))
                } ?: "Never"
                
                binding.photosLastTestText.text = lastTestTime
                
                // Show connection status with color
                if (result.isConnected) {
                    binding.photosStatusText.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                } else {
                    binding.photosStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                }
                
            } catch (e: Exception) {
                binding.photosStatusText.text = "Error"
                binding.photosStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
            } finally {
                // Re-enable button
                binding.testPhotosButton.isEnabled = true
                binding.testPhotosButton.text = "Test Connection"
                
                // Update storage selection status
                updateStorageSelectionStatus()
            }
        }
    }
    
    private fun updatePhotosStatus() {
        // Get the short status message from PhotosRepository
        binding.photosStatusText.text = photosRepository.getPhotosStatus()
        
        // Reset color to default
        binding.photosStatusText.setTextColor(requireContext().getColor(android.R.color.tab_indicator_text))
    }
    
    private fun setupStorageSelection() {
        // Load current selection
        when {
            storagePreferences.isGoogleDriveSelected() -> {
                binding.radioGoogleDrive.isChecked = true
            }
            storagePreferences.isGooglePhotosSelected() -> {
                binding.radioGooglePhotos.isChecked = true
            }
        }
        
        // Set up radio group listener
        binding.storageSelectionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_google_drive -> {
                    storagePreferences.setPhotoStorageType(StoragePreferences.STORAGE_TYPE_GOOGLE_DRIVE)
                    updateStorageSelectionStatus()
                }
                R.id.radio_google_photos -> {
                    storagePreferences.setPhotoStorageType(StoragePreferences.STORAGE_TYPE_GOOGLE_PHOTOS)
                    updateStorageSelectionStatus()
                }
            }
        }
        
        // Update initial state
        updateStorageSelectionStatus()
    }
    
    private fun updateStorageSelectionStatus() {
        val isDriveReady = driveRepository.isDriveReady()
        val isPhotosReady = photosRepository.isPhotosReady()
        
        // Enable/disable radio buttons based on connection status
        binding.radioGoogleDrive.isEnabled = isDriveReady
        binding.radioGooglePhotos.isEnabled = isPhotosReady
        
        // Update status text
        val statusText = when {
            !isDriveReady && !isPhotosReady -> "Test connections above to enable storage options"
            isDriveReady && !isPhotosReady -> "Only Google Drive is available"
            !isDriveReady && isPhotosReady -> "Only Google Photos is available"
            else -> {
                val selectedType = storagePreferences.getPhotoStorageType()
                when (selectedType) {
                    StoragePreferences.STORAGE_TYPE_GOOGLE_DRIVE -> "✓ Using Google Drive for photo storage"
                    StoragePreferences.STORAGE_TYPE_GOOGLE_PHOTOS -> "✓ Using Google Photos for photo storage"
                    else -> "Both services available - select your preferred storage option"
                }
            }
        }
        
        binding.storageSelectionStatusText.text = statusText
        
        // Clear selection if the selected service becomes unavailable
        val currentSelection = storagePreferences.getPhotoStorageType()
        if (currentSelection != null) {
            val isCurrentSelectionValid = when (currentSelection) {
                StoragePreferences.STORAGE_TYPE_GOOGLE_DRIVE -> isDriveReady
                StoragePreferences.STORAGE_TYPE_GOOGLE_PHOTOS -> isPhotosReady
                else -> false
            }
            
            if (!isCurrentSelectionValid) {
                storagePreferences.clearPhotoStorageType()
                binding.storageSelectionRadioGroup.clearCheck()
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
