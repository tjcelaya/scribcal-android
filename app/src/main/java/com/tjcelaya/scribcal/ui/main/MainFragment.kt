package com.tjcelaya.scribcal.ui.main

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import com.tjcelaya.scribcal.MainActivity
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.EventWithType
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.databinding.FragmentMainBinding
import com.tjcelaya.scribcal.util.PhotoUtils
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main fragment showing today's events and quick actions
 */
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private var currentEventTypes: List<EventType> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d("MainFragment", "onViewCreated: Starting fragment setup")
        setupViewModel()
        setupClickListeners()
        observeData()

        // Check if launched with shared photo
        // TODO: Implement photo sharing functionality
        // val sharedPhotoPath = arguments?.getString(MainActivity.EXTRA_SHARED_PHOTO_PATH)
        // if (sharedPhotoPath != null) {
        //     handleSharedPhoto(sharedPhotoPath)
        // }

        Log.d("MainFragment", "onViewCreated: Fragment setup complete")
    }

    private fun setupViewModel() {
        Log.d("MainFragment", "setupViewModel: Creating database and repository")
        // Create repository with database
        val database = ScribCalDatabase.getInstance(requireContext())
        val repository = EventRepository(database)
        val factory = MainViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]
        Log.d("MainFragment", "setupViewModel: ViewModel created successfully")
    }

    private fun setupClickListeners() {
        binding.btnInstantEvent.setOnClickListener {
            if (currentEventTypes.isNotEmpty()) {
                EventTypePicker.show(requireContext(), currentEventTypes) { eventType ->
                    viewModel.createInstantEvent(eventType.id)
                    showMessage("Created instant ${eventType.name} event!")
                }
            } else {
                showMessage("Loading event types...")
            }
        }

        binding.btnStartTimed.setOnClickListener {
            if (currentEventTypes.isNotEmpty()) {
                EventTypePicker.show(requireContext(), currentEventTypes) { eventType ->
                    viewModel.startTimedEvent(eventType.id)
                    showMessage("Started ${eventType.name} timer!")
                }
            } else {
                showMessage("Loading event types...")
            }
        }
    }

    private fun observeData() {
        // Observe event types
        viewModel.allEventTypes.observe(viewLifecycleOwner) { eventTypes ->
            Log.d("MainFragment", "Received ${eventTypes.size} event types: ${eventTypes.map { it.name }}")
            currentEventTypes = eventTypes
        }

        // Observe today's events
        viewModel.todaysEvents.observe(viewLifecycleOwner) { events ->
            Log.d("MainFragment", "Received ${events.size} today's events")
            if (events.isEmpty()) {
                showEmptyState()
            } else {
                showEvents(events)
            }
        }
    }

    private fun showEmptyState() {
        binding.ongoingEventsHeader.visibility = View.GONE
        binding.completedEventsHeader.visibility = View.GONE
        binding.emptyStateText.visibility = View.VISIBLE
    }

    private fun showEvents(events: List<EventWithType>) {
        val ongoingEvents = events.filter { it.isOngoing }
        val completedEvents = events.filter { !it.isOngoing }

        // Show/hide sections based on content
        if (ongoingEvents.isNotEmpty()) {
            binding.ongoingEventsHeader.visibility = View.VISIBLE
            showOngoingEvents(ongoingEvents)
        } else {
            binding.ongoingEventsHeader.visibility = View.GONE
        }

        if (completedEvents.isNotEmpty()) {
            binding.completedEventsHeader.visibility = View.VISIBLE
            showCompletedEvents(completedEvents)
            binding.emptyStateText.visibility = View.GONE
        } else {
            binding.completedEventsHeader.visibility = View.GONE
            if (ongoingEvents.isEmpty()) {
                binding.emptyStateText.visibility = View.VISIBLE
            }
        }
    }

    private fun showOngoingEvents(events: List<EventWithType>) {
        binding.ongoingEventsContainer.removeAllViews()

        events.forEach { event ->
            val eventView = createEventView(event, isOngoing = true)
            binding.ongoingEventsContainer.addView(eventView)
        }
    }

    private fun showCompletedEvents(events: List<EventWithType>) {
        binding.completedEventsContainer.removeAllViews()

        events.forEach { event ->
            val eventView = createEventView(event, isOngoing = false)
            binding.completedEventsContainer.addView(eventView)
        }
    }

    private fun createEventView(event: EventWithType, isOngoing: Boolean): View {
        val view = layoutInflater.inflate(R.layout.item_event, null)

        // Find views
        val photoThumbnail = view.findViewById<android.widget.ImageView>(R.id.photoThumbnail)
        val eventIcon = view.findViewById<TextView>(R.id.eventIcon)
        val eventTitle = view.findViewById<TextView>(R.id.eventTitle)
        val eventTime = view.findViewById<TextView>(R.id.eventTime)
        val eventStatus = view.findViewById<TextView>(R.id.eventStatus)
        val eventNotes = view.findViewById<TextView>(R.id.eventNotes)

        // Setup photo thumbnail if available
        val eventPhotoPath = event.photoPath
        if (event.hasPhoto && eventPhotoPath != null) {
            val thumbnail = PhotoUtils.createThumbnail(eventPhotoPath)
            if (thumbnail != null) {
                photoThumbnail.setImageBitmap(thumbnail)
                photoThumbnail.visibility = View.VISIBLE
                photoThumbnail.setOnClickListener {
                    PhotoViewerDialog.show(requireContext(), eventPhotoPath)
                }
            }
        } else {
            photoThumbnail.visibility = View.GONE
        }

        // Setup event details
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val startTime = timeFormat.format(Date(event.startTime))

        val (icon, statusText, textColor) = if (isOngoing) {
            val elapsed = (System.currentTimeMillis() - event.startTime) / 1000
            val minutes = elapsed / 60
            val seconds = elapsed % 60
            Triple("🔴", "${minutes}m ${seconds}s (tap to complete)", android.graphics.Color.parseColor("#FF6B35"))
        } else if (event.isInstant) {
            Triple("📌", "Instant event", android.graphics.Color.parseColor("#4ECDC4"))
        } else {
            val durationMs = event.durationMs
            val minutes = durationMs / (1000 * 60)
            val seconds = (durationMs % (1000 * 60)) / 1000
            Triple("✅", "${minutes}m ${seconds}s", android.graphics.Color.parseColor("#45B7D1"))
        }

        eventIcon.text = icon
        eventTitle.text = event.eventTypeName
        eventTime.text = startTime
        eventStatus.text = statusText
        eventStatus.setTextColor(textColor)

        // Show notes if available
        if (event.notes.isNotBlank()) {
            eventNotes.text = "Note: ${event.notes}"
            eventNotes.visibility = View.VISIBLE
        } else {
            eventNotes.visibility = View.GONE
        }

        // Setup background and click listeners
        if (isOngoing) {
            view.setBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
            view.setOnClickListener {
                // Complete the ongoing event
                viewModel.completeEvent(event.id)
                showMessage("Completed ${event.eventTypeName}!")
            }
        } else {
            view.setBackgroundColor(android.graphics.Color.parseColor("#F8F9FA"))
        }

        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 8)
        view.layoutParams = params

        return view
    }

    private fun showMessage(message: String) {
        // Simple toast-like message - in a real app you'd use proper Toast or Snackbar
        binding.emptyStateText.text = message
        binding.emptyStateText.visibility = View.VISIBLE
    }

    /**
     * Handles a shared photo by showing the photo event dialog
     */
    fun handleSharedPhoto(photoPath: String) {
        Log.d("MainFragment", "handleSharedPhoto: $photoPath")

        if (currentEventTypes.isNotEmpty()) {
            PhotoEventDialog.show(requireContext(), photoPath, currentEventTypes) { eventType, notes, isInstant ->
                if (isInstant) {
                    viewModel.createInstantEventWithPhoto(eventType.id, photoPath, notes)
                    showMessage("Created instant ${eventType.name} event with photo!")
                } else {
                    viewModel.startTimedEventWithPhoto(eventType.id, photoPath, notes)
                    showMessage("Started ${eventType.name} timer with photo!")
                }
            }
        } else {
            // Wait for event types to load, then show dialog
            viewModel.allEventTypes.observe(viewLifecycleOwner) { eventTypes ->
                if (eventTypes.isNotEmpty()) {
                    PhotoEventDialog.show(requireContext(), photoPath, eventTypes) { eventType, notes, isInstant ->
                        if (isInstant) {
                            viewModel.createInstantEventWithPhoto(eventType.id, photoPath, notes)
                            showMessage("Created instant ${eventType.name} event with photo!")
                        } else {
                            viewModel.startTimedEventWithPhoto(eventType.id, photoPath, notes)
                            showMessage("Started ${eventType.name} timer with photo!")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
