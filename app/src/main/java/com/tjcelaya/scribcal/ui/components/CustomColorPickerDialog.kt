package com.tjcelaya.scribcal.ui.components

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.tjcelaya.scribcal.databinding.DialogCustomColorPickerBinding

/**
 * Custom color picker dialog using RGB sliders
 * No external dependencies - uses only Material Design components
 */
class CustomColorPickerDialog(
    context: Context,
    private val initialColor: Int = Color.BLUE,
    private val onColorSelected: (Int) -> Unit
) : AlertDialog(context) {

    private lateinit var binding: DialogCustomColorPickerBinding
    private var currentColor: Int = initialColor
    private var isUpdatingFromWheel = false
    private var isUpdatingFromHex = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = DialogCustomColorPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize color wheel
        binding.colorWheel.setColor(initialColor)
        binding.colorWheel.onColorChanged = { color ->
            if (!isUpdatingFromHex) {
                isUpdatingFromWheel = true
                currentColor = color
                updateColorPreview()
                updateHexInput()
                isUpdatingFromWheel = false
            }
        }
        
        // Initialize brightness slider
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        binding.valueSeekBar.progress = (hsv[2] * 100).toInt()
        binding.valueSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.colorWheel.setValue(progress / 100f)
                    currentColor = binding.colorWheel.getColor()
                    updateColorPreview()
                    updateHexInput()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Initialize hex input
        updateHexInput()
        binding.hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromWheel && s != null && s.isNotEmpty()) {
                    try {
                        val hexString = if (s.startsWith("#")) s.toString() else "#$s"
                        if (hexString.length == 7) {
                            isUpdatingFromHex = true
                            val color = Color.parseColor(hexString)
                            currentColor = color
                            binding.colorWheel.setColor(color)
                            updateColorPreview()
                            isUpdatingFromHex = false
                        }
                    } catch (e: IllegalArgumentException) {
                        // Invalid hex color, ignore
                    }
                }
            }
        })
        
        // Update preview
        updateColorPreview()
        
        // Set up buttons
        binding.cancelButton.setOnClickListener {
            dismiss()
        }
        
        binding.selectButton.setOnClickListener {
            onColorSelected(currentColor)
            dismiss()
        }
    }
    
    
    private fun updateColorPreview() {
        binding.colorPreview.setBackgroundColor(currentColor)
    }
    
    private fun updateHexInput() {
        if (!isUpdatingFromHex) {
            val hexString = String.format("#%06X", 0xFFFFFF and currentColor)
            binding.hexInput.setText(hexString)
        }
    }
    
    companion object {
        fun show(
            context: Context,
            initialColor: Int = Color.BLUE,
            onColorSelected: (Int) -> Unit
        ) {
            CustomColorPickerDialog(context, initialColor, onColorSelected).show()
        }
    }
}
