package dev.vhs.proof

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.DataSpace
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import java.util.Locale
import kotlin.math.roundToLong
import java.util.concurrent.atomic.AtomicBoolean

/** One worker and at most one pending display frame; camera buffers never cross into UI code. */
internal class LivePreview(
    size: Size, private val preset: String, private val rotation: () -> Int,
    private val display: (Bitmap, Int, String?) -> Unit,
    private val failure: (Exception) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("ntsc-live").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val pendingDisplay = AtomicBoolean(false)
    val reader: ImageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
    private var firstTimestamp = -1L
    private var lastFrame = -1
    private var skipped = 0
    private var frames = 0
    private var elapsedNs = 0L
    private var windowStart = SystemClock.elapsedRealtimeNanos()

    init {
        reader.setOnImageAvailableListener({ source ->
            if (!closed.get()) consume(source)
        }, worker)
    }

    @android.annotation.SuppressLint("WrongConstant") // YUV images carry color dataspaces; Image also allows depth values.
    private fun consume(source: ImageReader) {
        try {
            val image = source.acquireLatestImage() ?: return
            image.use {
                if (closed.get() || !pendingDisplay.compareAndSet(false, true)) return
                val start = SystemClock.elapsedRealtimeNanos()
                val angle = rotation()
                if (firstTimestamp < 0) firstTimestamp = image.timestamp
                val frame = frameNumber(firstTimestamp, image.timestamp)
                skipped += (frame - lastFrame - 1).coerceAtLeast(0)
                lastFrame = frame
                val crop = image.cropRect
                val width = if (angle % 180 == 0) crop.width() else crop.height()
                val height = if (angle % 180 == 0) crop.height() else crop.width()
                val space = if (Build.VERSION.SDK_INT >= 33) image.dataSpace else DataSpace.DATASPACE_UNKNOWN
                val standard = if (Build.VERSION.SDK_INT >= 33) DataSpace.getStandard(space) else 0
                val range = if (Build.VERSION.SDK_INT >= 33) DataSpace.getRange(space) else 0
                require(standard in intArrayOf(DataSpace.STANDARD_UNSPECIFIED, DataSpace.STANDARD_BT709,
                    DataSpace.STANDARD_BT601_525, DataSpace.STANDARD_BT601_625,
                    DataSpace.STANDARD_BT601_525_UNADJUSTED, DataSpace.STANDARD_BT601_625_UNADJUSTED)) {
                    "Unsupported live camera color standard $standard"
                }
                require(range in intArrayOf(DataSpace.RANGE_UNSPECIFIED, DataSpace.RANGE_FULL, DataSpace.RANGE_LIMITED)) {
                    "Unsupported live camera color range $range"
                }
                val pixels = Yuv.toRgb(image.planes.map { Plane(it.buffer, it.rowStride, it.pixelStride) },
                    crop.left, crop.top, crop.width(), crop.height(), width, height, angle,
                    standard == DataSpace.STANDARD_BT709, range == DataSpace.RANGE_FULL)
                val result = Native.process(pixels, width, height, frame, preset, height / 480f)
                val bitmap = Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
                frames++
                elapsedNs += SystemClock.elapsedRealtimeNanos() - start
                val now = SystemClock.elapsedRealtimeNanos()
                val stats = if (now - windowStart >= 1_000_000_000L) {
                    String.format(Locale.US, "Live %d×%d · %.1f fps · %.1f ms/frame · %d skipped%s",
                        width, height, frames * 1e9 / (now - windowStart), elapsedNs / 1e6 / frames, skipped,
                        if (standard == DataSpace.STANDARD_UNSPECIFIED || range == DataSpace.RANGE_UNSPECIFIED)
                            " · color assumed: unspecified → 601/limited" else "")
                        .also {
                            Log.i("VhsLive", "$it · dataspace=$space")
                            frames = 0; elapsedNs = 0; skipped = 0; windowStart = now
                        }
                } else null
                main.post {
                    try {
                        if (!closed.get() && angle == rotation()) display(bitmap, angle, stats)
                        else bitmap.recycle()
                    } finally { pendingDisplay.set(false) }
                }
            }
        } catch (error: Exception) {
            pendingDisplay.set(false)
            if (!closed.get()) {
                close()
                main.post { failure(error) }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Close on the conversion thread so JNI cannot read a freed direct buffer.
        worker.post { reader.setOnImageAvailableListener(null, null); reader.close(); thread.quitSafely() }
    }

    companion object {
        fun frameNumber(first: Long, timestamp: Long): Int =
            ((timestamp - first).coerceAtLeast(0) / 1_000_000_000.0 * 30).roundToLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
