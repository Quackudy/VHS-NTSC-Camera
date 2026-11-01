package dev.vhs.proof

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    // shortcut: exports belong to this screen; add a foreground job when background export is needed.
    private val worker = Executors.newSingleThreadExecutor()
    private var preset = PresetStore.DEFAULT_JSON
    private lateinit var source: Bitmap
    private var processed: Bitmap? = null
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var presetLabel: TextView
    private lateinit var showEffect: Switch
    private val buttons = mutableListOf<Button>()
    private lateinit var saveButton: Button
    private lateinit var openVideoButton: Button
    private lateinit var exportStatus: TextView
    private lateinit var exportProgress: ProgressBar
    private lateinit var cancelExport: Button
    @Volatile private var cancelRequested = false
    private var exporting = false
    private var savedVideo: Uri? = null
    private var busy = false
    private var importErrorDialog: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("vhs", MODE_PRIVATE)
        preset = (if (intent.action == PROCESS_VIDEO) intent.getStringExtra("preset") else null)
            ?: preferences.getString("preset", preset) ?: preset
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(16)
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        val developerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (intent.action == PROCESS_VIDEO) View.GONE else View.VISIBLE
            column.addView(this)
        }
        var section = developerPanel
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, dp(8), 0, dp(8))
            section.addView(this)
        }
        label("Developer mode", 24f)
        label("Camera and processing · all processing stays on this phone")
        presetLabel = label(PresetStore.label(this))
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            minHeight = dp(48)
            setOnClickListener { action() }
            section.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            buttons.add(this)
        }
        button("Import ntsc-rs preset") { openDocument(IMPORT_PRESET, "*/*") }
        button("Reset preset to default") {
            background("Resetting preset…") {
                val result = PresetStore.reset(this)
                onUi {
                    preset = result.json
                    presetLabel.text = "Preset: ${result.name}"
                    clearResult()
                    status.text = if (result.remembered) "Preset reset to default." else "Default preset active, but saving the reset failed."
                }
            }
        }.tag = "resetPreset"
        button("Open camera · live ntsc-rs preview") {
            startActivityForResult(Intent(this, CameraActivity::class.java).putExtra("preset", preset), IMPORT_VIDEO)
        }
        button("Choose test image") { openDocument(IMPORT_IMAGE, "image/*") }
        button("Process and measure 10 frames") { measure() }
        showEffect = Switch(this).apply {
            text = "Show processed image"
            minHeight = dp(48)
            isEnabled = false
            setOnCheckedChangeListener { _, checked ->
                image.setImageBitmap(if (checked) processed else source)
            }
            section.addView(this)
        }
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Test image; switch between source and ntsc-rs result above"
            section.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        status = label("Ready. Import a preset, or test the upstream legacy defaults.")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        saveButton = button("Save processed PNG") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/png"
                putExtra(Intent.EXTRA_TITLE, "vhs-test.png")
            }, SAVE_IMAGE)
        }.apply { isEnabled = false }
        section = column
        label(if (intent.action == PROCESS_VIDEO) "Process recording" else "Video export", 20f)
        label(if (intent.action == PROCESS_VIDEO) "Make a VHS copy of your recording. The original stays in Gallery. Keep this screen open until saving finishes." else
            "SDR video with AAC audio or no audio. Exports save automatically to Gallery → Movies/VHS. " +
                "Aspect ratio is preserved within 640 × 480. Keep this screen open until export finishes.")
        button("Choose and process video") { openDocument(IMPORT_VIDEO, "video/*") }
            .visibility = if (intent.action == PROCESS_VIDEO) View.GONE else View.VISIBLE
        exportProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            contentDescription = "Video export progress"
            column.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        exportStatus = label("Processed videos save to Gallery automatically. Originals stay available.")
        exportStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        cancelExport = button("Cancel export") {
            cancelRequested = true
            cancelExport.isEnabled = false
            exportStatus.text = "Cancelling export… Original video is kept."
        }.apply { visibility = View.GONE; tag = "cancelExport" }
        openVideoButton = button("Open saved video") {
            savedVideo?.let { uri ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: android.content.ActivityNotFoundException) {
                    exportStatus.text = "Video saved in Gallery. No video player is installed to open it."
                }
            }
        }.apply { isEnabled = false }
        if (intent.action == PROCESS_VIDEO) {
            button("Back to camera") { finish() }
        }
        source = testPattern()
        image.setImageBitmap(source)
        if (savedInstanceState == null && intent.action == PROCESS_VIDEO) {
            intent.data?.let { processVideo(it) }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun openDocument(code: Int, mime: String) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }, code)
    }

    @Deprecated("Platform activity result API keeps this proof dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            IMPORT_PRESET -> background("Reading preset…", presetImport = true) {
                val (text, remembered, name, warnings) = PresetStore.import(this, uri)
                onUi {
                    preset = text
                    presetLabel.text = "Preset: $name${if (remembered) "" else " (not remembered)"}${if (warnings.isBlank()) "" else "\n$warnings"}"
                    if (warnings.isNotBlank() && !isFinishing) {
                        importErrorDialog?.dismiss()
                        importErrorDialog = android.app.AlertDialog.Builder(this).setTitle("Preset loaded with compatibility notice")
                            .setMessage("$name\n\n$warnings\n\nAll other settings are loaded.")
                            .setPositiveButton("OK", null).show()
                    }
                    clearResult()
                    status.text = if (remembered) "Preset accepted and saved for future launches." else "Preset accepted, but saving the selection failed. It may reset after restart."
                }
            }
            IMPORT_IMAGE -> background("Decoding image…") {
                val bitmap = decodeImage(uri)
                onUi {
                    source = bitmap
                    clearResult()
                    image.setImageBitmap(source)
                    status.text = "Image loaded at ${source.width} × ${source.height}; aspect ratio preserved."
                }
            }
            SAVE_IMAGE -> {
                val bitmap = processed ?: return
                background("Saving PNG…") {
                    contentResolver.openOutputStream(uri, "w")?.use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed" }
                    } ?: error("Cannot open destination")
                    onUi { status.text = "Processed PNG saved. Source image was not changed." }
                }
            }
            IMPORT_VIDEO -> processVideo(uri)
        }
    }

    private fun processVideo(uri: Uri) {
        val selectedPreset = preset
        background("Preparing video export…", videoExport = true) {
            val result = VideoProcessor.process(this, uri, selectedPreset, isCancelled = { cancelRequested }) { progress ->
                onUi {
                    if (!cancelRequested) {
                        val duration = progress.durationUs
                        exportProgress.isIndeterminate = progress.phase != VideoPhase.VIDEO || duration == null
                        val percent = duration?.let { (progress.positionUs.toDouble() / it * 100).coerceIn(0.0, 100.0) }
                        if (percent != null) exportProgress.progress = (percent * 10).roundToInt()
                        exportStatus.text = when (progress.phase) {
                            VideoPhase.VIDEO -> if (percent == null) "Processing video · ${progress.frames} frames" else
                                String.format(Locale.US, "Processing video: %.1f%% · %d frames\nAudio copy and finalization follow.", percent, progress.frames)
                            VideoPhase.AUDIO -> "Video processed. Copying audio…"
                            VideoPhase.FINALIZING -> "Finalizing processed video…"
                        }
                    }
                }
            }
            try {
                onUi { if (!cancelRequested) { exportProgress.isIndeterminate = true; exportStatus.text = "Saving processed video to Gallery…" } }
                val galleryUri = GalleryVideos.saveProcessed(this, result.file) { cancelRequested }
                onUi {
                    savedVideo = galleryUri
                    exportStatus.text = String.format(Locale.US,
                        "Saved to Gallery → Movies/VHS. Original video kept.\n" +
                        "%d × %d · %d video frames in %.2f seconds\nMean total: %.1f ms/frame\n" +
                            "YUV → RGB: %.1f ms/frame\nntsc-rs + JNI: %.1f ms/frame\nRGB → YUV: %.1f ms/frame\n" +
                            "Codecs/setup/audio/other: %.1f ms/frame\n" +
                            "Means include first-frame work. Other includes asynchronous codec waits.\n%s\n%s\n" +
                            "Processing timings exclude Gallery save. Tap Open saved video.",
                        result.width, result.height, result.frames, result.elapsedMs / 1000.0,
                        result.elapsedMs.toDouble() / result.frames,
                        result.toRgbNs / 1_000_000.0 / result.frames,
                        result.effectNs / 1_000_000.0 / result.frames,
                        result.toYuvNs / 1_000_000.0 / result.frames,
                        (result.elapsedMs - (result.toRgbNs + result.effectNs + result.toYuvNs) / 1_000_000.0) / result.frames,
                        if (result.audioCopied) "AAC audio copied with original timestamps" else "Source has no audio track",
                        if (result.assumedColor) "Missing color metadata: assumed BT.601 / limited range where unspecified" else "Source color matrix and range read from decoder")
                    if (intent.action == PROCESS_VIDEO) exportStatus.text = "VHS copy saved to Gallery → Movies/VHS.\nYour original is kept."
                }
            } finally {
                result.file.delete()
            }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(contentResolver, uri)
    ) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        val scale = min(1.0, min(640.0 / info.size.width, 480.0 / info.size.height))
        val width = (info.size.width * scale).roundToInt()
        val height = (info.size.height * scale).roundToInt()
        require(width >= 2 && height >= 2) { "Image is too narrow or too short" }
        decoder.setTargetSize(width, height)
    }

    private fun clearResult() {
        showEffect.isChecked = false
        processed = null
        showEffect.isEnabled = false
        saveButton.isEnabled = false
    }

    private fun measure() {
        val bitmap = source
        val selectedPreset = preset
        background("Warming up ntsc-rs, then measuring 10 frames…") {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            Native.process(pixels, bitmap.width, bitmap.height, 0, selectedPreset)
            val timings = DoubleArray(10)
            var output = pixels
            for (frame in timings.indices) {
                val start = SystemClock.elapsedRealtimeNanos()
                output = Native.process(pixels, bitmap.width, bitmap.height, frame, selectedPreset)
                timings[frame] = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }
            val result = Bitmap.createBitmap(output, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val mean = timings.average()
            val report = String.format(Locale.US,
                "%d × %d · 10 frames after warm-up\nMean %.1f ms · min %.1f · max %.1f\n" +
                    "%.1f frames/s equivalent (image test only)\nIncludes preset parsing, allocation and JNI copies. " +
                    "Excludes video codecs; this is not sustained camera performance. Showing frame 9.",
                bitmap.width, bitmap.height, mean, timings.min(), timings.max(), 1000.0 / mean)
            onUi {
                processed = result
                showEffect.isChecked = true
                image.setImageBitmap(result)
                status.text = report
            }
        }
    }

    private fun showTaskStatus(message: String) {
        if (exporting) exportStatus.text = message else status.text = message
    }

    private fun background(message: String, videoExport: Boolean = false, presetImport: Boolean = false, task: () -> Unit) {
        if (busy) return
        busy = true
        exporting = videoExport
        cancelRequested = false
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setControls(false)
        showTaskStatus(message)
        if (videoExport) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            exportProgress.visibility = View.VISIBLE
            exportProgress.isIndeterminate = true
            cancelExport.visibility = View.VISIBLE
            cancelExport.isEnabled = true
            cancelExport.post { cancelExport.requestRectangleOnScreen(android.graphics.Rect(0, 0, cancelExport.width, cancelExport.height), true) }
        }
        worker.execute {
            try {
                task()
            } catch (_: CancellationException) {
                onUi { showTaskStatus("Export cancelled. Original video kept.") }
            } catch (e: Exception) {
                onUi {
                    showTaskStatus("Failed: ${e.message}. Original files are kept. Try again or choose another file.")
                    if (presetImport && !isFinishing) {
                        importErrorDialog?.dismiss()
                        importErrorDialog = android.app.AlertDialog.Builder(this).setTitle("Preset not imported")
                            .setMessage("${e.message}\n\nYour previous preset is still selected.")
                            .setPositiveButton("OK", null).show()
                    }
                }
            } catch (e: LinkageError) {
                onUi { showTaskStatus("Rust library could not load: ${e.message}. Rebuild for this phone's ABI.") }
            } finally {
                onUi {
                    busy = false
                    exporting = false
                    cancelExport.visibility = View.GONE
                    exportProgress.visibility = View.GONE
                    if (videoExport) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setControls(true)
                }
            }
        }
    }

    private fun setControls(enabled: Boolean) {
        buttons.forEach { it.isEnabled = enabled }
        saveButton.isEnabled = enabled && processed != null
        showEffect.isEnabled = enabled && processed != null
        openVideoButton.isEnabled = enabled && savedVideo != null
    }

    private fun onUi(action: () -> Unit) = runOnUiThread { if (!isDestroyed) action() }

    override fun onDestroy() {
        importErrorDialog?.dismiss()
        worker.shutdownNow()
        cancelRequested = true
        super.onDestroy()
    }

    private fun testPattern(): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val colors = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN,
            Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK)
        colors.forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(index * 80f, 0f, (index + 1) * 80f, 280f, paint)
        }
        for (x in 0 until 640) {
            val level = x * 255 / 639
            paint.color = Color.rgb(level, level, level)
            canvas.drawRect(x.toFloat(), 280f, x + 1f, 380f, paint)
            paint.color = if (x % 8 < 4) Color.WHITE else Color.BLACK
            canvas.drawRect(x.toFloat(), 380f, x + 1f, 430f, paint)
        }
        paint.color = Color.BLACK
        canvas.drawRect(0f, 430f, 640f, 480f, paint)
        paint.color = Color.WHITE
        paint.textSize = 22f
        canvas.drawText("Synthetic test pattern · 640 × 480", 16f, 463f, paint)
        return bitmap
    }

    companion object {
        internal const val PROCESS_VIDEO = "dev.vhs.proof.PROCESS_VIDEO"
        private const val IMPORT_PRESET = 1
        private const val IMPORT_IMAGE = 2
        private const val SAVE_IMAGE = 3
        private const val IMPORT_VIDEO = 4
    }
}
