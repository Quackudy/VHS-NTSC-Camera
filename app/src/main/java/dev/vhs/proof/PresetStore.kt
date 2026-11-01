package dev.vhs.proof

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal data class ImportedPreset(val json: String, val remembered: Boolean, val name: String, val warnings: String)

internal object PresetStore {
    const val DEFAULT_JSON = "{\"version\":1}"

    fun reset(context: Context): ImportedPreset {
        val remembered = context.getSharedPreferences("vhs", Context.MODE_PRIVATE).edit()
            .remove("preset").remove("presetName").remove("presetWarnings").commit()
        return ImportedPreset(DEFAULT_JSON, remembered, "ntsc-rs legacy defaults", "")
    }

    fun label(context: Context): String {
        val preferences = context.getSharedPreferences("vhs", Context.MODE_PRIVATE)
        val label = if (preferences.contains("preset")) "Preset: ${preferences.getString("presetName", "imported ntsc-rs preset")}" else
            "Preset: ntsc-rs legacy defaults"
        val warnings = preferences.getString("presetWarnings", "").orEmpty()
        return if (warnings.isBlank()) label else "$label\n$warnings"
    }

    fun import(context: Context, uri: Uri): ImportedPreset {
        val json = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = ByteArray(65537)
            var size = 0
            while (size < bytes.size) {
                val count = stream.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= 65536) { "Preset exceeds 64 KiB. Choose an ntsc-rs JSON preset." }
            val text = try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, size)).toString()
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("This is not a UTF-8 JSON preset. PDF documents are not presets.")
            }
            require(text.trimStart().startsWith("{")) {
                "This is not an ntsc-rs JSON preset. Choose an exported version-1 preset; PDF documents are not supported."
            }
            text
        } ?: error("Cannot open preset")
        val warnings = Native.validatePreset(json)
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
        val displayName = (name ?: uri.lastPathSegment ?: "Imported preset").take(128)
        val remembered = context.getSharedPreferences("vhs", Context.MODE_PRIVATE).edit()
            .putString("preset", json).putString("presetName", displayName).putString("presetWarnings", warnings).commit()
        return ImportedPreset(json, remembered, displayName, warnings)
    }
}
