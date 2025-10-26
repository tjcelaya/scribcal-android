package com.tjcelaya.scribcal.ui.events

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.OngoingEvent
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.databinding.FragmentEventsBinding

class EventsFragment : Fragment() {

    private var _binding: FragmentEventsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: EventsViewModel
    private lateinit var adapter: UnifiedEventAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEventsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupViewModel() {
        val database = ScribCalDatabase.getDatabase(requireContext())
        val eventRepository = EventRepository(database)
        val factory = EventsViewModelFactory(eventRepository)
        viewModel = ViewModelProvider(this, factory)[EventsViewModel::class.java]
    }

    private fun setupRecyclerView() {
        adapter = UnifiedEventAdapter(
            onEditClick = { eventType ->
                // TODO: Navigate to edit screen
                navigateToAddEditEventType(eventType)
            },
            onDeleteClick = { eventType ->
                viewModel.showDeleteConfirmation(eventType)
            },
            onStopEventClick = { ongoingEvent ->
                showStopEventConfirmationDialog(ongoingEvent)
            }
        )

        binding.eventTypesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@EventsFragment.adapter
        }
    }

    private fun setupClickListeners() {
        binding.addEventTypeFab.setOnClickListener {
            navigateToAddEditEventType(null)
        }
    }

    private fun observeViewModel() {
        viewModel.displayItems.observe(viewLifecycleOwner) { displayItems ->
            adapter.submitList(displayItems)
            updateEmptyState(displayItems.isEmpty())
        }

        viewModel.deleteConfirmation.observe(viewLifecycleOwner) { eventType ->
            if (eventType != null) {
                showDeleteConfirmationDialog(eventType)
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                viewModel.clearErrorMessage()
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.eventTypesRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showDeleteConfirmationDialog(eventType: EventType) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Event Type")
            .setMessage("Are you sure you want to delete '${eventType.name}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteEventType(eventType)
            }
            .setNegativeButton("Cancel") { _, _ ->
                viewModel.hideDeleteConfirmation()
            }
            .setOnCancelListener {
                viewModel.hideDeleteConfirmation()
            }
            .show()
    }

    private fun showStopEventConfirmationDialog(ongoingEvent: OngoingEvent) {
        AlertDialog.Builder(requireContext())
            .setTitle("Stop Event")
            .setMessage("Are you sure you want to stop this event? It will be saved to your calendar.")
            .setPositiveButton("Stop") { _, _ ->
                viewModel.stopOngoingEvent(ongoingEvent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigateToAddEditEventType(eventType: EventType?) {
        val action = EventsFragmentDirections.actionEventsToAddEditEventType(
            eventTypeId = eventType?.id ?: 0L
        )
        findNavController().navigate(action)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
