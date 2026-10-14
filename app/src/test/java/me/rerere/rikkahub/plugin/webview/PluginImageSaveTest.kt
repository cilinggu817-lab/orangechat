package me.rerere.rikkahub.plugin.webview

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import me.rerere.rikkahub.plugin.data.PluginDataStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginImageSaveTest {
    @Test fun saveOriginalAndUpscaledBytesWithoutReencoding() {
        val store = PluginDataStore(RuntimeEnvironment.getApplication(), "image-save-test")
        for (size in listOf(64, 128)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val source = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, source); bitmap.recycle()
            // Include trailing metadata: recompressing would destroy it.
            val bytes = source.toByteArray() + "preserved-source-metadata".toByteArray()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val key = "nai.asset.original_$size"
            val chunks = encoded.chunked(24000)
            chunks.forEachIndexed { i, v -> store.setData("$key.$i", v) }
            store.setData(key, JSONObject().put("chunks", chunks.size).put("mime", "image/png").toString())
            val asset = PluginImageSave.load(store, key)
            val output = ByteArrayOutputStream()
            assertTrue(PluginImageSave.write(asset.encoded, false) { output }.getBoolean("success"))
            assertArrayEquals(bytes, output.toByteArray())
            assertTrue(asset.fileName.endsWith(".png"))
        }
    }

    @Test fun cancellationDoesNotOpenOrReportSuccess() {
        val result = PluginImageSave.write(null, true) { error("Must not open destination") }
        assertFalse(result.getBoolean("success")); assertTrue(result.getBoolean("cancelled"))
    }

    @Test fun nullStreamAndWriteFailureNeverReportSuccess() {
        assertFalse(PluginImageSave.write("YQ==", false) { null }.getBoolean("success"))
        assertFalse(PluginImageSave.write("YQ==", false) { object : OutputStream() {
            override fun write(b: Int) { throw IOException("disk full") }
        } }.getBoolean("success"))
    }
}
