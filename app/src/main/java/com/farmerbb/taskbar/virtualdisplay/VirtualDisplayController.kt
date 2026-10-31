package com.farmerbb.taskbar.virtualdisplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the preview SurfaceView as the virtual-display output, without an ImageReader/Bitmap copy. */
class VirtualDisplayController(
    context: Context,
    private val shizukuBridge: ShizukuBridge,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val appContext = context
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    @Volatile
    var displayId: Int = Display.INVALID_DISPLAY
        private set

    fun start(width: Int, height: Int, densityDpi: Int, outputSurface: Surface) {
        releaseDisplay()
        closed.set(false)
        if (!outputSurface.isValid) {
            reportStatus("Surface preview belum siap. Coba buat layar virtual lagi.")
            return
        }
        val safeWidth = width.coerceIn(320, 2560)
        val safeHeight = height.coerceIn(320, 2560)
        val safeDpi = densityDpi.coerceIn(120, 640)
        reportStatus("Membuat display langsung ke SurfaceView • ${safeWidth}×${safeHeight} • ${safeDpi}dpi…")
        shizukuBridge.createVirtualDisplay(safeWidth, safeHeight, safeDpi, outputSurface) { createdId, error ->
            if (closed.get()) {
                if (createdId >= 0) shizukuBridge.releaseVirtualDisplay()
                return@createVirtualDisplay
            }
            if (createdId < 0) {
                displayId = Display.INVALID_DISPLAY
                reportStatus("Pembuatan virtual display gagal: $error")
                return@createVirtualDisplay
            }
            displayId = createdId
            mainHandler.post {
                if (closed.get()) {
                    shizukuBridge.releaseVirtualDisplay()
                    return@post
                }
                val display = displayManager.getDisplay(createdId)
                val mode = display?.mode
                reportStatus(
                    "Virtual display aktif • ID $createdId • ${safeWidth}×${safeHeight} • " +
                        "state=${display?.state ?: -1} • surfaceValid=${outputSurface.isValid} • " +
                        "output langsung SurfaceView • $error"
                )
                Log.i(TAG, "Display $createdId output direct to preview SurfaceView mode=$mode")
            }
        }
    }

    fun updateSurface(surface: Surface): Boolean {
        if (displayId < 0 || !surface.isValid) return false
        return shizukuBridge.setVirtualDisplaySurface(surface)
    }

    fun injectMouseMove(x: Float, y: Float): Boolean {
        val id = displayId
        if (id < 0) return false
        return shizukuBridge.injectHover(id, x, y)
    }

    fun injectMouseClick(x: Float, y: Float): Boolean {
        val id = displayId
        if (id < 0) return false
        return shizukuBridge.injectMouseClick(id, x, y)
    }

    fun injectTouchEvent(action: Int, x: Float, y: Float): Boolean {
        val id = displayId
        if (id < 0) return false
        return shizukuBridge.injectTouchEvent(id, action, x, y)
    }

    fun injectTouchPoints(action: Int, pointerIds: IntArray, xs: FloatArray, ys: FloatArray): Boolean {
        val id = displayId
        if (id < 0) return false
        return shizukuBridge.injectTouchPoints(id, action, pointerIds, xs, ys)
    }

    fun injectTouchClick(x: Float, y: Float): Boolean {
        val id = displayId
        if (id < 0) return false
        return shizukuBridge.injectTouchClick(id, x, y)
    }

    fun injectMouseButton(action: Int, x: Float, y: Float): Boolean {
        val id = displayId
        if (id < 0) return false
        return shizukuBridge.injectMouseButton(id, action, x, y)
    }

    fun injectKeyEvents(actions: IntArray, keyCodes: IntArray, metaStates: IntArray): Boolean {
        val id = displayId
        if (id < 0) return false
        return shizukuBridge.injectKeyEvents(id, actions, keyCodes, metaStates)
    }

    fun injectMouseScroll(x: Float, y: Float, horizontal: Float, vertical: Float): Boolean {
        val id = displayId
        if (id < 0) return false
        return shizukuBridge.injectMouseScroll(id, x, y, horizontal, vertical)
    }

    private fun reportStatus(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) onStatus(message)
        else mainHandler.post { onStatus(message) }
    }

    override fun close() {
        closed.set(true)
        releaseDisplay()
    }

    private fun releaseDisplay() {
        if (displayId >= 0) shizukuBridge.releaseVirtualDisplay()
        displayId = Display.INVALID_DISPLAY
    }

    companion object {
        private const val TAG = "SakuVirtualDisplay"
    }
}
