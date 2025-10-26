package com.tjcelaya.scribcal.ui.tracking

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.snackbar.Snackbar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import com.tjcelaya.scribcal.MainActivity
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent
import com.tjcelaya.scribcal.databinding.FragmentTrackingBinding
import com.tjcelaya.scribcal.ui.dialogs.NotificationPermissionDialog
import com.tjcelaya.scribcal.ui.dialogs.SaveEventDialog
import com.tjcelaya.scribcal.ui.main.PhotoEventDialog
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackingFragment : Fragment() {

    private var _binding: FragmentTrackingBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: TrackingViewModel
    private lateinit var eventTypesAdapter: EventTypesTrackingAdapter
    private lateinit var ongoingEventsAdapter: OngoingEventsAdapter
    private lateinit var futureEventsAdapter: FutureEventsAdapter

    // Timer for real-time updates
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    // Photo capture variables
    private var currentPhotoUri: Uri? = null
    
    // FAB menu state
    private var isFabMenuOpen = false

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
            Snackbar.make(binding.root, "Camera permission is required to take photos", Snackbar.LENGTH_LONG).show()
        }
    }

    // Notification permission launcher (Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("TrackingFragment", "Notification permission granted")
        } else {
            Log.w("TrackingFragment", "Notification permission denied - notifications will not appear")
            Snackbar.make(binding.root, "Notification permission is required to show ongoing event notifications", Snackbar.LENGTH_LONG).show()
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
        checkNotificationPermissions()

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
            },
            onStopEvent = { ongoingEvent ->
                viewModel.showStopEventConfirmation(ongoingEvent)
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

        // Future Events RecyclerView
        futureEventsAdapter = FutureEventsAdapter(
            onCompleteEarly = { futureEvent, eventType ->
                viewModel.recordEarlyEvent(futureEvent.id, eventType.id, eventType.name)
            },
            onCancelEvent = { futureEvent ->
                viewModel.deleteFutureEvent(futureEvent.id)
            }
        )

        binding.futureEventsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = futureEventsAdapter
        }
    }

    private fun setupClickListeners() {
        // Main FAB toggles the menu
        binding.fab.setOnClickListener {
            toggleFabMenu()
        }
        
        // Overlay closes the menu
        binding.fabOverlay.setOnClickListener {
            closeFabMenu()
        }

        // FAB menu item: Create new event type
        binding.fabNewEvent.setOnClickListener {
            closeFabMenu()
            findNavController().navigate(R.id.action_tracking_to_add_edit_event_type)
        }

        // FAB menu item: Capture photo
        binding.fabCamera.setOnClickListener {
            closeFabMenu()
            checkStorageConfigurationThenOpenCamera()
        }

        // FAB menu item: Select image
        binding.fabImage.setOnClickListener {
            closeFabMenu()
            checkStorageConfigurationThenOpenImagePicker()
        }

        // FAB menu item: Schedule future event
        binding.fabScheduleEvent.setOnClickListener {
            closeFabMenu()
            showEventTypePickerForScheduling()
        }

        binding.manageEventTypesButton.setOnClickListener {
            findNavController().navigate(R.id.eventsFragment)
        }

        binding.calendarSetupButton.setOnClickListener {
            findNavController().navigate(R.id.calendarSetupFragment)
        }
        
        // Temporary debug: Add long click to test notifications
        binding.manageEventTypesButton.setOnLongClickListener {
            val app = requireActivity().application as ScribCalApplication
            app.notificationService.showTestNotification()
            true
        }
    }

    private fun observeViewModel() {
        // Observe event types with counts for main adapter
        viewModel.eventTypesWithCounts.observe(viewLifecycleOwner) { eventTypesWithCounts ->
            eventTypesAdapter.submitList(eventTypesWithCounts)
            binding.emptyEventTypesText.visibility =
                if (eventTypesWithCounts.isEmpty()) View.VISIBLE else View.GONE
        }

        // Observe displayable ongoing items (both events and photo uploads) for timer updates only
        viewModel.displayableOngoingItems.observe(viewLifecycleOwner) { displayableItems ->
            ongoingEventsAdapter.submitList(displayableItems)
            binding.ongoingEventsCard.visibility = View.GONE // Hide the separate ongoing events card

            // Start or stop timer based on whether there are ongoing items
            val hasOngoingEvents = displayableItems.any { it.type == DisplayableOngoingItem.Type.REGULAR_EVENT }
            if (hasOngoingEvents) {
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

        // Observe save confirmation dialog
        viewModel.showStopConfirmation.observe(viewLifecycleOwner) { ongoingEvent ->
            if (ongoingEvent != null) {
                showSaveConfirmationDialog(ongoingEvent)
            }
        }

        // Observe messages
        viewModel.message.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                viewModel.clearMessage()
            }
        }

        // Observe future events
        viewModel.futureEventsWithTypes.observe(viewLifecycleOwner) { futureEvents ->
            futureEventsAdapter.submitList(futureEvents)
            
            // Show/hide section based on whether there are future events
            binding.futureEventsSection.visibility = if (futureEvents.isEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }
            
            // Start timer if there are future events (for countdown updates)
            if (futureEvents.isNotEmpty()) {
                startTimerUpdates()
            }
        }
    }

    private fun showSaveConfirmationDialog(ongoingEvent: OngoingEvent) {
        // Get event type for the dialog
        viewModel.getEventTypeById(ongoingEvent.eventTypeId)?.let { eventType ->
            SaveEventDialog.show(
                context = requireContext(),
                ongoingEvent = ongoingEvent,
                eventType = eventType,
                onSave = { viewModel.stopEvent(ongoingEvent) },
                onDiscardWithoutSaving = { viewModel.stopEventWithoutSaving(ongoingEvent) },
                onCancel = { viewModel.hideStopEventConfirmation() }
            )
        } ?: run {
            // If event type not found, just hide the dialog
            viewModel.hideStopEventConfirmation()
        }
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

    private fun checkNotificationPermissions() {
        // Check notification permission for Android 13+ (API level 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasNotificationPermission) {
                Log.d("TrackingFragment", "Notification permission not granted, showing dialog...")
                
                // Show explanatory dialog before requesting permission
                NotificationPermissionDialog.show(
                    requireContext(),
                    onPermissionRequested = {
                        Log.d("TrackingFragment", "User chose to allow notifications, requesting permission...")
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onSkipped = {
                        Log.d("TrackingFragment", "User chose to skip notifications")
                        // Permission can be requested later if needed
                    }
                )
            } else {
                Log.d("TrackingFragment", "Notification permission already granted")
            }
        } else {
            Log.d("TrackingFragment", "Android version < 13, notification permission not required")
        }
    }

    private fun startTimerUpdates() {
        // Don't start multiple timers
        if (timerRunnable != null) return

        timerRunnable = object : Runnable {
            override fun run() {
                // Refresh the event types adapter to update elapsed time displays
                eventTypesAdapter.refreshTimers()
                // Refresh future event countdown timers
                futureEventsAdapter.refreshTimers()
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
    
    private fun toggleFabMenu() {
        if (isFabMenuOpen) {
            closeFabMenu()
        } else {
            openFabMenu()
        }
    }
    
    private fun openFabMenu() {
        isFabMenuOpen = true
        
        // Show overlay
        binding.fabOverlay.visibility = View.VISIBLE
        binding.fabOverlay.alpha = 0f
        binding.fabOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
        
        // Change main FAB to close button with lighter background
        binding.fab.setImageResource(R.drawable.ic_close)
        binding.fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#E3F2FD")
        )
        binding.fab.imageTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#1976D2")
        )
        
        // Show and animate menu items (from bottom to top)
        animateFabMenuItem(binding.fabImage, 0)
        animateFabMenuItem(binding.fabCamera, 50)
        animateFabMenuItem(binding.fabScheduleEvent, 100)
        animateFabMenuItem(binding.fabNewEvent, 150)
    }
    
    private fun closeFabMenu() {
        isFabMenuOpen = false
        
        // Hide overlay
        binding.fabOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                _binding?.fabOverlay?.visibility = View.GONE
            }
            .start()
        
        // Change main FAB back to add button with original colors
        binding.fab.setImageResource(R.drawable.ic_add)
        binding.fab.backgroundTintList = null // Reset to theme default
        binding.fab.imageTintList = null // Reset to theme default
        
        // Hide menu items
        hideFabMenuItem(binding.fabNewEvent, 0)
        hideFabMenuItem(binding.fabScheduleEvent, 50)
        hideFabMenuItem(binding.fabCamera, 100)
        hideFabMenuItem(binding.fabImage, 150)
    }
    
    private fun animateFabMenuItem(layout: View, delay: Long) {
        layout.visibility = View.VISIBLE
        layout.alpha = 0f
        layout.translationY = 20f
        layout.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(150)
            .start()
    }
    
    private fun hideFabMenuItem(layout: View, delay: Long) {
        layout.animate()
            .alpha(0f)
            .translationY(20f)
            .setStartDelay(delay)
            .setDuration(150)
            .withEndAction {
                if (_binding != null) {
                    layout.visibility = View.GONE
                }
            }
            .start()
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
            Snackbar.make(binding.root, "Unable to create photo file", Snackbar.LENGTH_SHORT).show()
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
                Snackbar.make(binding.root, "No event types available. Create an event type first.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun handleSelectedImage(imagePath: String) {
        // Get available event types and show photo dialog
        viewModel.eventTypes.observe(viewLifecycleOwner) { eventTypes ->
            if (eventTypes.isNotEmpty()) {
                showPhotoEventDialog(imagePath, eventTypes)
            } else {
                Snackbar.make(binding.root, "No event types available. Create an event type first.", Snackbar.LENGTH_LONG).show()
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

    private fun showEventTypePickerForScheduling() {
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as ScribCalApplication
            val calendarRepository = app.calendarRepository
            
            // Fetch both event types and upcoming calendar events
            val eventTypes = viewModel.eventTypes.value ?: emptyList()
            val calendarEvents = calendarRepository.getUpcomingEvents(30)
            
            if (eventTypes.isEmpty() && calendarEvents.isEmpty()) {
                Snackbar.make(binding.root, "No event types or calendar events available.", Snackbar.LENGTH_LONG).show()
                return@launch
            }
            
            // Build combined list of options
            val items = mutableListOf<String>()
            val itemTypes = mutableListOf<ItemType>()
            
            // Add header and event types
            if (eventTypes.isNotEmpty()) {
                items.add("--- Your Event Types ---")
                itemTypes.add(ItemType.Header)
                
                eventTypes.forEach { eventType ->
                    items.add(eventType.name)
                    itemTypes.add(ItemType.EventType(eventType.id))
                }
            }
            
            // Add header and calendar events
            if (calendarEvents.isNotEmpty()) {
                items.add("--- Upcoming Calendar Events ---")
                itemTypes.add(ItemType.Header)
                
                calendarEvents.take(10).forEach { calendarEvent ->
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    val timeStr = dateFormat.format(Date(calendarEvent.startTime))
                    items.add("${calendarEvent.title} ($timeStr)")
                    itemTypes.add(ItemType.CalendarEvent(calendarEvent.title, calendarEvent.startTime))
                }
            }
            
            AlertDialog.Builder(requireContext())
                .setTitle("Select Event Type or Calendar Event")
                .setItems(items.toTypedArray()) { _, which ->
                    when (val itemType = itemTypes[which]) {
                        is ItemType.EventType -> {
                            showScheduleFutureEventDialog(itemType.eventTypeId)
                        }
                        is ItemType.CalendarEvent -> {
                            handleCalendarEventSelection(itemType.title, itemType.startTime)
                        }
                        is ItemType.Header -> {
                            // Do nothing for headers
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private sealed class ItemType {
        object Header : ItemType()
        data class EventType(val eventTypeId: Long) : ItemType()
        data class CalendarEvent(val title: String, val startTime: Long) : ItemType()
    }
    
    private fun handleCalendarEventSelection(eventTitle: String, startTime: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Create new event type from calendar event title
            val eventTypeId = viewModel.createEventTypeFromCalendarEvent(eventTitle)
            
            if (eventTypeId != null) {
                // Schedule the future event with the calendar event's start time
                viewModel.createFutureEvent(
                    eventTypeId = eventTypeId,
                    targetTime = startTime,
                    notes = "From calendar event"
                )
            } else {
                Snackbar.make(binding.root, "Failed to create event type", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showScheduleFutureEventDialog(eventTypeId: Long) {
        // Use MaterialDatePicker and MaterialTimePicker
        val datePicker = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select date")
            .setSelection(com.google.android.material.datepicker.MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        
        datePicker.addOnPositiveButtonClickListener { dateMillis ->
            // Then show time picker
            val timePicker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTitleText("Select time")
                .setHour(12)
                .setMinute(0)
                .build()
            
            timePicker.addOnPositiveButtonClickListener {
                val calendar = java.util.Calendar.getInstance().apply {
                    timeInMillis = dateMillis
                    set(java.util.Calendar.HOUR_OF_DAY, timePicker.hour)
                    set(java.util.Calendar.MINUTE, timePicker.minute)
                    set(java.util.Calendar.SECOND, 0)
                }
                
                viewModel.createFutureEvent(
                    eventTypeId = eventTypeId,
                    targetTime = calendar.timeInMillis,
                    notes = null
                )
            }
            
            timePicker.show(childFragmentManager, "time_picker")
        }
        
        datePicker.show(childFragmentManager, "date_picker")
    }

    override fun onResume() {
        super.onResume()
        // Check for expired future events when fragment resumes
        viewModel.checkExpiredFutureEvents()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTimerUpdates()
        _binding = null
    }
}
