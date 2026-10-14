package me.rerere.rikkahub.plugin.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import me.rerere.rikkahub.plugin.data.PluginDataStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Local-only reference preparation; does not encode Vibe or make network requests. */
internal object PluginReferenceAssets {
    fun prepare(store: PluginDataStore, id: String, purpose: String): String {
        require(Regex("[A-Za-z0-9_-]{8,80}").matches(id))
        require(purpose in listOf("vibe", "precise"))
        val key = "nai.asset.$id"
        val meta = JSONObject(requireNotNull(store.getData(key)))
        val count = meta.getInt("chunks")
        require(count in 1..700)
        val encoded = buildString {
            repeat(count) { i ->
                val part = requireNotNull(store.getData("$key.$i"))
                require(part.length <= 24000 && length + part.length <= 16000000)
                append(part)
            }
        }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val preparedId = "ref_v1_${purpose}_${hash.take(40)}"
        val preparedKey = "nai.asset.$preparedId"
        if (store.getData(preparedKey) == null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
            require(bounds.outWidth.toLong() * bounds.outHeight <= 16777216)
            require(bounds.outMimeType in listOf("image/png", "image/jpeg", "image/webp"))
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                val (width, height) = if (purpose == "precise") {
                    val ratio = bitmap.width.toDouble() / bitmap.height
                    listOf(1024 to 1536, 1536 to 1024, 1472 to 1472)
                        .minBy { abs(it.first.toDouble() / it.second - ratio) }
                } else {
                    val scale = min(1.0, sqrt(1048576.0 / (bitmap.width.toDouble() * bitmap.height)))
                    (bitmap.width * scale).toInt().coerceAtLeast(1) to (bitmap.height * scale).toInt().coerceAtLeast(1)
                }
                val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val out = ByteArrayOutputStream()
                try {
                    val canvas = Canvas(target)
                    if (purpose == "precise") canvas.drawColor(Color.BLACK)
                    val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                    val w = (bitmap.width * scale).roundToInt()
                    val h = (bitmap.height * scale).roundToInt()
                    val left = ((width - w) / 2f).roundToInt()
                    val top = ((height - h) / 2f).roundToInt()
                    canvas.drawBitmap(bitmap, null, android.graphics.Rect(left, top, left + w, top + h),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    check(target.compress(Bitmap.CompressFormat.PNG, 100, out))
                } finally { target.recycle() }
                val result = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                require(result.length <= 16000000)
                val chunks = result.chunked(24000)
                chunks.forEachIndexed { index, text -> store.setData("$preparedKey.$index", text) }
                store.setData(preparedKey, JSONObject().put("chunks", chunks.size).put("mime", "image/png")
                    .put("width", width).put("height", height).toString())
            } finally { bitmap.recycle() }
        }
        return JSONObject().put("image_id", preparedId).put("source_sha256", hash)
            .put("preparation", "atelier-reference-v1").toString()
    }
}
