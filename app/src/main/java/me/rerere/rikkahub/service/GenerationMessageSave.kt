package me.rerere.rikkahub.service

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.rikkahub.data.ai.hasDurableToolEvidence
import me.rerere.rikkahub.data.ai.recoverToolHistoryForRequest
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Recover selected tool history before applying the legacy incomplete-message cleanup. */
internal fun Conversation.withRecoveredToolHistory(json: Json): Conversation {
    requireMessageNodesLoaded()
    val archived = activeCompressedMessageNodeIds
    val recovered = messageNodes.filterNot { it.id in archived }.map { it.currentMessage }
        .recoverToolHistoryForRequest(json).associateBy { it.id }
    val nodes = messageNodes.mapNotNull { node ->
        if (node.id in archived) return@mapNotNull node
        val selected = recovered.getValue(node.currentMessage.id)
        val tools = selected.getTools()
        val removeSelected = tools.any { !it.isExecuted } && tools.none {
            !it.isExecuted && it.approvalState.canResumeToolExecution()
        }
        val messages = if (removeSelected) node.messages.filterNot { it.id == selected.id } else
            node.messages.map { if (it.id == selected.id) selected else it }
        if (messages.isEmpty()) return@mapNotNull null
        val index = if (removeSelected) node.selectIndex - 1 else node.selectIndex
        node.copy(messages = messages, selectIndex = index.takeIf { it in messages.indices } ?: 0)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

/** A delayed UI emission must never undo a tool checkpoint that has already reached storage. */
internal fun Conversation.withGenerationMessages(
    messages: List<UIMessage>,
    targets: Map<Uuid, Uuid> = emptyMap(),
): Conversation {
    val latest = messageNodes.flatMap { it.messages }.associateBy { it.id }
    val merged = messages.map { incoming ->
        val evidence = latest[incoming.id]?.getTools()?.filter { it.hasDurableToolEvidence }.orEmpty()
        if (evidence.isEmpty()) incoming else {
            val incomingIds = incoming.getTools().map { it.toolCallId }.toSet()
            incoming.copy(parts = incoming.parts.map { part ->
                if (part !is UIMessagePart.Tool) part else {
                    val saved = evidence.find { it.toolCallId == part.toolCallId }
                    if (saved != null && (!part.hasDurableToolEvidence || (saved.isExecuted && !part.isExecuted))) {
                        saved
                    } else part
                }
            } + evidence.filter { it.toolCallId !in incomingIds })
        }
    }
    return updateCurrentMessages(merged, targets)
}
