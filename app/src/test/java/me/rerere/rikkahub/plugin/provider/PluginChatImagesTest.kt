package me.rerere.rikkahub.plugin.provider

import android.app.Application
import android.graphics.Bitmap
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.plugin.data.PluginDataStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginChatImagesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val pluginId = "com.nanzhi.naistudio"
    private fun message(uri: String, role: MessageRole = MessageRole.USER) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Image(uri)))
    private fun execute(service: PluginChatImages, action: String, messages: List<UIMessage>, id: String = "") =
        runBlocking { service.execute(pluginId, buildJsonObject {
            put("action", action); put("attachment_id", id)
        }, messages, "test-chat").jsonObject }

    @Test fun branchAndRoleIsolation() {
        val service = PluginChatImages(context)
        val old = message("file:///old.png")
        val selected = message("file:///new.png")
        val otherChat = message("file:///other.png")
        val assistant = message("file:///assistant.png", MessageRole.ASSISTANT)
        val branch = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode(messages = listOf(old, selected), selectIndex = 1), MessageNode.of(assistant),
        )).currentMessages
        val listed = execute(service, "list", branch)["images"]!!.jsonArray
        assertEquals(1, listed.size)
        assertEquals(service.sources(listOf(selected), "test-chat").single().id, listed.single().jsonObject["attachment_id"]!!.jsonPrimitive.content)
        for (foreign in listOf(old, otherChat, assistant)) {
            val foreignId = service.sources(listOf(foreign.copy(role = MessageRole.USER)), "test-chat").single().id
            assertFalse(execute(service, "import", branch, foreignId)["success"]!!.jsonPrimitive.boolean)
        }
        val clonedChatId = service.sources(listOf(selected), "another-chat").single().id
        assertFalse(execute(service, "import", branch, clonedChatId)["success"]!!.jsonPrimitive.boolean)
        val editedSameMessage = selected.copy(parts = listOf(UIMessagePart.Image("file:///edited.png")))
        val staleId = service.sources(listOf(selected), "test-chat").single().id
        assertFalse(execute(service, "import", listOf(editedSameMessage), staleId)["success"]!!.jsonPrimitive.boolean)
        assertEquals(0, execute(service, "list", emptyList())["images"]!!.jsonArray.size)
    }

    @Test fun localImportsPreserveDefaultsAndDeduplicate() {
        val service = PluginChatImages(context)
        val store = PluginDataStore(context, pluginId)
        store.setData("nai.studio.v2", "unchanged-defaults")
        val directory = File(context.filesDir, "upload").apply { mkdirs() }
        for ((format, extension) in listOf(Bitmap.CompressFormat.PNG to "png", Bitmap.CompressFormat.JPEG to "jpg", Bitmap.CompressFormat.WEBP to "webp")) {
            val file = File(directory, "cat.$extension")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.CYAN)
            file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }; bitmap.recycle()
            val uri = "content://atelier-test/cat.$extension".toUri()
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { file.inputStream() }
            val m = message(uri.toString())
            val attachmentId = service.sources(listOf(m), "test-chat").single().id
            val first = execute(service, "import", listOf(m), attachmentId)
            assertTrue(first.toString(), first["success"]!!.jsonPrimitive.boolean)
            val second = execute(service, "import", listOf(m), attachmentId)
            assertEquals(first["image_id"], second["image_id"])
            val key = "nai.asset.${first["image_id"]!!.jsonPrimitive.content}"
            assertEquals("image/png", Json.parseToJsonElement(store.getData(key)!!).jsonObject["mime"]!!.jsonPrimitive.content)
            assertEquals("unchanged-defaults", store.getData("nai.studio.v2"))
            assertFalse(first.toString().contains("base64"))
        }
    }

    @Test fun imageDocumentsOnlyAndUntrustedPathsRejected() {
        val service = PluginChatImages(context)
        val m = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Document(url = "file:///outside/cat.png", fileName = "cat.png", mime = "image/png"),
            UIMessagePart.Document(url = "file:///outside/a.txt", fileName = "a.txt", mime = "text/plain"),
        ))
        val sources = service.sources(listOf(m), "test-chat")
        assertEquals(1, sources.size)
        assertFalse(execute(service, "import", listOf(m), sources.single().id)["success"]!!.jsonPrimitive.boolean)
    }
}
