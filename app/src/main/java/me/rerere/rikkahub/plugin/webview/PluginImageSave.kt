package me.rerere.rikkahub.plugin.webview

import android.graphics.BitmapFactory
import android.util.Base64
import me.rerere.rikkahub.plugin.data.PluginDataStore
import org.json.JSONObject
import java.io.OutputStream

/** Original bytes stay inside the host; the WebView only passes an asset key. */
internal object PluginImageSave {
    data class Asset(val fileName: String, val encoded: String)

    fun load(store: PluginDataStore, key: String): Asset {
        require(Regex("nai\\.asset\\.[A-Za-z0-9_-]{8,80}").matches(key))
        val metadata = requireNotNull(store.getData(key))
        require(metadata.length <= 4096)
        val meta = JSONObject(metadata)
        val count = meta.getInt("chunks")
        require(count in 1..700)
        val encoded = buildString {
            repeat(count) { index ->
                val chunk = requireNotNull(store.getData("$key.$index"))
                require(chunk.length <= 24000 && length + chunk.length <= 16000000)
                append(chunk)
            }
        }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.isNotEmpty() && bytes.size <= 12000000)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
        require(bounds.outWidth.toLong() * bounds.outHeight <= 16777216)
        require(meta.getString("mime") == bounds.outMimeType)
        val extension = when (bounds.outMimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> error("Unsupported image format")
        }
        return Asset("${key.removePrefix("nai.asset.")}.$extension", encoded)
    }

    fun write(encoded: String?, cancelled: Boolean, open: () -> OutputStream?): JSONObject {
        if (cancelled) return JSONObject().put("success", false).put("cancelled", true)
            .put("error", "User cancelled")
        return try {
            val bytes = Base64.decode(requireNotNull(encoded), Base64.DEFAULT)
            requireNotNull(open()) { "Cannot open save destination" }.use {
                it.write(bytes)
                it.flush()
            }
            JSONObject().put("success", true)
        } catch (_: Exception) {
            JSONObject().put("success", false).put("error", "File could not be saved")
        }
    }
}
