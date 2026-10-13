package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.summaryParagraphs

internal data class CompressionSummaryMergePlan(
    val textsToMerge: List<String>,
    val preservedSummaries: List<ConversationSummary>,
)

internal fun Conversation.planCompressionSummaryMerge(
    newSummaries: List<String>,
    mergeWithPrevious: Boolean = true,
): CompressionSummaryMergePlan {
    require(newSummaries.isNotEmpty() && newSummaries.all { it.isNotBlank() })
    // Excluded or disabled summaries must never be sent to the compression model.
    if (!mergeWithPrevious || !sendCompressedSummary) {
        return CompressionSummaryMergePlan(newSummaries, compressionSummaries)
    }
    return CompressionSummaryMergePlan(
        textsToMerge = compressionSummaries.filter { it.sendToModel }.map { it.text } + newSummaries,
        preservedSummaries = compressionSummaries.filterNot { it.sendToModel },
    )
}

internal suspend fun mergeExistingSummaryDraft(
    summaries: List<ConversationSummary>,
    enabled: Boolean,
    generateSummary: suspend (String) -> String,
): List<ConversationSummary> {
    require(enabled) { "请先开启发送摘要，再合并已勾选的摘要。" }
    require(summaries.isNotEmpty() && summaries.all { it.text.isNotBlank() }) { "摘要不能为空。" }
    val selected = summaries.filter { it.sendToModel }
    require(selected.isNotEmpty()) { "请至少勾选一段摘要。" }
    val merged = generateSummary(selected.joinToString("\n\n") { it.text }).trim()
    require(merged.isNotBlank()) { "合并结果为空，原摘要未变更。" }
    return summaries.filterNot { it.sendToModel } + summaryParagraphs(merged)
}
