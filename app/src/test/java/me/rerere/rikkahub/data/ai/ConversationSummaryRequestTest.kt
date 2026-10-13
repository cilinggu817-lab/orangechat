package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.CodexResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.summaryParagraphs
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

class ConversationSummaryRequestTest {
    private fun compressed(): Conversation {
        val c = Conversation(assistantId = Uuid.random(), messageNodes = (1..6).map {
            MessageNode.of(UIMessage(role = if (it % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(if (it < 5) "RAW_ONLY_7K2" else "RECENT_$it"))))
        })
        return c.withCompressionResult(c, c.compressionCandidates(2),
            summaryParagraphs("SUMMARY_ONLY_9Q4\n\nOMITTED_1A2")).let {
                it.copy(compressionSummaries = it.compressionSummaries.mapIndexed { index, p -> p.copy(sendToModel = index == 0) })
            }
    }

    // Invoke the actual final request serializers, using no credentials or network.
    private fun bodies(messages: List<UIMessage>): List<JsonObject> {
        val http = OkHttpClient()
        val params = TextGenerationParams(model = Model(modelId = "synthetic-model"))
        fun call(instance: Any, name: String, vararg args: Any): JsonObject {
            val method = instance.javaClass.declaredMethods.single {
                it.name.startsWith(name) && it.parameterCount == args.size && it.returnType == JsonObject::class.java
            }
            method.isAccessible = true
            return method.invoke(instance, *args) as JsonObject
        }
        return listOf(false, true).flatMap { stream -> listOf(
            call(ChatCompletionsAPI(http, KeyRoulette.default()), "buildChatCompletionRequest", messages, params, ProviderSetting.OpenAI(), stream),
            call(ResponseAPI(http), "buildRequestBody", ProviderSetting.OpenAI(), messages, params, stream),
            call(GoogleProvider(http), "buildCompletionRequestBody", messages, params),
            call(ClaudeProvider(http), "buildMessageRequest", ProviderSetting.Claude(), messages, params, stream),
            call(CodexResponseAPI(http), "buildRequestBody", messages, params),
        ) }
    }

    @Test fun `serialized requests retain selected summary after tiny sliding window regardless of persona override`() {
        val c = compressed()
        for (system in listOf("assistant persona", "conversation persona", "")) {
            val request = buildConversationRequestMessages(system, c.messagesForGeneration(), 1, c.summaryForGeneration())
            for (body in bodies(request)) {
                val serialized = body.toString()
                assertTrue(serialized, serialized.contains("SUMMARY_ONLY_9Q4"))
                assertFalse(serialized.contains("RAW_ONLY_7K2"))
                assertFalse(serialized.contains("OMITTED_1A2"))
                assertTrue(serialized.contains("RECENT_6"))
                assertEquals(1, Regex("SUMMARY_ONLY_9Q4").findAll(serialized).count())
            }
        }
    }

    @Test fun `disabled summary and deselect all disappear in all final serializers without old raw history`() {
        val c = compressed()
        for (off in listOf(c.copy(sendCompressedSummary = false), c.copy(compressionSummaries =
            c.compressionSummaries.map { it.copy(sendToModel = false) }))) {
            val request = buildConversationRequestMessages("persona", off.messagesForGeneration(), 1, off.summaryForGeneration())
            for (body in bodies(request)) {
                assertFalse(body.toString().contains("SUMMARY_ONLY_9Q4"))
                assertFalse(body.toString().contains("RAW_ONLY_7K2"))
                assertTrue(body.toString().contains("RECENT_6"))
            }
        }
    }

    @Test fun `tool continuation and retries include selected summary exactly once without mutating stored history`() {
        val c = compressed()
        val originals = c.messageNodes
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("synthetic_call", "lookup", "{}", listOf(UIMessagePart.Text("synthetic_result"))),
        ))
        repeat(2) {
            val request = buildConversationRequestMessages("persona", c.messagesForGeneration() + tool, 2, c.summaryForGeneration())
            for (body in bodies(request)) {
                val serialized = body.toString()
                assertTrue(serialized.contains("synthetic_result"))
                assertEquals(1, Regex("SUMMARY_ONLY_9Q4").findAll(serialized).count())
                assertFalse(serialized.contains("RAW_ONLY_7K2"))
                assertFalse(serialized.contains("OMITTED_1A2"))
            }
        }
        assertEquals(originals, c.messageNodes)
    }
}
