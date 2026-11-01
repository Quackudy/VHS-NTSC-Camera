package dev.vhs.proof

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CancellationException

internal object GalleryVideos {
    fun saveProcessed(context: Context, file: File, isCancelled: () -> Boolean): Uri {
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted || isCancelled()) throw CancellationException("Export cancelled")
        }
        checkCancelled()
        require(file.isFile && file.length() > 0) { "Processed video is missing or empty" }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VHS-processed-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VHS")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: error("Cannot create processed video in Gallery")
        var published = false
        try {
            resolver.openOutputStream(uri, "w")?.use { destination ->
                file.inputStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val size = source.read(buffer)
                        if (size < 0) break
                        destination.write(buffer, 0, size)
                    }
                }
            } ?: error("Cannot open Gallery destination")
            checkCancelled()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) > 0) {
                "Cannot publish processed video in Gallery"
            }
            published = true
            return uri
        } finally {
            if (!published) resolver.delete(uri, null, null)
        }
    }
}
