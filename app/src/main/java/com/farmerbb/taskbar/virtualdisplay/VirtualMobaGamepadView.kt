package com.farmerbb.taskbar.virtualdisplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private val targetSize: () -> Pair<Int, Int>,
    private val targetBounds: () -> android.graphics.RectF?
) : View(context) {
    private data class Control(val id: String, var x: Float, var y: Float, val label: String, val radius: Float, var targetX: Float, var targetY: Float)
    private val prefs = context.getSharedPreferences("saku_moba_gamepad", Context.MODE_PRIVATE)
    private val controls = mutableListOf(
        Control("stick", .14f, .76f, "MOVE", .105f, .14f, .76f),
        Control("attack", .87f, .78f, "ATK", .055f, .87f, .78f),
        Control("skill1", .70f, .84f, "1", .052f, .70f, .84f),
        Control("skill2", .78f, .91f, "2", .052f, .78f, .91f),
        Control("skill3", .88f, .92f, "3", .052f, .88f, .92f),
        Control("ult", .96f, .82f, "ULT", .052f, .96f, .82f),
        Control("spell", .65f, .69f, "SP", .045f, .65f, .69f)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val active = linkedMapOf<Int, Control>()
    private val activePositions = linkedMapOf<Int, Pair<Float, Float>>()
    private var editMode = false
    private var mappingControl: Control? = null
    private var mappingCallback: ((String) -> Unit)? = null
    private var dragId: Int? = null
    private var lastX = 0f
    private var lastY = 0f

    init {
        controls.forEach { c ->
            c.x = prefs.getFloat("${c.id}_x", c.x).coerceIn(.04f, .96f)
            c.y = prefs.getFloat("${c.id}_y", c.y).coerceIn(.08f, .94f)
            c.targetX = prefs.getFloat("${c.id}_target_x", c.targetX).coerceIn(0f, 1f)
            c.targetY = prefs.getFloat("${c.id}_target_y", c.targetY).coerceIn(0f, 1f)
        }
        isClickable = true
        contentDescription = "Virtual Game Pad MOBA. Tahan tombol untuk mengatur posisi."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height).toFloat()
        // Show persistent target markers on the actual game preview, not just the floating controls.
        targetBounds()?.takeIf { it.width() > 0f && it.height() > 0f }?.let { bounds ->
            controls.forEach { c ->
                val tx = bounds.left + c.targetX * bounds.width()
                val ty = bounds.top + c.targetY * bounds.height()
                val selected = mappingControl?.id == c.id
                paint.style = Paint.Style.FILL
                paint.color = if (selected) Color.argb(95, 255, 190, 35) else Color.argb(75, 0, 210, 235)
                canvas.drawCircle(tx, ty, dp(19).toFloat(), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(if (selected) 3 else 2).toFloat()
                paint.color = if (selected) Color.YELLOW else Color.CYAN
                canvas.drawCircle(tx, ty, dp(19).toFloat(), paint)
                canvas.drawLine(tx - dp(25), ty, tx + dp(25), ty, paint)
                canvas.drawLine(tx, ty - dp(25), tx, ty + dp(25), paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = dp(11).toFloat()
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(c.label, tx, ty + dp(4).toFloat(), paint)
            }
        }
        controls.forEach { c ->
            val cx = c.x * width
            val cy = c.y * height
            val r = c.radius * scale
            paint.style = Paint.Style.FILL
            paint.color = if (active.values.any { it.id == c.id }) Color.argb(220, 35, 160, 235)
                else Color.argb(if (editMode) 205 else 155, 12, 25, 42)
            canvas.drawCircle(cx, cy, r, paint)
            if (c.id == "stick") {
                val point = active.entries.firstOrNull { it.value.id == "stick" }?.key?.let { activePositions[it] }
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(220, 225, 240, 255)
                canvas.drawCircle(point?.first ?: cx, point?.second ?: cy, r * .34f, paint)
            }
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
        if (mappingControl != null) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(225, 5, 18, 30)
            canvas.drawRoundRect(dp(12).toFloat(), dp(12).toFloat(), width - dp(12).toFloat(), dp(48).toFloat(), dp(10).toFloat(), dp(10).toFloat(), paint)
            paint.color = Color.WHITE
            paint.textSize = dp(13).toFloat()
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("PETAKAN ${mappingControl?.label} • ketuk tepat di atas skill game", width / 2f, dp(35).toFloat(), paint)
        }
        if (editMode) {
            paint.color = Color.YELLOW
            paint.textSize = dp(12).toFloat()
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("ATUR POSISI • ketuk Selesai di panel", width / 2f, dp(28).toFloat(), paint)
        }
    }

    fun startMapping(controlId: String, onMapped: (String) -> Unit) {
        mappingControl = controls.firstOrNull { it.id == controlId } ?: return
        mappingCallback = onMapped
        editMode = false
        invalidate()
    }

    fun cancelMapping() { mappingControl = null; mappingCallback = null; invalidate() }

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
        if (mappingControl != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val c = mappingControl!!
            val bounds = targetBounds()
            val (tw, th) = targetSize()
            if (bounds != null && bounds.width() > 0f && bounds.height() > 0f &&
                event.x >= bounds.left && event.x <= bounds.right &&
                event.y >= bounds.top && event.y <= bounds.bottom && tw > 0 && th > 0
            ) {
                c.targetX = ((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                c.targetY = ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                persist()
                val mappedId = c.id
                mappingControl = null
                mappingCallback?.invoke(mappedId)
                mappingCallback = null
            }
            invalidate()
            return true
        }
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
                    activePositions[id] = event.getX(index) to event.getY(index)
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
                } else {
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        if (active[id]?.id == "stick") activePositions[id] = event.getX(i) to event.getY(i)
                    }
                    inject(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (editMode) {
                    dragId = null
                    persist()
                } else {
                    inject(event)
                    val id = event.getPointerId(event.actionIndex)
                    active.remove(id)
                    activePositions.remove(id)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!editMode) inject(event)
                active.clear()
                activePositions.clear()
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
        val xs = FloatArray(event.pointerCount)
        val ys = FloatArray(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val control = active[id]
            var tx: Float
            var ty: Float
            if (control?.id == "stick") {
                val dx = event.getX(i) - control.x * width
                val dy = event.getY(i) - control.y * height
                val radius = control.radius * minOf(width, height) * .72f
                val distance = hypot(dx, dy)
                val factor = if (distance > radius && distance > 0f) radius / distance else 1f
                tx = control.targetX * targetW + dx * factor / width * targetW
                ty = control.targetY * targetH + dy * factor / height * targetH
            } else if (control != null) {
                tx = control.targetX * targetW
                ty = control.targetY * targetH
            } else {
                tx = event.getX(i) / width * targetW
                ty = event.getY(i) / height * targetH
            }
            xs[i] = tx.coerceIn(0f, (targetW - 1).toFloat())
            ys[i] = ty.coerceIn(0f, (targetH - 1).toFloat())
        }
        display.injectTouchPoints(event.action, ids, xs, ys)
    }

    private fun persist() {
        prefs.edit().apply {
            controls.forEach {
                putFloat("${it.id}_x", it.x); putFloat("${it.id}_y", it.y)
                putFloat("${it.id}_target_x", it.targetX); putFloat("${it.id}_target_y", it.targetY)
            }
        }.apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
