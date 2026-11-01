package dev.vhs.proof

import android.content.Context
import android.net.Uri

internal object PresetStore {
    fun import(context: Context, uri: Uri): Pair<String, Boolean> {
        val json = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = ByteArray(65537)
            var size = 0
            while (size < bytes.size) {
                val count = stream.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= 65536) { "Preset exceeds 64 KiB" }
            String(bytes, 0, size, Charsets.UTF_8)
        } ?: error("Cannot open preset")
        Native.validatePreset(json)
        val remembered = context.getSharedPreferences("vhs", Context.MODE_PRIVATE).edit().putString("preset", json).commit()
        return json to remembered
    }
}
