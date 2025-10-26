package com.tjcelaya.scribcal.ui.events

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.android.material.snackbar.Snackbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.databinding.FragmentAddEventBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AddEventFragment : Fragment() {

    private var _binding: FragmentAddEventBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AddEventViewModel
    private lateinit var autocompleteAdapter: EventTypeAutocompleteAdapter
    private var eventTypes: List<EventType> = emptyList()
    private var selectedDateTime: Calendar = Calendar.getInstance()
    private val dateTimeFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEventBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupAutoCompleteTextView()
        setupDateTimeDisplay()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupViewModel() {
        val app = requireActivity().application as ScribCalApplication
        val eventRepository = app.eventRepository
        val calendarRepository = app.calendarRepository

        val factory = AddEventViewModelFactory(eventRepository, calendarRepository)
        viewModel = ViewModelProvider(this, factory)[AddEventViewModel::class.java]
    }

    private fun setupAutoCompleteTextView() {
        // Initialize custom adapter with colored dots
        autocompleteAdapter = EventTypeAutocompleteAdapter(
            requireContext(),
            emptyList()
        )

        binding.eventTypeAutoComplete.setAdapter(autocompleteAdapter)
        binding.eventTypeAutoComplete.threshold = 0 // Show all suggestions immediately

        // Show dropdown when user taps the field
        binding.eventTypeAutoComplete.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.eventTypeAutoComplete.showDropDown()
            }
        }

        // Also show dropdown when user taps the field
        binding.eventTypeAutoComplete.setOnClickListener {
            binding.eventTypeAutoComplete.showDropDown()
        }

        // Handle selection from dropdown
        binding.eventTypeAutoComplete.setOnItemClickListener { _, _, position, _ ->
            val selectedEventType = autocompleteAdapter.getItem(position)
            binding.eventTypeAutoComplete.setText(selectedEventType.name)
        }
    }

    private fun setupDateTimeDisplay() {
        // Initialize with current date/time
        updateDateTimeDisplay()

        // Handle click to show date/time picker
        binding.dateTimeDisplay.setOnClickListener {
            showDateTimePicker()
        }
    }

    private fun updateDateTimeDisplay() {
        binding.dateTimeDisplay.text = dateTimeFormat.format(selectedDateTime.time)
    }

    private fun showDateTimePicker() {
        val currentDate = selectedDateTime

        // Show date picker first
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                // Update selected date
                selectedDateTime.set(Calendar.YEAR, year)
                selectedDateTime.set(Calendar.MONTH, month)
                selectedDateTime.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                // Now show time picker
                showTimePicker()
            },
            currentDate.get(Calendar.YEAR),
            currentDate.get(Calendar.MONTH),
            currentDate.get(Calendar.DAY_OF_MONTH)
        )

        datePickerDialog.show()
    }

    private fun showTimePicker() {
        val currentTime = selectedDateTime

        val timePickerDialog = TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                // Update selected time
                selectedDateTime.set(Calendar.HOUR_OF_DAY, hourOfDay)
                selectedDateTime.set(Calendar.MINUTE, minute)
                selectedDateTime.set(Calendar.SECOND, 0)
                selectedDateTime.set(Calendar.MILLISECOND, 0)

                // Update display
                updateDateTimeDisplay()
            },
            currentTime.get(Calendar.HOUR_OF_DAY),
            currentTime.get(Calendar.MINUTE),
            false // Use 12-hour format
        )

        timePickerDialog.show()
    }

    private fun setupClickListeners() {
        binding.recordInstantEventButton.setOnClickListener {
            recordEvent(isInstant = true)
        }

        binding.startTimedEventButton.setOnClickListener {
            recordEvent(isInstant = false)
        }

        binding.cancelButton.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun observeViewModel() {
        // Observe available event types for autocomplete
        viewModel.eventTypes.observe(viewLifecycleOwner) { types ->
            eventTypes = types
            autocompleteAdapter.updateEventTypes(types)
        }

        // Observe messages
        viewModel.message.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                val displayMessage = when {
                    message == "SUCCESS_INSTANT_EVENT" -> getString(R.string.instant_event_recorded)
                    message == "SUCCESS_TIMED_EVENT" -> getString(R.string.timed_event_started)
                    message == "ERROR_ONGOING_EVENT_EXISTS" -> getString(R.string.ongoing_event_exists)
                    message.startsWith("ERROR_RECORDING_EVENT:") -> {
                        val errorMsg = message.substringAfter(":")
                        getString(R.string.error_recording_event, errorMsg)
                    }
                    message.startsWith("ERROR_STARTING_EVENT:") -> {
                        val errorMsg = message.substringAfter(":")
                        getString(R.string.error_starting_event, errorMsg)
                    }
                    else -> message // Fallback to original message
                }
                Snackbar.make(binding.root, displayMessage, Snackbar.LENGTH_LONG).show()
                viewModel.clearMessage()
            }
        }

        // Observe navigation events
        viewModel.navigateBack.observe(viewLifecycleOwner) { shouldNavigate ->
            if (shouldNavigate) {
                findNavController().navigateUp()
                viewModel.onNavigatedBack()
            }
        }
    }


    private fun recordEvent(isInstant: Boolean) {
        val eventTypeName = binding.eventTypeAutoComplete.text.toString().trim()

        if (eventTypeName.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.please_enter_event_type), Snackbar.LENGTH_SHORT).show()
            return
        }

        // Show loading state
        setLoadingState(true)

        lifecycleScope.launch {
            try {
                if (isInstant) {
                    viewModel.recordInstantEvent(eventTypeName, selectedDateTime.timeInMillis)
                } else {
                    viewModel.startTimedEvent(eventTypeName, selectedDateTime.timeInMillis)
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, getString(R.string.error_format, e.message), Snackbar.LENGTH_LONG).show()
                setLoadingState(false)
            }
        }
    }

    private fun setLoadingState(loading: Boolean) {
        binding.recordInstantEventButton.isEnabled = !loading
        binding.startTimedEventButton.isEnabled = !loading
        binding.eventTypeAutoComplete.isEnabled = !loading

        if (loading) {
            binding.recordInstantEventButton.text = getString(R.string.recording)
            binding.startTimedEventButton.text = getString(R.string.starting)
        } else {
            binding.recordInstantEventButton.text = getString(R.string.save_right_now)
            binding.startTimedEventButton.text = getString(R.string.start_timed_event)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}