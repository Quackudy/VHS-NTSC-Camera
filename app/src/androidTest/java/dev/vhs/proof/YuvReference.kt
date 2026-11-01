// Pre-optimization Kotlin converter retained only as a pixel-equality reference.
package dev.vhs.proof

import java.nio.ByteBuffer
import kotlin.math.roundToInt

// Image planes may be padded, interleaved, and start at a nonzero buffer position.
internal class ReferencePlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int) {
    private val offset = buffer.position()
    fun read(x: Int, y: Int) = buffer.get(offset + y * rowStride + x * pixelStride).toInt() and 255
    fun write(x: Int, y: Int, value: Int) {
        buffer.put(offset + y * rowStride + x * pixelStride, value.coerceIn(0, 255).toByte())
    }
}

internal object YuvReference {
    // shortcut: nearest-neighbor downscaling; add filtered resampling when tuning export quality.
    fun toRgb(
        planes: List<ReferencePlane>, left: Int, top: Int, width: Int, height: Int,
        outWidth: Int, outHeight: Int, rotation: Int, bt709: Boolean, fullRange: Boolean,
    ): IntArray {
        require(planes.size == 3 && width > 0 && height > 0)
        require(rotation in listOf(0, 90, 180, 270))
        val rotatedWidth = if (rotation % 180 == 0) width else height
        val rotatedHeight = if (rotation % 180 == 0) height else width
        return IntArray(outWidth * outHeight) { index ->
            val x = (index % outWidth) * rotatedWidth / outWidth
            val y = (index / outWidth) * rotatedHeight / outHeight
            val sx = left + when (rotation) { 90 -> y; 180 -> width - 1 - x; 270 -> width - 1 - y; else -> x }
            val sy = top + when (rotation) { 90 -> height - 1 - x; 180 -> height - 1 - y; 270 -> x; else -> y }
            val luma = planes[0].read(sx, sy).toDouble()
            val u = planes[1].read(sx / 2, sy / 2) - 128.0
            val v = planes[2].read(sx / 2, sy / 2) - 128.0
            val yy = if (fullRange) luma else (luma - 16) * 255 / 219
            val chroma = if (fullRange) 1.0 else 255.0 / 224
            val red = yy + (if (bt709) 1.5748 else 1.402) * chroma * v
            val green = yy - (if (bt709) 0.187324 else 0.344136) * chroma * u -
                (if (bt709) 0.468124 else 0.714136) * chroma * v
            val blue = yy + (if (bt709) 1.8556 else 1.772) * chroma * u
            (255 shl 24) or (red.roundToInt().coerceIn(0, 255) shl 16) or
                (green.roundToInt().coerceIn(0, 255) shl 8) or blue.roundToInt().coerceIn(0, 255)
        }
    }

    // Output is explicitly tagged BT.601 limited range in the encoder format.
    fun fromRgb(rgb: IntArray, width: Int, height: Int, planes: List<ReferencePlane>) {
        require(width % 2 == 0 && height % 2 == 0 && rgb.size == width * height && planes.size == 3)
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            for (dy in 0..1) for (dx in 0..1) {
                val pixel = rgb[(y + dy) * width + x + dx]
                val red = (pixel shr 16) and 255
                val green = (pixel shr 8) and 255
                val blue = pixel and 255
                planes[0].write(x + dx, y + dy, ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16)
                redSum += red; greenSum += green; blueSum += blue
            }
            val red = redSum / 4
            val green = greenSum / 4
            val blue = blueSum / 4
            planes[1].write(x / 2, y / 2, ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128)
            planes[2].write(x / 2, y / 2, ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128)
        }
    }
}
