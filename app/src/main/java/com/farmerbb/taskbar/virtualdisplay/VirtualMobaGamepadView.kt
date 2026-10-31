package com.farmerbb.taskbar.virtualdisplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Landscape touch-mapping overlay. Control positions are normalized to the actual game preview,
 * not the whole phone window, so edit mode can place them directly over in-game touch targets.
 */
internal class VirtualMobaGamepadView(
    context: Context,
    private val controller: () -> VirtualDisplayController?,
    private val targetSize: () -> Pair<Int, Int>,
    private val previewBounds: () -> RectF
) : View(context) {
    private data class Control(
        val id: String,
        var x: Float,
        var y: Float,
        val label: String,
        val radius: Float
    )

    private val prefs = context.getSharedPreferences("saku_moba_gamepad", Context.MODE_PRIVATE)
    private val controls = mutableListOf(
        Control("stick", .16f, .76f, "MOVE", .105f),
        Control("attack", .88f, .76f, "ATK", .055f),
        Control("skill1", .70f, .84f, "1", .052f),
        Control("skill2", .78f, .91f, "2", .052f),
        Control("skill3", .88f, .92f, "3", .052f),
        Control("ult", .96f, .82f, "ULT", .052f),
        Control("spell", .65f, .69f, "SP", .045f)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val active = linkedMapOf<Int, Control>()
    private val stickOffsets = mutableMapOf<Int, Pair<Float, Float>>()
    private var editMode = false
    private var dragId: Int? = null
    private var dragControl: Control? = null
    private var lastX = 0f
    private var lastY = 0f

    init {
        controls.forEach { c ->
            c.x = prefs.getFloat("${c.id}_x", c.x).coerceIn(.04f, .96f)
            c.y = prefs.getFloat("${c.id}_y", c.y).coerceIn(.08f, .94f)
        }
        isClickable = true
        contentDescription = "Virtual Game Pad. Aktifkan Atur Mapping untuk menempatkan tombol di layar game."
    }

    private fun gameRect(): RectF = previewBounds().apply {
        left = left.coerceIn(0f, width.toFloat())
        top = top.coerceIn(0f, height.toFloat())
        right = right.coerceIn(left, width.toFloat())
        bottom = bottom.coerceIn(top, height.toFloat())
    }

    private fun center(c: Control, rect: RectF) =
        (rect.left + c.x * rect.width()) to (rect.top + c.y * rect.height())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = gameRect()
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val scale = min(rect.width(), rect.height())
        controls.forEach { c ->
            val (cx, cy) = center(c, rect)
            val radius = c.radius * scale
            paint.style = Paint.Style.FILL
            paint.color = if (active.values.any { it.id == c.id }) Color.argb(220, 35, 160, 235)
                else Color.argb(if (editMode) 205 else 112, 12, 25, 42)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = if (editMode) Color.YELLOW else Color.argb(220, 210, 235, 255)
            canvas.drawCircle(cx, cy, radius, paint)
            if (c.id == "stick") {
                val offset = stickOffsets.entries.firstOrNull { active[it.key]?.id == "stick" }?.value
                val knobX = cx + (offset?.first ?: 0f)
                val knobY = cy + (offset?.second ?: 0f)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(220, 220, 240, 255)
                canvas.drawCircle(knobX, knobY, radius * .30f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1).toFloat()
                paint.color = Color.argb(180, 235, 245, 255)
                canvas.drawCircle(knobX, knobY, radius * .30f, paint)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = dp(12).toFloat()
                canvas.drawText(c.label, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
            }
        }
        if (editMode) {
            paint.color = Color.YELLOW
            paint.textSize = dp(12).toFloat()
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("ATUR MAPPING • seret tombol ke target game", rect.centerX(), rect.top + dp(20), paint)
        }
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        if (!enabled) {
            persist()
            cancelActivePointers()
        }
        invalidate()
    }

    fun cancelActivePointers() {
        if (active.isNotEmpty()) {
            val ids = active.keys.toIntArray()
            val xs = FloatArray(ids.size)
            val ys = FloatArray(ids.size)
            val (w, h) = targetSize()
            val c = controller()
            if (c != null && w > 0 && h > 0) c.injectTouchPoints(MotionEvent.ACTION_CANCEL, ids, xs, ys)
        }
        active.clear()
        stickOffsets.clear()
        dragId = null
        dragControl = null
        invalidate()
    }

    private fun hit(x: Float, y: Float, rect: RectF): Control? = controls.asReversed().firstOrNull {
        val (cx, cy) = center(it, rect)
        hypot(x - cx, y - cy) <= it.radius * min(rect.width(), rect.height()) * 1.45f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = gameRect()
        if (rect.width() <= 0f || rect.height() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val c = hit(event.getX(index), event.getY(index), rect) ?: return false
                if (editMode) {
                    dragId = id
                    dragControl = c
                    lastX = event.getX(index)
                    lastY = event.getY(index)
                } else {
                    active[id] = c
                    if (c.id == "stick") stickOffsets[id] = 0f to 0f
                    inject(event, rect)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (editMode && dragId != null) {
                    val index = (0 until event.pointerCount).firstOrNull { event.getPointerId(it) == dragId } ?: return true
                    dragControl?.let { c ->
                        c.x = (c.x + (event.getX(index) - lastX) / rect.width()).coerceIn(.025f, .975f)
                        c.y = (c.y + (event.getY(index) - lastY) / rect.height()).coerceIn(.04f, .96f)
                    }
                    lastX = event.getX(index)
                    lastY = event.getY(index)
                } else inject(event, rect)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (editMode) {
                    dragId = null
                    dragControl = null
                    persist()
                } else {
                    inject(event, rect)
                    val id = event.getPointerId(event.actionIndex)
                    active.remove(id)
                    stickOffsets.remove(id)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!editMode) inject(event, rect)
                active.clear()
                stickOffsets.clear()
                dragId = null
                dragControl = null
            }
        }
        invalidate()
        return true
    }

    private fun inject(event: MotionEvent, rect: RectF) {
        val display = controller() ?: return
        val (targetW, targetH) = targetSize()
        if (targetW <= 0 || targetH <= 0) return
        val ids = IntArray(event.pointerCount) { event.getPointerId(it) }
        val xs = FloatArray(event.pointerCount)
        val ys = FloatArray(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            val id = ids[i]
            val control = active[id]
            if (control == null) {
                xs[i] = ((event.getX(i) - rect.left) / rect.width() * targetW).coerceIn(0f, (targetW - 1).toFloat())
                ys[i] = ((event.getY(i) - rect.top) / rect.height() * targetH).coerceIn(0f, (targetH - 1).toFloat())
            } else {
                val (cx, cy) = center(control, rect)
                if (control.id == "stick") {
                    val radius = control.radius * min(rect.width(), rect.height()) * .72f
                    val dx = event.getX(i) - cx
                    val dy = event.getY(i) - cy
                    val distance = hypot(dx, dy)
                    val factor = if (distance > radius && distance > 0f) radius / distance else 1f
                    val ox = dx * factor
                    val oy = dy * factor
                    stickOffsets[id] = ox to oy
                    xs[i] = ((cx + ox - rect.left) / rect.width() * targetW).coerceIn(0f, (targetW - 1).toFloat())
                    ys[i] = ((cy + oy - rect.top) / rect.height() * targetH).coerceIn(0f, (targetH - 1).toFloat())
                } else {
                    xs[i] = ((cx - rect.left) / rect.width() * targetW).coerceIn(0f, (targetW - 1).toFloat())
                    ys[i] = ((cy - rect.top) / rect.height() * targetH).coerceIn(0f, (targetH - 1).toFloat())
                }
            }
        }
        // Keep Android's pointer index bits intact for simultaneous controls.
        display.injectTouchPoints(event.action, ids, xs, ys)
    }

    private fun persist() {
        prefs.edit().apply {
            controls.forEach { putFloat("${it.id}_x", it.x); putFloat("${it.id}_y", it.y) }
        }.apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
