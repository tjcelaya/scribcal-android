package com.tjcelaya.scribcal.ui.events

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.databinding.FragmentAddEventBinding
import kotlinx.coroutines.launch

class AddEventFragment : Fragment() {

    private var _binding: FragmentAddEventBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AddEventViewModel
    private lateinit var autocompleteAdapter: EventTypeAutocompleteAdapter
    private var eventTypes: List<EventType> = emptyList()

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
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
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
            Toast.makeText(requireContext(), "Please enter an event type", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading state
        setLoadingState(true)
        
        lifecycleScope.launch {
            try {
                if (isInstant) {
                    viewModel.recordInstantEvent(eventTypeName)
                } else {
                    viewModel.startTimedEvent(eventTypeName)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                setLoadingState(false)
            }
        }
    }

    private fun setLoadingState(loading: Boolean) {
        binding.recordInstantEventButton.isEnabled = !loading
        binding.startTimedEventButton.isEnabled = !loading
        binding.eventTypeAutoComplete.isEnabled = !loading
        
        if (loading) {
            binding.recordInstantEventButton.text = "Recording..."
            binding.startTimedEventButton.text = "Starting..."
        } else {
            binding.recordInstantEventButton.text = "Record Instant Event"
            binding.startTimedEventButton.text = "Start Timed Event"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}