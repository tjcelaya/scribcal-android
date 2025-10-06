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
import kotlinx.coroutines.delay
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

                        // Clear any cached tokens first to ensure fresh permissions
                        driveRepository.clearCachedTokens()

                        // Wait a moment for cleanup
                        kotlinx.coroutines.delay(300)

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

                // Clear any cached tokens first to ensure fresh permissions
                setPhotosButtonState(enabled = false, text = "Refreshing permissions...")
                photosRepository.clearCachedTokens()

                // Wait a moment for cleanup
                kotlinx.coroutines.delay(500)

                // Get OAuth token with fresh permissions
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
                        // Update UI to show successful configuration
                        binding.photosStatusText.text = "Album configured successfully"
                        binding.photosStatusText.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))

                        // Update last test time
                        val currentTime = System.currentTimeMillis()
                        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        binding.photosLastTestText.text = formatter.format(Date(currentTime))

                        Log.d("SettingsFragment", "Album configured successfully: $albumName")

                        // Set the connection as successful since we just configured the album successfully
                        // This mimics what testPhotosConnection would do but without the complexity
                        photosRepository.markConnectionSuccessful()

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
            // Only check if we already have this album stored in the database
            Log.d("SettingsFragment", "Checking database for album: $albumName")
            val storedAlbum = photosRepository.getSelectedAlbum()

            if (storedAlbum != null) {
                Log.d("SettingsFragment", "Found stored album in database: ${storedAlbum.googlePhotosAlbumName} (${storedAlbum.googlePhotosAlbumId})")
                if (storedAlbum.googlePhotosAlbumName.equals(albumName, ignoreCase = true)) {
                    Log.d("SettingsFragment", "Album names match! Using stored album: $albumName (${storedAlbum.googlePhotosAlbumId})")
                    return storedAlbum.googlePhotosAlbumId
                } else {
                    Log.d("SettingsFragment", "Album names don't match: stored='${storedAlbum.googlePhotosAlbumName}' vs requested='$albumName'")
                }
            } else {
                Log.d("SettingsFragment", "No stored album found in database")
            }

            // If not in database or different name, ask user to create new album
            // Note: We don't try to search Google Photos because album listing API is unreliable
            Log.d("SettingsFragment", "No matching album in database, asking user to create: $albumName")
            return askUserToCreateAlbum(token, albumName)

        } catch (e: Exception) {
            Log.e("SettingsFragment", "Error finding or creating album", e)
            null
        }
    }


    private suspend fun askUserToCreateAlbum(token: String, albumName: String): String? {
        Log.d("SettingsFragment", "Asking user to create album: $albumName")
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            try {
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle("Create Album?")
                    .setMessage("Album '$albumName' doesn't exist in your Google Photos. Would you like to create it?")
                    .setPositiveButton("Create") { _, _ ->
                        Log.d("SettingsFragment", "User chose to create album: $albumName")
                        lifecycleScope.launch {
                            try {
                                setPhotosButtonState(enabled = false, text = "Creating album...")
                                val albumId = createAlbum(token, albumName)
                                Log.d("SettingsFragment", "Album creation result: $albumId")
                                continuation.resume(albumId, null)
                            } catch (e: Exception) {
                                Log.e("SettingsFragment", "Error creating album", e)
                                continuation.resume(null, null)
                            }
                        }
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        Log.d("SettingsFragment", "User canceled album creation")
                        continuation.resume(null, null)
                    }
                    .setOnCancelListener {
                        Log.d("SettingsFragment", "Album creation dialog was canceled")
                        continuation.resume(null, null)
                    }
                    .create()

                Log.d("SettingsFragment", "Showing album creation dialog")
                dialog.show()
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Failed to show album creation dialog", e)
                continuation.resume(null, null)
            }
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
        // Load current selections from preferences
        binding.checkboxGoogleDrive.isChecked = storagePreferences.isGoogleDriveEnabled()
        binding.checkboxGooglePhotos.isChecked = storagePreferences.isGooglePhotosEnabled()

        // Set up checkbox listeners
        binding.checkboxGoogleDrive.setOnCheckedChangeListener { _, isChecked ->
            storagePreferences.setGoogleDriveEnabled(isChecked)
            updateStorageSelectionStatus()
        }

        binding.checkboxGooglePhotos.setOnCheckedChangeListener { _, isChecked ->
            storagePreferences.setGooglePhotosEnabled(isChecked)
            updateStorageSelectionStatus()
        }

        // Update initial state
        updateStorageSelectionStatus()
    }

    private fun updateStorageSelectionStatus() {
        // Use unified health check methods - single source of truth
        val isDriveHealthy = driveRepository.isHealthy()
        val isPhotosHealthy = photosRepository.isHealthy()

        // Debug logging to understand health status
        Log.d("SettingsFragment", "Storage health check: Drive healthy=$isDriveHealthy, Photos healthy=$isPhotosHealthy")
        Log.d("SettingsFragment", "Photos detailed status: ${photosRepository.getPhotosStatus()}")
        Log.d("SettingsFragment", "Drive detailed status: ${driveRepository.getDriveStatus()}")

        // Enable/disable checkboxes based on health status
        binding.checkboxGoogleDrive.isEnabled = isDriveHealthy
        binding.checkboxGooglePhotos.isEnabled = isPhotosHealthy

        // Get current selections
        val isDriveEnabled = storagePreferences.isGoogleDriveEnabled()
        val isPhotosEnabled = storagePreferences.isGooglePhotosEnabled()
        val hasAnySelection = isDriveEnabled || isPhotosEnabled

        // Update status text
        val statusText = when {
            !isDriveHealthy && !isPhotosHealthy -> "Test connections above to enable storage options"
            !hasAnySelection -> when {
                isDriveHealthy && isPhotosHealthy -> "Both services available - select at least one storage option"
                isDriveHealthy -> "Google Drive is available - enable it to save photos"
                isPhotosHealthy -> "Google Photos is available - enable it to save photos"
                else -> "No storage services available"
            }
            isDriveEnabled && isPhotosEnabled -> "✓ Using both Google Drive and Google Photos for photo storage"
            isDriveEnabled -> "✓ Using Google Drive for photo storage"
            isPhotosEnabled -> "✓ Using Google Photos for photo storage"
            else -> "Select at least one storage option"
        }

        binding.storageSelectionStatusText.text = statusText

        // Auto-disable selections if services become unhealthy
        if (isDriveEnabled && !isDriveHealthy) {
            Log.d("SettingsFragment", "Auto-disabling Drive storage - service became unhealthy")
            storagePreferences.setGoogleDriveEnabled(false)
            binding.checkboxGoogleDrive.isChecked = false
        }

        if (isPhotosEnabled && !isPhotosHealthy) {
            Log.d("SettingsFragment", "Auto-disabling Photos storage - service became unhealthy")
            storagePreferences.setGooglePhotosEnabled(false)
            binding.checkboxGooglePhotos.isChecked = false
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
