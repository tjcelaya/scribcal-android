package com.tjcelaya.scribcal.ui.events

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.databinding.FragmentAddEditEventTypeBinding

class AddEditEventTypeFragment : Fragment() {

    private var _binding: FragmentAddEditEventTypeBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AddEditEventTypeViewModel
    private var editingEventType: EventType? = null
    private var selectedColor: Int? = null

    // Predefined color palette
    private val colorPalette = listOf(
        Color.parseColor("#F44336"), // Red
        Color.parseColor("#E91E63"), // Pink
        Color.parseColor("#9C27B0"), // Purple
        Color.parseColor("#673AB7"), // Deep Purple
        Color.parseColor("#3F51B5"), // Indigo
        Color.parseColor("#2196F3"), // Blue
        Color.parseColor("#03A9F4"), // Light Blue
        Color.parseColor("#00BCD4"), // Cyan
        Color.parseColor("#009688"), // Teal
        Color.parseColor("#4CAF50"), // Green
        Color.parseColor("#8BC34A"), // Light Green
        Color.parseColor("#CDDC39"), // Lime
        Color.parseColor("#FFEB3B"), // Yellow
        Color.parseColor("#FFC107"), // Amber
        Color.parseColor("#FF9800"), // Orange
        Color.parseColor("#FF5722"), // Deep Orange
        Color.parseColor("#795548"), // Brown
        Color.parseColor("#607D8B"), // Blue Grey
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEditEventTypeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupColorGrid()
        setupClickListeners()
        observeViewModel()

        // TODO: Get event type from arguments if editing
        // For now, we'll assume we're always creating new
        updateUI()
    }

    private fun setupViewModel() {
        val database = ScribCalDatabase.getDatabase(requireContext())
        val eventRepository = EventRepository(database)
        val factory = AddEditEventTypeViewModelFactory(eventRepository)
        viewModel = ViewModelProvider(this, factory)[AddEditEventTypeViewModel::class.java]
    }

    private fun setupColorGrid() {
        val gridLayout = binding.colorGrid
        val colorButtonSize = resources.getDimensionPixelSize(R.dimen.color_button_size)
        val colorButtonMargin = resources.getDimensionPixelSize(R.dimen.color_button_margin)

        colorPalette.forEachIndexed { index, color ->
            val colorButton = MaterialButton(requireContext()).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = colorButtonSize
                    height = colorButtonSize
                    setMargins(colorButtonMargin, colorButtonMargin, colorButtonMargin, colorButtonMargin)
                }
                setBackgroundColor(color)
                cornerRadius = colorButtonSize / 2
                setOnClickListener {
                    selectColor(color)
                }
                tag = color
            }
            
            gridLayout.addView(colorButton)
        }

        // Select first color by default
        if (selectedColor == null) {
            selectColor(colorPalette.first())
        }
    }

    private fun selectColor(color: Int) {
        selectedColor = color
        updateColorSelection()
    }

    private fun updateColorSelection() {
        val gridLayout = binding.colorGrid
        for (i in 0 until gridLayout.childCount) {
            val button = gridLayout.getChildAt(i) as MaterialButton
            val buttonColor = button.tag as Int
            
            if (buttonColor == selectedColor) {
                // Add selection indicator (white stroke)
                button.strokeWidth = 4
                button.strokeColor = ContextCompat.getColorStateList(requireContext(), android.R.color.white)
            } else {
                // Remove selection indicator
                button.strokeWidth = 0
            }
        }
    }

    private fun setupClickListeners() {
        binding.saveButton.setOnClickListener {
            saveEventType()
        }

        binding.cancelButton.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun observeViewModel() {
        viewModel.saveResult.observe(viewLifecycleOwner) { success ->
            if (success) {
                Toast.makeText(
                    requireContext(),
                    if (editingEventType == null) "Event type created" else "Event type updated",
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().navigateUp()
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                binding.nameInputLayout.error = message
                viewModel.clearErrorMessage()
            }
        }
    }

    private fun updateUI() {
        editingEventType?.let { eventType ->
            binding.nameEditText.setText(eventType.name)
            binding.descriptionEditText.setText(eventType.description)
            binding.saveButton.text = "Update"
            
            eventType.color?.let { color ->
                selectedColor = color
                updateColorSelection()
            }
        }
    }

    private fun saveEventType() {
        val name = binding.nameEditText.text?.toString()?.trim()
        val description = binding.descriptionEditText.text?.toString()?.trim()

        if (name.isNullOrBlank()) {
            binding.nameInputLayout.error = "Event type name is required"
            return
        }

        binding.nameInputLayout.error = null

        val eventType = if (editingEventType == null) {
            // Creating new event type
            EventType(
                name = name,
                description = description.takeIf { it?.isNotBlank() == true },
                color = selectedColor
            )
        } else {
            // Updating existing event type
            editingEventType!!.copy(
                name = name,
                description = description.takeIf { it?.isNotBlank() == true },
                color = selectedColor
            )
        }

        viewModel.saveEventType(eventType)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
