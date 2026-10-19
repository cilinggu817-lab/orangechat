package me.rerere.rikkahub.data.ai

import android.app.Application
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.ui.hooks.DirectAudioRecorder
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class VoiceAudioPayloadTest {
    @Test fun routesGeminiThroughBothTransportsButRespectsAsrOverride() {
        val model = Model(modelId = "google/gemini-2.5-flash")
        assertTrue(model.supportsDirectAudio(ProviderSetting.Google()))
        assertTrue(model.supportsDirectAudio(ProviderSetting.OpenAI()))
        assertFalse(model.supportsDirectAudio(ProviderSetting.OpenAI(useResponseApi = true)))
        assertFalse(model.copy(audioInputMode = AudioInputMode.ASR).supportsDirectAudio(ProviderSetting.Google()))
        assertFalse(Model(modelId = "text-model").supportsDirectAudio(ProviderSetting.OpenAI()))
        assertTrue(Model(modelId = "audio-alias", audioInputMode = AudioInputMode.AUDIO)
            .supportsDirectAudio(ProviderSetting.OpenAI()))
    }

    @Test fun wavBytesSurviveOpenAiAndGoogleMessageSerialization() {
        val bytes = DirectAudioRecorder.wavHeader(4) + byteArrayOf(0, 0, 1, 0)
        val file = File.createTempFile("voice", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            file.writeBytes(bytes)
            val message = UIMessage(role = MessageRole.USER, parts = listOf(
                UIMessagePart.Text("听听这段音频"), UIMessagePart.Audio(Uri.fromFile(file).toString())))
            val openai = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
            method.isAccessible = true
            val messages = method.invoke(openai, listOf(message), Model(modelId = "gemini-2.5-flash")) as JsonArray
            val audio = messages[0].jsonObject["content"]!!.jsonArray[1].jsonObject
            assertEquals("input_audio", audio["type"]!!.jsonPrimitive.content)
            val data = audio["input_audio"]!!.jsonObject
            assertEquals("wav", data["format"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.decode(data["data"]!!.jsonPrimitive.content, Base64.DEFAULT))
            val google = GoogleProvider(OkHttpClient())
            val googleMethod = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
            googleMethod.isAccessible = true
            val contents = googleMethod.invoke(google, listOf(message)) as JsonArray
            val inline = contents[0].jsonObject["parts"]!!.jsonArray[1].jsonObject["inlineData"]!!.jsonObject
            assertEquals("audio/wav", inline["mimeType"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.decode(inline["data"]!!.jsonPrimitive.content, Base64.DEFAULT))
        } finally { file.delete() }
    }
}
