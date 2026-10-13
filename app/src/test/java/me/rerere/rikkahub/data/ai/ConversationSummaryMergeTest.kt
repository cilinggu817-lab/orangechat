package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.summaryParagraphs
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSummaryMergeTest {
    @Test fun `existing summary merge sends only selected draft text and previews without mutating original`() = runBlocking {
        val original = compressed()
        val draft = original.compressionSummaries + ConversationSummary(text = "latest detail")
        var calls = 0
        val result = mergeExistingSummaryDraft(draft, true) { content ->
            calls++
            assertEquals("old selected\n\nlatest detail", content)
            assertFalse(content.contains("old excluded"))
            assertFalse(content.contains("raw_"))
            "  shorter summary\n\nrecent detail  "
        }
        assertEquals(1, calls)
        assertEquals(draft[1], result[0])
        assertEquals(listOf("old excluded", "shorter summary", "recent detail"), result.map { it.text })
        assertTrue(result.drop(1).all { it.sendToModel })
        assertEquals(listOf("old selected", "old excluded"), original.compressionSummaries.map { it.text })
        assertEquals("latest detail", draft.last().text)
    }

    @Test fun `existing merge also recompresses one selected entry to requested target`() = runBlocking {
        val base = compressed()
        val result = mergeExistingSummaryDraft(base.compressionSummaries, true) { input ->
            assertEquals("old selected", input)
            "shorter"
        }
        assertEquals(base.compressionSummaries[1], result.first())
        assertEquals("shorter", result.last().text)
    }

    @Test fun `disabled sending or no selected entries blocks generation before any request`() = runBlocking {
        val entries = compressed().compressionSummaries
        for ((draft, enabled) in listOf(entries to false, entries.map { it.copy(sendToModel = false) } to true)) {
            var called = false
            try {
                mergeExistingSummaryDraft(draft, enabled) { called = true; "unexpected" }
                fail("invalid merge should be rejected")
            } catch (_: IllegalArgumentException) {
                assertFalse(called)
            }
        }
    }

    @Test fun `blank result and failed request keep original summaries and checkpoints intact`() = runBlocking {
        val original = compressed()
        val snapshot = JsonInstant.encodeToString(original)
        for (mode in listOf("blank", "failure")) {
            try {
                mergeExistingSummaryDraft(original.compressionSummaries, true) {
                    if (mode == "failure") throw IllegalStateException("synthetic network failure")
                    " \n "
                }
                fail("invalid request should fail")
            } catch (_: IllegalArgumentException) {
                assertEquals("blank", mode)
            } catch (_: IllegalStateException) {
                assertEquals("failure", mode)
            }
            assertEquals(snapshot, JsonInstant.encodeToString(original))
        }
    }

    @Test fun `cancelled merge propagates cancellation without editing source`() = runBlocking {
        val original = compressed()
        val entries = original.compressionSummaries.toList()
        try {
            mergeExistingSummaryDraft(entries, true) { throw CancellationException("cancelled") }
            fail("cancellation should propagate")
        } catch (_: CancellationException) {
            assertEquals(entries, original.compressionSummaries)
        }
    }

    @Test fun `saving merged draft preserves raw history coverage and compression undo checkpoints`() = runBlocking {
        val original = compressed()
        val result = mergeExistingSummaryDraft(original.compressionSummaries, true) { "shorter" }
        val saved = original.withSummaryEdits(original.compressionRevision, result, true)
        assertEquals(original.messageNodes, saved.messageNodes)
        assertEquals(original.compressedMessageNodeIds, saved.compressedMessageNodeIds)
        assertEquals(original.compressionHistory, saved.compressionHistory)
        assertEquals(original.compressionRevision + 1, saved.compressionRevision)
        val reopened = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(saved))
        assertEquals(saved.compressionSummaries, reopened.compressionSummaries)
        val undone = reopened.undoLatestCompression(reopened.compressionRevision)
        assertTrue(undone.compressionSummaries.isEmpty())
        assertEquals(original.messageNodes, undone.messageNodes)
    }

    @Test fun `merged preview cannot overwrite a concurrent summary edit`() = runBlocking {
        val original = compressed()
        val result = mergeExistingSummaryDraft(original.compressionSummaries, true) { "preview" }
        val concurrent = original.withSummaryEdits(original.compressionRevision,
            original.compressionSummaries.map { it.copy(text = "concurrent " + it.text) }, false)
        try {
            concurrent.withSummaryEdits(original.compressionRevision, result, true)
            fail("stale preview must be rejected")
        } catch (_: IllegalStateException) {
            assertFalse(concurrent.sendCompressedSummary)
            assertTrue(concurrent.compressionSummaries.all { it.text.startsWith("concurrent ") })
        }
    }

    private fun compressed(): Conversation {
        val original = Conversation(
            assistantId = Uuid.random(),
            messageNodes = (1..8).map { index ->
                MessageNode.of(UIMessage(
                    role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("raw_$index")),
                ))
            },
        )
        return original.withCompressionResult(
            original,
            original.compressionCandidates(4),
            listOf(
                ConversationSummary(text = "old selected"),
                ConversationSummary(text = "old excluded", sendToModel = false),
            ),
        )
    }

    @Test fun `merge defaults to selected old summaries and preserves excluded entries`() {
        val base = compressed()
        val plan = base.planCompressionSummaryMerge(listOf("new chunk one", "new chunk two"))
        assertEquals(listOf("old selected", "new chunk one", "new chunk two"), plan.textsToMerge)
        assertEquals(listOf(base.compressionSummaries[1]), plan.preservedSummaries)
    }

    @Test fun `separate mode keeps exact old ids text order and sending choices out of compression input`() {
        val base = compressed()
        val plan = base.planCompressionSummaryMerge(listOf("new summary"), mergeWithPrevious = false)
        assertEquals(listOf("new summary"), plan.textsToMerge)
        assertEquals(base.compressionSummaries, plan.preservedSummaries)
        val sources = base.compressionCandidates(2)
        val updated = base.withCompressionResult(base, sources,
            plan.preservedSummaries + summaryParagraphs(plan.textsToMerge.single()))
        assertEquals(base.compressionSummaries, updated.compressionSummaries.take(2))
        assertEquals("old selected\n\nnew summary", updated.summaryForGeneration())
        assertEquals(base.messageNodes, updated.messageNodes)
        assertEquals(base.activeCompressedMessageNodeIds + sources.map { it.id }, updated.activeCompressedMessageNodeIds)
        assertEquals(base.messageNodes.takeLast(2).map { it.currentMessage }, updated.messagesForGeneration())
    }

    @Test fun `disabled sending keeps every old entry out of compression in both merge modes`() {
        val base = compressed().copy(sendCompressedSummary = false)
        for (merge in listOf(true, false)) {
            val plan = base.planCompressionSummaryMerge(listOf("new summary"), merge)
            assertEquals(listOf("new summary"), plan.textsToMerge)
            assertEquals(base.compressionSummaries, plan.preservedSummaries)
            val updated = base.withCompressionResult(base, base.compressionCandidates(2),
                plan.preservedSummaries + summaryParagraphs("new summary"))
            assertFalse(updated.sendCompressedSummary)
            assertNull(updated.summaryForGeneration())
        }
    }

    @Test fun `multiple new chunks still merge only this round when previous merging is off`() {
        val base = compressed()
        val plan = base.planCompressionSummaryMerge(listOf("part one", "part two"), false)
        assertEquals(listOf("part one", "part two"), plan.textsToMerge)
        assertEquals(base.compressionSummaries, plan.preservedSummaries)
    }

    @Test fun `no selected old summaries leaves them intact in either mode`() {
        val base = compressed().let { it.copy(compressionSummaries = it.compressionSummaries.map { entry ->
            entry.copy(sendToModel = false)
        }) }
        for (merge in listOf(true, false)) {
            val plan = base.planCompressionSummaryMerge(listOf("new summary"), merge)
            assertEquals(listOf("new summary"), plan.textsToMerge)
            assertEquals(base.compressionSummaries, plan.preservedSummaries)
        }
    }

    @Test fun `separate then merge survives serialization and each undo restores its own preceding state`() {
        val first = compressed()
        val separatePlan = first.planCompressionSummaryMerge(listOf("second round"), false)
        val second = first.withCompressionResult(first, first.compressionCandidates(2),
            separatePlan.preservedSummaries + summaryParagraphs("second round"))
        val appended = second.copy(messageNodes = second.messageNodes + listOf(
            MessageNode.of(UIMessage.user("later user")),
            MessageNode.of(UIMessage.assistant("later assistant")),
        ))
        val mergePlan = appended.planCompressionSummaryMerge(listOf("third round"), true)
        assertEquals(listOf("old selected", "second round", "third round"), mergePlan.textsToMerge)
        val third = appended.withCompressionResult(appended, appended.compressionCandidates(2),
            mergePlan.preservedSummaries + summaryParagraphs("combined rounds"))
        val reopened = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(third))
        val undoThird = reopened.undoLatestCompression(reopened.compressionRevision)
        assertEquals(second.compressionSummaries, undoThird.compressionSummaries)
        assertEquals(second.activeCompressedMessageNodeIds, undoThird.activeCompressedMessageNodeIds)
        assertEquals(third.messageNodes, undoThird.messageNodes)
        val undoSecond = undoThird.undoLatestCompression(undoThird.compressionRevision)
        assertEquals(first.compressionSummaries, undoSecond.compressionSummaries)
        assertEquals(first.activeCompressedMessageNodeIds, undoSecond.activeCompressedMessageNodeIds)
    }
}
