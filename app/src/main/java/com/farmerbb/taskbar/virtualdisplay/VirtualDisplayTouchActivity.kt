package com.farmerbb.taskbar.virtualdisplay

import android.app.AlertDialog

import android.graphics.Canvas
import android.graphics.Paint
import java.util.concurrent.Executors
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.KeyCharacterMap
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.SeekBar
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity

/** Main touchpad UI. Preview fills its frame; touchpad maps to the fixed 720 x 1280 virtual display. */
class VirtualDisplayTouchActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var runtimeLog: TextView
    private lateinit var runtimeLogScroll: ScrollView
    private val runtimeLogBuffer = StringBuilder()
    private var targetWidth = 960
    private var targetHeight = 820
    private var targetDpi = 160
    private var cursorSensitivity = 0.45f
    private var touchpadHeightPercent = 36
    private var touchpadWidthPercent = 27
    private var touchpadLandscapeHeightPercent = 94
    private var touchpadPortraitHeightPercent = 30
    private var touchpadPortraitWidthPercent = 100
    private var touchpadPortraitHasCustomPosition = false
    private var touchpadLandscapeHasCustomPosition = false
    private var touchpadPortraitXPercent = 50
    private var touchpadPortraitYPercent = 70
    private var touchpadLandscapeXPercent = 0
    private var touchpadLandscapeYPercent = 50
    private var touchpadDragStartX = 0f
    private var touchpadDragStartY = 0f
    private var touchpadDragOriginX = 0
    private var touchpadDragOriginY = 0
    private var touchpadDragStarted = false
    private lateinit var touchpadWidthValue: TextView
    private lateinit var resolutionWidthInput: EditText
    private lateinit var resolutionHeightInput: EditText
    private lateinit var resolutionDpiInput: EditText
    private lateinit var touchpadSizeValue: TextView
    private lateinit var sensitivityValue: TextView
    private lateinit var touchpadSizeSeekBar: SeekBar
    private lateinit var shizukuBridge: ShizukuBridge
    private var virtualDisplayController: VirtualDisplayController? = null
    private var preview: SurfaceView? = null
    private var cursorOverlay: View? = null
    private val mouseInputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SakuDirectMouseInput").apply { isDaemon = true }
    }
    // Keep keyboard batches independent from high-frequency trackpad/mouse events.
    private val keyboardInputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SakuCyberdeckKeyboard").apply { isDaemon = true }
    }
    private lateinit var runtimeLogToggle: Button
    private lateinit var bottomPanel: LinearLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var imeBridgeEditText: EditText
    private var keyboardPanelVisible = false
    @Volatile private var systemKeyboardRequested = false
    private var keyboardWidthPercent = 100
    private var keyboardHeightDp = 240
    private var keyboardHasCustomPosition = false
    private var keyboardLandscapeHasCustomPosition = false
    private var keyboardLandscapeXPercent = 100
    private var keyboardLandscapeYPercent = 50
    private var keyboardPositionX = 0
    private var keyboardPositionY = 0
    private var keyboardDragStartX = 0f
    private var keyboardDragStartY = 0f
    private var keyboardDragOriginX = 0
    private var keyboardDragOriginY = 0
    private var cyberShift = false
    private var cyberCtrl = false
    private var cyberAlt = false
    private var cyberSymbols = false
    private val keyboardRepeatHandler = Handler(Looper.getMainLooper())
    private lateinit var panelToggleButton: Button
    private lateinit var directTouchButton: Button
    private lateinit var previewSizeSeekBar: SeekBar
    private lateinit var panelPositionSeekBar: SeekBar
    private lateinit var previewSizeValue: TextView
    private lateinit var panelPositionValue: TextView
    private var previewSizePercent = 100
    private var panelPositionPercent = 0
    private var directTouchDown = false
    private var directTouchEnabled = true
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var longPressTask: Runnable? = null
    private var touchpadFingerDown = false
    private var dragActive = false
    private lateinit var runtimeLogPanel: LinearLayout
    private var suppressKeyboardWatcher = false
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val cursorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
    }
    private var cursorPulseRadius = 13f
    private var cursorX = targetWidth / 2f
    private var cursorY = targetHeight / 2f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var pointerMoved = false
    private var hadMultiTouch = false
    private var scrollGestureActive = false
    private var lastScrollCentroidX = 0f
    private var lastScrollCentroidY = 0f

    private val mouseMoveLock = Any()
    private var pendingMouseX = Float.NaN
    private var pendingMouseY = Float.NaN
    private var mouseMoveDrainScheduled = false

    private val scrollLock = Any()
    private var pendingScrollHorizontal = 0f
    private var pendingScrollVertical = 0f
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var scrollDrainScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyboardPrefs = getSharedPreferences("saku_desktop_keyboard", Context.MODE_PRIVATE)
        keyboardWidthPercent = keyboardPrefs.getInt("width_percent", 100).coerceIn(55, 100)
        keyboardHeightDp = keyboardPrefs.getInt("height_dp", 240).coerceIn(170, 360)
        keyboardHasCustomPosition = keyboardPrefs.getBoolean("custom_position", false)
        keyboardLandscapeHasCustomPosition = keyboardPrefs.getBoolean("landscape_custom_position", false)
        keyboardLandscapeXPercent = keyboardPrefs.getInt("landscape_x_percent", 100).coerceIn(0, 100)
        keyboardLandscapeYPercent = keyboardPrefs.getInt("landscape_y_percent", 50).coerceIn(0, 100)
        keyboardPositionX = keyboardPrefs.getInt("position_x", 0)
        keyboardPositionY = keyboardPrefs.getInt("position_y", 0)
        val touchpadPrefs = getSharedPreferences("saku_desktop_touchpad", Context.MODE_PRIVATE)
        touchpadWidthPercent = touchpadPrefs.getInt("width_percent", 27).coerceIn(18, 45)
        touchpadLandscapeHeightPercent = touchpadPrefs.getInt("height_percent", 94).coerceIn(40, 100)
        touchpadPortraitHeightPercent = touchpadPrefs.getInt("portrait_height_percent", 30).coerceIn(15, 65)
        touchpadPortraitWidthPercent = touchpadPrefs.getInt("portrait_width_percent", 100).coerceIn(60, 100)
        // Migrate once from old shared position keys. They could retain a stale
        // right-side position and override the new left-side landscape default.
        val landscapePositionV2 = touchpadPrefs.getBoolean("landscape_position_v2", false)
        touchpadLandscapeHasCustomPosition = landscapePositionV2 &&
            touchpadPrefs.getBoolean("landscape_custom_position", false)
        touchpadLandscapeXPercent = if (landscapePositionV2) {
            touchpadPrefs.getInt("landscape_x_percent", 0).coerceIn(0, 100)
        } else 0
        touchpadLandscapeYPercent = if (landscapePositionV2) {
            touchpadPrefs.getInt("landscape_y_percent", 50).coerceIn(0, 100)
        } else 50
        if (!landscapePositionV2) {
            touchpadPrefs.edit()
                .putBoolean("landscape_position_v2", true)
                .putBoolean("landscape_custom_position", false)
                .putInt("landscape_x_percent", 0)
                .putInt("landscape_y_percent", 50)
                .apply()
        }
        touchpadPortraitHasCustomPosition = touchpadPrefs.getBoolean("portrait_custom_position", false)
        touchpadPortraitXPercent = touchpadPrefs.getInt("portrait_x_percent", 50).coerceIn(0, 100)
        touchpadPortraitYPercent = touchpadPrefs.getInt("portrait_y_percent", 70).coerceIn(0, 100)
        // Keep the IME host on the phone display; never auto-open Gboard on Activity launch.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
        applyImmersiveMode()

        // SurfaceView tetap memenuhi root; toolbar, kursor, touchpad, dan log adalah overlay.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(7, 24, 45)) }
        preview = SurfaceView(this).apply {
            contentDescription = "Desktop virtual display 960 x 820"
            setOnTouchListener { view, event ->
                if (!directTouchEnabled) return@setOnTouchListener false
                handleDirectTouch(event, view.width, view.height)
            }
            holder.setFixedSize(targetWidth, targetHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    virtualDisplayController?.updateSurface(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    virtualDisplayController?.updateSurface(holder.surface)
                    cursorOverlay?.invalidate()
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, dp(240), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        cursorOverlay = object : View(this) {
            override fun onDraw(canvas: Canvas) { super.onDraw(canvas); drawDextopCursor(canvas) }
        }.apply {
            isClickable = false
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Kursor putih Saku Desktop"
        }
        root.addView(cursorOverlay, FrameLayout.LayoutParams(-1, -1))

        shizukuBridge = ShizukuBridge(this) { message ->
            runOnUiThread {
                if (::status.isInitialized) status.text = message
                appendRuntimeLog(message)
            }
        }

        fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 9f
            isAllCaps = false
            minHeight = dp(32); minimumHeight = dp(32)
            minWidth = 0; minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = roundedPanel(220, 12)
        }
        topBar.addView(TextView(this).apply {
            text = "SAKU DESKTOP  •  960 × 820"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(20)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(actionButton("Hubungkan") { shizukuBridge.connect() }, LinearLayout.LayoutParams(0, dp(34), 1.2f))
        actions.addView(actionButton("Root") {
            shizukuBridge.connect { connected ->
                if (!connected) {
                    runOnUiThread { status.text = "Hubungkan Shizuku sebelum meminta root." }
                } else {
                    keyboardInputExecutor.execute {
                        val result = shizukuBridge.requestRootAccess()
                        runOnUiThread {
                            status.text = result
                            appendRuntimeLog(result)
                        }
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, dp(34), 0.65f))
        actions.addView(actionButton("Mulai") { startOwnedVirtualDisplay() }, LinearLayout.LayoutParams(0, dp(34), 0.75f))
        actions.addView(actionButton("Cek") {
            val id = virtualDisplayController?.displayId ?: -1
            if (id < 0) status.text = "Mulai virtual display dahulu." else Thread({
                val detail = shizukuBridge.getDisplayDiagnostics(id)
                runOnUiThread {
                    appendRuntimeLog("Diagnostik display $id\n$detail")
                    status.text = "Diagnostik selesai • detail di Log"
                }
            }, "SakuDisplayDiagnostics").start()
        }, LinearLayout.LayoutParams(0, dp(34), 0.5f))
        actions.addView(actionButton("Tutup") {
            virtualDisplayController?.close()
            virtualDisplayController = null
            clearPreview()
            status.text = "Virtual display ditutup."
        }, LinearLayout.LayoutParams(0, dp(34), 0.65f))
        runtimeLogToggle = actionButton("Log") { toggleRuntimeLog() }
        actions.addView(runtimeLogToggle, LinearLayout.LayoutParams(0, dp(34), 0.48f))
        panelToggleButton = actionButton("Panel") { toggleInputPanel() }
        actions.addView(panelToggleButton, LinearLayout.LayoutParams(0, dp(34), 0.58f))
        directTouchButton = actionButton("Touch: ON") { toggleDirectTouch() }
        actions.addView(directTouchButton, LinearLayout.LayoutParams(0, dp(34), 0.78f))
        topBar.addView(actions)
        val previewSizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        previewSizeValue = TextView(this).apply { text = "Desktop 100%"; textSize = 9f; setTextColor(Color.WHITE) }
        previewSizeRow.addView(previewSizeValue, LinearLayout.LayoutParams(dp(84), dp(26)))
        previewSizeSeekBar = SeekBar(this).apply {
            max = 50
            progress = 50
            contentDescription = "Ukuran preview desktop 50 sampai 100 persen"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    previewSizePercent = 50 + progress
                    previewSizeValue.text = "Desktop ${previewSizePercent}%"
                    updateDesktopLayout(root.width, root.height)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        previewSizeRow.addView(previewSizeSeekBar, LinearLayout.LayoutParams(0, dp(28), 1f))
        topBar.addView(previewSizeRow)
        val panelPositionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        panelPositionValue = TextView(this).apply { text = "Panel 0%"; textSize = 9f; setTextColor(Color.WHITE) }
        panelPositionRow.addView(panelPositionValue, LinearLayout.LayoutParams(dp(84), dp(26)))
        panelPositionSeekBar = SeekBar(this).apply {
            max = 100
            progress = 0
            contentDescription = "Posisi panel touchpad"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    panelPositionPercent = progress
                    panelPositionValue.text = "Panel ${panelPositionPercent}%"
                    updateDesktopLayout(root.width, root.height)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        panelPositionRow.addView(panelPositionSeekBar, LinearLayout.LayoutParams(0, dp(28), 1f))
        topBar.addView(panelPositionRow)
        val resolutionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun resolutionInput(initial: String, hintText: String, widthDp: Int) = EditText(this).apply {
            setText(initial); hint = hintText; textSize = 10f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(4), 0, dp(4), 0)
            background = roundedPanel(180, 6)
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(32)).apply { rightMargin = dp(3) }
        }
        resolutionWidthInput = resolutionInput("960", "Lebar", 62)
        resolutionHeightInput = resolutionInput("820", "Tinggi", 58)
        resolutionDpiInput = resolutionInput("160", "DPI", 48)
        resolutionRow.addView(resolutionWidthInput)
        resolutionRow.addView(resolutionHeightInput)
        resolutionRow.addView(resolutionDpiInput)
        resolutionRow.addView(actionButton("Terapkan") { applyDisplaySettings() },
            LinearLayout.LayoutParams(0, dp(32), 1f))
        topBar.addView(resolutionRow)
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        touchpadSizeValue = TextView(this).apply {
            val portraitNow = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            text = if (portraitNow) "Tinggi TP P ${touchpadPortraitHeightPercent}%" else "Tinggi TP L ${touchpadLandscapeHeightPercent}%"
            textSize = 9f; setTextColor(Color.WHITE)
        }
        controlRow.addView(touchpadSizeValue, LinearLayout.LayoutParams(dp(92), dp(25)))
        touchpadSizeSeekBar = SeekBar(this).apply {
            max = 60; progress = if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) touchpadPortraitHeightPercent - 15 else touchpadLandscapeHeightPercent - 40
            contentDescription = "Tinggi touchpad portrait atau landscape"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val portraitNow = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    if (portraitNow) {
                        touchpadPortraitHeightPercent = 15 + progress
                        touchpadSizeValue.text = "Tinggi TP P ${touchpadPortraitHeightPercent}%"
                    } else {
                        touchpadLandscapeHeightPercent = 40 + progress
                        touchpadSizeValue.text = "Tinggi TP L ${touchpadLandscapeHeightPercent}%"
                    }
                    persistTouchpadSettings()
                    updateDesktopLayout(root.width, root.height)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controlRow.addView(touchpadSizeSeekBar, LinearLayout.LayoutParams(0, dp(25), 1f))
        sensitivityValue = TextView(this).apply { text = "Kursor 45%"; textSize = 9f; setTextColor(Color.WHITE) }
        controlRow.addView(sensitivityValue, LinearLayout.LayoutParams(dp(76), dp(25)))
        val sensitivitySeekBar = SeekBar(this).apply {
            max = 80; progress = 25
            contentDescription = "Sensitivitas kursor"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    cursorSensitivity = (20 + progress) / 100f
                    sensitivityValue.text = "Kursor ${(cursorSensitivity * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controlRow.addView(sensitivitySeekBar, LinearLayout.LayoutParams(0, dp(25), 1f))
        topBar.addView(controlRow)
        val touchpadWidthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        touchpadWidthValue = TextView(this).apply {
            val portraitNow = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            text = if (portraitNow) "Lebar TP P ${touchpadPortraitWidthPercent}%" else "Lebar TP L ${touchpadWidthPercent}%"
            textSize = 9f; setTextColor(Color.WHITE)
        }
        touchpadWidthRow.addView(touchpadWidthValue, LinearLayout.LayoutParams(dp(92), dp(25)))
        val touchpadWidthSeekBar = SeekBar(this).apply {
            max = 40; progress = if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) touchpadPortraitWidthPercent - 60 else touchpadWidthPercent - 18
            contentDescription = "Lebar touchpad portrait atau landscape"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val portraitNow = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    if (portraitNow) {
                        touchpadPortraitWidthPercent = 60 + progress
                        touchpadWidthValue.text = "Lebar TP P ${touchpadPortraitWidthPercent}%"
                    } else {
                        touchpadWidthPercent = (18 + progress).coerceAtMost(45)
                        touchpadWidthValue.text = "Lebar TP L ${touchpadWidthPercent}%"
                    }
                    persistTouchpadSettings()
                    updateDesktopLayout(root.width, root.height)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        touchpadWidthRow.addView(touchpadWidthSeekBar, LinearLayout.LayoutParams(0, dp(25), 1f))
        topBar.addView(touchpadWidthRow)
        status = TextView(this).apply {
            text = "Hubungkan Shizuku, lalu tekan Mulai."
            textSize = 9f
            maxLines = 2
            setTextColor(Color.rgb(105, 221, 245))
            setPadding(dp(2), dp(2), dp(2), 0)
        }
        topBar.addView(status, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        topBar.addView(actionButton("Sembunyikan panel pengaturan") {
            topBar.visibility = View.GONE
        }, LinearLayout.LayoutParams(-1, dp(30)))
        // Semua pengaturan berada di panel yang dibuka dari bola asisten.
        topBar.visibility = View.GONE
        root.addView(topBar, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(7); rightMargin = dp(7); topMargin = dp(7)
        })
        val assistantBall = TextView(this).apply {
            text = "S"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(225, 24, 104, 164))
                setStroke(dp(2), Color.argb(220, 180, 230, 255))
            }
            elevation = dp(12).toFloat()
            contentDescription = "Bola asisten Saku Desktop. Ketuk untuk pengaturan, seret untuk memindahkan."
            isClickable = true
        }
        val ballParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(12)
        }
        var ballDownX = 0f
        var ballDownY = 0f
        var ballStartX = 0f
        var ballStartY = 0f
        var ballDragged = false
        assistantBall.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ballDownX = event.rawX; ballDownY = event.rawY
                    ballStartX = if (ballParams.gravity and Gravity.RIGHT == Gravity.RIGHT) (root.width - view.width - ballParams.rightMargin).coerceAtLeast(0).toFloat() else ballParams.leftMargin.toFloat()
                    ballStartY = if (ballParams.gravity and Gravity.CENTER_VERTICAL == Gravity.CENTER_VERTICAL) ((root.height - view.height) / 2f) else ballParams.topMargin.toFloat()
                    ballDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - ballDownX
                    val dy = event.rawY - ballDownY
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) ballDragged = true
                    if (ballDragged) {
                        ballParams.gravity = Gravity.TOP or Gravity.START
                        ballParams.leftMargin = (ballStartX + dx).toInt().coerceIn(0, (root.width - view.width).coerceAtLeast(0))
                        ballParams.topMargin = (ballStartY + dy).toInt().coerceIn(0, (root.height - view.height).coerceAtLeast(0))
                        root.updateViewLayout(view, ballParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!ballDragged) {
                        val menu = android.widget.PopupMenu(this, view)
                        menu.menu.add(0, 1, 0, "Pengaturan Saku Desktop")
                        menu.menu.add(0, 2, 1, if (systemKeyboardRequested) "Sembunyikan Keyboard Android" else "Keyboard Android")
                        menu.menu.add(0, 3, 2, "Keyboard Cyberdeck")
                        menu.menu.add(0, 4, 3, if (keyboardPanelVisible) "Sembunyikan keyboard Cyberdeck" else "Tampilkan keyboard Cyberdeck")
                        menu.menu.add(0, 5, 4, if (gamepadOverlay?.visibility == View.VISIBLE) "Sembunyikan Virtual Game Pad" else "Tampilkan Virtual Game Pad")
                        menu.menu.add(0, 6, 5, "Atur posisi tombol Game Pad")
                        menu.setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                1 -> topBar.visibility = View.VISIBLE
                                2 -> toggleSystemKeyboardBridge()
                                3 -> showVirtualKeyboard()
                                4 -> {
                                    keyboardPanelVisible = !keyboardPanelVisible
                                    keyboardPanel.visibility = if (keyboardPanelVisible) View.VISIBLE else View.GONE
                                    updateDesktopLayout(root.width, root.height)
                                }
                                5 -> {
                                    if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                                        status.text = "Virtual Game Pad hanya tersedia dalam mode landscape."
                                    } else if ((virtualDisplayController?.displayId ?: -1) < 0) {
                                        status.text = "Buat virtual display terlebih dahulu."
                                    } else {
                                        gamepadOverlay?.visibility = if (gamepadOverlay?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                                        status.text = if (gamepadOverlay?.visibility == View.VISIBLE) "Virtual Game Pad aktif." else "Virtual Game Pad disembunyikan."
                                    }
                                }
                                6 -> {
                                    if (gamepadOverlay == null) {
                                        status.text = "Tampilkan Virtual Game Pad dahulu."
                                    } else {
                                        gamepadOverlay?.visibility = View.VISIBLE
                                        gamepadOverlay?.setEditMode(true)
                                        AlertDialog.Builder(this)
                                            .setTitle("Atur posisi Game Pad")
                                            .setMessage("Seret analog dan tombol ke posisi yang kamu inginkan. Posisi tersimpan otomatis.")
                                            .setPositiveButton("Selesai") { _, _ -> gamepadOverlay?.setEditMode(false) }
                                            .setNegativeButton("Batal") { _, _ -> gamepadOverlay?.setEditMode(false) }
                                            .show()
                                    }
                                }
                            }
                            true
                        }
                        menu.show()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        root.addView(assistantBall, ballParams)
        var gamepadOverlay: VirtualMobaGamepadView? = null
        gamepadOverlay = VirtualMobaGamepadView(
            this,
            { virtualDisplayController },
            { targetWidth to targetHeight }
        ).apply {
            visibility = View.GONE
            elevation = dp(24).toFloat()
        }
        root.addView(gamepadOverlay, FrameLayout.LayoutParams(-1, -1))

        // Keyboard sederhana bergaya Gboard: tombol besar, baris standar, tanpa tombol fungsi yang memenuhi ruang.
        // Area preview, trackpad, dan jalur Direct Touch sengaja tidak diubah.
        keyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = roundedPanel(242, 16)
            // Keep the keyboard above the bottom trackpad panel in both drawing
            // and touch-dispatch order; otherwise the trackpad steals key taps.
            elevation = dp(18).toFloat()
            translationZ = dp(2).toFloat()
            contentDescription = "Keyboard virtual sederhana"
        }
        val keyboardTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val keyboardDragHandle = TextView(this).apply {
            text = "Keyboard  ⋮⋮"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(225, 231, 239))
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val params = keyboardPanel.layoutParams as? FrameLayout.LayoutParams
                        if (params != null) {
                            val landscapeNow = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            if (landscapeNow) {
                                keyboardLandscapeHasCustomPosition = true
                                val maxX = (root.width - keyboardPanel.width).coerceAtLeast(1)
                                val maxY = (root.height - keyboardPanel.height).coerceAtLeast(1)
                                keyboardLandscapeXPercent = (keyboardPanel.left.toFloat() / maxX * 100).toInt().coerceIn(0, 100)
                                keyboardLandscapeYPercent = (keyboardPanel.top.toFloat() / maxY * 100).toInt().coerceIn(0, 100)
                            } else {
                                keyboardHasCustomPosition = true
                            }
                            keyboardPositionX = keyboardPanel.x.toInt().coerceIn(0, (root.width - keyboardPanel.width).coerceAtLeast(0))
                            keyboardPositionY = keyboardPanel.y.toInt().coerceIn(0, (root.height - keyboardPanel.height).coerceAtLeast(0))
                            params.gravity = Gravity.TOP or Gravity.START
                            params.leftMargin = keyboardPositionX
                            params.topMargin = keyboardPositionY
                            params.rightMargin = 0
                            params.bottomMargin = 0
                            keyboardPanel.layoutParams = params
                            keyboardDragStartX = event.rawX
                            keyboardDragStartY = event.rawY
                            keyboardDragOriginX = keyboardPositionX
                            keyboardDragOriginY = keyboardPositionY
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val params = keyboardPanel.layoutParams as? FrameLayout.LayoutParams
                        if (params != null) {
                            keyboardPositionX = (keyboardDragOriginX + event.rawX - keyboardDragStartX).toInt()
                                .coerceIn(0, (root.width - keyboardPanel.width).coerceAtLeast(0))
                            keyboardPositionY = (keyboardDragOriginY + event.rawY - keyboardDragStartY).toInt()
                                .coerceIn(0, (root.height - keyboardPanel.height).coerceAtLeast(0))
                            params.gravity = Gravity.TOP or Gravity.START
                            params.leftMargin = keyboardPositionX
                            params.topMargin = keyboardPositionY
                            params.rightMargin = 0
                            params.bottomMargin = 0
                            root.updateViewLayout(keyboardPanel, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val landscapeNow = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        if (landscapeNow) {
                            val maxX = (root.width - keyboardPanel.width).coerceAtLeast(1)
                            val maxY = (root.height - keyboardPanel.height).coerceAtLeast(1)
                            keyboardLandscapeXPercent = (keyboardPositionX.toFloat() / maxX * 100).toInt().coerceIn(0, 100)
                            keyboardLandscapeYPercent = (keyboardPositionY.toFloat() / maxY * 100).toInt().coerceIn(0, 100)
                        }
                        getSharedPreferences("saku_desktop_keyboard", Context.MODE_PRIVATE).edit()
                            .putBoolean("custom_position", if (landscapeNow) keyboardHasCustomPosition else true)
                            .putInt("position_x", keyboardPositionX)
                            .putInt("position_y", keyboardPositionY)
                            .putBoolean("landscape_custom_position", if (landscapeNow) true else keyboardLandscapeHasCustomPosition)
                            .putInt("landscape_x_percent", keyboardLandscapeXPercent)
                            .putInt("landscape_y_percent", keyboardLandscapeYPercent)
                            .apply()
                        true
                    }
                    else -> true
                }
            }
        }
        keyboardTitle.addView(keyboardDragHandle, LinearLayout.LayoutParams(0, dp(24), 1f))
        keyboardTitle.addView(actionButton("W−") {
            keyboardWidthPercent = (keyboardWidthPercent - 5).coerceAtLeast(55)
            persistKeyboardSize()
            updateDesktopLayout(root.width, root.height)
        }, LinearLayout.LayoutParams(dp(35), dp(27)))
        keyboardTitle.addView(actionButton("W+") {
            keyboardWidthPercent = (keyboardWidthPercent + 5).coerceAtMost(100)
            persistKeyboardSize()
            updateDesktopLayout(root.width, root.height)
        }, LinearLayout.LayoutParams(dp(35), dp(27)))
        keyboardTitle.addView(actionButton("H−") {
            keyboardHeightDp = (keyboardHeightDp - 15).coerceAtLeast(170)
            persistKeyboardSize()
            updateDesktopLayout(root.width, root.height)
        }, LinearLayout.LayoutParams(dp(35), dp(27)))
        keyboardTitle.addView(actionButton("H+") {
            keyboardHeightDp = (keyboardHeightDp + 15).coerceAtMost(360)
            persistKeyboardSize()
            updateDesktopLayout(root.width, root.height)
        }, LinearLayout.LayoutParams(dp(35), dp(27)))
        keyboardTitle.addView(actionButton("×") {
            keyboardPanelVisible = false
            keyboardPanel.visibility = View.GONE
            updateDesktopLayout(root.width, root.height)
        }, LinearLayout.LayoutParams(dp(32), dp(27)))
        keyboardPanel.addView(keyboardTitle)

        // Editing shortcuts are sent to the focused app on the virtual display.
        val editActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("Cut", "Copy", "Paste", "Select all").forEach { label ->
            val button = actionButton(label) {
                sendKeyboardEditCommand(label)
            }
            editActions.addView(button, LinearLayout.LayoutParams(0, dp(29), 1f).apply {
                setMargins(dp(2), dp(1), dp(2), dp(1))
            })
        }
        keyboardPanel.addView(editActions, LinearLayout.LayoutParams(-1, dp(31)))

        fun keyRow(labels: List<String>, weights: List<Float> = labels.map { 1f }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            labels.forEachIndexed { index, label ->
                val utility = label in setOf("SHIFT", "⌫", "?123", "ENTER")
                val button = cyberKey(label, utility, repeatable = label == "⌫" || (label.length == 1 && label != "," && label != ".")) {
                    when (label) {
                        "?123" -> {
                            cyberSymbols = !cyberSymbols
                            refreshCyberdeckLabels()
                        }
                        "SHIFT" -> {
                            cyberShift = !cyberShift
                            updateModifierLabels()
                        }
                        "SPACE" -> sendKeyboardKey(android.view.KeyEvent.KEYCODE_SPACE)
                        "," -> sendTypedText(if (cyberSymbols) ";" else ",")
                        "." -> sendTypedText(if (cyberSymbols) ":" else ".")
                        "⌫" -> sendKeyboardKey(android.view.KeyEvent.KEYCODE_DEL)
                        "ENTER" -> sendKeyboardKey(android.view.KeyEvent.KEYCODE_ENTER)
                        else -> {
                            val key = if (cyberSymbols) symbolForCyberdeckKey(label) else label.lowercase()
                            val printable = if (cyberShift && !cyberSymbols) key.uppercase() else key
                            sendTypedText(printable)
                            if (cyberShift && !cyberSymbols && printable.any { it.isLetter() }) {
                                cyberShift = false
                                updateModifierLabels()
                            }
                        }
                    }
                }
                button.tag = label
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weights[index]).apply {
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                })
            }
            keyboardPanel.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        keyRow(listOf("1","2","3","4","5","6","7","8","9","0"), List(10) { 1f })
        keyRow(listOf("Q","W","E","R","T","Y","U","I","O","P"), List(10) { 1f })
        keyRow(listOf("A","S","D","F","G","H","J","K","L"), List(9) { 1f })
        keyRow(listOf("SHIFT","Z","X","C","V","B","N","M","⌫"), listOf(1.55f,1f,1f,1f,1f,1f,1f,1f,1.55f))
        keyRow(listOf("?123",",","SPACE",".","ENTER"), listOf(1.45f,0.8f,3.6f,0.8f,1.5f))
        root.addView(keyboardPanel, FrameLayout.LayoutParams(-1, dp(keyboardHeightDp), Gravity.BOTTOM).apply {
            leftMargin = dp(7); rightMargin = dp(7); bottomMargin = dp(12)
        })
        keyboardPanel.visibility = View.GONE

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(4), dp(7), dp(7))
            background = roundedPanel(250, 15)
            elevation = dp(8).toFloat()
        }
        val panelHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val touchpadMoveHandle = TextView(this).apply {
            text = "⠿ PINDAH"
            textSize = 9f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(7), 0, dp(7), 0)
            contentDescription = "Seret untuk memindahkan posisi touchpad"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(47, 83, 108))
                cornerRadius = dp(6).toFloat()
            }
        }
        panelHeader.addView(touchpadMoveHandle, LinearLayout.LayoutParams(dp(68), dp(26)).apply {
            rightMargin = dp(5)
        })
        panelHeader.addView(TextView(this).apply {
            text = "TRACKPAD • 1 jari kursor / tap klik / 2 jari scroll"
            textSize = 9f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }, LinearLayout.LayoutParams(0, dp(28), 1f))
        panelHeader.addView(actionButton("🖱 Kursor") {
            directTouchEnabled = false
            directTouchButton.text = "Touch: OFF"
            bottomPanel.visibility = View.VISIBLE
            status.text = "Mode trackpad aktif."
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        panelHeader.addView(actionButton("⌨") {
            keyboardPanelVisible = !keyboardPanelVisible
            keyboardPanel.visibility = if (keyboardPanelVisible) View.VISIBLE else View.GONE
            updateDesktopLayout(root.width, root.height)
        }, LinearLayout.LayoutParams(dp(38), dp(28)))
        panelHeader.addView(actionButton("⚙") {
            topBar.visibility = View.VISIBLE
            status.text = "Pengaturan resolusi dan DPI tersedia di panel Saku Desktop."
        }, LinearLayout.LayoutParams(dp(38), dp(28)))
        touchpadMoveHandle.isClickable = true
        touchpadMoveHandle.isFocusable = true
        touchpadMoveHandle.setOnTouchListener { _, event ->
            val params = bottomPanel.layoutParams as? FrameLayout.LayoutParams
                ?: return@setOnTouchListener false
            val landscapeNow = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchpadMoveHandle.parent?.requestDisallowInterceptTouchEvent(true)
                    touchpadDragOriginX = bottomPanel.left.coerceIn(0,
                        (root.width - bottomPanel.width).coerceAtLeast(0))
                    touchpadDragOriginY = bottomPanel.top.coerceIn(0,
                        (root.height - bottomPanel.height).coerceAtLeast(0))
                    touchpadDragStartX = event.rawX
                    touchpadDragStartY = event.rawY
                    touchpadDragStarted = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchpadDragStartX
                    val deltaY = event.rawY - touchpadDragStartY
                    if (!touchpadDragStarted) {
                        if (kotlin.math.abs(deltaX) < dp(6) && kotlin.math.abs(deltaY) < dp(6)) {
                            return@setOnTouchListener true
                        }
                        touchpadDragStarted = true
                        if (landscapeNow) touchpadLandscapeHasCustomPosition = true
                        else touchpadPortraitHasCustomPosition = true
                    }
                    val maxX = (root.width - bottomPanel.width).coerceAtLeast(0)
                    val maxY = (root.height - bottomPanel.height).coerceAtLeast(0)
                    val x = (touchpadDragOriginX + deltaX).toInt().coerceIn(0, maxX)
                    val y = (touchpadDragOriginY + deltaY).toInt().coerceIn(0, maxY)
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    params.leftMargin = x
                    params.topMargin = y
                    params.rightMargin = 0
                    params.bottomMargin = 0
                    root.updateViewLayout(bottomPanel, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (touchpadDragStarted) {
                        val maxX = (root.width - bottomPanel.width).coerceAtLeast(1)
                        val maxY = (root.height - bottomPanel.height).coerceAtLeast(1)
                        val xPercent = (params.leftMargin.toFloat() / maxX * 100).toInt().coerceIn(0, 100)
                        val yPercent = (params.topMargin.toFloat() / maxY * 100).toInt().coerceIn(0, 100)
                        if (landscapeNow) {
                            touchpadLandscapeXPercent = xPercent
                            touchpadLandscapeYPercent = yPercent
                        } else {
                            touchpadPortraitXPercent = xPercent
                            touchpadPortraitYPercent = yPercent
                        }
                        persistTouchpadSettings()
                    }
                    touchpadDragStarted = false
                    touchpadMoveHandle.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
        bottomPanel.addView(panelHeader)
        val virtualNavRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        virtualNavRow.addView(actionButton("◀ Back") { sendVirtualNavigationKey(android.view.KeyEvent.KEYCODE_BACK) },
            LinearLayout.LayoutParams(0, dp(30), 1f))
        virtualNavRow.addView(actionButton("⌂ Home") { sendVirtualNavigationKey(android.view.KeyEvent.KEYCODE_HOME) },
            LinearLayout.LayoutParams(0, dp(30), 1f))
        virtualNavRow.addView(actionButton("▢ Recent") { showVirtualDisplayRecentTasks() },
            LinearLayout.LayoutParams(0, dp(30), 1f))
        bottomPanel.addView(virtualNavRow)
        val stage = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(19, 39, 56))
            contentDescription = "Touchpad: gerak 1 jari, tap klik, 2 jari scroll, tahan 1 detik lalu geser untuk drag"
        }
        stage.setOnTouchListener { _, event -> handleTouch(event, stage.width, stage.height) }
        bottomPanel.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, dp(210), Gravity.BOTTOM).apply {
            leftMargin = dp(7); rightMargin = dp(7); bottomMargin = dp(7)
        })
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            updateDesktopLayout(right - left, bottom - top)
        }

        runtimeLog = TextView(this).apply {
            textSize = 9f
            setTextColor(Color.rgb(190, 236, 249))
            setTextIsSelectable(true)
            text = "Runtime log siap. Hasil Shizuku dan error muncul di sini."
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Saku Simple runtime log", runtimeLogBuffer.toString()))
                appendRuntimeLog("Log disalin ke clipboard.")
                true
            }
        }
        runtimeLogScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(runtimeLog, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        runtimeLogPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            background = roundedPanel(245, 12)
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }
        val logHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logHeader.addView(TextView(this).apply {
            text = "RUNTIME LOG • tahan isi untuk salin"
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(28), 1f))
        logHeader.addView(actionButton("Tutup") { toggleRuntimeLog(false) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        runtimeLogPanel.addView(logHeader)
        runtimeLogPanel.addView(runtimeLogScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(runtimeLogPanel, FrameLayout.LayoutParams(-1, dp(185), Gravity.TOP).apply {
            leftMargin = dp(8); rightMargin = dp(8); topMargin = dp(102)
        })

        // IME bridge: field 1x1 transparan tetap berada di Activity utama (display 0).
        // IME virtual display sendiri disembunyikan oleh ShizukuUserService.
        imeBridgeEditText = object : EditText(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val base = super.onCreateInputConnection(outAttrs) ?: return null
                return object : InputConnectionWrapper(base, false) {
                    // Keep Gboard's composing buffer in sync with the target app. Gboard
                    // commonly sends setComposingText first and commitText only later.
                    private var composingText = ""
                    private var justFinishedComposingText = ""

                    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        val next = text?.toString().orEmpty()
                        justFinishedComposingText = ""
                        if (composingText.isNotEmpty()) sendImeBridgeDelete(composingText.codePointCount(0, composingText.length))
                        if (next.isNotEmpty()) sendImeBridgeText(next)
                        composingText = next
                        return super.setComposingText(text, newCursorPosition)
                    }

                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        val committed = text?.toString().orEmpty()
                        val alreadyInjected = committed.isNotEmpty() &&
                            (committed == composingText || committed == justFinishedComposingText)
                        if (composingText.isNotEmpty() && !alreadyInjected) {
                            sendImeBridgeDelete(composingText.codePointCount(0, composingText.length))
                        }
                        composingText = ""
                        justFinishedComposingText = ""
                        if (committed.isNotEmpty() && !alreadyInjected) sendImeBridgeText(committed)
                        return super.commitText(text, newCursorPosition)
                    }

                    override fun finishComposingText(): Boolean {
                        // Composing characters were already mirrored to the target display.
                        // Finalize locally without deleting/re-inserting them.
                        justFinishedComposingText = composingText
                        composingText = ""
                        return super.finishComposingText()
                    }

                    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                        if (beforeLength > 0) sendImeBridgeDelete(beforeLength)
                        if (afterLength > 0) sendImeBridgeForwardDelete(afterLength)
                        composingText = ""
                        justFinishedComposingText = ""
                        return super.deleteSurroundingText(beforeLength, afterLength)
                    }

                    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                        if (beforeLength > 0) sendImeBridgeDelete(beforeLength)
                        if (afterLength > 0) sendImeBridgeForwardDelete(afterLength)
                        composingText = ""
                        justFinishedComposingText = ""
                        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                    }

                    override fun sendKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_DEL -> {
                                    sendImeBridgeDelete(1)
                                    if (composingText.isNotEmpty()) composingText = composingText.dropLast(1)
                                }
                                KeyEvent.KEYCODE_ENTER -> sendImeBridgeText("\n")
                                else -> event.unicodeChar.takeIf { it > 0 }?.let {
                                    sendImeBridgeText(String(Character.toChars(it)))
                                }
                            }
                        }
                        return true
                    }
                }
            }
        }.apply {
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE
            // Stay attached/focusable for a valid InputConnection while remaining invisible.
            alpha = 0.01f
            visibility = View.VISIBLE
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            background = null
            // Host the IME proxy on the physical phone display (display 0).
            // It must remain focusable so Gboard cannot attach to the virtual display.
            isFocusable = true
            isFocusableInTouchMode = true
            setShowSoftInputOnFocus(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = "Jembatan input Gboard ke virtual display"

        }
        // Keep the proxy attached to the host window (display 0).
        // GONE would remove its InputConnection and let Android route IME elsewhere.
        imeBridgeEditText.visibility = View.VISIBLE
        root.addView(imeBridgeEditText, FrameLayout.LayoutParams(dp(1), dp(1), Gravity.TOP or Gravity.START))
        setContentView(root)
    }

    private fun roundedPanel(alpha: Int, radius: Int) = GradientDrawable().apply {
        setColor(Color.argb(alpha.coerceIn(0, 255), 7, 25, 45))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(105, 182, 225, 247))
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun showVirtualDisplayRecentTasks() {
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0) {
            status.text = "Mulai virtual display dahulu."
            return
        }
        status.text = "Membaca aplikasi pada display $displayId…"
        keyboardInputExecutor.execute {
            val result = shizukuBridge.getRecentTasks(displayId)
            runOnUiThread {
                appendRuntimeLog("Recent display $displayId\\n$result")
                AlertDialog.Builder(this)
                    .setTitle("Recent • Display $displayId")
                    .setMessage(result)
                    .setPositiveButton("Perbarui") { _, _ -> showVirtualDisplayRecentTasks() }
                    .setNegativeButton("Tutup", null)
                    .show()
                status.text = "Daftar Recent display $displayId diperbarui."
            }
        }
    }

    private fun sendVirtualNavigationKey(keyCode: Int) {
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0) {
            status.text = "Mulai virtual display dahulu."
            return
        }

        // APP_SWITCH is a system-policy key. Sending it through the generic
        // injected KeyEvent path can invoke Recents on the host (display 0).
        // Route this one key through Android's display-targeted input command.
        if (keyCode == android.view.KeyEvent.KEYCODE_APP_SWITCH) {
            keyboardInputExecutor.execute {
                val accepted = shizukuBridge.runShellCommand("input -d $displayId keyevent ${android.view.KeyEvent.KEYCODE_APP_SWITCH}")
                runOnUiThread {
                    if (!accepted) {
                        status.text = "Recent gagal dikirim ke display virtual."
                        appendRuntimeLog("Recent display $displayId gagal: ${shizukuBridge.getLastError()}")
                    } else {
                        status.text = "Perintah Recent dikirim ke display virtual $displayId."
                    }
                }
            }
            return
        }

        // Back and Home keep using the existing virtual-display key injection.
        sendVirtualKey(keyCode)
    }

    private fun sendVirtualKey(keyCode: Int) {
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0) {
            status.text = "Virtual display belum aktif."
            return
        }
        keyboardInputExecutor.execute {
            val accepted = virtualDisplayController?.injectKeyEvents(
                intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP),
                intArrayOf(keyCode, keyCode),
                intArrayOf(0, 0)
            ) ?: false
            runOnUiThread {
                if (!accepted) {
                    status.text = "Tombol tidak diterima display virtual."
                    appendRuntimeLog("KeyEvent keyCode=$keyCode gagal: ${shizukuBridge.getLastError()}")
                } else {
                    status.text = "Tombol dikirim ke display virtual • keyCode=$keyCode"
                }
            }
        }
    }

    private fun toggleDirectTouch() {
        directTouchEnabled = !directTouchEnabled
        directTouchButton.text = if (directTouchEnabled) "Touch: ON" else "Touch: OFF"
        preview?.isClickable = directTouchEnabled
        status.text = if (directTouchEnabled) {
            bottomPanel.visibility = View.GONE
            panelToggleButton.text = "Panel+"
            "Direct Touch aktif • sentuh dan geser desktop seperti layar sentuh."
        } else {
            bottomPanel.visibility = View.VISIBLE
            panelToggleButton.text = "Panel"
            updateDesktopLayout((preview?.parent as? View)?.width ?: 0, (preview?.parent as? View)?.height ?: 0)
            "Touchpad aktif • 1 jari kursor, tap layar, 2 jari scroll."
        }
    }

    private fun handleDirectTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        if (event.pointerCount > 5) {
            status.text = "Direct Touch mendukung maksimal 5 jari."
            return true
        }
        val count = event.pointerCount
        val xs = FloatArray(count) { i ->
            (event.getX(i) / width.toFloat() * targetWidth).coerceIn(0f, (targetWidth - 1).toFloat())
        }
        val ys = FloatArray(count) { i ->
            (event.getY(i) / height.toFloat() * targetHeight).coerceIn(0f, (targetHeight - 1).toFloat())
        }
        cursorX = xs[0]
        cursorY = ys[0]
        cursorOverlay?.invalidate()
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or
                (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_POINTER_UP or
                (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
            else -> return true
        }
        val controller = virtualDisplayController
        if (controller == null || controller.displayId < 0) {
            if (action == MotionEvent.ACTION_DOWN) status.text = "Mulai virtual display melalui Shizuku dahulu."
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) directTouchDown = true
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) directTouchDown = false
        val pointerIds = IntArray(count) { i -> event.getPointerId(i) }
        val isFinalUp = event.actionMasked == MotionEvent.ACTION_UP
        mouseInputExecutor.execute {
            val accepted = controller.injectTouchPoints(action, pointerIds, xs, ys)
            runOnUiThread {
                if (!accepted) {
                    appendRuntimeLog("Direct Touch multi-jari gagal • action=$action • ${shizukuBridge.getLastError()}")
                    status.text = "Direct Touch gagal • periksa Log."
                } else if (isFinalUp) {
                    pulseDextopCursor()
                }
            }
        }
        return true
    }

    private fun toggleInputPanel() {
        val show = bottomPanel.visibility != View.VISIBLE
        bottomPanel.visibility = if (show) View.VISIBLE else View.GONE
        panelToggleButton.text = if (show) "Panel" else "Panel+"
        if (!show) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
    }


    private fun showVirtualKeyboard() {
        keyboardPanelVisible = true
        keyboardPanel.visibility = View.VISIBLE
        updateDesktopLayout((preview?.parent as? View)?.width ?: 0, (preview?.parent as? View)?.height ?: 0)
        status.text = "Keyboard Cyberdeck aktif • terpisah dari preview dan trackpad."
    }

    private fun toggleSystemKeyboardBridge() {
        if (systemKeyboardRequested) {
            systemKeyboardRequested = false
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(imeBridgeEditText.windowToken, 0)
            imeBridgeEditText.clearFocus()
            status.text = "Gboard disembunyikan • fokus virtual display tetap tersedia."
        } else {
            showSystemKeyboardBridge()
        }
    }

    private fun showSystemKeyboardBridge() {
        val targetDisplayId = virtualDisplayController?.displayId ?: -1
        if (targetDisplayId < 0) {
            status.text = "Mulai virtual display dahulu."
            return
        }
        // Gboard must be hosted by this Activity on the physical built-in display 0.
        val hostDisplayId = if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId ?: -1 else 0
        if (hostDisplayId != 0) {
            appendRuntimeLog("Gboard dibatalkan: Activity berada di display $hostDisplayId, bukan built-in display 0.")
            status.text = "Buka kontrol VDT dari layar HP utama (Display 0) untuk memakai Gboard."
            return
        }
        if (::keyboardPanel.isInitialized && keyboardPanelVisible) {
            keyboardPanel.visibility = View.VISIBLE
        }
        val root = preview?.parent as? View
        if (root != null) updateDesktopLayout(root.width, root.height)

        systemKeyboardRequested = true
        imeBridgeEditText.visibility = View.VISIBLE
        imeBridgeEditText.isFocusable = true
        imeBridgeEditText.isFocusableInTouchMode = true
        imeBridgeEditText.requestFocus()

        // Some OEM builds ignore showSoftInput until the host window is attached and focused.
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        val showTask = object : Runnable {
            override fun run() {
                if (!systemKeyboardRequested || isFinishing || isDestroyed) return
                attempts++
                imeBridgeEditText.requestFocus()
                val windowReady = window.decorView.isAttachedToWindow &&
                    window.decorView.hasWindowFocus() &&
                    imeBridgeEditText.windowToken != null
                val shown = if (windowReady) {
                    imm.showSoftInput(imeBridgeEditText, InputMethodManager.SHOW_IMPLICIT)
                } else false
                appendRuntimeLog(
                    "Gboard host=0 target=$targetDisplayId attempt=$attempts " +
                        "windowReady=$windowReady requested=$shown active=${imm.isActive(imeBridgeEditText)}"
                )
                if (shown || imm.isActive(imeBridgeEditText)) {
                    status.text = "Gboard diminta di layar HP (Display 0)."
                } else if (attempts < 6) {
                    handler.postDelayed(this, 180L)
                } else {
                    status.text = "Gboard gagal dibuka • pastikan Activity VDT berada di layar HP utama."
                }
            }
        }
        imeBridgeEditText.post(showTask)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun sendImeBridgeText(text: String) {
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0 || text.isEmpty()) return
        keyboardInputExecutor.execute {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val parts = text.split('\n')
            var allAccepted = true
            parts.forEachIndexed { index, part ->
                if (part.isNotEmpty()) {
                    // Android's input tool uses %s as its space escape. Shell quoting
                    // protects quotes, semicolons, $, backticks and other metacharacters.
                    // The input tool is reliable for ordinary ASCII; non-ASCII and
                    // literal "%s" go straight to clipboard because input text cannot
                    // represent them faithfully on every Android build.
                    val needsClipboard = part.any { it.code > 127 } || part.contains("%s")
                    var accepted = false
                    if (!needsClipboard) {
                        val encoded = part.replace(" ", "%s")
                        val command = "input -d $displayId text ${shellQuote(encoded)}"
                        accepted = shizukuBridge.runShellCommand(command)
                    }
                    if (!accepted) {
                        runCatching {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Saku keyboard", part))
                        }.onFailure {
                            appendRuntimeLog("Clipboard fallback gagal: ${it.message}")
                        }
                        accepted = shizukuBridge.runShellCommand("input -d $displayId keyevent 279")
                    }
                    if (!accepted) {
                        allAccepted = false
                        runOnUiThread {
                            appendRuntimeLog("Teks gagal dikirim ke display $displayId: ${shizukuBridge.getLastError()}")
                            status.text = "Input teks gagal • periksa fokus virtual display dan izin Shizuku."
                        }
                    }
                }
                if (index < parts.lastIndex) {
                    if (!shizukuBridge.runShellCommand("input -d $displayId keyevent 66")) {
                        allAccepted = false
                    }
                }
            }
            if (allAccepted) runOnUiThread {
                status.text = "Teks dikirim melalui Shizuku ke display $displayId."
            }
        }
    }

    private fun sendImeBridgeForwardDelete(count: Int) {
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0 || count <= 0) return
        keyboardInputExecutor.execute {
            repeat(count.coerceAtMost(64)) {
                if (!shizukuBridge.runShellCommand("input -d $displayId keyevent 112")) {
                    runOnUiThread {
                        appendRuntimeLog("Forward-delete gagal: ${shizukuBridge.getLastError()}")
                        status.text = "Penghapusan karakter setelah kursor gagal."
                    }
                    return@execute
                }
            }
        }
    }

    private fun sendImeBridgeDelete(count: Int) {
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0 || count <= 0) return
        keyboardInputExecutor.execute {
            repeat(count.coerceAtMost(64)) {
                if (!shizukuBridge.runShellCommand("input -d $displayId keyevent 67")) {
                    runOnUiThread {
                        appendRuntimeLog("Backspace gagal: ${shizukuBridge.getLastError()}")
                        status.text = "Backspace gagal dikirim ke virtual display."
                    }
                    return@execute
                }
            }
        }
    }

    private fun cyberKey(label: String, utility: Boolean, repeatable: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = when (label) {
                "SPACE" -> 11f
                "?123", "SHIFT", "ENTER", "⌫" -> 12f
                else -> 14f
            }
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (utility) Color.rgb(242, 245, 250) else Color.rgb(31, 36, 44))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (utility) Color.rgb(82, 96, 116) else Color.rgb(225, 230, 237))
                setStroke(dp(1), if (utility) Color.rgb(105, 119, 139) else Color.rgb(208, 215, 225))
            }
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            stateListAnimator = null
            if (repeatable) {
                var repeatTask: Runnable? = null
                var repeating = false
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            repeating = true
                            action()
                            val task = object : Runnable {
                                override fun run() {
                                    if (repeating) {
                                        action()
                                        keyboardRepeatHandler.postDelayed(this, 55L)
                                    }
                                }
                            }
                            repeatTask = task
                            keyboardRepeatHandler.postDelayed(task, 420L)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            repeating = false
                            repeatTask?.let(keyboardRepeatHandler::removeCallbacks)
                            repeatTask = null
                            true
                        }
                        else -> true
                    }
                }
            } else {
                setOnClickListener { action() }
            }
        }

    private fun symbolForCyberdeckKey(label: String): String = when (label.uppercase()) {
        "Q" -> "@"; "W" -> "#"; "E" -> "\$"; "R" -> "%"; "T" -> "&"
        "Y" -> "*"; "U" -> "("; "I" -> ")"; "O" -> "-"; "P" -> "+"
        "A" -> "="; "S" -> "/"; "D" -> "?"; "F" -> "!"; "G" -> ":"
        "H" -> ";"; "J" -> "["; "K" -> "]"; "L" -> "{"
        "Z" -> "}"; "X" -> "_"; "C" -> "\\"; "V" -> "|"
        "B" -> "~"; "N" -> "<"; "M" -> ">"
        "1" -> "!"; "2" -> "@"; "3" -> "#"; "4" -> "\$"; "5" -> "%"
        "6" -> "&"; "7" -> "*"; "8" -> "("; "9" -> ")"; "0" -> "="
        else -> label
    }

    private fun refreshCyberdeckLabels() {
        if (!::keyboardPanel.isInitialized) return
        for (i in 0 until keyboardPanel.childCount) {
            val row = keyboardPanel.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val button = row.getChildAt(j) as? Button ?: continue
                val original = button.tag as? String ?: continue
                if (original == "?123") {
                    button.text = if (cyberSymbols) "ABC" else "?123"
                } else if (original.length == 1 && original[0].isLetterOrDigit()) {
                    button.text = if (cyberSymbols) symbolForCyberdeckKey(original) else original
                }
            }
        }
        updateModifierLabels()
    }

    private fun updateModifierLabels() {
        if (!::keyboardPanel.isInitialized) return
        for (i in 0 until keyboardPanel.childCount) {
            val row = keyboardPanel.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val button = row.getChildAt(j) as? Button ?: continue
                when (button.tag as? String) {
                    "SHIFT" -> button.text = if (cyberShift) "SHIFT •" else "SHIFT"
                    "CTRL" -> button.text = if (cyberCtrl) "CTRL •" else "CTRL"
                    "ALT" -> button.text = if (cyberAlt) "ALT •" else "ALT"
                }
            }
        }
    }

    private fun sendKeyboardEditCommand(label: String) {
        val keyCode = when (label) {
            "Cut" -> android.view.KeyEvent.KEYCODE_CUT
            "Copy" -> android.view.KeyEvent.KEYCODE_COPY
            "Paste" -> android.view.KeyEvent.KEYCODE_PASTE
            "Select all" -> android.view.KeyEvent.KEYCODE_A
            else -> return
        }
        val displayId = virtualDisplayController?.displayId ?: -1
        if (displayId < 0) {
            status.text = "Mulai virtual display dahulu."
            return
        }
        val meta = if (label == "Select all") android.view.KeyEvent.META_CTRL_ON else 0
        keyboardInputExecutor.execute {
            val accepted = virtualDisplayController?.injectKeyEvents(
                intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP),
                intArrayOf(keyCode, keyCode),
                intArrayOf(meta, meta)
            ) ?: false
            runOnUiThread {
                if (accepted) {
                    status.text = "$label dikirim ke aplikasi pada display $displayId."
                    appendRuntimeLog("Keyboard edit: $label • display=$displayId")
                } else {
                    status.text = "$label gagal dikirim."
                    appendRuntimeLog("Keyboard edit gagal ($label): ${shizukuBridge.getLastError()}")
                }
            }
        }
    }

    private fun sendKeyboardKey(keyCode: Int) {
        val meta = (if (cyberShift) android.view.KeyEvent.META_SHIFT_ON else 0) or
            (if (cyberCtrl) android.view.KeyEvent.META_CTRL_ON else 0) or
            (if (cyberAlt) android.view.KeyEvent.META_ALT_ON else 0)
        if ((virtualDisplayController?.displayId ?: -1) < 0) {
            status.text = "Mulai virtual display dahulu."
            return
        }
        keyboardInputExecutor.execute {
            val accepted = virtualDisplayController?.injectKeyEvents(
                intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP),
                intArrayOf(keyCode, keyCode),
                intArrayOf(meta, meta)
            ) ?: false
            if (!accepted) runOnUiThread {
                status.text = "Tombol keyboard gagal dikirim."
                appendRuntimeLog("Cyberdeck keyCode=$keyCode gagal: " + shizukuBridge.getLastError())
            }
        }
    }

    private fun sendTypedText(text: String) {
        // Build KeyEvents and call Shizuku Binder off the UI thread.
        val shift = cyberShift
        val ctrl = cyberCtrl
        val alt = cyberAlt
        keyboardInputExecutor.execute {
            val events = runCatching {
                KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            }.getOrNull()
            if (events.isNullOrEmpty()) {
                runOnUiThread { appendRuntimeLog("Keyboard: karakter tidak didukung KeyCharacterMap: ${text.take(16)}") }
                return@execute
            }
            val actions = IntArray(events.size) { events[it].action }
            val codes = IntArray(events.size) { events[it].keyCode }
            val modifierMeta = (if (shift) android.view.KeyEvent.META_SHIFT_ON else 0) or
                (if (ctrl) android.view.KeyEvent.META_CTRL_ON else 0) or
                (if (alt) android.view.KeyEvent.META_ALT_ON else 0)
            val meta = IntArray(events.size) { events[it].metaState or modifierMeta }
            val accepted = virtualDisplayController?.injectKeyEvents(actions, codes, meta) ?: false
            if (!accepted) runOnUiThread {
                status.text = "Teks keyboard gagal dikirim. Periksa Log."
                appendRuntimeLog("Injeksi key event gagal: ${shizukuBridge.getLastError()}")
            }
        }
    }

    private fun toggleRuntimeLog(forceShow: Boolean? = null) {
        val show = forceShow ?: (runtimeLogPanel.visibility != View.VISIBLE)
        runtimeLogPanel.visibility = if (show) View.VISIBLE else View.GONE
        runtimeLogToggle.text = if (show) "Log−" else "Log"
    }

    private fun queueNativeMouseButton(action: Int, x: Float, y: Float) {
        mouseInputExecutor.execute {
            val accepted = virtualDisplayController?.injectMouseButton(action, x, y) ?: false
            if (!accepted && action != MotionEvent.ACTION_MOVE) runOnUiThread {
                appendRuntimeLog("Mouse button gagal: ${shizukuBridge.getLastError()}")
            }
        }
    }

    private fun queueNativeMouseClick(x: Float, y: Float) {
        mouseInputExecutor.execute {
            val accepted = virtualDisplayController?.injectTouchClick(x, y) ?: false
            runOnUiThread {
                if (accepted) {
                    pulseDextopCursor()
                    status.text = "Tap layar • x=${x.toInt()} y=${y.toInt()}"
                } else {
                    status.text = "Klik ditolak InputManager. Periksa Log."
                    appendRuntimeLog("Klik gagal: ${shizukuBridge.getLastError()}")
                }
            }
        }
    }

    override fun onDestroy() {
        longPressTask?.let(gestureHandler::removeCallbacks)
        if (dragActive) virtualDisplayController?.injectMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
        mouseInputExecutor.shutdownNow()
        keyboardInputExecutor.shutdownNow()
        virtualDisplayController?.close()
        virtualDisplayController = null
        clearPreview()
        if (::shizukuBridge.isInitialized) shizukuBridge.close()
        super.onDestroy()
    }

    private fun startOwnedVirtualDisplay() {
        val surface = preview?.holder?.surface
        if (surface == null || !surface.isValid) {
            status.text = "Surface preview belum siap. Tunggu sebentar lalu coba lagi."
            return
        }
        if (virtualDisplayController == null) {
            virtualDisplayController = VirtualDisplayController(
                this,
                shizukuBridge,
                onStatus = { message ->
                    status.text = message
                    appendRuntimeLog(message)
                }
            )
        }
        status.text = "Membuat display langsung ke SurfaceView • ${targetWidth}×${targetHeight} • densityDpi=${targetDpi}…"
        appendRuntimeLog("Output langsung ke SurfaceView • ${targetWidth}×${targetHeight} • ${targetDpi}dpi; ImageReader/Bitmap copy tidak digunakan.")
        preview?.holder?.setFixedSize(targetWidth, targetHeight)
        virtualDisplayController?.start(
            width = targetWidth,
            height = targetHeight,
            densityDpi = targetDpi,
            outputSurface = surface
        )
    }

    private fun appendRuntimeLog(message: String) {
        if (!::runtimeLog.isInitialized) return
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        runtimeLogBuffer.append("[").append(timestamp).append("] ")
            .append(message).append('\n')
        if (runtimeLogBuffer.length > 12000) {
            runtimeLogBuffer.delete(0, runtimeLogBuffer.length - 9000)
        }
        runtimeLog.text = runtimeLogBuffer.toString()
        if (::runtimeLogScroll.isInitialized) {
            runtimeLogScroll.post { runtimeLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearPreview() {
        val holder = preview?.holder ?: return
        if (!holder.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            canvas?.drawColor(Color.rgb(7, 24, 45))
        } catch (error: Throwable) {
            Log.w("SakuPreview", "Gagal membersihkan preview", error)
        } finally {
            if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
        }
        cursorOverlay?.invalidate()
    }

    /** SakuDextop-style CursorView: filled white dot, dark outline, and click pulse. */
    private fun drawDextopCursor(canvas: Canvas) {
        val surface = preview ?: return
        if (surface.width <= 0 || surface.height <= 0) return
        val pointerX = surface.left + (cursorX / targetWidth) * surface.width
        val pointerY = surface.top + (cursorY / targetHeight) * surface.height
        canvas.drawCircle(pointerX, pointerY, cursorPulseRadius, cursorPaint)
        canvas.drawCircle(pointerX, pointerY, cursorPulseRadius, cursorStrokePaint)
    }

    private fun pulseDextopCursor() {
        cursorPulseRadius = 18f
        cursorOverlay?.invalidate()
        cursorOverlay?.postDelayed({
            cursorPulseRadius = 13f
            cursorOverlay?.invalidate()
        }, 100L)
    }

    /** Dispatch the newest pointer coordinate through the Shizuku InputManager path. */
    private fun queueNativeMouseMove(x: Float, y: Float) {
        var scheduleDrain = false
        synchronized(mouseMoveLock) {
            pendingMouseX = x
            pendingMouseY = y
            if (!mouseMoveDrainScheduled) {
                mouseMoveDrainScheduled = true
                scheduleDrain = true
            }
        }
        if (!scheduleDrain) return
        mouseInputExecutor.execute {
            while (true) {
                val point = synchronized(mouseMoveLock) {
                    if (pendingMouseX.isNaN() || pendingMouseY.isNaN()) {
                        mouseMoveDrainScheduled = false
                        null
                    } else {
                        val latest = pendingMouseX to pendingMouseY
                        pendingMouseX = Float.NaN
                        pendingMouseY = Float.NaN
                        latest
                    }
                } ?: break
                virtualDisplayController?.injectMouseMove(point.first, point.second)
            }
        }
    }

    /** Accumulate fractional two-finger scroll deltas so input stays smooth without a backlog. */
    private fun queueNativeMouseScroll(x: Float, y: Float, horizontal: Float, vertical: Float) {
        if (kotlin.math.abs(horizontal) < 0.0005f && kotlin.math.abs(vertical) < 0.0005f) return
        var scheduleDrain = false
        synchronized(scrollLock) {
            pendingScrollX = x
            pendingScrollY = y
            pendingScrollHorizontal += horizontal
            pendingScrollVertical += vertical
            if (!scrollDrainScheduled) {
                scrollDrainScheduled = true
                scheduleDrain = true
            }
        }
        if (!scheduleDrain) return
        mouseInputExecutor.execute {
            while (true) {
                val batch = synchronized(scrollLock) {
                    if (kotlin.math.abs(pendingScrollHorizontal) < 0.0005f &&
                        kotlin.math.abs(pendingScrollVertical) < 0.0005f
                    ) {
                        scrollDrainScheduled = false
                        null
                    } else {
                        val values = floatArrayOf(
                            pendingScrollX, pendingScrollY,
                            pendingScrollHorizontal, pendingScrollVertical
                        )
                        pendingScrollHorizontal = 0f
                        pendingScrollVertical = 0f
                        values
                    }
                } ?: break
                virtualDisplayController?.injectMouseScroll(batch[0], batch[1], batch[2], batch[3])
            }
        }
    }

    private fun handleTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchpadFingerDown = true
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                pointerMoved = false
                hadMultiTouch = false
                scrollGestureActive = false
                longPressTask?.let(gestureHandler::removeCallbacks)
                longPressTask = Runnable {
                    if (touchpadFingerDown && !hadMultiTouch && !pointerMoved &&
                        (virtualDisplayController?.displayId ?: -1) >= 0
                    ) {
                        dragActive = true
                        queueNativeMouseButton(MotionEvent.ACTION_DOWN, cursorX, cursorY)
                        status.text = "Drag aktif • geser jari lalu lepaskan."
                    }
                }.also { gestureHandler.postDelayed(it, 500L) }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                longPressTask?.let(gestureHandler::removeCallbacks)
                if (dragActive) {
                    queueNativeMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    dragActive = false
                }
                if (event.pointerCount >= 2) {
                    hadMultiTouch = true
                    scrollGestureActive = true
                    val centroid = touchCentroid(event)
                    lastScrollCentroidX = centroid.first
                    lastScrollCentroidY = centroid.second
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hadMultiTouch) {
                    if (scrollGestureActive && event.pointerCount >= 2) {
                        val centroid = touchCentroid(event)
                        val dx = centroid.first - lastScrollCentroidX
                        val dy = centroid.second - lastScrollCentroidY
                        lastScrollCentroidX = centroid.first
                        lastScrollCentroidY = centroid.second
                        queueNativeMouseScroll(
                            cursorX, cursorY,
                            horizontal = -dx / dp(12).toFloat(),
                            vertical = -dy / dp(12).toFloat()
                        )
                    }
                    return true
                }

                val x = event.x
                val y = event.y
                val deltaX = x - lastTouchX
                val deltaY = y - lastTouchY
                if (kotlin.math.abs(x - downX) > dp(6) || kotlin.math.abs(y - downY) > dp(6)) {
                    pointerMoved = true
                    // Do not cancel: timer decides whether the hold was stationary long enough.
                }
                cursorX = (cursorX + deltaX * targetWidth / width.toFloat() * cursorSensitivity)
                    .coerceIn(0f, (targetWidth - 1).toFloat())
                cursorY = (cursorY + deltaY * targetHeight / height.toFloat() * cursorSensitivity)
                    .coerceIn(0f, (targetHeight - 1).toFloat())
                lastTouchX = x
                lastTouchY = y
                cursorOverlay?.invalidate()
                if (dragActive) queueNativeMouseButton(MotionEvent.ACTION_MOVE, cursorX, cursorY)
                else queueNativeMouseMove(cursorX, cursorY)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                hadMultiTouch = true
                scrollGestureActive = event.pointerCount > 2
                return true
            }

            MotionEvent.ACTION_UP -> {
                touchpadFingerDown = false
                longPressTask?.let(gestureHandler::removeCallbacks)
                if (dragActive) {
                    queueNativeMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    dragActive = false
                } else if (!hadMultiTouch && !pointerMoved) {
                    val controller = virtualDisplayController
                    if (controller == null || controller.displayId < 0) {
                        status.text = "Buat virtual display lewat Shizuku sebelum mengklik."
                    } else {
                        val clickX = cursorX.coerceIn(0f, (targetWidth - 1).toFloat())
                        val clickY = cursorY.coerceIn(0f, (targetHeight - 1).toFloat())
                        mouseInputExecutor.execute {
                            val clicked = controller.injectTouchClick(clickX, clickY)
                            runOnUiThread {
                                if (clicked) {
                                    pulseDextopCursor()
                                    status.text = "Tap layar • x=${clickX.toInt()} y=${clickY.toInt()}"
                                } else {
                                    status.text = "Klik mouse ditolak InputManager. Periksa Runtime Log."
                                    appendRuntimeLog("Injeksi tap layar gagal di x=${clickX.toInt()} y=${clickY.toInt()}: ${shizukuBridge.getLastError()}")
                                }
                            }
                        }
                    }
                }
                pointerMoved = false
                hadMultiTouch = false
                scrollGestureActive = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchpadFingerDown = false
                longPressTask?.let(gestureHandler::removeCallbacks)
                if (dragActive) {
                    queueNativeMouseButton(MotionEvent.ACTION_UP, cursorX, cursorY)
                    dragActive = false
                }
                pointerMoved = false
                hadMultiTouch = false
                scrollGestureActive = false
                return true
            }
        }
        return true
    }

    private fun touchCentroid(event: MotionEvent): Pair<Float, Float> =
        ((event.getX(0) + event.getX(1)) / 2f) to ((event.getY(0) + event.getY(1)) / 2f)

    private fun applyDisplaySettings() {
        val width = resolutionWidthInput.text.toString().toIntOrNull()
        val height = resolutionHeightInput.text.toString().toIntOrNull()
        val dpi = resolutionDpiInput.text.toString().toIntOrNull()
        if (width == null || height == null || dpi == null ||
            width !in 320..2560 || height !in 320..2560 || dpi !in 120..640
        ) {
            status.text = "Resolusi harus 320–2560 px dan DPI 120–640."
            return
        }
        targetWidth = width
        targetHeight = height
        targetDpi = dpi
        cursorX = cursorX.coerceIn(0f, (targetWidth - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (targetHeight - 1).toFloat())
        preview?.contentDescription = "Desktop virtual display ${targetWidth} x ${targetHeight} • ${targetDpi} DPI"
        preview?.holder?.setFixedSize(targetWidth, targetHeight)
        if ((virtualDisplayController?.displayId ?: -1) >= 0) {
            startOwnedVirtualDisplay()
        } else {
            status.text = "Pengaturan disimpan: ${targetWidth}×${targetHeight} • ${targetDpi} DPI. Tekan Mulai."
        }
        updateDesktopLayout((preview?.parent as? View)?.width ?: 0, (preview?.parent as? View)?.height ?: 0)
    }

    private fun updateDesktopLayout(rootWidth: Int, rootHeight: Int) {
        if (rootWidth <= 0 || rootHeight <= 0 || !::bottomPanel.isInitialized) return
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val aspect = targetWidth.toFloat() / targetHeight.toFloat()
        val previewParams = preview?.layoutParams as? FrameLayout.LayoutParams
        val panelParams = bottomPanel.layoutParams as? FrameLayout.LayoutParams
        val keyboardParams = keyboardPanel.layoutParams as? FrameLayout.LayoutParams

        if (landscape) {
            // Three fixed columns: touchpad | virtual screen | keyboard.
            // The SurfaceView keeps its source aspect ratio and stays centered.
            val sideGap = dp(6)
            val keyboardWidth = ((rootWidth * 30 / 100) * keyboardWidthPercent / 100).coerceIn(dp(140), (rootWidth * 38 / 100).coerceAtLeast(dp(140)))
            val touchWidth = (rootWidth * touchpadWidthPercent / 100).coerceIn(dp(120), (rootWidth * 45 / 100).coerceAtLeast(dp(120)))
            val centerWidth = (rootWidth - keyboardWidth - touchWidth - sideGap * 4).coerceAtLeast(dp(120))
            val maxCenterHeight = (rootHeight * 92 / 100).coerceAtLeast(dp(100))
            var desktopWidth = centerWidth
            var desktopHeight = (desktopWidth / aspect).toInt()
            if (desktopHeight > maxCenterHeight) {
                desktopHeight = maxCenterHeight
                desktopWidth = (desktopHeight * aspect).toInt()
            }
            previewParams?.let {
                it.width = desktopWidth
                it.height = desktopHeight
                it.gravity = Gravity.CENTER
                it.leftMargin = 0; it.rightMargin = 0; it.topMargin = 0; it.bottomMargin = 0
                preview?.layoutParams = it
            }
            panelParams?.let {
                it.width = touchWidth
                it.height = (rootHeight * touchpadLandscapeHeightPercent / 100).coerceIn(dp(130), rootHeight)
                if (touchpadLandscapeHasCustomPosition) {
                    val maxX = (rootWidth - it.width).coerceAtLeast(0)
                    val maxY = (rootHeight - it.height).coerceAtLeast(0)
                    it.gravity = Gravity.TOP or Gravity.LEFT
                    it.leftMargin = (maxX * touchpadLandscapeXPercent / 100f).toInt()
                    it.topMargin = (maxY * touchpadLandscapeYPercent / 100f).toInt()
                    it.rightMargin = 0; it.bottomMargin = 0
                } else {
                    it.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                    it.leftMargin = sideGap; it.rightMargin = 0; it.topMargin = 0; it.bottomMargin = 0
                }
                bottomPanel.layoutParams = it
            }
            keyboardParams?.let {
                keyboardPanel.visibility = if (keyboardPanelVisible) View.VISIBLE else View.GONE
                it.width = keyboardWidth
                it.height = dp(keyboardHeightDp).coerceAtMost(rootHeight)
                if (keyboardLandscapeHasCustomPosition) {
                    val maxX = (rootWidth - it.width).coerceAtLeast(0)
                    val maxY = (rootHeight - it.height).coerceAtLeast(0)
                    it.gravity = Gravity.TOP or Gravity.START
                    it.leftMargin = (maxX * keyboardLandscapeXPercent / 100f).toInt()
                    it.topMargin = (maxY * keyboardLandscapeYPercent / 100f).toInt()
                    it.rightMargin = 0; it.bottomMargin = 0
                } else {
                    it.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    it.leftMargin = 0; it.rightMargin = sideGap; it.topMargin = 0; it.bottomMargin = 0
                }
                keyboardPanel.layoutParams = it
            }
        } else {
            // Portrait keeps the existing stacked layout and floating keyboard behavior.
            val maxPreviewWidth = (rootWidth * previewSizePercent / 100).coerceIn(dp(180), rootWidth)
            val maxPreviewHeight = (rootHeight * 40 / 100).coerceAtLeast(dp(100))
            var desktopWidth = maxPreviewWidth
            var desktopHeight = (desktopWidth / aspect).toInt()
            if (desktopHeight > maxPreviewHeight) {
                desktopHeight = maxPreviewHeight
                desktopWidth = (desktopHeight * aspect).toInt()
            }
            previewParams?.let {
                it.width = desktopWidth
                it.height = desktopHeight
                it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                it.leftMargin = 0; it.rightMargin = 0
                preview?.layoutParams = it
            }
            panelParams?.let {
                val panelHeight = (rootHeight * touchpadPortraitHeightPercent / 100)
                    .coerceIn(dp(120), (rootHeight * 65 / 100).coerceAtLeast(dp(120)))
                it.width = (rootWidth * touchpadPortraitWidthPercent / 100)
                    .coerceIn(dp(150).coerceAtMost(rootWidth), rootWidth)
                it.height = panelHeight
                if (touchpadPortraitHasCustomPosition) {
                    val maxX = (rootWidth - it.width).coerceAtLeast(0)
                    val maxY = (rootHeight - it.height).coerceAtLeast(0)
                    it.gravity = Gravity.TOP or Gravity.START
                    it.leftMargin = (maxX * touchpadPortraitXPercent / 100f).toInt()
                    it.topMargin = (maxY * touchpadPortraitYPercent / 100f).toInt()
                    it.rightMargin = 0; it.bottomMargin = 0
                } else {
                    it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    it.topMargin = (rootHeight * 70 / 100).coerceAtMost(rootHeight - panelHeight)
                    it.bottomMargin = 0
                    it.leftMargin = 0; it.rightMargin = 0
                }
                bottomPanel.layoutParams = it
            }
            keyboardParams?.let {
                keyboardPanel.visibility = if (keyboardPanelVisible) View.VISIBLE else View.GONE
                val availableKeyboardWidth = (rootWidth - dp(14)).coerceAtLeast(dp(260).coerceAtMost(rootWidth))
                val keyboardWidth = (availableKeyboardWidth * keyboardWidthPercent / 100).coerceIn(dp(260).coerceAtMost(rootWidth), rootWidth)
                val keyboardHeight = dp(keyboardHeightDp).coerceAtMost(rootHeight)
                val desiredGravity = if (keyboardHasCustomPosition) Gravity.TOP or Gravity.START else Gravity.BOTTOM
                if (keyboardHasCustomPosition) {
                    keyboardPositionX = keyboardPositionX.coerceIn(0, (rootWidth - keyboardWidth).coerceAtLeast(0))
                    keyboardPositionY = keyboardPositionY.coerceIn(0, (rootHeight - keyboardHeight).coerceAtLeast(0))
                    it.leftMargin = keyboardPositionX; it.topMargin = keyboardPositionY
                    it.rightMargin = 0; it.bottomMargin = 0
                } else {
                    it.leftMargin = dp(7); it.rightMargin = dp(7)
                    it.bottomMargin = dp(12); it.topMargin = 0
                }
                it.width = keyboardWidth
                it.height = keyboardHeight
                it.gravity = desiredGravity
                keyboardPanel.layoutParams = it
            }
        }
    }

    private fun persistKeyboardSize() {
        getSharedPreferences("saku_desktop_keyboard", Context.MODE_PRIVATE).edit()
            .putInt("width_percent", keyboardWidthPercent)
            .putInt("height_dp", keyboardHeightDp)
            .apply()
    }

    private fun persistTouchpadSettings() {
        getSharedPreferences("saku_desktop_touchpad", Context.MODE_PRIVATE).edit()
            .putInt("width_percent", touchpadWidthPercent)
            .putInt("height_percent", touchpadLandscapeHeightPercent)
            .putInt("portrait_height_percent", touchpadPortraitHeightPercent)
            .putInt("portrait_width_percent", touchpadPortraitWidthPercent)
            .putBoolean("landscape_position_v2", true)
            .putBoolean("landscape_custom_position", touchpadLandscapeHasCustomPosition)
            .putInt("landscape_x_percent", touchpadLandscapeXPercent)
            .putInt("landscape_y_percent", touchpadLandscapeYPercent)
            .putBoolean("portrait_custom_position", touchpadPortraitHasCustomPosition)
            .putInt("portrait_x_percent", touchpadPortraitXPercent)
            .putInt("portrait_y_percent", touchpadPortraitYPercent)
            .apply()
    }

    private fun topBarHeight(): Int {
        val parent = status.parent as? View
        return parent?.height ?: dp(170)
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}