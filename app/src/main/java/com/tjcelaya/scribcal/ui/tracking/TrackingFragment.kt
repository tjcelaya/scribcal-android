package com.tjcelaya.scribcal.ui.tracking

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.database.OngoingEvent
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.databinding.FragmentTrackingBinding
import com.tjcelaya.scribcal.ui.main.PhotoEventDialog
import com.tjcelaya.scribcal.MainActivity
import com.tjcelaya.scribcal.data.StoragePreferences
import android.util.Log
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class TrackingFragment : Fragment() {

    private var _binding: FragmentTrackingBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: TrackingViewModel
    private lateinit var eventTypesAdapter: EventTypesTrackingAdapter
    private lateinit var ongoingEventsAdapter: OngoingEventsAdapter
    
    // Timer for real-time updates
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    
    // Photo capture variables
    private var currentPhotoUri: Uri? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val calendarReadGranted = permissions[Manifest.permission.READ_CALENDAR] ?: false
        val calendarWriteGranted = permissions[Manifest.permission.WRITE_CALENDAR] ?: false
        
        if (calendarReadGranted && calendarWriteGranted) {
            viewModel.onCalendarPermissionsGranted()
        }
    }
    
    // Camera permission launcher
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required to take photos", Toast.LENGTH_LONG).show()
        }
    }
    
    // Camera launcher
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            currentPhotoUri?.let { uri ->
                val filePath = getFilePathFromUri(uri)
                if (filePath != null) {
                    handleCapturedPhoto(filePath)
                }
            }
        }
    }
    
    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val filePath = getFilePathFromUri(uri)
                if (filePath != null) {
                    handleSelectedImage(filePath)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrackingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupRecyclerViews()
        setupClickListeners()
        observeViewModel()
        checkPermissionsAndSetup()
        
        // Check if we have a shared photo to handle
        checkForSharedPhoto()
    }

    private fun setupViewModel() {
        val app = requireActivity().application as ScribCalApplication
        val eventRepository = app.eventRepository
        val calendarRepository = app.calendarRepository
        
        val factory = TrackingViewModelFactory(eventRepository, calendarRepository)
        viewModel = ViewModelProvider(this, factory)[TrackingViewModel::class.java]
    }

    private fun setupRecyclerViews() {
        // Event Types RecyclerView
        eventTypesAdapter = EventTypesTrackingAdapter(
            onStartEvent = { eventType ->
                viewModel.startEvent(eventType.id)
            },
            onRecordInstantEvent = { eventType ->
                viewModel.recordInstantaneousEvent(eventType.id)
            }
        )
        
        binding.eventTypesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = eventTypesAdapter
        }

        // Ongoing Events RecyclerView
        ongoingEventsAdapter = OngoingEventsAdapter(
            onStopEvent = { ongoingEvent ->
                viewModel.showStopEventConfirmation(ongoingEvent)
            }
        )
        
        binding.ongoingEventsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ongoingEventsAdapter
        }
    }

    private fun setupClickListeners() {
        binding.fab.setOnClickListener {
            findNavController().navigate(R.id.addEventFragment)
        }

        binding.cameraFab.setOnClickListener {
            checkStorageConfigurationThenOpenCamera()
        }
        
        binding.imageFab.setOnClickListener {
            checkStorageConfigurationThenOpenImagePicker()
        }

        binding.manageEventTypesButton.setOnClickListener {
            findNavController().navigate(R.id.eventsFragment)
        }

        binding.calendarSetupButton.setOnClickListener {
            findNavController().navigate(R.id.calendarSetupFragment)
        }
    }

    private fun observeViewModel() {
        // Observe event types with counts for main adapter
        viewModel.eventTypesWithCounts.observe(viewLifecycleOwner) { eventTypesWithCounts ->
            eventTypesAdapter.submitList(eventTypesWithCounts)
            binding.emptyEventTypesText.visibility = 
                if (eventTypesWithCounts.isEmpty()) View.VISIBLE else View.GONE
        }

        // Observe displayable ongoing items (both events and photo uploads)
        viewModel.displayableOngoingItems.observe(viewLifecycleOwner) { displayableItems ->
            ongoingEventsAdapter.submitList(displayableItems)
            binding.ongoingEventsCard.visibility = 
                if (displayableItems.isNotEmpty()) View.VISIBLE else View.GONE
            
            // Start or stop timer based on whether there are ongoing items
            if (displayableItems.isNotEmpty()) {
                startTimerUpdates()
            } else {
                stopTimerUpdates()
            }
        }

        viewModel.calendarStatus.observe(viewLifecycleOwner) { status ->
            binding.calendarStatusText.text = status
        }

        viewModel.needsCalendarSetup.observe(viewLifecycleOwner) { needsSetup ->
            if (needsSetup) {
                binding.calendarSetupButton.text = "Setup Required"
                binding.calendarSetupButton.isEnabled = true
            } else {
                binding.calendarSetupButton.text = "Change"
                binding.calendarSetupButton.isEnabled = true
            }
        }
        
        // Observe stop confirmation dialog
        viewModel.showStopConfirmation.observe(viewLifecycleOwner) { ongoingEvent ->
            if (ongoingEvent != null) {
                showStopConfirmationDialog(ongoingEvent)
            }
        }
        
        // Observe messages
        viewModel.message.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                viewModel.clearMessage()
            }
        }
    }
    
    private fun showStopConfirmationDialog(ongoingEvent: OngoingEvent) {
        // Calculate elapsed time for display
        val elapsedMillis = System.currentTimeMillis() - ongoingEvent.startTime
        val elapsedSeconds = elapsedMillis / 1000
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        val elapsedTimeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        
        val startTime = Date(ongoingEvent.startTime)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        
        AlertDialog.Builder(requireContext())
            .setTitle("Stop Event")
            .setMessage("Stop this event?\n\nElapsed time: $elapsedTimeString\nStarted at: ${timeFormat.format(startTime)}\n\nThe event will be saved to your calendar.")
            .setPositiveButton("Stop") { _, _ ->
                viewModel.stopEvent(ongoingEvent)
            }
            .setNegativeButton("Cancel") { _, _ ->
                viewModel.hideStopEventConfirmation()
            }
            .setOnCancelListener {
                viewModel.hideStopEventConfirmation()
            }
            .show()
    }

    private fun checkPermissionsAndSetup() {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        val hasWritePermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasReadPermission || !hasWritePermission) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            )
        } else {
            viewModel.onCalendarPermissionsGranted()
        }
    }
    
    private fun startTimerUpdates() {
        // Don't start multiple timers
        if (timerRunnable != null) return
        
        timerRunnable = object : Runnable {
            override fun run() {
                // Refresh the ongoing events adapter to update elapsed time displays
                ongoingEventsAdapter.refreshTimers()
                timerHandler.postDelayed(this, 1000) // Update every second
            }
        }
        timerHandler.post(timerRunnable!!)
    }
    
    private fun stopTimerUpdates() {
        timerRunnable?.let {
            timerHandler.removeCallbacks(it)
            timerRunnable = null
        }
    }

    private fun checkForSharedPhoto() {
        val activity = requireActivity()
        val sharedPhotoPath = activity.intent?.getStringExtra(MainActivity.EXTRA_SHARED_PHOTO_PATH)
        
        Log.d("TrackingFragment", "Checking for shared photo: $sharedPhotoPath")
        
        if (!sharedPhotoPath.isNullOrEmpty()) {
            Log.d("TrackingFragment", "Found shared photo, showing dialog")
            // Clear the intent extra so we don't show the dialog again
            activity.intent?.removeExtra(MainActivity.EXTRA_SHARED_PHOTO_PATH)
            
            // Wait for event types to be loaded, then show the photo dialog
            viewModel.eventTypes.observe(viewLifecycleOwner) { eventTypes ->
                if (eventTypes.isNotEmpty()) {
                    handleSharedPhoto(sharedPhotoPath, eventTypes)
                }
            }
        }
    }
    
    private fun handleSharedPhoto(photoPath: String, eventTypes: List<EventType>) {
        Log.d("TrackingFragment", "Handling shared photo with ${eventTypes.size} event types")
        
        PhotoEventDialog.show(
            requireContext(),
            photoPath,
            eventTypes
        ) { eventType, notes, isInstant ->
            Log.d("TrackingFragment", "Creating event: ${eventType.name}, instant: $isInstant")
            
            if (isInstant) {
                // Create instant event with photo
                createInstantEventWithPhoto(eventType.id, photoPath, notes)
            } else {
                // Start timed event with photo
                startTimedEventWithPhoto(eventType.id, photoPath, notes)
            }
        }
    }
    
    private fun createInstantEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String) {
        Log.d("TrackingFragment", "Creating instant event with photo: $photoPath, notes: $notes")
        viewModel.recordInstantaneousEventWithPhoto(eventTypeId, photoPath, notes)
    }
    
    private fun startTimedEventWithPhoto(eventTypeId: Long, photoPath: String, notes: String) {
        Log.d("TrackingFragment", "Starting timed event with photo: $photoPath, notes: $notes")
        viewModel.startTimedEventWithPhoto(eventTypeId, photoPath, notes)
    }

    // Camera and image picker methods
    
    private fun checkStorageConfigurationThenOpenCamera() {
        if (isPhotoStorageConfigured()) {
            openCamera()
        } else {
            showStorageConfigurationDialog()
        }
    }
    
    private fun checkStorageConfigurationThenOpenImagePicker() {
        if (isPhotoStorageConfigured()) {
            openImagePicker()
        } else {
            showStorageConfigurationDialog()
        }
    }
    
    private fun isPhotoStorageConfigured(): Boolean {
        val app = requireActivity().application as ScribCalApplication
        val storagePreferences = app.storagePreferences
        val driveRepository = app.driveRepository
        val photosRepository = app.photosRepository
        
        val isDriveEnabled = storagePreferences.isGoogleDriveEnabled()
        val isPhotosEnabled = storagePreferences.isGooglePhotosEnabled()
        
        // Use unified health check methods
        val isDriveHealthy = driveRepository.isHealthy()
        val isPhotosHealthy = photosRepository.isHealthy()
        
        // At least one storage option must be enabled and healthy
        return (isDriveEnabled && isDriveHealthy) || (isPhotosEnabled && isPhotosHealthy)
    }
    
    private fun showStorageConfigurationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Photo Storage Required")
            .setMessage("Please set up at least one photo storage option (Google Drive or Google Photos) before taking or selecting photos.")
            .setPositiveButton("Go to Settings") { _, _ ->
                findNavController().navigate(R.id.settingsFragment)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun openCamera() {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        if (hasCameraPermission) {
            launchCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    private fun launchCamera() {
        val photoFile = createImageFile()
        if (photoFile != null) {
            currentPhotoUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                photoFile
            )
            
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri)
            }
            
            cameraLauncher.launch(intent)
        } else {
            Toast.makeText(requireContext(), "Unable to create photo file", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        imagePickerLauncher.launch(intent)
    }
    
    private fun createImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "SCRIBCAL_${timeStamp}_"
            val storageDir = File(requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ScribCal")
            
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            
            File.createTempFile(imageFileName, ".jpg", storageDir)
        } catch (ex: IOException) {
            Log.e("TrackingFragment", "Error creating image file", ex)
            null
        }
    }
    
    private fun getFilePathFromUri(uri: Uri): String? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val file = createImageFile()
                if (file != null) {
                    file.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    inputStream.close()
                    file.absolutePath
                } else null
            } else null
        } catch (e: Exception) {
            Log.e("TrackingFragment", "Error copying file from URI", e)
            null
        }
    }
    
    private fun handleCapturedPhoto(photoPath: String) {
        // Get available event types and show photo dialog
        viewModel.eventTypes.observe(viewLifecycleOwner) { eventTypes ->
            if (eventTypes.isNotEmpty()) {
                showPhotoEventDialog(photoPath, eventTypes)
            } else {
                Toast.makeText(requireContext(), "No event types available. Create an event type first.", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun handleSelectedImage(imagePath: String) {
        // Get available event types and show photo dialog
        viewModel.eventTypes.observe(viewLifecycleOwner) { eventTypes ->
            if (eventTypes.isNotEmpty()) {
                showPhotoEventDialog(imagePath, eventTypes)
            } else {
                Toast.makeText(requireContext(), "No event types available. Create an event type first.", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showPhotoEventDialog(photoPath: String, eventTypes: List<EventType>) {
        PhotoEventDialog.show(
            requireContext(),
            photoPath,
            eventTypes
        ) { eventType, notes, isInstant ->
            if (isInstant) {
                createInstantEventWithPhoto(eventType.id, photoPath, notes)
            } else {
                startTimedEventWithPhoto(eventType.id, photoPath, notes)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTimerUpdates()
        _binding = null
    }
}
