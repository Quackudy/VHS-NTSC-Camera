package dev.vhs.proof

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest as Q
import android.hardware.camera2.CaptureResult as R
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private val handler = Handler(Looper.getMainLooper())
    private val motion = ZoomMotion()
    private lateinit var texture: TextureView
    private lateinit var previewHost: FrameLayout
    private lateinit var effectImage: ImageView
    private lateinit var liveToggle: CheckBox
    private lateinit var liveStatus: TextView
    private var live: LivePreview? = null
    @Volatile private var previewRotation = 0
    private var preset = "{\"version\":1}"
    private var rememberedPreset: String? = null
    private lateinit var lensPicker: Spinner
    private lateinit var eis: CheckBox
    private lateinit var ois: CheckBox
    private lateinit var status: TextView
    private lateinit var metadata: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var recordButton: Button
    private lateinit var pauseButton: Button
    private lateinit var settingsButton: Button
    private lateinit var galleryButton: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var defaultZoomButton: Button
    private lateinit var recordState: TextView
    private lateinit var previewLabel: TextView
    private lateinit var presetLabel: TextView
    private var settingsDialog: AlertDialog? = null
    private val importWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val recordingTime = RecordingTime()
    private val preferences by lazy { getSharedPreferences("vhs", MODE_PRIVATE) }
    private lateinit var processButton: Button
    private var lenses = emptyList<CameraLens>()
    private var selected = 0
    private var resumed = false
    private var configuring = false
    private var closing = false
    private var generation = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var size = Size(640, 480)
    private var recorder: MediaRecorder? = null
    private var presetImporting = false
    private var recording = false
    private var pendingUri: Uri? = null
    private var outputFd: ParcelFileDescriptor? = null
    private var lastClip: Uri? = null
    private var zoomSpeed = 0.5
    private var lastTick = 0L
    private var lastMetadata = 0L
    private var loggedMetadata = ""
    private var currentCrop: android.graphics.Rect? = null
    private var notice: String? = null
    private val lens get() = lenses[selected]
    private val maxZoom get() = CameraGeometry.maxCropZoom(
        requireNotNull(lens.characteristics[C.SENSOR_INFO_ACTIVE_ARRAY_SIZE]),
        (lens.characteristics[C.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f).toDouble())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        lastClip = (state?.getString("lastClip") ?: preferences.getString("lastClip", null))?.let(Uri::parse)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(12) + insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    dp(12) + insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(column)
        column.requestApplyInsets()
        rememberedPreset = preferences.getString("preset", null)
        preset = intent.getStringExtra("preset") ?: rememberedPreset ?: preset
        zoomSpeed = preferences.getFloat("zoomSpeed", 0.5f).toDouble().takeIf { it.isFinite() }?.coerceIn(0.15, 1.0) ?: 0.5
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; column.addView(this) }
        TextView(this).apply {
            text = "VHS"; textSize = 22f
            top.addView(this, LinearLayout.LayoutParams(0, dp(56), 1f))
            gravity = Gravity.CENTER_VERTICAL
        }
        previewLabel = TextView(this).apply {
            text = "LIVE EFFECT · 4:3"; textSize = 12f
            top.addView(this)
        }
        settingsButton = Button(this).apply {
            text = "Settings"; tag = "settings"; minHeight = dp(48)
            top.addView(this)
            setOnClickListener { showSettings() }
        }
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val body = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            column.addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        previewHost = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            body.addView(this, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        body.addView(ScrollView(this).apply { addView(controls) },
            if (landscape) LinearLayout.LayoutParams(dp(280), -1) else LinearLayout.LayoutParams(-1, -2))
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8))
        }
        lensPicker = Spinner(this).also { settingsPanel.addView(it, LinearLayout.LayoutParams(-1, dp(56))) }
        texture = TextureView(this).apply {
            contentDescription = "Unprocessed camera preview. Pinch or use W and T to zoom."
            surfaceTextureListener = this@CameraActivity
            previewHost.addView(this)
        }
        effectImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Live ntsc-rs preview. Pinch or use W and T to zoom."
            visibility = View.INVISIBLE
            previewHost.addView(this)
        }
        previewHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (lenses.isEmpty() || session == null) return false
                stopZoom()
                motion.zoom = (motion.zoom * detector.scaleFactor).coerceIn(1.0, maxZoom)
                applyControls()
                return true
            }
        })
        val previewTouch = View.OnTouchListener { view, event ->
            pinch.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        texture.setOnTouchListener(previewTouch)
        effectImage.setOnTouchListener(previewTouch)
        recordState = TextView(this).apply {
            text = "READY"; textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xCC000000.toInt())
            previewHost.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        presetLabel = TextView(this).apply {
            text = if (preferences.contains("preset")) "Preset: imported ntsc-rs preset" else "Preset: ntsc-rs legacy defaults"
            settingsPanel.addView(this)
        }
        Button(this).apply {
            text = "Import ntsc-rs preset"; minHeight = dp(48); settingsPanel.addView(this)
            setOnClickListener {
                settingsDialog?.dismiss()
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, IMPORT_PRESET)
            }
        }
        liveToggle = CheckBox(this).apply {
            text = "Live ntsc-rs preview"; tag = "live"; minHeight = dp(48)
            isChecked = state?.getBoolean("live", true) ?: true
            settingsPanel.addView(this)
            setOnCheckedChangeListener { _, _ ->
                if (device != null && !configuring && !recording) {
                    try { createSession(false) } catch (error: Exception) { configuring = false; showError(error); refreshButtons() }
                }
            }
        }
        liveStatus = TextView(this).apply {
            text = "Live preview uses the remembered preset. Originals record without the effect."
            settingsPanel.addView(this)
        }
        metadata = TextView(this).apply {
            text = "Camera metadata will appear here."; textSize = 14f
            minHeight = dp(48); settingsPanel.addView(this)
        }
        val stabilizers = LinearLayout(this)
        settingsPanel.addView(stabilizers)
        eis = CheckBox(this).apply { minHeight = dp(48); stabilizers.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) }
        ois = CheckBox(this).apply { minHeight = dp(48); stabilizers.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) }
        eis.setOnCheckedChangeListener { _, _ -> applyControls(true) }
        ois.setOnCheckedChangeListener { _, _ -> applyControls(true) }
        val zoomRow = LinearLayout(this)
        controls.addView(zoomRow)
        fun zoomButton(text: String, direction: Int) = Button(this).apply {
            this.text = text
            contentDescription = if (direction < 0) "Zoom out. Hold for continuous zoom." else "Zoom in. Hold for continuous zoom."
            minHeight = dp(48)
            zoomRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            var touchClick = false
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { touchClick = false; startZoom(direction) }
                    MotionEvent.ACTION_MOVE -> if (event.x !in 0f..view.width.toFloat() || event.y !in 0f..view.height.toFloat()) motion.direction = 0
                    MotionEvent.ACTION_UP -> { motion.direction = 0; touchClick = view.isPressed }
                    MotionEvent.ACTION_CANCEL -> { motion.direction = 0; touchClick = false }
                }
                false
            }
            setOnClickListener {
                if (touchClick) touchClick = false
                else if (lenses.isNotEmpty()) {
                    stopZoom()
                    motion.zoom = (motion.zoom * 2.0.pow(direction * 0.1)).coerceIn(1.0, maxZoom)
                    applyControls()
                }
            }
        }
        zoomButton("W", -1)
        zoomLabel = TextView(this).apply {
            text = "1.00×"; gravity = Gravity.CENTER; minHeight = dp(48)
            zoomRow.addView(this, LinearLayout.LayoutParams(0, -1, 1f))
        }
        zoomButton("T", 1)
        defaultZoomButton = Button(this).apply {
            text = "Use current zoom as default"; minHeight = dp(48); tag = "defaultZoom"
            settingsPanel.addView(this)
            setOnClickListener {
                if (lenses.isNotEmpty()) {
                    preferences.edit().putFloat("defaultZoom.${lens.id}", motion.zoom.toFloat()).apply()
                    updateDefaultZoomLabel()
                }
            }
        }
        val speedRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; settingsPanel.addView(this) }
        val speedLabel = TextView(this).apply {
            text = String.format(Locale.US, "Zoom %.2f stops/s", zoomSpeed); speedRow.addView(this)
        }
        SeekBar(this).apply {
            max = 85; progress = (zoomSpeed * 100 - 15).roundToInt(); minHeight = dp(48); contentDescription = "Continuous zoom speed"
            speedRow.addView(this, LinearLayout.LayoutParams(0, dp(48), 1f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    zoomSpeed = (value + 15) / 100.0
                    if (fromUser) preferences.edit().putFloat("zoomSpeed", zoomSpeed.toFloat()).apply()
                    speedLabel.text = String.format(Locale.US, "Zoom %.2f stops/s", zoomSpeed)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        Button(this).apply {
            text = "Developer mode"; minHeight = dp(48); settingsPanel.addView(this)
            setOnClickListener {
                settingsDialog?.dismiss()
                startActivity(Intent(this@CameraActivity, MainActivity::class.java))
            }
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        controls.addView(actions)
        galleryButton = Button(this).apply {
            text = "Gallery"; minHeight = dp(48); isAllCaps = false; setPadding(dp(4), 0, dp(4), 0)
            actions.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(lastClip ?: MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: android.content.ActivityNotFoundException) { status.text = "Open Movies/VHS in your Gallery app." }
            }
        }
        recordButton = Button(this).apply {
            text = "Record"; tag = "record"; minHeight = dp(48)
            isAllCaps = false; setPadding(dp(4), 0, dp(4), 0)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFC62828.toInt()) }
            actions.addView(this, LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                marginStart = dp(8); marginEnd = dp(8)
            })
            setOnClickListener {
                if (!permissionsGranted()) requestPermissions(PERMISSIONS, 1)
                else if (recording) stopRecording() else startRecording()
            }
        }
        pauseButton = Button(this).apply {
            text = "Pause"; tag = "pause"; minHeight = dp(48); isEnabled = false
            isAllCaps = false; setPadding(dp(4), 0, dp(4), 0)
            actions.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { pauseOrResume() }
        }
        processButton = Button(this).apply {
            text = "Process last clip"; tag = "process"; minHeight = dp(48); isEnabled = false
            controls.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener {
                lastClip?.let { uri ->
                    if (callingActivity != null) {
                        setResult(RESULT_OK, Intent().setData(uri))
                        finish()
                    } else startActivity(Intent(this@CameraActivity, MainActivity::class.java)
                        .setAction(MainActivity.PROCESS_VIDEO).setData(uri).putExtra("preset", preset))
                }
            }
        }
        status = TextView(this).apply { text = "Checking cameras…"; textSize = 14f; controls.addView(this) }
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        try {
            lenses = CameraCatalog.rearLenses(this)
            require(lenses.isNotEmpty()) { "No rear camera is exposed by Android" }
            selected = lenses.indexOfFirst { it.id == state?.getString("lens") }.coerceAtLeast(0)
            lensPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lenses.map { it.label })
            lensPicker.setSelection(selected)
            lensPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (recording) return
                    if (position == selected && (device != null || configuring)) return
                    closeCamera()
                    selected = position
                    notice = null
                    configureLensControls()
                    openCamera()
                }
            }
            configureLensControls()
            state?.let {
                motion.zoom = CameraGeometry.startingZoom(it.getDouble("zoom", motion.zoom), maxZoom)
                eis.isChecked = it.getBoolean("eis", false) && eis.isEnabled
                ois.isChecked = it.getBoolean("ois", false) && ois.isEnabled
            }
            refreshButtons()
            metadata.setOnClickListener { AlertDialog.Builder(this).setTitle("Exposed camera capabilities")
                .setMessage(CameraCatalog.report(this)).setPositiveButton("OK", null).show() }
        } catch (error: Exception) { showError(error) }
        if (!permissionsGranted()) requestPermissions(PERMISSIONS, 1)
    }

    private fun showSettings() {
        updateDefaultZoomLabel()
        (settingsPanel.parent as? ViewGroup)?.removeView(settingsPanel)
        settingsDialog = AlertDialog.Builder(this).setTitle("Camera settings")
            .setView(ScrollView(this).apply { addView(settingsPanel) })
            .setPositiveButton("Done", null).show()
    }

    private fun updateDefaultZoomLabel() {
        if (lenses.isEmpty()) return
        val saved = CameraGeometry.startingZoom(preferences.getFloat("defaultZoom.${lens.id}", 1f).toDouble(), maxZoom)
        defaultZoomButton.text = String.format(Locale.US, "Set %.2f× as default · saved %.2f×", motion.zoom, saved)
    }

    @Deprecated("Platform result API keeps this app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_PRESET || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Importing preset…"
        presetImporting = true
        refreshButtons()
        importWorker.execute {
            try {
                val (json, remembered) = PresetStore.import(this, uri)
                handler.post {
                    if (!isDestroyed) {
                        preset = json
                        presetImporting = false
                        presetLabel.text = "Preset: imported ntsc-rs preset"
                        notice = if (remembered) "Preset imported. Originals stay clean; Process last clip saves the effect." else "Preset imported, but could not remember it for next launch."
                        status.text = notice
                        if (device != null && !recording && !configuring) {
                            try { createSession(false) } catch (error: Exception) { configuring = false; showError(error) }
                        }
                        refreshButtons()
                    }
                }
            } catch (error: Exception) {
                handler.post { if (!isDestroyed) { presetImporting = false; showError(error); refreshButtons() } }
            }
        }
    }

    private fun pauseOrResume() {
        if (!recording || configuring) return
        try {
            if (recordingTime.paused) {
                requireNotNull(recorder).resume()
                recordingTime.resume(SystemClock.elapsedRealtime())
                status.text = "Recording continues in the same video."
            } else {
                requireNotNull(recorder).pause()
                recordingTime.pause(SystemClock.elapsedRealtime())
                status.text = "Paused. Resume to continue this video, or Stop to save."
            }
            refreshButtons()
        } catch (error: Exception) { stopRecording("Pause/resume failed: ${error.message}") }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun permissionsGranted() = PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (permissionsGranted()) openCamera()
        else {
            status.text = "Camera and microphone permissions are required. Grant them in app permissions, then reopen camera."
            recordButton.text = "Grant permissions"
            recordButton.isEnabled = true
        }
    }

    private fun configureLensControls() {
        if (lenses.isEmpty()) return
        val c = lens.characteristics
        eis.isChecked = false; ois.isChecked = false
        eis.isEnabled = c[C.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.contains(Q.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        ois.isEnabled = c[C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.contains(Q.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        eis.text = if (eis.isEnabled) "EIS" else "EIS: OFF only"
        ois.text = if (ois.isEnabled) "OIS" else "OIS: OFF only"
        currentCrop = null
        motion.zoom = CameraGeometry.startingZoom(preferences.getFloat("defaultZoom.${lens.id}", 1f).toDouble(), maxZoom)
        zoomLabel.text = String.format(Locale.US, "%.2f×", motion.zoom)
        updateDefaultZoomLabel()
    }

    private fun openCamera() {
        if (!resumed || !texture.isAvailable || configuring || device != null || lenses.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            require(lens.physicalId == null) { "This prototype needs a directly exposed camera ID; physical stream routing needs device-specific testing" }
            size = lens.recordingSizes().firstOrNull() ?: error("This lens exposes no matching 4:3 preview/record stream size")
            texture.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
            fitPreview()
            configuring = true
            recordButton.isEnabled = false
            lensPicker.isEnabled = false
            status.text = "Opening rear camera ${lens.id}…"
            val token = ++generation
            getSystemService(CameraManager::class.java).openCamera(lens.openId, mainExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!resumed || token != generation) { camera.close(); return }
                    device = camera
                    try { createSession(false) } catch (error: Exception) { configuring = false; showError(error); refreshButtons() }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device == camera || token == generation) { closeCamera(); status.text = "Camera disconnected. Reopen the camera screen." }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device == camera || token == generation) { closeCamera(); status.text = "Camera error $error. Reopen the camera screen." }
                }
            })
        } catch (error: Exception) { configuring = false; showError(error); refreshButtons() }
    }

    private fun createSession(forRecording: Boolean) {
        val camera = device ?: return
        val token = ++generation
        configuring = true
        refreshButtons()
        session?.close(); session = null
        if (live == null) previewSurface?.release()
        live?.close(); live = null
        effectImage.visibility = View.INVISIBLE
        effectImage.setImageDrawable(null)
        texture.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
        texture.alpha = if (liveToggle.isChecked) 0f else 1f
        previewLabel.text = if (liveToggle.isChecked) "LIVE EFFECT · 4:3" else "ORIGINAL · 4:3"
        val preview = if (liveToggle.isChecked) {
            Native.validatePreset(preset)
            val map = requireNotNull(lens.characteristics[C.SCALER_STREAM_CONFIGURATION_MAP])
            val liveSize = map.getOutputSizes(ImageFormat.YUV_420_888)?.filter {
                it.width * 3 == it.height * 4 && it.width in 320..640 && it.height <= 480
            }?.minByOrNull { it.width * it.height }
            requireNotNull(liveSize) { "No supported 320–640 pixel 4:3 YUV stream. Disable live preview to record." }
            liveStatus.text = "Starting live ntsc-rs · $liveSize. Originals remain unprocessed."
            LivePreview(liveSize, preset, { previewRotation }, { bitmap, _, stats ->
                if (token == generation && resumed) {
                    effectImage.setImageBitmap(bitmap)
                    effectImage.visibility = View.VISIBLE
                    if (stats != null) liveStatus.text = "$stats\nOriginals record without the effect."
                } else bitmap.recycle()
            }, { error ->
                if (token == generation) {
                    val reason = "Live preview failed: ${error.message}"
                    notice = reason
                    liveToggle.isChecked = false
                    if (recording) stopRecording(reason)
                    liveStatus.text = "$reason. Switched to unprocessed preview."
                }
            }).also { live = it }.reader.surface
        } else {
            liveStatus.text = "Unprocessed preview. Originals record without the effect."
            Surface(requireNotNull(texture.surfaceTexture))
        }
        previewSurface = preview
        val outputs = if (forRecording) listOf(preview, requireNotNull(recorder).surface) else listOf(preview)
        camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(created: CameraCaptureSession) {
                if (!resumed || token != generation) { created.close(); return }
                session = created
                configuring = false
                try {
                    check(applyControls(true, forRecording)) { "Camera rejected the capture controls" }
                    if (forRecording) {
                        requireNotNull(recorder).start()
                        recording = true
                        recordingTime.start(SystemClock.elapsedRealtime())
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        handler.post(recordTimer)
                        status.text = "Recording with audio. Original stays clean; process after shooting."
                    } else status.text = notice ?: "${if (liveToggle.isChecked) "Live effect" else "Original"} preview. Originals record clean; process after shooting."
                    refreshButtons()
                } catch (error: Exception) {
                    abortPreparedRecording()
                    session?.close(); session = null
                    showError(error); refreshButtons()
                }
            }
            override fun onConfigureFailed(failed: CameraCaptureSession) {
                failed.close()
                if (token != generation) return
                configuring = false
                abortPreparedRecording()
                status.text = "This lens rejected the stream combination. Disable live preview or choose another lens."
                refreshButtons()
            }
        }, handler)
    }

    private fun applyControls(force: Boolean = false, recordingTarget: Boolean = recording): Boolean {
        if (lenses.isEmpty()) return false
        zoomLabel.text = String.format(Locale.US, "%.2f×", motion.zoom)
        val active = lens.characteristics[C.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return false
        val crop = CameraGeometry.crop(active, motion.zoom)
        val camera = device ?: return false
        val captureSession = session ?: return false
        if (!force && crop == currentCrop) return true
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(requireNotNull(previewSurface))
                if (recordingTarget) addTarget(requireNotNull(recorder).surface)
                set(Q.CONTROL_MODE, Q.CONTROL_MODE_AUTO)
                val af = lens.characteristics[C.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
                set(Q.CONTROL_AF_MODE, when {
                    Q.CONTROL_AF_MODE_CONTINUOUS_VIDEO in af -> Q.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    Q.CONTROL_AF_MODE_CONTINUOUS_PICTURE in af -> Q.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    else -> Q.CONTROL_AF_MODE_OFF
                })
                lens.characteristics[C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.firstOrNull { it.lower == 30 && it.upper == 30 }
                    ?.let { set(Q.CONTROL_AE_TARGET_FPS_RANGE, it) }
                set(Q.SCALER_CROP_REGION, crop)
                set(Q.CONTROL_VIDEO_STABILIZATION_MODE, if (eis.isChecked && eis.isEnabled) Q.CONTROL_VIDEO_STABILIZATION_MODE_ON else Q.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                set(Q.LENS_OPTICAL_STABILIZATION_MODE, if (ois.isChecked && ois.isEnabled) Q.LENS_OPTICAL_STABILIZATION_MODE_ON else Q.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                if (Build.VERSION.SDK_INT >= 31 && lens.characteristics[C.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES]?.contains(Q.SCALER_ROTATE_AND_CROP_NONE) == true) {
                    set(Q.SCALER_ROTATE_AND_CROP, Q.SCALER_ROTATE_AND_CROP_NONE)
                }
            }
            captureSession.setRepeatingRequest(request.build(), captureCallback, handler)
            currentCrop = crop
            return true
        } catch (error: Exception) { showError(error); return false }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(s: CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
            if (s == session) status.text = "A camera frame failed (reason ${failure.reason}). Check the recording for missing frames."
        }
        override fun onCaptureCompleted(s: CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: TotalCaptureResult) {
            if (s != session || SystemClock.elapsedRealtime() - lastMetadata < 500) return
            lastMetadata = SystemClock.elapsedRealtime()
            val report = "ID ${lens.id} · focal ${result[R.LENS_FOCAL_LENGTH] ?: "not reported"} mm · crop ${result[R.SCALER_CROP_REGION]}\n" +
                "Reported EIS=${result[R.CONTROL_VIDEO_STABILIZATION_MODE] ?: "unknown"}, OIS=${result[R.LENS_OPTICAL_STABILIZATION_MODE] ?: "unknown"} (0=OFF, 1=ON)"
            metadata.text = "Rear ${lens.id} · ${result[R.LENS_FOCAL_LENGTH] ?: "unknown"} mm\n" +
                "EIS=${result[R.CONTROL_VIDEO_STABILIZATION_MODE] ?: "?"}, OIS=${result[R.LENS_OPTICAL_STABILIZATION_MODE] ?: "?"} (0=OFF, 1=ON) · tap for camera info"
            if (report != loggedMetadata) { Log.i("VhsCamera", report); loggedMetadata = report }
        }
    }

    private fun startRecording() {
        if (device == null || configuring || recorder != null) return
        try {
            notice = null
            status.text = "Preparing recording…"
            recordButton.isEnabled = false; lensPicker.isEnabled = false; processButton.isEnabled = false
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "VHS-original-${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VHS")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }) ?: error("Cannot create original recording in MediaStore")
            pendingUri = uri
            val fd = contentResolver.openFileDescriptor(uri, "rw") ?: error("Cannot open original recording")
            outputFd = fd
            val mediaRecorder = MediaRecorder()
            recorder = mediaRecorder
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fd.fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(size.width, size.height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(3_000_000)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(128_000)
                setOrientationHint(CameraGeometry.rotation(lens.characteristics[C.SENSOR_ORIENTATION] ?: 90, displayDegrees()))
                setOnErrorListener { _, what, extra -> stopRecording("Recorder error $what/$extra") }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stopRecording("File-size limit reached")
                }
                prepare()
            }
            createSession(true)
        } catch (error: Exception) {
            abortPreparedRecording()
            configuring = false
            showError(error)
            refreshButtons()
        }
    }

    private fun stopRecording(reason: String? = null) {
        if (!recording) { abortPreparedRecording(); return }
        stopZoom()
        recording = false
        handler.removeCallbacks(recordTimer)
        val uri = pendingUri
        try {
            runCatching { session?.stopRepeating(); session?.abortCaptures() }
            requireNotNull(recorder).stop()
            pendingUri = null
            lastClip = uri
            runCatching { recorder?.release() }; recorder = null
            runCatching { outputFd?.close() }; outputFd = null
            if (uri != null) {
                check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) > 0) {
                    "Original retained but could not be published: $uri"
                }
                preferences.edit().putString("lastClip", uri.toString()).apply()
            }
            notice = "${reason?.plus(". ") ?: ""}Original saved to Movies/VHS. Process last clip to apply your preset; the original stays available."
            status.text = notice
        } catch (error: Exception) {
            if (pendingUri != null) abortPreparedRecording()
            showError(error)
        } finally {
            runCatching { recorder?.release() }; recorder = null
            runCatching { outputFd?.close() }; outputFd = null
            session?.close(); session = null
            currentCrop = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (!resumed) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (resumed && !closing && device != null && texture.isAvailable) {
                try { createSession(false) } catch (error: Exception) { configuring = false; showError(error) }
            }
            refreshButtons()
        }
    }

    private fun abortPreparedRecording() {
        runCatching { recorder?.release() }; recorder = null
        runCatching { outputFd?.close() }; outputFd = null
        pendingUri?.let { runCatching { contentResolver.delete(it, null, null) } }
        pendingUri = null
    }

    private fun refreshButtons() {
        settingsButton.isEnabled = !recording && !configuring && !presetImporting
        galleryButton.isEnabled = !recording && !configuring
        pauseButton.isEnabled = recording && !configuring
        pauseButton.text = if (recording && recordingTime.paused) "Resume" else "Pause"
        if (!recording) recordState.text = "READY"
        liveToggle.isEnabled = !recording && !configuring
        lensPicker.isEnabled = !recording && !configuring
        recordButton.isEnabled = !configuring && !presetImporting && device != null
        recordButton.text = if (recording) "Stop" else "Record"
        processButton.isEnabled = !recording && !configuring && lastClip != null
    }

    private val recordTimer = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = recordingTime.elapsed(SystemClock.elapsedRealtime()) / 1000
            recordState.text = String.format(Locale.US, "%s %02d:%02d", if (recordingTime.paused) "PAUSED" else "REC", seconds / 60, seconds % 60)
            handler.postDelayed(this, 250)
        }
    }
    private val zoomTick = object : Runnable {
        override fun run() {
            if (!resumed || lenses.isEmpty()) return
            val now = SystemClock.elapsedRealtimeNanos()
            motion.step(((now - lastTick) / 1_000_000_000.0).coerceIn(0.0, 0.1), zoomSpeed, maxZoom)
            lastTick = now
            applyControls()
            if (motion.direction != 0 || motion.velocity != 0.0) handler.postDelayed(this, 33)
        }
    }
    private fun startZoom(direction: Int) {
        if (lenses.isEmpty() || session == null) return
        motion.direction = direction
        lastTick = SystemClock.elapsedRealtimeNanos()
        handler.removeCallbacks(zoomTick)
        handler.post(zoomTick)
    }
    private fun stopZoom() {
        motion.direction = 0; motion.velocity = 0.0
        handler.removeCallbacks(zoomTick)
    }

    private fun displayDegrees() = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }
    private fun fitPreview() {
        if (lenses.isEmpty() || previewHost.width == 0 || previewHost.height == 0) return
        previewRotation = CameraGeometry.rotation(lens.characteristics[C.SENSOR_ORIENTATION] ?: 90, displayDegrees())
        val rotated = previewRotation % 180 != 0
        val ratio = if (rotated) size.height.toDouble() / size.width else size.width.toDouble() / size.height
        val width = min(previewHost.width, (previewHost.height * ratio).roundToInt()).coerceAtLeast(1)
        val height = (width / ratio).roundToInt().coerceAtLeast(1)
        // Avoid requesting another layout from every layout callback.
        if (texture.layoutParams.width != width || texture.layoutParams.height != height) {
            texture.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
        if (effectImage.layoutParams.width != width || effectImage.layoutParams.height != height) {
            effectImage.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
        texture.setTransform(CameraGeometry.previewTransform(width, height, displayDegrees()))
    }
    private fun showError(error: Exception) {
        notice = "Camera: ${error.message}. Your existing saved clips are retained."
        status.text = notice
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { if (texture.display?.displayId == id) fitPreview() }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        val latestPreset = preferences.getString("preset", null)
        if (latestPreset != rememberedPreset) {
            preset = latestPreset ?: "{\"version\":1}"
            rememberedPreset = latestPreset
        }
        presetLabel.text = if (preferences.contains("preset")) "Preset: imported ntsc-rs preset" else "Preset: ntsc-rs legacy defaults"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        openCamera()
    }
    override fun onPause() {
        resumed = false
        settingsDialog?.dismiss()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        closeCamera(); super.onPause()
    }
    private fun closeCamera() {
        closing = true
        stopZoom()
        if (recording) stopRecording() else abortPreparedRecording()
        ++generation
        session?.close(); session = null
        device?.close(); device = null
        if (live == null) previewSurface?.release()
        live?.close(); live = null
        previewSurface = null
        effectImage.visibility = View.INVISIBLE
        effectImage.setImageDrawable(null)
        currentCrop = null; configuring = false
        closing = false
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { fitPreview(); openCamera() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { fitPreview() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("lastClip", lastClip?.toString())
        if (lenses.isNotEmpty()) out.putString("lens", lens.id)
        out.putDouble("zoom", motion.zoom)
        out.putBoolean("live", liveToggle.isChecked)
        out.putBoolean("eis", eis.isChecked)
        out.putBoolean("ois", ois.isChecked)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        importWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val IMPORT_PRESET = 1
        private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
