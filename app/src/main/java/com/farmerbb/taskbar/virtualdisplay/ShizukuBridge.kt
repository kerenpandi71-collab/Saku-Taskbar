package com.farmerbb.taskbar.virtualdisplay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import rikka.shizuku.Shizuku

/**
 * Owns the app-side connection to the Shizuku UserService. The user must explicitly grant
 * Shizuku permission in the Shizuku app; the bridge never assumes elevated access is available.
 */
class ShizukuBridge(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var remote: IVirtualDisplayService? = null
    private var binding = false
    private var wantsConnection = false
    private var permissionRequestPending = false
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuUserService::class.java.name)
    ).processNameSuffix("saku_virtual_display")
        .tag("saku-virtual-display-v2")
        .version(5)
        .daemon(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = IVirtualDisplayService.Stub.asInterface(binder)
            binding = false
            val uid = runCatching { Shizuku.getUid() }.getOrNull()
            onStatus(if (uid != null) "Shizuku terhubung (UID $uid)." else "Shizuku terhubung.")
            finishPending(remote != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            binding = false
            onStatus("Koneksi Shizuku terputus. Hubungkan ulang.")
            finishPending(false)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        mainHandler.post { tryConnectIfRequested() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mainHandler.post {
            remote = null
            binding = false
            onStatus("Shizuku berhenti. Nyalakan kembali Shizuku.")
            finishPending(false)
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_PERMISSION_CODE) return@OnRequestPermissionResultListener
        permissionRequestPending = false
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            mainHandler.post { tryConnectIfRequested() }
        } else {
            wantsConnection = false
            onStatus("Izin Shizuku ditolak. Izinkan Saku Simple di aplikasi Shizuku.")
            finishPending(false)
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    fun connect(callback: (Boolean) -> Unit = {}) {
        if (remote != null) {
            callback(true)
            return
        }
        pendingCallbacks += callback
        wantsConnection = true
        tryConnectIfRequested()
    }

    private fun tryConnectIfRequested() {
        if (!wantsConnection || remote != null || binding) return
        if (!Shizuku.pingBinder()) {
            onStatus("Shizuku belum aktif. Jalankan Shizuku dahulu, lalu tekan Hubungkan Shizuku.")
            return
        }

        val permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (error: Throwable) {
            val reason = error.message ?: error.javaClass.simpleName
            onStatus("Belum dapat memeriksa izin Shizuku: $reason")
            false
        }
        if (!permissionGranted) {
            if (!permissionRequestPending) {
                permissionRequestPending = true
                try {
                    Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
                    onStatus("Menunggu izin Shizuku…")
                } catch (error: Throwable) {
                    permissionRequestPending = false
                    val reason = error.message ?: error.javaClass.simpleName
                    onStatus("Tidak dapat meminta izin Shizuku: $reason")
                    finishPending(false)
                }
            }
            return
        }

        try {
            binding = true
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            onStatus("Memulai Shizuku UserService…")
        } catch (error: Throwable) {
            binding = false
            onStatus("Gagal mengikat UserService: ${error.message ?: error.javaClass.simpleName}")
            finishPending(false)
        }
    }

    private fun finishPending(connected: Boolean) {
        val callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
        callbacks.forEach { callback -> runCatching { callback(connected) } }
    }

    fun createVirtualDisplay(
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        callback: (displayId: Int, error: String) -> Unit
    ) {
        connect { connected ->
            if (!connected) {
                callback(-1, "Shizuku belum terhubung atau izinnya belum diberikan.")
                return@connect
            }
            try {
                val service = remote ?: throw IllegalStateException("UserService belum siap.")
                val id = service.createVirtualDisplay(width, height, densityDpi, surface)
                val error = service.getLastError().ifBlank { if (id >= 0) "OK" else "Pembuatan display gagal." }
                callback(id, error)
            } catch (error: Throwable) {
                Log.e(TAG, "createVirtualDisplay call failed", error)
                callback(-1, "${error.cause?.message ?: error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun injectHover(displayId: Int, x: Float, y: Float): Boolean {
        return try {
            remote?.injectHover(displayId, x, y) ?: false
        } catch (error: Throwable) {
            Log.e(TAG, "injectHover failed", error)
            false
        }
    }

    fun injectMouseClick(displayId: Int, x: Float, y: Float): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            val accepted = service.injectMouseClick(displayId, x, y)
            val details = service.getLastError()
            onStatus(if (accepted) details else "Klik mouse ditolak: $details")
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "injectMouseClick failed", error)
            onStatus("Klik mouse gagal: ${error.cause?.message ?: error.message}")
            false
        }
    }

    fun injectTouchEvent(displayId: Int, action: Int, x: Float, y: Float): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            val accepted = service.injectTouchEvent(displayId, action, x, y)
            if (!accepted) onStatus("Sentuhan ditolak: ${service.getLastError()}")
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "injectTouchEvent failed", error)
            onStatus("Sentuhan gagal: ${error.cause?.message ?: error.message}")
            false
        }
    }

    fun injectTouchPoints(displayId: Int, action: Int, pointerIds: IntArray, xs: FloatArray, ys: FloatArray): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            val accepted = service.injectTouchPoints(displayId, action, pointerIds, xs, ys)
            if (!accepted) onStatus("Multi-touch ditolak: ${service.getLastError()}")
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "injectTouchPoints failed", error)
            onStatus("Multi-touch gagal: ${error.cause?.message ?: error.message}")
            false
        }
    }

    fun injectTouchClick(displayId: Int, x: Float, y: Float): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            val accepted = service.injectTouchClick(displayId, x, y)
            if (!accepted) onStatus("Tap layar ditolak: ${service.getLastError()}")
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "injectTouchClick failed", error)
            onStatus("Tap layar gagal: ${error.cause?.message ?: error.message}")
            false
        }
    }

    fun injectMouseButton(displayId: Int, action: Int, x: Float, y: Float): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            val accepted = service.injectMouseButton(displayId, action, x, y)
            if (!accepted) onStatus("Input mouse ditolak: ${service.getLastError()}")
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "injectMouseButton failed", error)
            onStatus("Input mouse gagal: ${error.cause?.message ?: error.message}")
            false
        }
    }

    fun injectKeyEvents(displayId: Int, actions: IntArray, keyCodes: IntArray, metaStates: IntArray): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            val accepted = service.injectKeyEvents(displayId, actions, keyCodes, metaStates)
            if (!accepted) onStatus("Input keyboard ditolak: ${service.getLastError()}")
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "injectKeyEvents failed", error)
            onStatus("Input keyboard gagal: ${error.cause?.message ?: error.message}")
            false
        }
    }

    fun runShellCommand(command: String): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            val accepted = service.runShellCommand(command)
            if (!accepted) onStatus("Perintah input Shizuku gagal: ${service.getLastError()}")
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "runShellCommand failed", error)
            onStatus("Shell Shizuku gagal: ${error.cause?.message ?: error.message}")
            false
        }
    }

    fun getLastError(): String = try {
        remote?.getLastError() ?: "Shizuku UserService belum terhubung."
    } catch (error: Throwable) {
        error.cause?.message ?: error.message ?: "Gagal membaca status Shizuku."
    }

    fun injectMouseScroll(displayId: Int, x: Float, y: Float, horizontal: Float, vertical: Float): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            service.injectMouseScroll(displayId, x, y, horizontal, vertical)
        } catch (error: Throwable) {
            Log.e(TAG, "injectMouseScroll failed", error)
            false
        }
    }

    fun setVirtualDisplaySurface(surface: Surface): Boolean {
        return try {
            val service = remote ?: throw IllegalStateException("Shizuku UserService belum terhubung.")
            service.setVirtualDisplaySurface(surface)
        } catch (error: Throwable) {
            Log.e(TAG, "setVirtualDisplaySurface failed", error)
            false
        }
    }

    fun getDisplayDiagnostics(displayId: Int): String {
        return try {
            remote?.getDisplayDiagnostics(displayId) ?: "Shizuku belum terhubung."
        } catch (error: Throwable) {
            Log.w(TAG, "getDisplayDiagnostics failed", error)
            "Diagnostics gagal: ${error.cause?.message ?: error.message}"
        }
    }

    fun releaseVirtualDisplay() {
        try {
            remote?.releaseVirtualDisplay()
        } catch (error: Throwable) {
            Log.w(TAG, "releaseVirtualDisplay failed", error)
        }
    }

    override fun close() {
        releaseVirtualDisplay()
        wantsConnection = false
        val wasBound = binding || remote != null
        remote = null
        try { Shizuku.removeBinderReceivedListener(binderReceivedListener) } catch (_: Throwable) {}
        try { Shizuku.removeBinderDeadListener(binderDeadListener) } catch (_: Throwable) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Throwable) {}
        if (wasBound) {
            try { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) } catch (_: Throwable) {}
        }
        binding = false
        pendingCallbacks.clear()
    }

    companion object {
        private const val TAG = "SakuShizukuBridge"
        private const val REQUEST_PERMISSION_CODE = 24681
    }
}
