package dev.vhs.proof

import java.nio.ByteBuffer

internal data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

internal object Yuv {
    fun toRgb(
        planes: List<Plane>, left: Int, top: Int, width: Int, height: Int,
        outWidth: Int, outHeight: Int, rotation: Int, bt709: Boolean, fullRange: Boolean,
    ): IntArray {
        require(planes.size == 3)
        // Slices bound native access to each plane's current position and limit.
        return Native.yuvToRgb(planes[0].buffer.slice(), planes[1].buffer.slice(), planes[2].buffer.slice(),
            strides(planes), left, top, width, height, outWidth, outHeight, rotation, bt709, fullRange)
    }

    fun fromRgb(rgb: IntArray, width: Int, height: Int, planes: List<Plane>) {
        require(planes.size == 3)
        Native.rgbToYuv(rgb, width, height, planes[0].buffer.slice(), planes[1].buffer.slice(),
            planes[2].buffer.slice(), strides(planes))
    }

    private fun strides(p: List<Plane>) = intArrayOf(
        p[0].rowStride, p[0].pixelStride, p[1].rowStride, p[1].pixelStride, p[2].rowStride, p[2].pixelStride,
    )
}
