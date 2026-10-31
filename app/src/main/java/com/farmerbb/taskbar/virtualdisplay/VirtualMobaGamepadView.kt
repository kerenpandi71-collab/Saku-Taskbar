package com.farmerbb.taskbar.virtualdisplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Landscape MOBA touch overlay. It forwards real multi-pointer touchscreen events to the
 * owned virtual display; it does not emulate a gamepad device or steal touches outside controls.
 */
internal class VirtualMobaGamepadView(
    context: Context,
    private val controller: () -> VirtualDisplayController?,
    private val targetSize: () -> Pair<Int, Int>
) : View(context) {
    private data class Control(val id: String, var x: Float, var y: Float, val label: String, val radius: Float)
    private val prefs = context.getSharedPreferences("saku_moba_gamepad", Context.MODE_PRIVATE)
    private val controls = mutableListOf(
        Control("stick", .14f, .76f, "MOVE", .105f),
        Control("attack", .87f, .78f, "ATK", .055f),
        Control("skill1", .70f, .84f, "1", .052f),
        Control("skill2", .78f, .91f, "2", .052f),
        Control("skill3", .88f, .92f, "3", .052f),
        Control("ult", .96f, .82f, "ULT", .052f),
        Control("spell", .65f, .69f, "SP", .045f)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val active = linkedMapOf<Int, Control>()
    private var editMode = false
    private var dragId: Int? = null
    private var lastX = 0f
    private var lastY = 0f

    init {
        controls.forEach { c ->
            c.x = prefs.getFloat("${c.id}_x", c.x).coerceIn(.04f, .96f)
            c.y = prefs.getFloat("${c.id}_y", c.y).coerceIn(.08f, .94f)
        }
        isClickable = true
        contentDescription = "Virtual Game Pad MOBA. Tahan tombol untuk mengatur posisi."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height).toFloat()
        controls.forEach { c ->
            val cx = c.x * width
            val cy = c.y * height
            val r = c.radius * scale
            paint.style = Paint.Style.FILL
            paint.color = if (active.values.any { it.id == c.id }) Color.argb(220, 35, 160, 235)
                else Color.argb(if (editMode) 205 else 155, 12, 25, 42)
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = if (editMode) Color.YELLOW else Color.argb(220, 210, 235, 255)
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(if (c.id == "stick") 10 else 12).toFloat()
            canvas.drawText(c.label, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        if (editMode) {
            paint.color = Color.YELLOW
            paint.textSize = dp(12).toFloat()
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("ATUR POSISI • ketuk Selesai di panel", width / 2f, dp(28).toFloat(), paint)
        }
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        if (!enabled) persist()
        invalidate()
    }

    private fun hit(x: Float, y: Float): Control? = controls.asReversed().firstOrNull {
        hypot((x - it.x * width), (y - it.y * height)) <= it.radius * minOf(width, height) * 1.35f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val c = hit(event.getX(index), event.getY(index)) ?: return false
                if (editMode) {
                    dragId = id
                    lastX = event.getX(index); lastY = event.getY(index)
                } else {
                    active[id] = c
                    inject(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (editMode && dragId != null) {
                    val index = (0 until event.pointerCount).firstOrNull { event.getPointerId(it) == dragId } ?: return true
                    val c = hit(lastX, lastY) ?: controls.firstOrNull { hypot((lastX - it.x * width), (lastY - it.y * height)) < it.radius * minOf(width, height) * 1.8f }
                    c?.let {
                        it.x = (it.x + (event.getX(index) - lastX) / width).coerceIn(.04f, .96f)
                        it.y = (it.y + (event.getY(index) - lastY) / height).coerceIn(.08f, .94f)
                    }
                    lastX = event.getX(index); lastY = event.getY(index)
                } else inject(event)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (editMode) {
                    dragId = null
                    persist()
                } else {
                    inject(event)
                    active.remove(event.getPointerId(event.actionIndex))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!editMode) inject(event)
                active.clear()
                dragId = null
            }
        }
        invalidate()
        return true
    }

    private fun inject(event: MotionEvent) {
        val display = controller() ?: return
        val (targetW, targetH) = targetSize()
        if (targetW <= 0 || targetH <= 0) return
        val ids = IntArray(event.pointerCount) { event.getPointerId(it) }
        val xs = FloatArray(event.pointerCount) { event.getX(it) / width * targetW }
        val ys = FloatArray(event.pointerCount) { event.getY(it) / height * targetH }
        // ACTION_POINTER_DOWN/UP carry the original pointer index in the action bits.
        display.injectTouchPoints(event.action, ids, xs, ys)
    }

    private fun persist() {
        prefs.edit().apply {
            controls.forEach { putFloat("${it.id}_x", it.x); putFloat("${it.id}_y", it.y) }
        }.apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
