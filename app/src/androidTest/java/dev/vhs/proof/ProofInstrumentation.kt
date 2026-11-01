package dev.vhs.proof

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Default suite uses synthetic fixtures; cameraRecord=true is an explicitly approved hardware test. */
class ProofInstrumentation : Instrumentation() {
    private var probeOnly = false
    private var cameraRecord = false
    private var longExport = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        probeOnly = arguments?.getString("cameraProbe") == "true"
        cameraRecord = arguments?.getString("cameraRecord") == "true"
        longExport = arguments?.getString("longExport") == "true"
        start()
    }

    override fun onStart() {
        try {
            if (longExport) {
                val report = checkLongExport()
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: $report\n") })
                return
            }
            if (cameraRecord) {
                val report = checkCameraRecording()
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: $report\n") })
                return
            }
            if (probeOnly) {
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", CameraCatalog.report(targetContext)) })
                return
            }
            checkYuv()
            checkNativeConversionMatchesReference()
            checkCameraGeometry()
            checkLivePreview()
            RecorderPauseCheck.run(targetContext)
            for (name in listOf("clip.mp4", "rotated.mp4", "offset.mp4", "silent.mp4")) checkClip(name)
            checkExportWorkflow()
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: YUV/reference equality; camera geometry/zoom; video frames/timestamps/AAC; preset persistence; UI automatic Gallery save; cancellation and pending/cache cleanup; originals retained.\n")
            })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.stackTraceToString()}\n") })
        }
    }

    private fun checkLivePreview() {
        check(LivePreview.frameNumber(1_000_000_000, 1_033_333_333) == 1)
        check(LivePreview.frameNumber(1_000_000_000, 1_100_000_000) == 3)
        check(LivePreview.frameNumber(1_000_000_000, 999_000_000) == 0)
        for (rotation in listOf(0, 90, 180, 270)) {
            val done = java.util.concurrent.CountDownLatch(1)
            val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val live = LivePreview(android.util.Size(320, 240), "{\"version\":1}", { rotation }, { bitmap, angle, _ ->
                try {
                    check(angle == rotation)
                    check(bitmap.width == if (rotation % 180 == 0) 320 else 240)
                    check(bitmap.height == if (rotation % 180 == 0) 240 else 320)
                    check(bitmap.getPixel(0, 0) ushr 24 == 255)
                } catch (failure: Throwable) { error.set(failure) }
                finally { bitmap.recycle(); done.countDown() }
            }, { failure -> error.set(failure); done.countDown() })
            try {
                android.media.ImageWriter.newInstance(live.reader.surface, 2).use { writer ->
                    val image = writer.dequeueInputImage()
                    Yuv.fromRgb(IntArray(320 * 240) { 0xff8899aa.toInt() }, 320, 240,
                        image.planes.map { Plane(it.buffer, it.rowStride, it.pixelStride) })
                    image.timestamp = 1_000_000_000
                    writer.queueInputImage(image)
                    check(done.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Live frame timeout, rotation $rotation" }
                    error.get()?.let { throw it }
                }
            } finally { live.close(); live.close() }
        }
    }

    private fun checkYuv() {
        val y = ByteBuffer.allocateDirect(32).apply { position(2) }
        val u = ByteBuffer.allocateDirect(16).apply { position(1) }
        val v = ByteBuffer.allocateDirect(16).apply { position(2) }
        val planes = listOf(Plane(y, 8, 1), Plane(u, 8, 2), Plane(v, 8, 2))
        val red = IntArray(16) { 0xffff0000.toInt() }
        Yuv.fromRgb(red, 4, 4, planes)
        val restored = Yuv.toRgb(planes, 0, 0, 4, 4, 4, 4, 0, false, false)
        check(restored.all { ((it shr 16) and 255) >= 250 && ((it shr 8) and 255) <= 4 && (it and 255) <= 4 })
        val luma = intArrayOf(16, 32, 64, 96, 128, 160, 192, 235)
        val ys = Plane(ByteBuffer.allocateDirect(40).apply { position(3) }, 8, 1)
        val neutral = Plane(ByteBuffer.allocateDirect(20), 8, 2)
        for (row in 0..1) for (col in 0..3) ys.buffer.put(3 + row * 8 + col, luma[row * 4 + col].toByte())
        for (col in 0..1) neutral.buffer.put(col * 2, 128.toByte())
        for (rotation in listOf(0, 90, 180, 270)) {
            val w = if (rotation % 180 == 0) 4 else 2
            val h = if (rotation % 180 == 0) 2 else 4
            val rgb = Yuv.toRgb(listOf(ys, neutral, neutral), 0, 0, 4, 2, w, h, rotation, true, false)
            val order = when (rotation) {
                90 -> intArrayOf(4, 0, 5, 1, 6, 2, 7, 3)
                180 -> intArrayOf(7, 6, 5, 4, 3, 2, 1, 0)
                270 -> intArrayOf(3, 7, 2, 6, 1, 5, 0, 4)
                else -> IntArray(8) { it }
            }
            rgb.forEachIndexed { i, color ->
                check(abs((color and 255) - (luma[order[i]] - 16) * 255.0 / 219) <= 1)
            }
        }
    }

    private fun checkCameraGeometry() {
        check(CameraGeometry.startingZoom(2.5, 8.0) == 2.5)
        check(CameraGeometry.startingZoom(20.0, 8.0) == 8.0)
        check(CameraGeometry.startingZoom(0.5, 8.0) == 1.0)
        check(CameraGeometry.startingZoom(Double.NaN, 8.0) == 1.0)
        val clock = RecordingTime()
        clock.start(100)
        check(clock.elapsed(1100) == 1000L)
        clock.pause(1100)
        check(clock.elapsed(3100) == 1000L && clock.paused)
        clock.resume(3100)
        check(clock.elapsed(4100) == 2000L && !clock.paused)
        clock.pause(4100)
        check(clock.elapsed(5100) == 2000L)
        clock.resume(5100)
        check(clock.elapsed(6100) == 3000L)
        clock.start(10000)
        check(clock.elapsed(11000) == 1000L && !clock.paused)

        for (active in listOf(android.graphics.Rect(0, 0, 3264, 2448), android.graphics.Rect(0, 0, 4080, 3060),
            android.graphics.Rect(10, 20, 4010, 2270))) {
            val max = CameraGeometry.maxCropZoom(active, 8.0)
            for (zoom in listOf(1.0, 1.3, 2.0, max)) {
                val crop = CameraGeometry.crop(active, zoom)
                check(active.contains(crop) && crop.width() * 3 == crop.height() * 4)
                check(active.width().toDouble() / crop.width() <= 8.0 && active.height().toDouble() / crop.height() <= 8.0)
                check(abs(crop.centerX() - active.centerX()) <= 1 && abs(crop.centerY() - active.centerY()) <= 1)
            }
        }
        check(CameraGeometry.rotation(90, 0) == 90)
        check(CameraGeometry.rotation(90, 90) == 0)
        check(CameraGeometry.rotation(90, 270) == 180)
        for (rotation in listOf(0, 90, 180, 270)) {
            val rect = android.graphics.RectF(0f, 0f, 400f, 300f)
            CameraGeometry.previewTransform(400, 300, rotation).mapRect(rect)
            check(abs(rect.left) < 0.01 && abs(rect.top) < 0.01 && abs(rect.right - 400) < 0.01 && abs(rect.bottom - 300) < 0.01)
        }
        val motion = ZoomMotion().apply { direction = 1 }
        motion.step(1.0 / 30, 0.5, 8.0)
        check(motion.velocity in 0.0..0.5 && motion.zoom in 1.0..1.01)
        repeat(300) { motion.step(1.0 / 30, 0.5, 8.0) }
        check(motion.zoom == 8.0)
        motion.direction = -1
        repeat(15) { motion.step(1.0 / 30, 0.5, 8.0) }
        val beforeRelease = motion.zoom
        motion.direction = 0
        repeat(15) { motion.step(1.0 / 30, 0.5, 8.0) }
        check(motion.velocity == 0.0 && motion.zoom < beforeRelease)
        motion.direction = -1
        repeat(400) { motion.step(1.0 / 30, 1.0, 8.0) }
        check(motion.zoom == 1.0)
    }

    private fun checkNativeConversionMatchesReference() {
        fun paddedPlanes(): List<Plane> {
            val y = ByteBuffer.allocateDirect(43).apply { position(3); limit(39) }
            val uv = ByteBuffer.allocateDirect(20)
            val planes = listOf(Plane(y, 10, 1),
                Plane(uv.duplicate().apply { position(1); limit(19) }, 8, 2),
                Plane(uv.duplicate().apply { position(2) }, 8, 2))
            for (p in planes) for (i in 0 until p.buffer.limit()) p.buffer.put(i, (i * 53 + 17).toByte())
            return planes
        }
        val source = paddedPlanes()
        val reference = source.map { ReferencePlane(it.buffer, it.rowStride, it.pixelStride) }
        for (rotation in listOf(0, 90, 180, 270)) for (bt709 in listOf(false, true)) {
            for (full in listOf(false, true)) for ((w, h) in listOf(2 to 2, 4 to 4, 6 to 4)) {
                val expected = YuvReference.toRgb(reference, 1, 1, 4, 2, w, h, rotation, bt709, full)
                val actual = Yuv.toRgb(source, 1, 1, 4, 2, w, h, rotation, bt709, full)
                check(actual.contentEquals(expected)) { "Native RGB differs: rotation=$rotation BT709=$bt709 full=$full" }
            }
        }
        val rgb = IntArray(24) { i -> (0xff000000L or (i * 0x352781L and 0xffffff)).toInt() }
        val actual = paddedPlanes()
        val expected = paddedPlanes()
        Yuv.fromRgb(rgb, 6, 4, actual)
        YuvReference.fromRgb(rgb, 6, 4, expected.map { ReferencePlane(it.buffer, it.rowStride, it.pixelStride) })
        fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.capacity()).also {
            val copy = buffer.duplicate()
            copy.clear()
            copy.get(it)
        }
        for (i in 0..2) check(bytes(actual[i].buffer).contentEquals(bytes(expected[i].buffer))) { "Native YUV or padding differs" }

        fun rejected(action: () -> Unit) {
            try { action() } catch (_: IllegalArgumentException) { return }
            error("Invalid native buffer accepted")
        }
        rejected { Yuv.toRgb(source.map { it.copy(buffer = ByteBuffer.allocate(43)) }, 0, 0, 4, 2, 4, 2, 0, false, false) }
        rejected { Yuv.fromRgb(rgb, 6, 4, source.map { it.copy(buffer = it.buffer.asReadOnlyBuffer()) }) }
        rejected { Yuv.toRgb(source, 0, 0, 4096, 4096, 4, 2, 0, false, false) }
        rejected { Yuv.toRgb(source, -1, 0, 4, 2, 4, 2, 0, false, false) }
        rejected { Yuv.toRgb(source.map { it.copy(rowStride = 0) }, 0, 0, 4, 2, 4, 2, 0, false, false) }
    }

    private fun checkClip(name: String) {
        val input = File.createTempFile("fixture-", ".mp4", targetContext.cacheDir)
        var output: File? = null
        try {
            context.assets.open(name).use { source -> input.outputStream().use(source::copyTo) }
            val result = VideoProcessor.process(targetContext, Uri.fromFile(input), "{\"version\":1}") {}
            output = result.file
            check(result.frames == 30 && result.audioCopied == (name != "silent.mp4"))
            check(result.toRgbNs > 0 && result.effectNs > 0 && result.toYuvNs > 0)
            check(result.toRgbNs + result.effectNs + result.toYuvNs <= (result.elapsedMs + 1) * 1_000_000)
            val before = tracks(Uri.fromFile(input))
            val after = tracks(Uri.fromFile(result.file))
            check(after.keys == before.keys)
            val beforeVideo = before.getValue("video").timestamps
            val afterVideo = after.getValue("video").timestamps
            check(afterVideo.size == beforeVideo.size && beforeVideo.zip(afterVideo).all { (a, b) -> abs(a - b) <= 100 }) {
                "Video timestamps changed: ${before.getValue("video").timestamps.take(3)} -> ${after.getValue("video").timestamps.take(3)}; " +
                    "audio ${before["audio"]?.timestamps?.take(3)} -> ${after["audio"]?.timestamps?.take(3)}"
            }
            check(after["audio"] == before["audio"]) { "Audio payloads or timestamps changed" }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(result.file.absolutePath)
                val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val rotated = name == "rotated.mp4"
                check(format.getInteger(MediaFormat.KEY_WIDTH) == if (rotated) 240 else 320)
                check(format.getInteger(MediaFormat.KEY_HEIGHT) == if (rotated) 320 else 240)
                check(!format.containsKey(MediaFormat.KEY_ROTATION) || format.getInteger(MediaFormat.KEY_ROTATION) == 0)
                val expectedUs = 1_000_000L
                if (name != "offset.mp4") {
                    check(abs(format.getLong(MediaFormat.KEY_DURATION) - expectedUs) < 40_000) { "Video duration changed" }
                }
            } finally { extractor.release() }
            sendStatus(0, Bundle().apply {
                putString("stream", "$name: ${result.frames} frames, ${result.width}x${result.height}, ${result.elapsedMs} ms; " +
                    "mean YUV->RGB ${result.toRgbNs / 1_000_000.0 / result.frames} ms, " +
                    "ntsc/JNI ${result.effectNs / 1_000_000.0 / result.frames} ms, " +
                    "RGB->YUV ${result.toYuvNs / 1_000_000.0 / result.frames} ms; audio preserved\n")
            })
        } finally {
            input.delete()
            output?.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun galleryIds(): Set<Long> {
        val uri = android.provider.MediaStore.setIncludePending(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        return targetContext.contentResolver.query(uri, arrayOf("_id"),
            "_display_name LIKE ? AND relative_path = ?", arrayOf("VHS-processed-%", "Movies/VHS/"), null)!!.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    }

    private fun cacheOutputs() = targetContext.cacheDir.listFiles()!!.filter { it.name.startsWith("vhs-") }.map { it.name }.toSet()

    private fun checkExportWorkflow() {
        val input = File.createTempFile("workflow-", ".mp4", targetContext.cacheDir)
        val presetFile = File.createTempFile("preset-", ".json", targetContext.cacheDir)
        val preferences = targetContext.getSharedPreferences("vhs", Activity.MODE_PRIVATE)
        val previousPreset = preferences.getString("preset", null)
        var activity: MainActivity? = null
        var saved: Uri? = null
        try {
            context.assets.open("clip.mp4").use { source -> input.outputStream().use(source::copyTo) }
            val originalBytes = input.readBytes()
            val beforeCache = cacheOutputs()
            var cancelled = false
            try {
                VideoProcessor.process(targetContext, Uri.fromFile(input), "{\"version\":1}", { cancelled }) {
                    if (it.frames >= 10) cancelled = true
                }.file.delete()
                error("Processing did not honor cancellation")
            } catch (_: java.util.concurrent.CancellationException) {}
            check(cacheOutputs() == beforeCache) { "Cancelled processing leaked a cache output" }
            val beforeGallery = galleryIds()
            var checks = 0
            try {
                GalleryVideos.saveProcessed(targetContext, input) { ++checks >= 3 }
                error("Gallery write did not honor cancellation")
            } catch (_: java.util.concurrent.CancellationException) {}
            check(galleryIds() == beforeGallery) { "Cancelled publication leaked a Gallery row" }

            fun launch(video: Uri? = null, presetSnapshot: String? = null): MainActivity {
                val monitor = addMonitor(MainActivity::class.java.name, null, false)
                targetContext.startActivity(android.content.Intent(targetContext, MainActivity::class.java)
                    .apply {
                        if (video != null) { action = MainActivity.PROCESS_VIDEO; data = video }
                        if (presetSnapshot != null) putExtra("preset", presetSnapshot)
                    }
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                val created = waitForMonitorWithTimeout(monitor, 15_000) as? MainActivity ?: error("Main screen did not open")
                removeMonitor(monitor)
                activity = created
                return created
            }
            fun field(screen: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(screen)
            fun deliverResult(screen: MainActivity, request: Int, uri: Uri) {
                MainActivity::class.java.getDeclaredMethod("onActivityResult", Integer.TYPE, Integer.TYPE, android.content.Intent::class.java)
                    .apply { isAccessible = true }.invoke(screen, request, Activity.RESULT_OK, android.content.Intent().setData(uri))
            }
            fun await(condition: () -> Boolean) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                while (true) {
                    var ready = false
                    runOnMainSync { ready = condition() }
                    if (ready) return
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Export UI did not reach expected state" }
                    Thread.sleep(25)
                }
            }
            val imported = "{\"version\":1,\"snow_intensity\":0.1}"
            presetFile.writeText(imported)
            val first = launch()
            runOnMainSync { deliverResult(first, 1, Uri.fromFile(presetFile)) }
            await { field(first, "busy") == false && field(first, "preset") == imported }
            check(preferences.getString("preset", null) == imported)
            runOnMainSync { first.finish() }
            await { first.isDestroyed }
            val second = launch()
            runOnMainSync { check(field(second, "preset") == imported) { "Preset was not restored" } }
            runOnMainSync { deliverResult(second, 4, Uri.fromFile(input)) }
            await { field(second, "busy") == false && field(second, "savedVideo") != null }
            runOnMainSync { saved = field(second, "savedVideo") as Uri }
            targetContext.contentResolver.query(requireNotNull(saved), arrayOf("is_pending", "_display_name", "relative_path"), null, null, null)!!.use {
                check(it.moveToFirst() && it.getInt(0) == 0 && it.getString(1).startsWith("VHS-processed-") && it.getString(2) == "Movies/VHS/")
            }
            val outputTracks = tracks(requireNotNull(saved))
            check(outputTracks.getValue("video").timestamps.size == 30)
            check(outputTracks.getValue("audio") == tracks(Uri.fromFile(input)).getValue("audio"))
            val committedGallery = galleryIds()
            runOnMainSync {
                deliverResult(second, 4, Uri.fromFile(input))
                second.window.decorView.findViewWithTag<android.widget.Button>("cancelExport").performClick()
            }
            await { field(second, "busy") == false }
            runOnMainSync { check((field(second, "exportStatus") as android.widget.TextView).text.contains("cancelled", true)) }
            check(galleryIds() == committedGallery && cacheOutputs() == beforeCache)
            targetContext.contentResolver.delete(requireNotNull(saved), null, null)
            saved = null
            runOnMainSync { second.finish() }
            await { second.isDestroyed }
            // A camera restored as the task root has no activity-result recipient.
            val snapshot = "{\"version\":1,\"snow_intensity\":0.2}"
            val standalone = launch(Uri.fromFile(input), snapshot)
            runOnMainSync { check(field(standalone, "preset") == snapshot) }
            check(preferences.getString("preset", null) == imported) { "Camera export changed the remembered preset" }
            await { field(standalone, "busy") == false && field(standalone, "savedVideo") != null }
            runOnMainSync { saved = field(standalone, "savedVideo") as Uri }
            val standaloneTracks = tracks(requireNotNull(saved))
            check(standaloneTracks.getValue("video").timestamps == outputTracks.getValue("video").timestamps)
            check(standaloneTracks.getValue("audio") == outputTracks.getValue("audio"))
            check(input.readBytes().contentEquals(originalBytes)) { "Export or cancellation changed the source" }
        } finally {
            runOnMainSync { activity?.finish() }
            saved?.let { targetContext.contentResolver.delete(it, null, null) }
            check(preferences.edit().putString("preset", previousPreset).commit())
            input.delete(); presetFile.delete()
        }
    }

    private fun checkLongExport(): String {
        val input = File(targetContext.cacheDir, "long-fixture.mp4")
        check(input.isFile) { "Upload the synthetic long-fixture.mp4 to app cache first (see README)" }
        val before = tracks(Uri.fromFile(input))
        check(before.getValue("video").timestamps.size == 1800)
        var output: File? = null
        var gallery: Uri? = null
        val phases = mutableSetOf<VideoPhase>()
        var lastPosition = -1L
        var warmPss = 0L
        var peakPss = 0L
        var peakThermal = 0
        try {
            val result = VideoProcessor.process(targetContext, Uri.fromFile(input), "{\"version\":1}") { p ->
                phases.add(p.phase)
                if (p.phase == VideoPhase.VIDEO) {
                    check(p.positionUs >= lastPosition && p.durationUs != null && p.durationUs > 15_000_000)
                    lastPosition = p.positionUs
                    if (p.frames > 0 && p.frames % 300 == 0) {
                        val pss = android.os.Debug.getPss()
                        if (warmPss == 0L) warmPss = pss
                        peakPss = maxOf(peakPss, pss)
                        peakThermal = maxOf(peakThermal, targetContext.getSystemService(android.os.PowerManager::class.java).currentThermalStatus)
                        sendStatus(0, Bundle().apply { putString("stream", "Long export: ${p.frames}/1800 frames, app PSS=${pss / 1024} MiB, thermal=$peakThermal\n") })
                    }
                }
            }
            output = result.file
            check(result.frames == 1800 && phases.containsAll(VideoPhase.entries))
            val after = tracks(Uri.fromFile(result.file))
            val videoBefore = before.getValue("video").timestamps
            val videoAfter = after.getValue("video").timestamps
            check(videoAfter.size == videoBefore.size && videoBefore.zip(videoAfter).all { (a, b) -> abs(a - b) <= 100 })
            check(after.getValue("audio") == before.getValue("audio")) { "Long export changed AAC packets/timestamps" }
            val beforeSkew = videoBefore.last() - before.getValue("audio").timestamps.last()
            val afterSkew = videoAfter.last() - after.getValue("audio").timestamps.last()
            check(abs(beforeSkew - afterSkew) <= 100) { "Long export added A/V drift" }
            gallery = GalleryVideos.saveProcessed(targetContext, result.file) { false }
            val expectedHash = result.file.inputStream().use { source ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = source.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                hash.digest()
            }
            val actualHash = targetContext.contentResolver.openInputStream(gallery)!!.use { source ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = source.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                hash.digest()
            }
            check(expectedHash.contentEquals(actualHash)) { "Gallery copy differs from completed MP4" }
            val existingGallery = galleryIds()
            var calls = 0
            try {
                GalleryVideos.saveProcessed(targetContext, result.file) { ++calls >= 4 }
                error("Long Gallery copy ignored cancellation")
            } catch (_: java.util.concurrent.CancellationException) {}
            check(galleryIds() == existingGallery)
            return "60-second 640x480 export: ${result.frames} frames in ${result.elapsedMs} ms; all video timestamps and AAC preserved; " +
                "A/V end-offset change=${afterSkew - beforeSkew} us; app PSS warm=${warmPss / 1024} MiB / peak=${peakPss / 1024} MiB, " +
                "peak thermal status=$peakThermal; Gallery byte equality and partial-copy cancellation cleanup passed."
        } finally {
            gallery?.let { targetContext.contentResolver.delete(it, null, null) }
            output?.delete()
            input.delete()
        }
    }

    // Explicitly invoked only after the user approves activating the real camera/microphone.
    private fun checkCameraRecording(): String {
        val monitor = addMonitor(CameraActivity::class.java.name, null, false)
        targetContext.startActivity(android.content.Intent(targetContext, CameraActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val camera = waitForMonitorWithTimeout(monitor, 15_000) as? CameraActivity ?: error("Camera screen did not open")
        removeMonitor(monitor)
        var processed: File? = null
        val cameraPreferences = targetContext.getSharedPreferences("vhs", Activity.MODE_PRIVATE)
        var zoomKey: String? = null
        var previousZoom: Float? = null
        try {
            fun field(name: String): Any? = CameraActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(camera)
            fun await(condition: () -> Boolean) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (true) {
                    var ready = false
                    runOnMainSync { ready = condition() }
                    if (ready) return
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Camera did not reach the expected state" }
                    Thread.sleep(25)
                }
            }
            fun recordButton() = camera.window.decorView.findViewWithTag<android.widget.Button>("record")
            await { recordButton().isEnabled && recordButton().text == "Record" }
            await { (field("effectImage") as android.widget.ImageView).drawable != null }
            runOnMainSync {
                val lenses = field("lenses") as List<*>
                val id = (lenses[field("selected") as Int] as CameraLens).id
                zoomKey = "defaultZoom.$id"
                previousZoom = if (cameraPreferences.contains(zoomKey)) cameraPreferences.getFloat(zoomKey, 1f) else null
                val motion = field("motion") as ZoomMotion
                motion.zoom = 1.5
                (field("defaultZoomButton") as android.widget.Button).performClick()
                check(cameraPreferences.getFloat(zoomKey, 1f) == 1.5f)
                motion.zoom = 1.0
                CameraActivity::class.java.getDeclaredMethod("configureLensControls").apply { isAccessible = true }.invoke(camera)
                check(motion.zoom == 1.5) { "Saved starting zoom was not loaded" }
                CameraActivity::class.java.getDeclaredMethod("applyControls", java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }.invoke(camera, true, false)
            }
            val liveSamples = mutableListOf<String>()
            var maxThermal = 0
            var minPss = Int.MAX_VALUE
            var maxPss = 0
            repeat(2) {
                Thread.sleep(2500)
                runOnMainSync {
                    check((field("effectImage") as android.widget.ImageView).visibility == android.view.View.VISIBLE)
                    liveSamples.add((field("liveStatus") as android.widget.TextView).text.toString())
                }
                val memory = android.os.Debug.MemoryInfo()
                android.os.Debug.getMemoryInfo(memory)
                minPss = minOf(minPss, memory.totalPss); maxPss = maxOf(maxPss, memory.totalPss)
                maxThermal = maxOf(maxThermal, targetContext.getSystemService(android.os.PowerManager::class.java).currentThermalStatus)
            }
            sendStatus(0, Bundle().apply { putString("stream", "5-second live preview: ${liveSamples.last()}; PSS ${minPss / 1024}–${maxPss / 1024} MiB, thermal max=$maxThermal\n") })
            runOnMainSync { recordButton().performClick() }
            await { recordButton().isEnabled && recordButton().text.startsWith("Stop") }
            await { (field("effectImage") as android.widget.ImageView).drawable != null }
            Thread.sleep(1000)
            lateinit var tele: android.widget.Button
            runOnMainSync {
                val views = arrayListOf<android.view.View>()
                camera.window.decorView.findViewsWithText(views, "T", android.view.View.FIND_VIEWS_WITH_TEXT)
                tele = views.filterIsInstance<android.widget.Button>().first { it.text == "T" }
                val event = android.view.MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_DOWN, tele.width / 2f, tele.height / 2f, 0)
                tele.dispatchTouchEvent(event); event.recycle()
            }
            Thread.sleep(1000)
            runOnMainSync {
                val event = android.view.MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_UP, tele.width / 2f, tele.height / 2f, 0)
                tele.dispatchTouchEvent(event); event.recycle()
            }
            runOnMainSync {
                val pause = camera.window.decorView.findViewWithTag<android.widget.Button>("pause")
                check(pause.isEnabled && pause.text == "Pause")
                pause.performClick()
                check(pause.text == "Resume" && field("recording") == true)
            }
            val pausedAt = (field("recordingTime") as RecordingTime).elapsed(android.os.SystemClock.elapsedRealtime())
            Thread.sleep(2000)
            check((field("recordingTime") as RecordingTime).elapsed(android.os.SystemClock.elapsedRealtime()) == pausedAt)
            runOnMainSync {
                camera.window.decorView.findViewWithTag<android.widget.Button>("pause").performClick()
                check(!(field("recordingTime") as RecordingTime).paused)
            }
            Thread.sleep(2000)
            runOnMainSync {
                check(recordButton().text.startsWith("Stop")) { "Recording stopped before the test requested stop" }
                recordButton().performClick()
            }
            await { field("lastClip") != null && recordButton().isEnabled }
            lateinit var uri: Uri
            var zoom = 0.0
            runOnMainSync {
                uri = field("lastClip") as Uri
                zoom = (field("motion") as ZoomMotion).zoom
            }
            check(zoom > 1.1) { "Hold-T zoom did not change the crop" }
            targetContext.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Video.Media.IS_PENDING), null, null, null)!!.use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "Original was not published" }
            }
            sendStatus(0, Bundle().apply { putString("stream", "Original test recording saved at $uri\n") })
            val extractor = MediaExtractor()
            var rotation = 0
            var duration = 0L
            try {
                extractor.setDataSource(targetContext, uri, null)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                val video = formats.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                check(formats.any { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC })
                check(video.getInteger(MediaFormat.KEY_WIDTH) == 640 && video.getInteger(MediaFormat.KEY_HEIGHT) == 480)
                rotation = if (video.containsKey(MediaFormat.KEY_ROTATION)) video.getInteger(MediaFormat.KEY_ROTATION) else 0
                duration = video.getLong(MediaFormat.KEY_DURATION)
                check(duration in 3_400_000..4_800_000) { "Unexpected test recording duration $duration" }
            } finally { extractor.release() }
            val before = tracks(uri)
            val result = VideoProcessor.process(targetContext, uri, "{\"version\":1}") {}
            processed = result.file
            val after = tracks(Uri.fromFile(result.file))
            check(after.getValue("video").timestamps.size == before.getValue("video").timestamps.size)
            check(after.getValue("audio").hashes == before.getValue("audio").hashes)
            val a = before.getValue("audio").timestamps
            val b = after.getValue("audio").timestamps
            check(a.size == b.size && a.zip(b).all { (x, y) -> abs(x - y) <= 100 })
            val originalStartOffset = before.getValue("video").timestamps.first() - a.first()
            val processedStartOffset = after.getValue("video").timestamps.first() - b.first()
            check(abs(originalStartOffset - processedStartOffset) <= 100)
            return "real ultrawide recording ${duration / 1000} ms, 640x480, rotation=$rotation, " +
                "${result.frames} frames, AAC present, pause/resume removed 2s, live preview active, hold-T crop zoom=$zoom; original retained at $uri; " +
                "processed ${result.width}x${result.height} in ${result.elapsedMs} ms, AAC payloads and A/V start offset preserved."
        } finally {
            runOnMainSync { camera.finish() }
            zoomKey?.let { key ->
                val edit = cameraPreferences.edit()
                if (previousZoom == null) edit.remove(key) else edit.putFloat(key, previousZoom!!)
                check(edit.commit())
            }
            processed?.delete()
        }
    }

    private data class Samples(val timestamps: List<Long>, val hashes: List<String>)
    private fun tracks(uri: Uri): Map<String, Samples> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(targetContext, uri, null)
            return (0 until extractor.trackCount).associate { index ->
                extractor.selectTrack(index)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val times = mutableListOf<Long>()
                val hashes = mutableListOf<String>()
                val buffer = ByteBuffer.allocate(1024 * 1024)
                while (extractor.sampleTime >= 0) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    times.add(extractor.sampleTime)
                    val bytes = ByteArray(size)
                    buffer.position(0)
                    buffer.get(bytes)
                    hashes.add(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    extractor.advance()
                }
                extractor.unselectTrack(index)
                requireNotNull(extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)).substringBefore('/') to Samples(times, hashes)
            }
        } finally { extractor.release() }
    }
}
