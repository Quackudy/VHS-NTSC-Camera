package dev.vhs.proof

import java.nio.ByteBuffer

object Native {
    init { System.loadLibrary("vhs_native") }
    external fun validatePreset(preset: String): String
    external fun process(pixels: IntArray, width: Int, height: Int, frame: Int, preset: String, previewScale: Float = 1f, cleanEdges: Boolean = preset == PresetStore.DEFAULT_JSON): IntArray
    external fun yuvToRgb(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, strides: IntArray,
        left: Int, top: Int, width: Int, height: Int, outWidth: Int, outHeight: Int,
        rotation: Int, bt709: Boolean, fullRange: Boolean,
    ): IntArray
    external fun rgbToYuv(
        rgb: IntArray, width: Int, height: Int,
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, strides: IntArray,
    )
}
