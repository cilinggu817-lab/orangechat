package me.rerere.rikkahub.plugin.provider

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginReferenceAssetsTest {
    private val store get() = PluginDataStore(RuntimeEnvironment.getApplication(), "reference-test")
    private fun asset(id: String, width: Int, height: Int, color: Int = Color.RED): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val parts = encoded.chunked(24000)
        parts.forEachIndexed { i, part -> store.setData("nai.asset.$id.$i", part) }
        store.setData("nai.asset.$id", JSONObject().put("chunks", parts.size).put("mime", "image/png").toString())
        return encoded
    }
    private fun bitmap(id: String): Bitmap {
        val count = JSONObject(store.getData("nai.asset.$id")!!).getInt("chunks")
        val encoded = (0 until count).joinToString("") { store.getData("nai.asset.$id.$it")!! }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test fun precisePadsBlackAndPreservesSource() {
        val original = asset("source_cat", 64, 128)
        val prepared = JSONObject(PluginReferenceAssets.prepare(store, "source_cat", "precise"))
        val image = bitmap(prepared.getString("image_id"))
        assertEquals(1024, image.width); assertEquals(1536, image.height)
        assertEquals(Color.BLACK, image.getPixel(0, 0)); assertEquals(Color.RED, image.getPixel(512, 768))
        image.recycle()
        assertEquals(original, store.getData("nai.asset.source_cat.0"))
    }

    @Test fun contentIdentityAndPreparationAreIndependent() {
        asset("first_cat", 64, 64); asset("second_cat", 64, 64)
        val first = JSONObject(PluginReferenceAssets.prepare(store, "first_cat", "vibe"))
        val same = JSONObject(PluginReferenceAssets.prepare(store, "second_cat", "vibe"))
        assertEquals(first.getString("source_sha256"), same.getString("source_sha256"))
        assertEquals(first.getString("image_id"), same.getString("image_id"))
        val precise = JSONObject(PluginReferenceAssets.prepare(store, "first_cat", "precise"))
        assertNotEquals(first.getString("image_id"), precise.getString("image_id"))
        asset("first_cat", 64, 64, Color.BLUE)
        val changed = JSONObject(PluginReferenceAssets.prepare(store, "first_cat", "vibe"))
        assertNotEquals(first.getString("source_sha256"), changed.getString("source_sha256"))
    }

    @Test fun vibeCapsPixelsWithoutChangingSource() {
        asset("large_cat", 1600, 1200)
        val prepared = JSONObject(PluginReferenceAssets.prepare(store, "large_cat", "vibe"))
        val image = bitmap(prepared.getString("image_id"))
        assertTrue(image.width * image.height <= 1048576); image.recycle()
        val original = bitmap("large_cat"); assertEquals(1600, original.width); assertEquals(1200, original.height); original.recycle()
    }
}
