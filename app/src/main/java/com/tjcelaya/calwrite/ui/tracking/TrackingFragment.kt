package com.tjcelaya.calwrite.ui.tracking

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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import com.tjcelaya.calwrite.MainActivity
import com.tjcelaya.calwrite.R
import com.tjcelaya.calwrite.CalWriteApplication
import com.tjcelaya.calwrite.data.CardColorStyle
import com.tjcelaya.calwrite.data.EventViewMode
import com.tjcelaya.calwrite.data.StoragePreferences
import com.tjcelaya.calwrite.data.database.EventType
import com.tjcelaya.calwrite.data.database.OngoingEvent
import com.tjcelaya.calwrite.databinding.FragmentTrackingBinding
import com.tjcelaya.calwrite.ui.dialogs.NotificationPermissionDialog
import com.tjcelaya.calwrite.ui.dialogs.SaveEventDialog
import com.tjcelaya.calwrite.ui.main.PhotoEventDialog
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
    private lateinit var futureEventsAdapter: FutureEventsAdapter
    private lateinit var futureHeaderAdapter: SectionHeaderAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var storagePreferences: StoragePreferences
    private var currentViewMode: EventViewMode = EventViewMode.LIST

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
        setupViewControls()
        setupClickListeners()
        observeViewModel()
        checkPermissionsAndSetup()
        checkNotificationPermissions()

        // Check if we have a shared photo to handle
        checkForSharedPhoto()
    }

    private fun setupViewModel() {
        val app = requireActivity().application as CalWriteApplication
        val eventRepository = app.eventRepository
        val calendarRepository = app.calendarRepository
        storagePreferences = app.storagePreferences

        val factory = TrackingViewModelFactory(eventRepository, calendarRepository)
        viewModel = ViewModelProvider(this, factory)[TrackingViewModel::class.java]
    }

    private fun setupRecyclerViews() {
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

        futureEventsAdapter = FutureEventsAdapter(
            onCompleteEarly = { futureEvent, eventType ->
                viewModel.recordEarlyEvent(futureEvent.id, eventType.id, eventType.name)
            },
            onCancelEvent = { futureEvent ->
                viewModel.deleteFutureEvent(futureEvent.id)
            }
        )

        futureHeaderAdapter = SectionHeaderAdapter("Scheduled Events")

        // Single scrolling list so RecyclerView can recycle views properly (previously these
        // lists were nested with wrap_content inside a ScrollView, which broke recycling and
        // made scrolling hitch/stick once the content was taller than the screen).
        val concatAdapter = ConcatAdapter(eventTypesAdapter, futureHeaderAdapter, futureEventsAdapter)

        // GridLayoutManager backs both list (1 column) and card (N columns) modes. Only event-type
        // cards span a single column; everything else (section header, future events) spans the row.
        gridLayoutManager = GridLayoutManager(requireContext(), 1)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (currentViewMode != EventViewMode.CARD) return 1
                return if (position < eventTypesAdapter.itemCount) 1 else gridLayoutManager.spanCount
            }
        }
        gridLayoutManager.spanSizeLookup.isSpanIndexCacheEnabled = false

        binding.mainRecyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = concatAdapter
        }
    }

    private fun setupViewControls() {
        // Reflect persisted state without firing listeners
        val mode = storagePreferences.getEventViewMode()
        binding.viewModeToggle.check(if (mode == EventViewMode.CARD) R.id.cardViewButton else R.id.listViewButton)
        binding.cardSizeSlider.value = storagePreferences.getCardSizeDp().toFloat()

        binding.viewModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = if (checkedId == R.id.cardViewButton) EventViewMode.CARD else EventViewMode.LIST
            storagePreferences.setEventViewMode(newMode)
            applyViewMode()
        }

        binding.cardSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                storagePreferences.setCardSizeDp(value.toInt())
                recomputeSpanCount()
            }
        }

        binding.cardSizeDecrease.setOnClickListener { nudgeCardSize(-10) }
        binding.cardSizeIncrease.setOnClickListener { nudgeCardSize(10) }

        applyViewMode()
    }

    private fun nudgeCardSize(deltaDp: Int) {
        val newSize = (storagePreferences.getCardSizeDp() + deltaDp)
            .coerceIn(StoragePreferences.CARD_SIZE_MIN_DP, StoragePreferences.CARD_SIZE_MAX_DP)
        storagePreferences.setCardSizeDp(newSize)
        binding.cardSizeSlider.value = newSize.toFloat() // fromUser=false, handled below
        recomputeSpanCount()
    }

    private fun applyViewMode() {
        val mode = storagePreferences.getEventViewMode()
        currentViewMode = mode
        eventTypesAdapter.setViewMode(mode)
        eventTypesAdapter.setCardColorStyle(storagePreferences.getCardColorStyle())
        binding.cardSizeRow.visibility = if (mode == EventViewMode.CARD) View.VISIBLE else View.GONE
        recomputeSpanCount()
    }

    private fun recomputeSpanCount() {
        if (currentViewMode != EventViewMode.CARD) {
            gridLayoutManager.spanCount = 1
            eventTypesAdapter.setCellWidthPx(0)
            return
        }
        val rv = _binding?.mainRecyclerView ?: return
        val available = rv.width - rv.paddingLeft - rv.paddingRight
        if (available <= 0) {
            rv.post { recomputeSpanCount() }
            return
        }
        val target = dpToPx(storagePreferences.getCardSizeDp())
        val span = (available / target).coerceAtLeast(1)
        gridLayoutManager.spanCount = span
        eventTypesAdapter.setCellWidthPx(available / span)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

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

        
        setupQuickAddInput()
    }
    
    private fun setupQuickAddInput() {
        // Enable/disable send button based on input
        binding.quickAddInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.quickAddSendButton.isEnabled = !s.isNullOrBlank()
            }
        })
        
        // Handle enter key
        binding.quickAddInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                handleQuickAdd()
                true
            } else false
        }
        
        // Handle send button click
        binding.quickAddSendButton.setOnClickListener {
            handleQuickAdd()
        }
    }
    
    private fun handleQuickAdd() {
        val eventName = binding.quickAddInput.text?.toString()?.trim() ?: return
        if (eventName.isEmpty()) return
        
        lifecycleScope.launch {
            // Check if event type exists
            val app = requireActivity().application as CalWriteApplication
            val existingType = app.eventRepository.getEventTypeByName(eventName)
            
            if (existingType != null) {
                // Existing event type - record immediately
                viewModel.recordInstantaneousEvent(existingType.id)
            } else {
                // New event name - save to calendar, then ask about saving as type
                viewModel.recordInstantEventWithName(eventName)
                
                // Show dialog asking if they want to save as event type
                showSaveAsEventTypeDialog(eventName)
            }
            
            // Clear input
            binding.quickAddInput.text?.clear()
            
            // Hide keyboard
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.quickAddInput.windowToken, 0)
        }
    }
    
    private fun showSaveAsEventTypeDialog(eventName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Save as Event Type?")
            .setMessage("Do you want to save '$eventName' for quick access in the future?")
            .setPositiveButton("Yes") { _, _ ->
                viewModel.createEventTypeFromQuickAdd(eventName)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun observeViewModel() {
        // Observe event types with counts for main adapter
        viewModel.eventTypesWithCounts.observe(viewLifecycleOwner) { eventTypesWithCounts ->
            eventTypesAdapter.submitList(eventTypesWithCounts)
            binding.emptyEventTypesText.visibility =
                if (eventTypesWithCounts.isEmpty()) View.VISIBLE else View.GONE
            
            // Always keep timer running if there are event types (for live time-since updates)
            if (eventTypesWithCounts.isNotEmpty()) {
                startTimerUpdates()
            }
        }

        // Observe displayable ongoing items for timer updates only. Ongoing events are rendered
        // inline within each event-type row, so there is no separate ongoing list to submit to.
        viewModel.displayableOngoingItems.observe(viewLifecycleOwner) { displayableItems ->
            val hasOngoingEvents = displayableItems.any { it.type == DisplayableOngoingItem.Type.REGULAR_EVENT }
            if (hasOngoingEvents) {
                startTimerUpdates()
            } else {
                stopTimerUpdates()
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

            // Show/hide the "Scheduled Events" header based on whether there are future events
            futureHeaderAdapter.setVisible(futureEvents.isNotEmpty())

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
                
                // Calculate next update interval based on the shortest "time since"
                val nextInterval = calculateDynamicUpdateInterval()
                timerHandler.postDelayed(this, nextInterval)
            }
        }
        timerHandler.post(timerRunnable!!)
    }
    
    private fun calculateDynamicUpdateInterval(): Long {
        // Find the minimum time since last occurrence across all event types
        val eventTypesWithCounts = viewModel.eventTypesWithCounts.value ?: emptyList()
        val currentTime = System.currentTimeMillis()
        
        var minTimeSince = Long.MAX_VALUE
        
        // Check ongoing events (always need second-by-second updates)
        val hasOngoingEvents = eventTypesWithCounts.any { it.ongoingEvent != null }
        if (hasOngoingEvents) {
            return 1000L // Update every second if there are ongoing events
        }
        
        // Check future events (may need second-by-second updates)
        val futureEvents = viewModel.futureEventsWithTypes.value ?: emptyList()
        if (futureEvents.isNotEmpty()) {
            return 1000L // Update every second if there are future events with countdowns
        }
        
        // Calculate minimum time since last occurrence for all event types
        eventTypesWithCounts.forEach { item ->
            item.lastOccurrenceTime?.let { lastTime ->
                val timeSince = currentTime - lastTime
                if (timeSince < minTimeSince) {
                    minTimeSince = timeSince
                }
            }
        }
        
        // Dynamic interval based on shortest time since
        return when {
            minTimeSince < 60_000L -> 1000L        // Less than 1 minute: update every second
            minTimeSince < 3600_000L -> 60_000L    // Less than 1 hour: update every minute
            else -> 60_000L                         // 1 hour or more: update every minute (for consistency)
        }
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
        val app = requireActivity().application as CalWriteApplication
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
            val imageFileName = "CALWRITE_${timeStamp}_"
            val storageDir = File(requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "CalWrite")

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
            val app = requireActivity().application as CalWriteApplication
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
        
        // Restart timers when coming back to foreground
        val eventTypesWithCounts = viewModel.eventTypesWithCounts.value ?: emptyList()
        if (eventTypesWithCounts.isNotEmpty()) {
            startTimerUpdates()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Stop timers when going to background to save battery
        stopTimerUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTimerUpdates()
        _binding = null
    }
}
