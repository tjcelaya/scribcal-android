package com.tjcelaya.scribcal.ui.events

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.button.MaterialButton
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ui.components.CustomColorPickerDialog
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.databinding.FragmentAddEditEventTypeBinding
import com.tjcelaya.scribcal.utils.GoogleCalendarColors

class AddEditEventTypeFragment : Fragment() {

    private var _binding: FragmentAddEditEventTypeBinding? = null
    private val binding get() = _binding!!
    private val args: AddEditEventTypeFragmentArgs by navArgs()

    private lateinit var viewModel: AddEditEventTypeViewModel
    private var editingEventType: EventType? = null
    private var selectedColorId: Int? = null // Google Calendar color ID or CUSTOM_COLOR_ID
    private var selectedCustomColor: Int? = null // Hex color when using custom color

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
        loadEventTypeFromArguments()
        setupColorGrid()
        // setupCustomColorButton() // Commented out - custom colors not supported by Google Calendar API
        setupClickListeners()
        observeViewModel()
    }

    private fun setupViewModel() {
        val database = ScribCalDatabase.getDatabase(requireContext())
        val eventRepository = EventRepository(database)
        val factory = AddEditEventTypeViewModelFactory(eventRepository)
        viewModel = ViewModelProvider(this, factory)[AddEditEventTypeViewModel::class.java]
    }

    private fun loadEventTypeFromArguments() {
        val eventTypeId = args.eventTypeId
        if (eventTypeId > 0L) {
            // We're editing an existing event type
            viewModel.loadEventType(eventTypeId)
        } else {
            // We're creating a new event type
            updateUI()
        }
    }

    private fun setupColorGrid() {
        val gridLayout = binding.colorGrid
        val colorButtonSize = resources.getDimensionPixelSize(R.dimen.color_button_size)
        val colorButtonMargin = resources.getDimensionPixelSize(R.dimen.color_button_margin)

        // Add Google Calendar colors
        GoogleCalendarColors.ALL_COLORS.forEach { calColor ->
            val colorButton = createColorButton(
                colorButtonSize, 
                colorButtonMargin, 
                calColor.hexColor,
                calColor.id
            )
            gridLayout.addView(colorButton)
        }

        // Select first color by default if nothing is selected
        if (selectedColorId == null) {
            selectColor(GoogleCalendarColors.BLUEBERRY.id, GoogleCalendarColors.BLUEBERRY.hexColor)
        }
    }
    
    private fun createColorButton(
        size: Int,
        margin: Int,
        hexColor: Int,
        colorId: Int
    ): MaterialButton {
        return MaterialButton(requireContext()).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(margin, margin, margin, margin)
            }
            setBackgroundColor(hexColor)
            cornerRadius = size / 2
            setOnClickListener {
                selectColor(colorId, hexColor)
            }
            tag = colorId
        }
    }

    private fun selectColor(colorId: Int, hexColor: Int? = null) {
        selectedColorId = colorId
        if (GoogleCalendarColors.isCustomColor(colorId)) {
            selectedCustomColor = hexColor
        } else {
            selectedCustomColor = null
        }
        updateColorSelection()
    }
    
    // Custom color picker commented out - not supported by Google Calendar API
    /*
    private fun showCustomColorPicker() {
        val initialColor = selectedCustomColor ?: Color.BLUE
        
        CustomColorPickerDialog.show(
            requireContext(),
            initialColor
        ) { selectedColor ->
            selectCustomColor(selectedColor)
        }
    }
    */
    
    // Custom color functionality commented out - not supported by Google Calendar API
    /*
    private fun setupCustomColorButton() {
        binding.customColorButton.setOnClickListener {
            showCustomColorPicker()
        }
    }
    
    private fun selectCustomColor(hexColor: Int) {
        // Update the custom color button to show the selected color
        val hexString = String.format("#%06X", 0xFFFFFF and hexColor)
        binding.customColorButton.text = "Custom Color: $hexString"
        binding.customColorButton.iconTint = ContextCompat.getColorStateList(requireContext(), android.R.color.transparent)
        binding.customColorButton.setBackgroundColor(hexColor)
        selectColor(GoogleCalendarColors.CUSTOM_COLOR_ID, hexColor)
    }
    */

    private fun updateColorSelection() {
        val gridLayout = binding.colorGrid
        for (i in 0 until gridLayout.childCount) {
            val button = gridLayout.getChildAt(i) as MaterialButton
            val buttonColorId = button.tag as Int

            if (buttonColorId == selectedColorId) {
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
                Snackbar.make(
                    binding.root,
                    if (editingEventType == null) "Event type created" else "Event type updated",
                    Snackbar.LENGTH_SHORT
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

        viewModel.loadedEventType.observe(viewLifecycleOwner) { eventType ->
            editingEventType = eventType
            updateUI()
        }
    }
    
    private fun updateUI() {
        editingEventType?.let { eventType ->
            binding.nameEditText.setText(eventType.name)
            binding.descriptionEditText.setText(eventType.description)
            binding.saveButton.text = "Update"

            // Restore color selection (custom colors commented out)
            if (eventType.colorId != null) {
                val hexColor = GoogleCalendarColors.getHexColorById(eventType.colorId!!)
                selectColor(eventType.colorId!!, hexColor)
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
                colorId = selectedColorId,
                customColorHex = selectedCustomColor
            )
        } else {
            // Updating existing event type
            editingEventType!!.copy(
                name = name,
                description = description.takeIf { it?.isNotBlank() == true },
                colorId = selectedColorId,
                customColorHex = selectedCustomColor
            )
        }

        viewModel.saveEventType(eventType)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
