package com.tjcelaya.scribcal.ui.settings

import android.accounts.Account
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.GoogleAuthUtil
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.DriveRepository
import com.tjcelaya.scribcal.data.PhotosConnectionResult
import com.tjcelaya.scribcal.data.PhotosRepository
import com.tjcelaya.scribcal.data.StoragePreferences
import com.tjcelaya.scribcal.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

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
        testPhotosConnectionWithAlbumCreation()
    }
    
    // Activity result launcher for Google Drive consent screen
    private val driveConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // After consent screen, re-test the connection
        testDriveConnection()
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
                // Disable button and show connecting state
                binding.testDriveButton.isEnabled = false
                binding.testDriveButton.text = "Connecting..."
                binding.driveStatusText.text = "Connecting..."
                
                // Check if Drive needs setup first
                if (driveRepository.getDriveStatus() == "Setup required" || !driveRepository.isDriveInitialized()) {
                    // Try to initialize Drive first
                    binding.driveStatusText.text = "Setting up..."
                    
                    val account = getGoogleAccountForServices()
                    if (account != null) {
                        Log.d("SettingsFragment", "Attempting to initialize Drive with account: ${account.name}")
                        val initSuccess = driveRepository.retryDriveInitialization(account)
                        if (initSuccess) {
                            Log.d("SettingsFragment", "Drive initialization successful")
                        } else {
                            // Check if user consent is required
                            val consentException = driveRepository.getDriveConsentException()
                            if (consentException != null) {
                                Log.d("SettingsFragment", "Drive requires user consent, launching consent flow")
                                driveConsentLauncher.launch(consentException.intent)
                                return@launch // Exit early, don't update UI yet
                            } else {
                                Log.w("SettingsFragment", "Drive initialization failed")
                                binding.driveStatusText.text = "Setup failed"
                                binding.driveStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                                return@launch
                            }
                        }
                    } else {
                        Log.w("SettingsFragment", "No Google account found for Drive setup")
                        binding.driveStatusText.text = "No Google account"
                        binding.driveStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                        return@launch
                    }
                }
                
                // Now test the connection
                binding.driveStatusText.text = "Connecting..."
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
                Log.e("SettingsFragment", "Error testing Drive connection", e)
                binding.driveStatusText.text = "Error"
                binding.driveStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
            } finally {
                // Re-enable button
                binding.testDriveButton.isEnabled = true
                binding.testDriveButton.text = "Connect"
                
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
        // Load current album name into text field
        loadAlbumNameIntoTextField()
        updatePhotosStatus()
        
        // Show last test time if available
        val lastTestTime = photosRepository.getLastTestTime()?.let { timestamp ->
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            formatter.format(Date(timestamp))
        } ?: "Not tested"
        binding.photosLastTestText.text = lastTestTime
        
        // Set up test button to connect and create album if needed
        binding.testPhotosButton.setOnClickListener {
            testPhotosConnectionWithAlbumCreation()
        }
    }
    
    private fun loadAlbumNameIntoTextField() {
        lifecycleScope.launch {
            val selectedAlbum = photosRepository.getSelectedAlbum()
            val albumName = selectedAlbum?.googlePhotosAlbumName ?: "ScribCal Events"
            binding.photosAlbumNameEdit.setText(albumName)
        }
    }
    
    private fun testPhotosConnectionWithAlbumCreation() {
        lifecycleScope.launch {
            try {
                // Disable button while testing
                setPhotosButtonState(enabled = false, text = "Connecting...", status = "Connecting...")
                
                val albumName = binding.photosAlbumNameEdit.text.toString().trim()
                if (albumName.isEmpty()) {
                    binding.photosAlbumNameEdit.error = "Album name cannot be empty"
                    setPhotosButtonState(enabled = true, text = "Connect")
                    return@launch
                }
                
                // Check if Photos is initialized
                if (!photosRepository.isPhotosInitialized()) {
                    Log.d("SettingsFragment", "Photos not initialized, attempting to initialize...")
                    val account = getGoogleAccountForServices()
                    if (account != null) {
                        val initSuccess = photosRepository.initializePhotos(account)
                        if (!initSuccess) {
                            handlePhotosConnectionError()
                            return@launch
                        }
                    } else {
                        handlePhotosConnectionError()
                        return@launch
                    }
                }
                
                // Get OAuth token
                val account = getGoogleAccountForServices()
                if (account == null) {
                    Log.e("SettingsFragment", "No Google account available")
                    handlePhotosConnectionError()
                    return@launch
                }
                
                val token = try {
                    withContext(Dispatchers.IO) {
                        GoogleAuthUtil.getToken(
                            requireContext(),
                            account,
                            "oauth2:https://www.googleapis.com/auth/photoslibrary"
                        )
                    }
                } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
                    Log.w("SettingsFragment", "User consent required for Photos access")
                    photosConsentLauncher.launch(e.intent)
                    return@launch
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Failed to get OAuth token", e)
                    handlePhotosConnectionError()
                    return@launch
                }
                
                if (token.isEmpty()) {
                    Log.e("SettingsFragment", "OAuth token is empty")
                    handlePhotosConnectionError()
                    return@launch
                }
                
                // Try to find or create the album
                setPhotosButtonState(enabled = false, text = "Checking album...")
                val albumId = findOrCreateAlbumWithConfirmation(token, albumName)
                
                if (albumId != null) {
                    // Save the album configuration
                    val success = photosRepository.setSelectedAlbum(albumId, albumName)
                    if (success) {
                        // Test the connection
                        val result = photosRepository.testPhotosConnection()
                        updatePhotosConnectionResult(result)
                        Log.d("SettingsFragment", "Photos connection successful with album: $albumName")
                    } else {
                        Log.e("SettingsFragment", "Failed to save album configuration")
                        handlePhotosConnectionError()
                    }
                } else {
                    Log.e("SettingsFragment", "Failed to find or create album")
                    handlePhotosConnectionError()
                }
                
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Error testing Photos connection", e)
                handlePhotosConnectionError()
            } finally {
                setPhotosButtonState(enabled = true, text = "Connect")
                updateStorageSelectionStatus()
            }
        }
    }
    
    private suspend fun findOrCreateAlbumWithConfirmation(token: String, albumName: String): String? {
        return try {
            // First, try to find an existing album with this name
            Log.d("SettingsFragment", "Searching for existing album: $albumName")
            val existingAlbumId = findAlbumByName(token, albumName)
            
            if (existingAlbumId != null) {
                Log.d("SettingsFragment", "Found existing album: $albumName")
                return existingAlbumId
            }
            
            // Album doesn't exist, ask user if they want to create it
            Log.d("SettingsFragment", "Album not found, asking user for permission to create: $albumName")
            return askUserToCreateAlbum(token, albumName)
            
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Error finding or creating album", e)
            null
        }
    }
    
    private suspend fun findAlbumByName(token: String, albumName: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Search through user's albums to find one with matching name
                val albums = photosRepository.listAlbums(token)
                val matchingAlbum = albums.find { it.title.equals(albumName, ignoreCase = true) }
                matchingAlbum?.id
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Error searching for album", e)
                null
            }
        }
    }
    
    private suspend fun askUserToCreateAlbum(token: String, albumName: String): String? {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            AlertDialog.Builder(requireContext())
                .setTitle("Create Album?")
                .setMessage("Album '$albumName' doesn't exist in your Google Photos. Would you like to create it?")
                .setPositiveButton("Create") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            setPhotosButtonState(enabled = false, text = "Creating album...")
                            val albumId = createAlbum(token, albumName)
                            continuation.resume(albumId, null)
                        } catch (e: Exception) {
                            Log.e("SettingsFragment", "Error creating album", e)
                            continuation.resume(null, null)
                        }
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    continuation.resume(null, null)
                }
                .setOnCancelListener {
                    continuation.resume(null, null)
                }
                .show()
        }
    }
    
    private suspend fun createAlbum(token: String, albumName: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://photoslibrary.googleapis.com/v1/albums")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                
                val requestJson = JSONObject().apply {
                    put("album", JSONObject().apply {
                        put("title", albumName)
                    })
                }
                
                connection.outputStream.use { outputStream ->
                    outputStream.write(requestJson.toString().toByteArray())
                }
                
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val responseJson = JSONObject(response)
                    val albumId = responseJson.getString("id")
                    Log.d("SettingsFragment", "Created album: $albumName with ID: $albumId")
                    albumId
                } else {
                    val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e("SettingsFragment", "Failed to create album with code $responseCode: $errorStream")
                    null
                }
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Failed to create album: $albumName", e)
                null
            }
        }
    }
    
    private fun setPhotosButtonState(enabled: Boolean, text: String, status: String? = null) {
        binding.testPhotosButton.isEnabled = enabled
        binding.testPhotosButton.text = text
        status?.let { binding.photosStatusText.text = it }
    }
    
    private suspend fun handlePhotosConsentRequired() {
        // Clear cached tokens first to ensure fresh consent
        photosRepository.clearCachedTokens()
        
        // Get the consent intent and launch it
        val consentException = photosRepository.getUserConsentException()
        if (consentException != null) {
            photosConsentLauncher.launch(consentException.intent)
        }
    }
    
    private fun updatePhotosConnectionResult(result: PhotosConnectionResult) {
        // Update UI with results
        updatePhotosStatus()
        
        val lastTestTime = result.lastTestTime?.let { timestamp ->
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            formatter.format(Date(timestamp))
        } ?: "Never"
        
        binding.photosLastTestText.text = lastTestTime
        
        // Show connection status with color
        val color = if (result.isConnected) {
            android.R.color.holo_green_dark
        } else {
            android.R.color.holo_red_dark
        }
        binding.photosStatusText.setTextColor(requireContext().getColor(color))
    }
    
    private fun handlePhotosConnectionError() {
        binding.photosStatusText.text = "Error"
        binding.photosStatusText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
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
    
    /**
     * Get a Google account for initializing Drive and Photos services
     */
    private suspend fun getGoogleAccountForServices(): Account? {
        return try {
            val calendars = calendarRepository.getAvailableCalendars()
            val selectedCalendarId = calendarRepository.getSelectedCalendarId()
            
            Log.d("SettingsFragment", "Found ${calendars.size} calendars, selected ID: $selectedCalendarId")
            
            // Try to use the selected calendar's account first
            val selectedCalendar = calendars.find { it.id == selectedCalendarId }
            
            if (selectedCalendar != null && selectedCalendar.accountName.isNotEmpty()) {
                Log.d("SettingsFragment", "Using selected calendar account: ${selectedCalendar.accountName}")
                Account(selectedCalendar.accountName, selectedCalendar.accountType)
            } else {
                // Fallback to any Google account
                val googleCalendars = calendars.filter { 
                    it.accountType == "com.google" && it.accountName.isNotEmpty() 
                }
                
                if (googleCalendars.isNotEmpty()) {
                    Log.d("SettingsFragment", "Using first Google calendar account: ${googleCalendars[0].accountName}")
                    Account(googleCalendars[0].accountName, googleCalendars[0].accountType)
                } else {
                    Log.w("SettingsFragment", "No Google accounts found in calendars")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Error getting Google account for services", e)
            null
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
