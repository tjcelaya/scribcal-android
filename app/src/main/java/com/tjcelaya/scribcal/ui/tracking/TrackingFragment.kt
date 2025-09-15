package com.tjcelaya.scribcal.ui.tracking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.CalendarRepository
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.data.database.OngoingEvent
import com.tjcelaya.scribcal.databinding.FragmentTrackingBinding
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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val calendarReadGranted = permissions[Manifest.permission.READ_CALENDAR] ?: false
        val calendarWriteGranted = permissions[Manifest.permission.WRITE_CALENDAR] ?: false
        
        if (calendarReadGranted && calendarWriteGranted) {
            viewModel.onCalendarPermissionsGranted()
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
    }

    private fun setupViewModel() {
        val database = ScribCalDatabase.getDatabase(requireContext())
        val eventRepository = EventRepository(database)
        val calendarRepository = CalendarRepository(requireContext())
        
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
            findNavController().navigate(R.id.eventsFragment)
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

        // Observe ongoing events with types for ongoing adapter
        viewModel.ongoingEventsWithTypes.observe(viewLifecycleOwner) { ongoingEventsWithTypes ->
            ongoingEventsAdapter.submitList(ongoingEventsWithTypes)
            binding.ongoingEventsCard.visibility = 
                if (ongoingEventsWithTypes.isNotEmpty()) View.VISIBLE else View.GONE
            
            // Start or stop timer based on whether there are ongoing events
            if (ongoingEventsWithTypes.isNotEmpty()) {
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
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        stopTimerUpdates()
        _binding = null
    }
}
