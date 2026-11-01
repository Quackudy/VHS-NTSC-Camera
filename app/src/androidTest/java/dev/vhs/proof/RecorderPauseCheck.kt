package dev.vhs.proof

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.opengl.EGL14 as E
import android.opengl.EGLExt
import android.opengl.GLES20 as G
import android.os.SystemClock
import java.io.File

/** Generated color frames only: this test never opens a camera or microphone. */
internal object RecorderPauseCheck {
    fun run(context: Context) {
        for (stopPaused in listOf(false, true)) {
            val file = File.createTempFile("pause-fixture-", ".mp4", context.cacheDir)
            val recorder = MediaRecorder()
            val display = E.eglGetDisplay(E.EGL_DEFAULT_DISPLAY)
            var surface = E.EGL_NO_SURFACE
            var gl = E.EGL_NO_CONTEXT
            try {
                recorder.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(file.absolutePath)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(320, 240); setVideoFrameRate(30); setVideoEncodingBitRate(500_000)
                    prepare()
                }
                check(E.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                check(E.eglChooseConfig(display, intArrayOf(E.EGL_RED_SIZE, 8, E.EGL_GREEN_SIZE, 8,
                    E.EGL_BLUE_SIZE, 8, E.EGL_RENDERABLE_TYPE, E.EGL_OPENGL_ES2_BIT,
                    E.EGL_SURFACE_TYPE, E.EGL_WINDOW_BIT, 0x3142, 1, E.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
                val config = requireNotNull(configs[0])
                gl = E.eglCreateContext(display, config, E.EGL_NO_CONTEXT, intArrayOf(E.EGL_CONTEXT_CLIENT_VERSION, 2, E.EGL_NONE), 0)
                check(gl != E.EGL_NO_CONTEXT)
                surface = E.eglCreateWindowSurface(display, config, recorder.surface, intArrayOf(E.EGL_NONE), 0)
                check(surface != E.EGL_NO_SURFACE && E.eglMakeCurrent(display, surface, surface, gl))
                recorder.start()
                val start = SystemClock.elapsedRealtime()
                val count = if (stopPaused) 60 else 90
                repeat(count) { frame ->
                    if (frame == 30) recorder.pause()
                    if (frame == 60) recorder.resume()
                    val wait = start + frame * 1000 / 30 - SystemClock.elapsedRealtime()
                    if (wait > 0) SystemClock.sleep(wait)
                    G.glClearColor(frame / 90f, 0.25f, 0.6f, 1f)
                    G.glClear(G.GL_COLOR_BUFFER_BIT)
                    check(EGLExt.eglPresentationTimeANDROID(display, surface, System.nanoTime()))
                    check(E.eglSwapBuffers(display, surface))
                }
                recorder.stop()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val format = extractor.getTrackFormat(0)
                    check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
                    val duration = format.getLong(MediaFormat.KEY_DURATION)
                    val expected = if (stopPaused) 1_000_000L else 2_000_000L
                    check(kotlin.math.abs(duration - expected) < 450_000) { "Paused interval leaked into video: $duration us; stopPaused=$stopPaused" }
                    extractor.selectTrack(0)
                    val times = mutableListOf<Long>()
                    while (extractor.sampleTime >= 0) { times.add(extractor.sampleTime); extractor.advance() }
                    val sorted = times.sorted()
                    check(sorted.zipWithNext().all { (a, b) -> b > a && b - a < 200_000 }) { "Gap or duplicate timestamp after pause" }
                    check(times.size in (if (stopPaused) 20..35 else 45..65)) { "Unexpected recorder frame count ${times.size}" }
                } finally { extractor.release() }
            } finally {
                E.eglMakeCurrent(display, E.EGL_NO_SURFACE, E.EGL_NO_SURFACE, E.EGL_NO_CONTEXT)
                if (surface != E.EGL_NO_SURFACE) E.eglDestroySurface(display, surface)
                if (gl != E.EGL_NO_CONTEXT) E.eglDestroyContext(display, gl)
                E.eglReleaseThread(); E.eglTerminate(display)
                recorder.release(); file.delete()
            }
        }
    }
}
