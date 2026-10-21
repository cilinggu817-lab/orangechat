package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import kotlin.uuid.Uuid

internal fun Settings.compressionModelFor(assistantId: Uuid): Model? =
    compressModelId?.let { findModelById(it) } ?: getChatModelForAssistant(assistantId)

@Suppress("DEPRECATION")
private fun UIMessagePart.compressionText(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Tool -> "[Tool $toolName, id=$toolCallId]\nInput: $input\nOutput:\n" +
        output.joinToString("\n") { it.compressionText() }
    is UIMessagePart.ToolCall -> "[Tool call $toolName, id=$toolCallId]\n$arguments"
    is UIMessagePart.ToolResult -> "[Tool result $toolName, id=$toolCallId]\nArguments: $arguments\n$content"
    is UIMessagePart.VoiceMessage -> "[Voice message]\n$transcript"
    is UIMessagePart.Document -> "[Document: $fileName, $mime] ${compressionReference(url)}"
    is UIMessagePart.Image -> "[Image] ${compressionReference(url)}"
    is UIMessagePart.Audio -> "[Audio] ${compressionReference(url)}"
    is UIMessagePart.Video -> "[Video] ${compressionReference(url)}"
    is UIMessagePart.Reasoning, UIMessagePart.Search -> ""
}

private fun compressionReference(url: String): String =
    if (url.startsWith("data:", ignoreCase = true)) "[embedded media; content unavailable as text]" else url

internal fun UIMessage.compressionTranscript(): String =
    "[${role.name}]: " + parts.map { it.compressionText() }.filter { it.isNotBlank() }.joinToString("\n")

// A conservative character bound, not a model-specific token count. Never truncate source content.
internal const val COMPRESSION_INPUT_CHARS = 24_000

internal fun splitCompressionInput(texts: List<String>, maxChars: Int = COMPRESSION_INPUT_CHARS): List<String> {
    require(maxChars >= 2)
    val text = texts.joinToString("\n\n")
    if (text.isBlank()) return emptyList()
    return buildList {
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline >= start + maxChars / 2) end = newline + 1
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            add(text.substring(start, end))
            start = end
        }
    }
}

internal suspend fun compressTextChunks(
    texts: List<String>,
    summarize: suspend (String) -> String,
): String = compressTextChunksWithProgress(texts, {}, summarize)

internal suspend fun compressTextChunksWithProgress(
    texts: List<String>,
    onProgress: (String) -> Unit,
    summarize: suspend (String) -> String,
): String {
    var chunks = splitCompressionInput(texts)
    require(chunks.isNotEmpty()) { "没有可压缩的内容" }
    val semaphore = Semaphore(2)
    // Bound work if a provider ignores the length instruction or repeats its input.
    repeat(4) { round ->
        var completed = 0
        val progressLock = Mutex()
        onProgress("第 ${round + 1} 轮：已完成 $completed/${chunks.size} 批")
        val summaries = coroutineScope {
            chunks.map { chunk ->
                async {
                    semaphore.withPermit {
                        summarize(chunk).also {
                            progressLock.withLock {
                                completed++
                                onProgress("第 ${round + 1} 轮：已完成 $completed/${chunks.size} 批")
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        check(summaries.all { it.isNotBlank() }) { "模型返回了空摘要，原文未修改" }
        if (summaries.size == 1) return summaries.single()
        val next = splitCompressionInput(summaries)
        check(next.sumOf { it.length } < chunks.sumOf { it.length }) { "摘要未缩短，请调整压缩模型或提示词后重试" }
        chunks = next
    }
    error("摘要仍然过长，请分批压缩或降低摘要长度后重试")
}

internal fun UIMessage.compressionSummaryText(): String {
    @Suppress("DEPRECATION")
    check(parts.none { it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult }) {
        "模型返回了工具调用，未生成摘要；原文未修改"
    }
    return toText().replace(Regex("<think>[\\s\\S]*?(?:</think>|$)"), "").trim()
        .takeIf { it.isNotBlank() } ?: error("模型未返回有效摘要，原文未修改")
}
