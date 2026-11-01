package dev.vhs.proof

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.min

internal data class VideoResult(
    val file: File, val width: Int, val height: Int, val frames: Int,
    val elapsedMs: Long, val audioCopied: Boolean, val assumedColor: Boolean,
    val toRgbNs: Long, val effectNs: Long, val toYuvNs: Long,
)

internal enum class VideoPhase { VIDEO, AUDIO, FINALIZING }
internal data class VideoProgress(val frames: Int, val positionUs: Long, val durationUs: Long?, val phase: VideoPhase)

internal object VideoProcessor {
    private const val WAIT_US = 2_000L

    fun process(context: Context, source: Uri, preset: String, isCancelled: () -> Boolean = { false }, progress: (VideoProgress) -> Unit): VideoResult {
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted || isCancelled()) throw CancellationException("Export cancelled")
        }
        checkCancelled()
        val start = SystemClock.elapsedRealtime()
        Native.validatePreset(preset)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val output = File.createTempFile("vhs-", ".mp4", context.cacheDir)
        var success = false
        try {
            extractor.setDataSource(context, source, null)
            val videoTracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val audioTracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            require(videoTracks.size == 1 && audioTracks.size <= 1) { "Use a clip with one video track and at most one audio track" }
            val format = extractor.getTrackFormat(videoTracks.single())
            val audioFormat = audioTracks.firstOrNull()?.let(extractor::getTrackFormat)
            require(audioFormat == null || audioFormat.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
                "This proof copies AAC audio only; choose an SDR phone clip with AAC audio"
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } else null
            progress(VideoProgress(0, 0, durationUs, VideoPhase.VIDEO))
            checkCancelled()
            require(!format.containsKey("sar-width") || !format.containsKey("sar-height") ||
                format.getInteger("sar-width") == format.getInteger("sar-height")) { "Non-square video pixels are not supported yet" }
            checkColor(format)
            val rotation = format.intOr(MediaFormat.KEY_ROTATION, 0)
            require(rotation in listOf(0, 90, 180, 270)) { "Unsupported video rotation: $rotation" }
            val sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            require(sourceWidth in 2..4096 && sourceHeight in 2..4096) { "Choose a clip up to 4096 pixels per side" }
            val uprightWidth = if (rotation % 180 == 0) sourceWidth else sourceHeight
            val uprightHeight = if (rotation % 180 == 0) sourceHeight else sourceWidth
            val scale = min(1.0, min(640.0 / uprightWidth, 480.0 / uprightHeight))
            val frameRate = format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toInt() ?: 30
            require(frameRate in 1..60) { "Choose a clip at 60 fps or below" }

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            val capabilities = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            require(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capabilities.colorFormats) {
                "This encoder does not expose YUV image input"
            }
            val alignmentWidth = maxOf(2, capabilities.videoCapabilities.widthAlignment)
            val alignmentHeight = maxOf(2, capabilities.videoCapabilities.heightAlignment)
            val width = (uprightWidth * scale).toInt() / alignmentWidth * alignmentWidth
            val height = (uprightHeight * scale).toInt() / alignmentHeight * alignmentHeight
            require(width >= 2 && height >= 2 && capabilities.videoCapabilities.isSizeSupported(width, height)) {
                "Encoder cannot accept the downscaled clip dimensions"
            }
            val encodeFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            enc.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            // Apply orientation to pixels ourselves, so VHS scanlines stay horizontal when viewed.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val dec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = dec
            dec.configure(format, null, null, 0)
            dec.start()
            extractor.selectTrack(videoTracks.single())
            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val audioTrack = audioFormat?.let(mux::addTrack)
            var videoTrack = -1
            var muxStarted = false
            var encoderDone = false
            var frames = 0
            var lastVideoPts = -1L
            var firstVideoPts: Long? = null
            var lastMovement = SystemClock.elapsedRealtime()
            var assumedColor = false
            var toRgbNs = 0L
            var effectNs = 0L
            var toYuvNs = 0L
            val encodedInfo = MediaCodec.BufferInfo()

            fun active() {
                checkCancelled()
                check(SystemClock.elapsedRealtime() - lastMovement < 20_000) { "Video codec stalled; try another clip" }
            }
            fun drainEncoder() {
                while (true) {
                    active()
                    val index = enc.dequeueOutputBuffer(encodedInfo, 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxStarted) { "Encoder changed output format twice" }
                            videoTrack = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxStarted = true
                            lastMovement = SystemClock.elapsedRealtime()
                        }
                        index >= 0 -> {
                            try {
                                if (encodedInfo.size > 0 && encodedInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    check(muxStarted) { "Encoder produced data before its format" }
                                    mux.writeSampleData(videoTrack, requireNotNull(enc.getOutputBuffer(index)), encodedInfo)
                                }
                                encoderDone = encodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                lastMovement = SystemClock.elapsedRealtime()
                            } finally { enc.releaseOutputBuffer(index, false) }
                        }
                    }
                }
            }
            fun encoderInput(): Int {
                while (true) {
                    active()
                    drainEncoder()
                    val index = enc.dequeueInputBuffer(WAIT_US)
                    if (index >= 0) return index
                }
            }

            var inputDone = false
            var decoderDone = false
            val decodedInfo = MediaCodec.BufferInfo()
            while (!decoderDone) {
                active()
                drainEncoder()
                if (!inputDone) {
                    val index = dec.dequeueInputBuffer(WAIT_US)
                    if (index >= 0) {
                        val input = requireNotNull(dec.getInputBuffer(index))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted clips are not supported" }
                            require(extractor.sampleTime in 0..Long.MAX_VALUE - 1_000_000) { "Unsupported video timestamp" }
                            dec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastMovement = SystemClock.elapsedRealtime()
                    }
                }
                val index = dec.dequeueOutputBuffer(decodedInfo, WAIT_US)
                if (index >= 0) {
                    try {
                        if (decodedInfo.size > 0) {
                            require(decodedInfo.presentationTimeUs > lastVideoPts) { "Video timestamps are not strictly increasing" }
                            check(frames < Int.MAX_VALUE) { "Video exceeds the supported frame count" }
                            if (firstVideoPts == null) firstVideoPts = decodedInfo.presentationTimeUs
                            val rgbStart = SystemClock.elapsedRealtimeNanos()
                            val rgb = requireNotNull(dec.getOutputImage(index)) { "Decoder does not expose YUV images" }.use { image ->
                                require(image.format == ImageFormat.YUV_420_888) { "Only 8-bit SDR YUV video is supported" }
                                val color = dec.outputFormat
                                checkColor(color)
                                val standard = color.intOr(MediaFormat.KEY_COLOR_STANDARD,
                                    format.intOr(MediaFormat.KEY_COLOR_STANDARD, 0))
                                val range = color.intOr(MediaFormat.KEY_COLOR_RANGE,
                                    format.intOr(MediaFormat.KEY_COLOR_RANGE, 0))
                                assumedColor = assumedColor || standard == 0 || range == 0
                                val crop = image.cropRect
                                Yuv.toRgb(image.planes.map(::plane), crop.left, crop.top, crop.width(), crop.height(),
                                    width, height, rotation,
                                    standard == MediaFormat.COLOR_STANDARD_BT709,
                                    range == MediaFormat.COLOR_RANGE_FULL)
                            }
                            toRgbNs += SystemClock.elapsedRealtimeNanos() - rgbStart
                            val effectStart = SystemClock.elapsedRealtimeNanos()
                            val result = Native.process(rgb, width, height, frames, preset)
                            effectNs += SystemClock.elapsedRealtimeNanos() - effectStart
                            val encoderIndex = encoderInput()
                            val yuvStart = SystemClock.elapsedRealtimeNanos()
                            requireNotNull(enc.getInputImage(encoderIndex)) { "Encoder does not expose YUV images" }.use { image ->
                                require(image.format == ImageFormat.YUV_420_888) { "Encoder requires an unsupported pixel format" }
                                Yuv.fromRgb(result, width, height, image.planes.map(::plane))
                            }
                            toYuvNs += SystemClock.elapsedRealtimeNanos() - yuvStart
                            enc.queueInputBuffer(encoderIndex, 0, width * height * 3 / 2, decodedInfo.presentationTimeUs, 0)
                            lastVideoPts = decodedInfo.presentationTimeUs
                            frames++
                            if (frames % 10 == 0) progress(VideoProgress(frames,
                                decodedInfo.presentationTimeUs - requireNotNull(firstVideoPts), durationUs, VideoPhase.VIDEO))
                        }
                        decoderDone = decodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        lastMovement = SystemClock.elapsedRealtime()
                    } finally { dec.releaseOutputBuffer(index, false) }
                }
            }
            require(frames > 0) { "Clip contains no decodable video frames" }
            val endIndex = encoderInput()
            enc.queueInputBuffer(endIndex, 0, 0, lastVideoPts + 1_000_000 / frameRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            while (!encoderDone) { active(); drainEncoder() }
            if (audioTrack != null) {
                progress(VideoProgress(frames, lastVideoPts - requireNotNull(firstVideoPts), durationUs, VideoPhase.AUDIO))
                extractor.unselectTrack(videoTracks.single())
                extractor.selectTrack(audioTracks.single())
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val buffer = ByteBuffer.allocateDirect(1024 * 1024)
                var previousPts = -1L
                while (extractor.sampleTime >= 0) {
                    active()
                    val pts = extractor.sampleTime
                    require(pts >= previousPts) { "Unsupported audio timestamps" }
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted audio is not supported" }
                    require(extractor.sampleSize <= buffer.capacity()) { "Audio packet exceeds 1 MiB" }
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mux.writeSampleData(audioTrack, buffer, MediaCodec.BufferInfo().apply { set(0, size, pts, flags) })
                    previousPts = pts
                    extractor.advance()
                    lastMovement = SystemClock.elapsedRealtime()
                }
            }
            progress(VideoProgress(frames, lastVideoPts - requireNotNull(firstVideoPts), durationUs, VideoPhase.FINALIZING))
            checkCancelled()
            mux.stop()
            checkCancelled()
            success = true
            return VideoResult(output, width, height, frames, SystemClock.elapsedRealtime() - start,
                audioTrack != null, assumedColor, toRgbNs, effectNs, toYuvNs)
        } finally {
            runCatching { decoder?.release() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            extractor.release()
            if (!success) output.delete()
        }
    }

    private fun plane(plane: Image.Plane) = Plane(plane.buffer, plane.rowStride, plane.pixelStride)
    private fun MediaFormat.intOr(key: String, fallback: Int): Int {
        val value = if (containsKey(key)) getInteger(key) else 0
        // A26 emits AOSP extended standard 71: unspecified primaries, BT.709 matrix.
        if (key == MediaFormat.KEY_COLOR_STANDARD && value == 71) return MediaFormat.COLOR_STANDARD_BT709
        return if (value == 0) fallback else value
    }
    private fun checkColor(format: MediaFormat) {
        require(format.intOr(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) {
            "Unsupported color transfer ${format.intOr(MediaFormat.KEY_COLOR_TRANSFER, -1)}; choose an SDR clip"
        }
        require(format.intOr(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC) in listOf(
            MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT709,
        )) { "Unsupported video color matrix ${format.intOr(MediaFormat.KEY_COLOR_STANDARD, -1)}; choose BT.601 or BT.709 SDR video" }
        require(format.intOr(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) in listOf(
            MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.COLOR_RANGE_FULL,
        )) { "Unsupported video color range" }
    }
}
