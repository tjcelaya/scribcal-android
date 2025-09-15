package com.tjcelaya.scribcal.ui.events

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.databinding.FragmentEventsBinding

class EventsFragment : Fragment() {

    private var _binding: FragmentEventsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: EventsViewModel
    private lateinit var adapter: EventTypeAdapter

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
        adapter = EventTypeAdapter(
            onEditClick = { eventType ->
                // TODO: Navigate to edit screen
                navigateToAddEditEventType(eventType)
            },
            onDeleteClick = { eventType ->
                viewModel.showDeleteConfirmation(eventType)
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
        viewModel.eventTypes.observe(viewLifecycleOwner) { eventTypes ->
            adapter.submitList(eventTypes)
            updateEmptyState(eventTypes.isEmpty())
        }

        viewModel.deleteConfirmation.observe(viewLifecycleOwner) { eventType ->
            if (eventType != null) {
                showDeleteConfirmationDialog(eventType)
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
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

    private fun navigateToAddEditEventType(eventType: EventType?) {
        findNavController().navigate(R.id.action_events_to_add_edit_event_type)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
