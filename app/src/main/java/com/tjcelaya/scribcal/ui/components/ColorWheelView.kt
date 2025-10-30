package com.tjcelaya.scribcal.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Custom HSV color wheel view
 * Allows intuitive color selection by touching the wheel
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    
    private var wheelRadius = 0f
    private var centerX = 0f
    private var centerY = 0f
    
    // HSV values
    private var hue = 0f
    private var saturation = 1f
    private var value = 1f
    
    var onColorChanged: ((Int) -> Unit)? = null
    
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        wheelRadius = min(centerX, centerY) - 20f
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw color wheel
        drawColorWheel(canvas)
        
        // Draw cursor at current selection
        drawCursor(canvas)
    }
    
    private fun drawColorWheel(canvas: Canvas) {
        val sweepGradient = SweepGradient(
            centerX, centerY,
            intArrayOf(
                Color.RED,
                Color.YELLOW,
                Color.GREEN,
                Color.CYAN,
                Color.BLUE,
                Color.MAGENTA,
                Color.RED
            ),
            null
        )
        
        val radialGradient = RadialGradient(
            centerX, centerY, wheelRadius,
            Color.WHITE, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        
        wheelPaint.shader = ComposeShader(
            sweepGradient,
            radialGradient,
            PorterDuff.Mode.SRC_OVER
        )
        
        canvas.drawCircle(centerX, centerY, wheelRadius, wheelPaint)
    }
    
    private fun drawCursor(canvas: Canvas) {
        val angle = hue * PI / 180f
        val radius = saturation * wheelRadius
        val x = centerX + radius * cos(angle).toFloat()
        val y = centerY + radius * sin(angle).toFloat()
        
        canvas.drawCircle(x, y, 12f, cursorPaint)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                updateColor(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    private fun updateColor(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val distance = sqrt(dx * dx + dy * dy)
        
        // Calculate hue from angle
        var angle = atan2(dy.toDouble(), dx.toDouble()) * 180 / PI
        if (angle < 0) angle += 360
        hue = angle.toFloat()
        
        // Calculate saturation from distance
        saturation = min(distance / wheelRadius, 1f)
        
        invalidate()
        notifyColorChanged()
    }
    
    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        invalidate()
    }
    
    fun setValue(newValue: Float) {
        value = newValue.coerceIn(0f, 1f)
        notifyColorChanged()
    }
    
    fun getColor(): Int {
        return Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }
    
    private fun notifyColorChanged() {
        onColorChanged?.invoke(getColor())
    }
}
