package com.tjcelaya.scribcal.ui.events

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.data.BubbleMode
import com.tjcelaya.scribcal.data.EventRepository
import com.tjcelaya.scribcal.data.StoragePreferences
import com.tjcelaya.scribcal.data.database.Cadence
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.databinding.FragmentAddEditEventTypeBinding
import com.tjcelaya.scribcal.utils.GoogleCalendarColors

class AddEditEventTypeFragment : Fragment() {

    private var _binding: FragmentAddEditEventTypeBinding? = null
    private val binding get() = _binding!!
    private val args: AddEditEventTypeFragmentArgs by navArgs()

    private lateinit var viewModel: AddEditEventTypeViewModel
    private lateinit var storagePreferences: StoragePreferences
    private var editingEventType: EventType? = null
    private var selectedColorId: Int? = null // Google Calendar color ID (1-11)

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

        val app = requireActivity().application as ScribCalApplication
        storagePreferences = app.storagePreferences

        setupViewModel()
        setupBubbleToggle()
        setupCadenceToggle()
        loadEventTypeFromArguments()
        setupColorList()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupViewModel() {
        val database = ScribCalDatabase.getDatabase(requireContext())
        val eventRepository = EventRepository(database)
        val factory = AddEditEventTypeViewModelFactory(eventRepository)
        viewModel = ViewModelProvider(this, factory)[AddEditEventTypeViewModel::class.java]
    }

    private fun setupBubbleToggle() {
        // Only show bubble toggle when bubble mode is SELECTED
        val bubbleMode = storagePreferences.getBubbleMode()
        binding.bubbleSwitch.visibility = if (bubbleMode == BubbleMode.SELECTED) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun setupCadenceToggle() {
        // Default selection for new event types
        applyCadenceSelection(Cadence.BOTH)
    }

    private fun applyCadenceSelection(cadence: Cadence) {
        val buttonId = when (cadence) {
            Cadence.INSTANT -> R.id.cadenceInstantButton
            Cadence.TIMED -> R.id.cadenceTimedButton
            Cadence.BOTH -> R.id.cadenceBothButton
        }
        binding.cadenceToggleGroup.check(buttonId)
    }

    private fun selectedCadence(): Cadence = when (binding.cadenceToggleGroup.checkedButtonId) {
        R.id.cadenceInstantButton -> Cadence.INSTANT
        R.id.cadenceTimedButton -> Cadence.TIMED
        else -> Cadence.BOTH
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

    private fun setupColorList() {
        val colorList = binding.colorList
        val swatchSize = resources.getDimensionPixelSize(R.dimen.color_button_size)

        GoogleCalendarColors.ALL_COLORS.forEach { calColor ->
            colorList.addView(createColorRow(swatchSize, calColor))
        }

        // Default to Blueberry if nothing selected
        if (selectedColorId == null) {
            selectColor(GoogleCalendarColors.BLUEBERRY.id)
        }
    }

    private fun createColorRow(
        swatchSize: Int,
        calColor: GoogleCalendarColors.CalendarColor
    ): View {
        val dp = resources.displayMetrics.density

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * dp).toInt() }
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            isClickable = true
            isFocusable = true
            tag = calColor.id  // stores the colorId (1-11) on this row
            setOnClickListener { selectColor(calColor.id) }
        }

        // Swatch circle filled with the hex color for display
        val swatch = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                marginEnd = (16 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(calColor.hexColor)
            }
        }

        // Color name label
        val label = TextView(requireContext()).apply {
            text = calColor.name
            textSize = 16f
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.tab_indicator_text))
        }

        row.addView(swatch)
        row.addView(label)
        return row
    }

    /**
     * Select a color by its Google Calendar colorId (1-11).
     * The colorId is what gets stored in EventType and sent to the calendar API.
     */
    private fun selectColor(colorId: Int) {
        selectedColorId = colorId
        updateColorSelection()
    }

    private fun updateColorSelection() {
        val colorList = binding.colorList
        val dp = resources.displayMetrics.density

        for (i in 0 until colorList.childCount) {
            val row = colorList.getChildAt(i) as LinearLayout
            val rowColorId = row.tag as Int  // the colorId (1-11) stored on this row
            val swatch = row.getChildAt(0)
            val hexColor = GoogleCalendarColors.getHexColorById(rowColorId) ?: 0

            if (rowColorId == selectedColorId) {
                // Selection ring around swatch
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(hexColor)
                    setStroke((3 * dp).toInt(), ContextCompat.getColor(requireContext(), R.color.scribcal_blue))
                }
                row.setBackgroundColor(0x18000000) // subtle highlight
            } else {
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(hexColor)
                }
                row.setBackgroundColor(0) // transparent
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

            // Restore color selection
            if (eventType.colorId != null) {
                selectColor(eventType.colorId)
            }

            // Restore bubble toggle state
            binding.bubbleSwitch.isChecked = eventType.shouldBubble

            // Restore cadence selection
            applyCadenceSelection(eventType.cadence)
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

        val shouldBubble = binding.bubbleSwitch.isChecked
        val cadence = selectedCadence()

        val eventType = if (editingEventType == null) {
            // Creating new event type
            EventType(
                name = name,
                description = description.takeIf { it?.isNotBlank() == true },
                colorId = selectedColorId,
                shouldBubble = shouldBubble,
                cadence = cadence
            )
        } else {
            // Updating existing event type
            editingEventType!!.copy(
                name = name,
                description = description.takeIf { it?.isNotBlank() == true },
                colorId = selectedColorId,
                shouldBubble = shouldBubble,
                cadence = cadence
            )
        }

        viewModel.saveEventType(eventType)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
