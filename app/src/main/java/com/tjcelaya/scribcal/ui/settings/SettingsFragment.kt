package com.tjcelaya.scribcal.ui.settings

import android.accounts.Account
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.google.android.material.snackbar.Snackbar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.GoogleAuthUtil
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.CardColorStyle
import com.tjcelaya.scribcal.data.ConfigBackupManager
import com.tjcelaya.scribcal.data.DriveRepository
import com.tjcelaya.scribcal.data.InstantEventIcon
import com.tjcelaya.scribcal.data.PhotosConnectionResult
import com.tjcelaya.scribcal.data.PhotosRepository
import com.tjcelaya.scribcal.data.StoragePreferences
import com.tjcelaya.scribcal.data.VoiceStopBehavior
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
    private lateinit var configBackupManager: ConfigBackupManager

    // Activity result launcher for Google Photos consent screen
    private val photosConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("SettingsFragment", "Consent screen returned with result code: ${result.resultCode}")
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                Log.i("SettingsFragment", "✅ User granted consent - waiting for token propagation...")
                // Wait 5 seconds for OAuth token to propagate through Google's systems
                lifecycleScope.launch {
                    setPhotosButtonState(enabled = false, text = "Waiting for permissions...", status = "Processing consent...")
                    kotlinx.coroutines.delay(5000)
                    Log.d("SettingsFragment", "Token propagation delay complete, proceeding with connection test")
                    // After successful consent and delay, re-test the connection
                    testPhotosConnectionWithAlbumCreation()
                }
            }
            android.app.Activity.RESULT_CANCELED -> {
                Log.w("SettingsFragment", "❌ User canceled consent screen")
                handlePhotosConnectionError()
            }
            else -> {
                Log.w("SettingsFragment", "⚠️ Consent screen returned unexpected result: ${result.resultCode}")
                // Still try to proceed in case the consent was granted
                testPhotosConnectionWithAlbumCreation()
            }
        }
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
        configBackupManager = app.configBackupManager

        setupUI()

        return binding.root
    }

    private fun setupUI() {
        setupCalendarSection()
        setupEnhancedCalendarSection()
        setupBubbleSection()
        setupVoiceSection()
        setupDriveSection()
        setupPhotosSection()
        setupStorageSelection()
        setupInstantEventIconSelection()
        setupCardColorStyleSelection()
        setupBackupSection()
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
    
    private fun setupEnhancedCalendarSection() {
        // Enable switch only if calendar is set up
        val isCalendarSetup = calendarRepository.isCalendarSetupComplete()
        binding.enhancedCalendarSwitch.isEnabled = isCalendarSetup
        
        // Load current state
        binding.enhancedCalendarSwitch.isChecked = calendarRepository.isEnhancedCalendarEnabled()
        
        // Update status text
        updateEnhancedCalendarStatus()
        
        // Handle toggle
        binding.enhancedCalendarSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                try {
                    if (isChecked) {
                        // Try to initialize the API
                        binding.enhancedCalendarStatus.text = "Initializing..."
                        val success = calendarRepository.initializeEnhancedCalendar()
                        
                        if (success) {
                            calendarRepository.setEnhancedCalendarEnabled(true)
                            binding.enhancedCalendarStatus.text = "✓ Enabled - Colors will appear in Google Calendar"
                            binding.enhancedCalendarStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                            Snackbar.make(binding.root, "Enhanced calendar integration enabled", Snackbar.LENGTH_SHORT).show()
                        } else {
                            binding.enhancedCalendarSwitch.isChecked = false
                            binding.enhancedCalendarStatus.text = "Failed to initialize API"
                            binding.enhancedCalendarStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                            Snackbar.make(binding.root, "Failed to enable enhanced integration", Snackbar.LENGTH_LONG).show()
                        }
                    } else {
                        calendarRepository.setEnhancedCalendarEnabled(false)
                        updateEnhancedCalendarStatus()
                        Snackbar.make(binding.root, "Enhanced calendar integration disabled", Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Error toggling enhanced calendar", e)
                    binding.enhancedCalendarSwitch.isChecked = false
                    binding.enhancedCalendarStatus.text = "Error: ${e.message}"
                    binding.enhancedCalendarStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                }
            }
        }
    }
    
    private fun updateEnhancedCalendarStatus() {
        val isEnabled = calendarRepository.isEnhancedCalendarEnabled()
        val isCalendarSetup = calendarRepository.isCalendarSetupComplete()
        
        binding.enhancedCalendarStatus.text = when {
            !isCalendarSetup -> "Select a calendar first to enable this feature"
            isEnabled -> "✓ Enabled - Colors will appear in Google Calendar"
            else -> "Disabled - Colors only visible in some calendar apps"
        }
        
        val color = when {
            isEnabled -> android.R.color.holo_green_dark
            !isCalendarSetup -> android.R.color.tab_indicator_text
            else -> android.R.color.tab_indicator_text
        }
        binding.enhancedCalendarStatus.setTextColor(requireContext().getColor(color))
    }

    private fun setupBubbleSection() {
        // Load current state
        val currentMode = storagePreferences.getBubbleMode()
        when (currentMode) {
            com.tjcelaya.scribcal.data.BubbleMode.NEVER -> binding.bubbleModeNever.isChecked = true
            com.tjcelaya.scribcal.data.BubbleMode.SELECTED -> binding.bubbleModeSelected.isChecked = true
            com.tjcelaya.scribcal.data.BubbleMode.ALWAYS -> binding.bubbleModeAlways.isChecked = true
        }
        
        // Update status text
        updateBubbleStatus()
        
        // Handle radio button changes
        binding.bubbleModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.bubble_mode_never -> com.tjcelaya.scribcal.data.BubbleMode.NEVER
                R.id.bubble_mode_selected -> com.tjcelaya.scribcal.data.BubbleMode.SELECTED
                R.id.bubble_mode_always -> com.tjcelaya.scribcal.data.BubbleMode.ALWAYS
                else -> com.tjcelaya.scribcal.data.BubbleMode.NEVER
            }
            
            storagePreferences.setBubbleMode(newMode)
            updateBubbleStatus()
            
            val message = when (newMode) {
                com.tjcelaya.scribcal.data.BubbleMode.NEVER -> "Bubble notifications disabled"
                com.tjcelaya.scribcal.data.BubbleMode.SELECTED -> "Bubbles enabled for selected event types"
                com.tjcelaya.scribcal.data.BubbleMode.ALWAYS -> "Bubbles enabled for all ongoing events"
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }
    }
    
    private fun updateBubbleStatus() {
        val mode = storagePreferences.getBubbleMode()
        
        binding.bubblesStatus.text = when (mode) {
            com.tjcelaya.scribcal.data.BubbleMode.NEVER -> "Silent notifications only"
            com.tjcelaya.scribcal.data.BubbleMode.SELECTED -> "Bubbles for selected event types (configure in event type editor)"
            com.tjcelaya.scribcal.data.BubbleMode.ALWAYS -> "✓ All ongoing events will appear as bubbles"
        }
        
        val color = when (mode) {
            com.tjcelaya.scribcal.data.BubbleMode.NEVER -> android.R.color.tab_indicator_text
            com.tjcelaya.scribcal.data.BubbleMode.SELECTED -> android.R.color.holo_orange_dark
            com.tjcelaya.scribcal.data.BubbleMode.ALWAYS -> android.R.color.holo_green_dark
        }
        binding.bubblesStatus.setTextColor(requireContext().getColor(color))
    }

    private fun setupVoiceSection() {
        val current = storagePreferences.getVoiceStopBehavior()
        binding.voiceStopBehaviorGroup.check(radioIdFor(current))
        updateVoiceStopBehaviorStatus(current)

        binding.voiceStopBehaviorGroup.setOnCheckedChangeListener { _, checkedId ->
            val behavior = when (checkedId) {
                R.id.voice_stop_confirm_dialog -> VoiceStopBehavior.CONFIRM_DIALOG
                R.id.voice_stop_save_with_undo -> VoiceStopBehavior.SAVE_WITH_UNDO
                else -> VoiceStopBehavior.SAVE_SILENTLY
            }
            storagePreferences.setVoiceStopBehavior(behavior)
            updateVoiceStopBehaviorStatus(behavior)
            Log.d("SettingsFragment", "Voice stop behavior set to $behavior")
            Snackbar.make(binding.root, getString(statusStringFor(behavior)), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun radioIdFor(behavior: VoiceStopBehavior): Int = when (behavior) {
        VoiceStopBehavior.SAVE_SILENTLY -> R.id.voice_stop_save_silently
        VoiceStopBehavior.CONFIRM_DIALOG -> R.id.voice_stop_confirm_dialog
        VoiceStopBehavior.SAVE_WITH_UNDO -> R.id.voice_stop_save_with_undo
    }

    private fun statusStringFor(behavior: VoiceStopBehavior): Int = when (behavior) {
        VoiceStopBehavior.SAVE_SILENTLY -> R.string.settings_voice_stop_status_save_silently
        VoiceStopBehavior.CONFIRM_DIALOG -> R.string.settings_voice_stop_status_confirm_dialog
        VoiceStopBehavior.SAVE_WITH_UNDO -> R.string.settings_voice_stop_status_save_with_undo
    }

    private fun updateVoiceStopBehaviorStatus(behavior: VoiceStopBehavior) {
        binding.voiceStopBehaviorStatus.setText(statusStringFor(behavior))
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
                setPhotosButtonState(enabled = false, text = "Clearing cached permissions...")
                photosRepository.clearCachedTokens()
                
                // Also clear any Google Play Services cached tokens
                try {
                    val account = getGoogleAccountForServices()
                    if (account != null) {
                        // Invalidate all cached tokens for this account and scope
                        withContext(Dispatchers.IO) {
                            try {
                                // Try to get and immediately clear any existing token
                                val existingToken = GoogleAuthUtil.getToken(
                                    requireContext(),
                                    account,
                                    "oauth2:https://www.googleapis.com/auth/photoslibrary"
                                )
                                if (existingToken.isNotEmpty()) {
                                    GoogleAuthUtil.clearToken(requireContext(), existingToken)
                                    Log.d("SettingsFragment", "Force-cleared existing OAuth token")
                                    // Note: invalidateToken is deprecated, clearToken is sufficient
                                }
                                Log.d("SettingsFragment", "Token cleared successfully")
                            } catch (e: Exception) {
                                Log.d("SettingsFragment", "No cached token to clear: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SettingsFragment", "Error force-clearing tokens: ${e.message}")
                }

                // Wait longer for cleanup
                kotlinx.coroutines.delay(1000)

                // Get OAuth token with fresh permissions
                val account = getGoogleAccountForServices()
                if (account == null) {
                    Log.e("SettingsFragment", "No Google account available for OAuth token")
                    handlePhotosConnectionError()
                    return@launch
                }

                Log.d("SettingsFragment", "Getting OAuth token for account: ${account.name}")
                Log.d("SettingsFragment", "Requesting scope: https://www.googleapis.com/auth/photoslibrary")
                Snackbar.make(binding.root, "Getting OAuth token for ${account.name}", Snackbar.LENGTH_SHORT).show()
                
                val token = try {
                    withContext(Dispatchers.IO) {
                        GoogleAuthUtil.getToken(
                            requireContext(),
                            account,
                            "oauth2:https://www.googleapis.com/auth/photoslibrary"
                        )
                    }
                } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
                    Log.i("SettingsFragment", "✅ UserRecoverableAuthException caught - this is EXPECTED for initial setup")
                    Log.i("SettingsFragment", "User consent required for Photos access - launching consent screen")
                    Log.d("SettingsFragment", "Consent intent: ${e.intent}")
                    try {
                        photosConsentLauncher.launch(e.intent)
                        Log.d("SettingsFragment", "Consent screen launched successfully")
                    } catch (launchError: Exception) {
                        Log.e("SettingsFragment", "Failed to launch consent screen", launchError)
                        handlePhotosConnectionError()
                    }
                    return@launch
                } catch (e: com.google.android.gms.auth.GoogleAuthException) {
                    Log.e("SettingsFragment", "❌ GoogleAuthException - API/OAuth configuration issue")
                    Log.e("SettingsFragment", "Error type: ${e.javaClass.simpleName}")
                    Log.e("SettingsFragment", "Error message: ${e.message}")
                    Log.e("SettingsFragment", "This usually means Google Photos Library API is not enabled in Google Cloud Console")
                    handlePhotosConnectionError()
                    return@launch
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "❌ Unexpected OAuth error: ${e.javaClass.simpleName}: ${e.message}")
                    Log.e("SettingsFragment", "This might indicate a configuration problem")
                    e.printStackTrace()
                    handlePhotosConnectionError()
                    return@launch
                }
                
                Log.d("SettingsFragment", "OAuth token obtained successfully")

                if (token.isEmpty()) {
                    Log.e("SettingsFragment", "OAuth token is empty")
                    handlePhotosConnectionError()
                    return@launch
                }
                
                // Wait a moment for token to propagate through Google's systems
                // This prevents 403 "insufficient scopes" errors immediately after token grant
                Log.d("SettingsFragment", "Waiting for OAuth token propagation...")
                setPhotosButtonState(enabled = false, text = "Waiting for token activation...")
                kotlinx.coroutines.delay(3000)

                // First test if Google Photos API is available
                setPhotosButtonState(enabled = false, text = "Testing API access...")
                val apiAvailable = photosRepository.testPhotosAPIAvailability(token)
                
                if (!apiAvailable) {
                    Log.e("SettingsFragment", "Google Photos API is not available. Check Google Cloud Console setup.")
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


    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
                Log.d("SettingsFragment", "Starting album creation for: $albumName")
                Log.d("SettingsFragment", "Using OAuth token: ${token.take(20)}...")
                
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
                
                Log.d("SettingsFragment", "Request payload: ${requestJson.toString()}")

                connection.outputStream.use { outputStream ->
                    outputStream.write(requestJson.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                Log.d("SettingsFragment", "Album creation response code: $responseCode")
                
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d("SettingsFragment", "Album creation success response: $response")
                    val responseJson = JSONObject(response)
                    val albumId = responseJson.getString("id")
                    Log.d("SettingsFragment", "Created album: $albumName with ID: $albumId")
                    albumId
                } else {
                    val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e("SettingsFragment", "Album creation failed with HTTP $responseCode")
                    Log.e("SettingsFragment", "Error response: $errorStream")
                    
                    // Provide specific error guidance based on common response codes
                    when (responseCode) {
                        403 -> {
                            Log.e("SettingsFragment", "ERROR 403: Google Photos API Forbidden")
                            Log.e("SettingsFragment", "This usually means:")
                            Log.e("SettingsFragment", "  1. Google Photos Library API not enabled in Google Cloud Console")
                            Log.e("SettingsFragment", "  2. OAuth client not configured with correct package name and SHA-1")
                            Log.e("SettingsFragment", "  3. OAuth consent screen not configured with photoslibrary scope")
                            Log.e("SettingsFragment", "  4. App package/signature doesn't match OAuth client configuration")
                            Log.e("SettingsFragment", "Current package: com.tjcelaya.scribcal")
                            Log.e("SettingsFragment", "Expected SHA-1: 8C:61:BF:09:7B:91:85:1C:73:32:9F:A6:A0:C1:3A:6D:C8:D5:C8:49")
                        }
                        401 -> Log.e("SettingsFragment", "ERROR 401: OAuth token invalid or expired. This should have triggered consent flow.")
                        400 -> Log.e("SettingsFragment", "ERROR 400: Malformed request. Album name might be invalid: '$albumName'")
                        429 -> Log.e("SettingsFragment", "ERROR 429: Rate limit exceeded. Too many API requests.")
                        404 -> Log.e("SettingsFragment", "ERROR 404: Google Photos Library API endpoint not found. API might not be enabled.")
                        else -> Log.e("SettingsFragment", "ERROR $responseCode: Unexpected error creating album. Check network and API setup.")
                    }
                    null
                }
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Exception creating album: $albumName", e)
                when (e) {
                    is java.net.UnknownHostException -> Log.e("SettingsFragment", "Network error: No internet connection or DNS resolution failed")
                    is java.net.ConnectException -> Log.e("SettingsFragment", "Network error: Could not connect to Google Photos API")
                    is javax.net.ssl.SSLException -> Log.e("SettingsFragment", "SSL error: Certificate or secure connection issue")
                    is java.io.IOException -> Log.e("SettingsFragment", "IO error: Network or stream issue")
                    else -> Log.e("SettingsFragment", "Unexpected error type: ${e.javaClass.simpleName}")
                }
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

    private fun setupInstantEventIconSelection() {
        // Get all icon options
        val iconOptions = InstantEventIcon.values()
        val iconNames = iconOptions.map { it.displayName }

        // Create adapter for the dropdown
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, iconNames)
        binding.instantEventIconSpinner.setAdapter(adapter)

        // Set current selection
        val currentIcon = storagePreferences.getInstantEventIcon()
        binding.instantEventIconSpinner.setText(currentIcon.displayName, false)

        // Handle selection changes
        binding.instantEventIconSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedIcon = iconOptions[position]
            storagePreferences.setInstantEventIcon(selectedIcon)
            Snackbar.make(binding.root, "Icon changed to ${selectedIcon.displayName}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupCardColorStyleSelection() {
        val options = CardColorStyle.values()
        val names = options.map { it.displayName }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        binding.cardColorStyleSpinner.setAdapter(adapter)

        val current = storagePreferences.getCardColorStyle()
        binding.cardColorStyleSpinner.setText(current.displayName, false)

        binding.cardColorStyleSpinner.setOnItemClickListener { _, _, position, _ ->
            val selected = options[position]
            storagePreferences.setCardColorStyle(selected)
            Snackbar.make(binding.root, "Card color: ${selected.displayName}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupBackupSection() {
        binding.exportDeviceButton.setOnClickListener {
            runBackup { configBackupManager.exportToDocuments(requireContext()) }
        }
        binding.importDeviceButton.setOnClickListener {
            confirmImport { runBackup { configBackupManager.importFromDocuments(requireContext()) } }
        }
        binding.exportDriveButton.setOnClickListener {
            runBackup { configBackupManager.exportToDrive() }
        }
        binding.importDriveButton.setOnClickListener {
            confirmImport { runBackup { configBackupManager.importFromDrive() } }
        }
    }

    private fun confirmImport(onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_import_confirm_title)
            .setMessage(R.string.backup_import_confirm_message)
            .setPositiveButton(R.string.backup_import_confirm_button) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runBackup(action: suspend () -> ConfigBackupManager.BackupResult) {
        setBackupButtonsEnabled(false)
        binding.backupStatusText.text = getString(R.string.backup_working)
        lifecycleScope.launch {
            val result = action()
            val message = when (result) {
                is ConfigBackupManager.BackupResult.Success -> result.message
                is ConfigBackupManager.BackupResult.Error -> result.message
            }
            // Guard against the view being destroyed while the IO work was in flight
            if (_binding != null) {
                binding.backupStatusText.text = message
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                setBackupButtonsEnabled(true)
            }
        }
    }

    private fun setBackupButtonsEnabled(enabled: Boolean) {
        binding.exportDeviceButton.isEnabled = enabled
        binding.importDeviceButton.isEnabled = enabled
        binding.exportDriveButton.isEnabled = enabled
        binding.importDriveButton.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
