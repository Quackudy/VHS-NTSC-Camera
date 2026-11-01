package dev.vhs.proof

import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

internal object CameraGeometry {
    fun maxCropZoom(active: Rect, advertised: Double): Double {
        val base = crop(active, 1.0)
        return (advertised * min(base.width().toDouble() / active.width(), base.height().toDouble() / active.height())).coerceAtLeast(1.0)
    }

    fun crop(active: Rect, zoom: Double): Rect {
        require(active.width() >= 4 && active.height() >= 3 && zoom.isFinite() && zoom >= 1)
        val units = ceil(min(active.width() / 4, active.height() / 3) / zoom).toInt().coerceAtLeast(1)
        val width = units * 4
        val height = units * 3
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    fun rotation(sensor: Int, displayDegrees: Int) = (sensor - displayDegrees + 360) % 360

    fun previewTransform(width: Int, height: Int, displayDegrees: Int): Matrix = Matrix().apply {
        require(width > 0 && height > 0)
        // TextureView already applies sensor orientation; only undo display rotation here.
        setRotate(-displayDegrees.toFloat(), width / 2f, height / 2f)
        if (displayDegrees % 180 != 0) postScale(width.toFloat() / height, height.toFloat() / width, width / 2f, height / 2f)
    }
}

internal class ZoomMotion {
    var zoom = 1.0
    var velocity = 0.0
    var direction = 0

    fun step(dt: Double, speed: Double, maxZoom: Double) {
        require(dt in 0.0..0.1 && speed > 0 && maxZoom >= 1)
        val desired = direction * speed
        val acceleration = speed / 0.18
        velocity += (desired - velocity).coerceIn(-acceleration * dt, acceleration * dt)
        zoom = (zoom * 2.0.pow(velocity * dt)).coerceIn(1.0, maxZoom)
        if (zoom == 1.0 && velocity < 0 || zoom == maxZoom && velocity > 0) velocity = 0.0
    }
}
