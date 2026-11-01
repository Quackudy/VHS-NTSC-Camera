package dev.vhs.proof

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size
import kotlin.math.atan

internal data class CameraLens(val openId: String, val physicalId: String?, val characteristics: C) {
    val id get() = physicalId ?: openId
    val fov: Double get() {
        val sensor = characteristics[C.SENSOR_INFO_PHYSICAL_SIZE] ?: return 0.0
        val focal = characteristics[C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull() ?: return 0.0
        return Math.toDegrees(2 * atan(sensor.width / (2.0 * focal)))
    }
    val label get() = "Rear $id · ~${fov.toInt()}°${if (physicalId != null) " (physical)" else ""}"
    fun recordingSizes(): List<Size> {
        val map = characteristics[C.SCALER_STREAM_CONFIGURATION_MAP] ?: return emptyList()
        val preview = map.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
        return (map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList())
            .filter { it in preview && it.width * 3 == it.height * 4 && it.width in 640..1280 }
            .sortedBy { it.width * it.height }
    }
}

internal object CameraCatalog {
    fun rearLenses(context: Context): List<CameraLens> {
        val manager = context.getSystemService(CameraManager::class.java)
        val options = mutableListOf<CameraLens>()
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            if (characteristics[C.LENS_FACING] != C.LENS_FACING_BACK) continue
            val physical = characteristics.physicalCameraIds
            if (physical.isEmpty()) options.add(CameraLens(id, null, characteristics))
            else for (physicalId in physical) {
                options.add(CameraLens(id, physicalId, manager.getCameraCharacteristics(physicalId)))
            }
        }
        return options.distinctBy { it.id }.sortedByDescending { it.fov }
    }

    fun report(context: Context): String {
        val manager = context.getSystemService(CameraManager::class.java)
        return buildString {
            appendLine("Exposed IDs: ${manager.cameraIdList.joinToString()}")
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                appendLine("ID $id: facing=${c[C.LENS_FACING]}, physical=${c.physicalCameraIds}, capabilities=${c[C.REQUEST_AVAILABLE_CAPABILITIES]?.joinToString()}")
                appendLine("  physical request keys=${c.availablePhysicalCameraRequestKeys?.map { it.name }}")
            }
            for (lens in rearLenses(context)) {
                val c = lens.characteristics
                appendLine("${lens.label}: open=${lens.openId}, physical=${lens.physicalId}")
                appendLine("  focal=${c[C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.joinToString()}, sensor=${c[C.SENSOR_INFO_PHYSICAL_SIZE]}, orientation=${c[C.SENSOR_ORIENTATION]}")
                appendLine("  active=${c[C.SENSOR_INFO_ACTIVE_ARRAY_SIZE]}, max crop zoom=${c[C.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]}")
                appendLine("  EIS modes=${c[C.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.joinToString()}, OIS modes=${c[C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.joinToString()}")
                appendLine("  YUV 4:3 <=640=${c[C.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.filter { it.width * 3 == it.height * 4 && it.width <= 640 }}")
                appendLine("  4:3 preview/record sizes=${lens.recordingSizes()}, FPS=${c[C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.joinToString()}")
            }
        }
    }
}
