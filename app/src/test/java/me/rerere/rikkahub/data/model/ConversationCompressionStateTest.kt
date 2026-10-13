package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCompressionStateTest {
    private fun base(): Conversation = Conversation(assistantId = Uuid.random(), messageNodes = (1..6).map {
        MessageNode.of(UIMessage(role = if (it % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
            parts = buildList {
                add(UIMessagePart.Text(if (it < 5) "RAW_ONLY_7K2_$it" else "recent_$it"))
                if (it == 1) add(UIMessagePart.Image("file:///synthetic-image.png"))
            }))
    })
    private fun compressed(c: Conversation = base()) = c.withCompressionResult(
        c, c.compressionCandidates(2), summaryParagraphs("SUMMARY_ONLY_9Q4\n\nEXCLUDE_THIS"),
    )

    @Test fun `compression retains exact node identity alternatives attachments and original content`() {
        val old = base().let { c -> c.copy(messageNodes = c.messageNodes.mapIndexed { i, n ->
            if (i == 0) n.copy(messages = n.messages + UIMessage.user("alternate")) else n
        }) }
        val done = compressed(old)
        assertEquals(old.messageNodes, done.messageNodes)
        assertEquals(old.messageNodes.take(4).map { it.id }.toSet(), done.activeCompressedMessageNodeIds)
        assertEquals(old.currentMessages.takeLast(2), done.messagesForGeneration())
        assertEquals(2, done.visibleMessageNodes.size)
        assertEquals(6, done.messageNodes.size)
    }

    @Test fun `send off or deselection never restores covered originals to request`() {
        val c = compressed()
        assertNull(c.copy(sendCompressedSummary = false).summaryForGeneration())
        assertEquals(c.messagesForGeneration(), c.copy(sendCompressedSummary = false).messagesForGeneration())
        val selected = c.withSummaryEdits(c.compressionRevision,
            c.compressionSummaries.mapIndexed { i, p -> p.copy(sendToModel = i == 0) }, true)
        assertEquals("SUMMARY_ONLY_9Q4", selected.summaryForGeneration())
        assertNull(selected.copy(compressionSummaries = selected.compressionSummaries.map { it.copy(sendToModel = false) }).summaryForGeneration())
    }

    @Test fun `stream chunks match ids after filtering and append once without modifying history`() {
        val c = compressed()
        val output = UIMessage.assistant("new")
        val first = c.updateCurrentMessages(c.messagesForGeneration() + output)
        val second = first.updateCurrentMessages(c.messagesForGeneration() + output.copy(parts = listOf(UIMessagePart.Text("finished"))))
        assertEquals(c.messageNodes, second.messageNodes.take(6))
        assertEquals(7, second.messageNodes.size)
        assertEquals("finished", second.messageNodes.last().currentMessage.toText())
    }

    @Test fun `regeneration adds alternative to explicit target rather than archived first node`() {
        val c = compressed()
        val output = UIMessage.assistant("regenerated")
        val updated = c.updateCurrentMessages(c.messagesForGeneration(0..4) + output,
            mapOf(output.id to c.messageNodes[5].id))
        assertEquals(c.messageNodes.take(5), updated.messageNodes.take(5))
        assertEquals(6, updated.messageNodes.size)
        assertEquals(2, updated.messageNodes[5].messages.size)
        assertEquals(output.id, updated.messageNodes[5].currentMessage.id)
    }

    @Test fun `concurrent append survives compression commit`() {
        val c = base()
        val latest = c.updateCurrentMessages(listOf(UIMessage.user("appended")))
        val done = latest.withCompressionResult(c, c.compressionCandidates(2), summaryParagraphs("summary"))
        assertEquals(latest.messageNodes, done.messageNodes)
        assertEquals("appended", done.currentMessages.last().toText())
    }

    @Test fun `full window from generation started before compression cannot rewrite archived originals`() {
        val old = base()
        val done = compressed(old)
        val lateChunk = old.currentMessages.map { it.copy(parts = listOf(UIMessagePart.Text("late transformed text"))) } +
            UIMessage.assistant("new reply")
        val updated = done.updateCurrentMessages(lateChunk)
        assertEquals(old.messageNodes.take(4), updated.messageNodes.take(4))
        assertEquals("new reply", updated.currentMessages.last().toText())
        assertEquals(7, updated.messageNodes.size)
    }

    @Test fun `editing or switching any source alternative invalidates pending result`() {
        val c = base()
        val sources = c.compressionCandidates(2)
        val changed = c.copy(messageNodes = c.messageNodes.mapIndexed { i, n ->
            if (i == 0) n.copy(messages = n.messages + UIMessage.user("edited"), selectIndex = 1) else n
        })
        assertThrows(IllegalStateException::class.java) {
            changed.withCompressionResult(c, sources, summaryParagraphs("stale"))
        }
        assertThrows(IllegalStateException::class.java) {
            c.copy(messageNodes = c.messageNodes.drop(1)).withCompressionResult(c, sources, summaryParagraphs("stale"))
        }
    }

    @Test fun `summary edits switches undo and other compression reject old commits`() {
        val c = compressed()
        val newer = c.withSummaryEdits(c.compressionRevision, c.compressionSummaries, false)
        assertThrows(IllegalStateException::class.java) { newer.withCompressionResult(c, emptyList(), summaryParagraphs("stale")) }
        assertThrows(IllegalStateException::class.java) { newer.withSummaryEdits(c.compressionRevision, c.compressionSummaries, true) }
        val undone = c.withoutCompression(c.compressionRevision)
        assertEquals(c.currentMessages, undone.messagesForGeneration())
        assertEquals(c.messageNodes, undone.messageNodes)
        assertThrows(IllegalStateException::class.java) { undone.withCompressionResult(c, emptyList(), summaryParagraphs("stale")) }
    }

    @Test fun `blank summary with coverage fails explicitly and remains visible for repair`() {
        val c = base().let { it.copy(compressedMessageNodeIds = it.messageNodes.take(2).map { it.id }.toSet()) }
        assertTrue(c.hasCompressionState)
        assertEquals(c.messageNodes, c.visibleMessageNodes)
        assertThrows(IllegalStateException::class.java) { c.messagesForGeneration() }
        assertThrows(IllegalArgumentException::class.java) { compressed().withSummaryEdits(1, listOf(ConversationSummary(text = " ")), true) }
    }

    @Test fun `dangling coverage ids cannot hide nodes`() {
        val c = compressed().let { it.copy(compressedMessageNodeIds = it.compressedMessageNodeIds + Uuid.random()) }
        assertEquals(4, c.activeCompressedMessageNodeIds.size)
        assertEquals(2, c.messagesForGeneration().size)
    }

    @Test fun `fork within history discards future summary and later fork remaps ids and switches`() {
        val c = compressed().copy(sendCompressedSummary = false)
        val early = c.messageNodes.take(3)
        val earlyFork = c.compressionStateForFork(early, early.map { it.copy(id = Uuid.random()) })
        assertTrue(earlyFork.compressionSummaries.isEmpty())
        assertTrue(earlyFork.compressedMessageNodeIds.isEmpty())
        val copies = c.messageNodes.map { it.copy(id = Uuid.random()) }
        val late = c.compressionStateForFork(c.messageNodes, copies)
        assertEquals(c.compressionSummaries, late.compressionSummaries)
        assertEquals(copies.take(4).map { it.id }.toSet(), late.compressedMessageNodeIds)
        assertFalse(late.sendCompressedSummary)
    }

    @Test fun `history edits and regeneration cannot use future summary`() {
        val c = compressed()
        assertThrows(IllegalStateException::class.java) { c.requireEditableMessage(c.currentMessages[0].id) }
        assertThrows(IllegalStateException::class.java) { c.messagesForGeneration(0..2) }
        c.requireEditableMessage(c.currentMessages.last().id)
    }

    @Test fun `serialization preserves full originals selection revision and disabled switch`() {
        val c = compressed().let { it.withSummaryEdits(it.compressionRevision,
            it.compressionSummaries.map { p -> p.copy(sendToModel = false) }, false) }
        assertEquals(c, JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(c)))
        val fields = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(c)).jsonObject.filterKeys {
            it !in setOf("compressionSummaries", "compressedMessageNodeIds", "compressionRevision", "sendCompressedSummary", "compressionHistory")
        }
        val old = JsonInstant.decodeFromString<Conversation>(JsonObject(fields).toString())
        assertEquals(c.messageNodes, old.messageNodes)
        assertFalse(old.hasCompressionState)
        assertTrue(old.sendCompressedSummary)
        assertEquals(old.currentMessages, old.messagesForGeneration())
    }

    @Test fun `all covered corrupt state shows originals and permits undo instead of sending an empty request`() {
        val c = compressed().let { it.copy(compressedMessageNodeIds = it.messageNodes.map { n -> n.id }.toSet()) }
        assertEquals(c.messageNodes, c.visibleMessageNodes)
        assertTrue(c.hasCompressionState)
        assertThrows(IllegalStateException::class.java) { c.messagesForGeneration() }
        assertEquals(c.currentMessages, c.withoutCompression(c.compressionRevision).messagesForGeneration())
    }

    @Test fun `splitting an excluded edited paragraph preserves its selection and first identity`() {
        val part = ConversationSummary(text = "first\nsecond\n\nthird", sendToModel = false)
        val split = part.splitLines()
        assertEquals(listOf("first", "second", "third"), split.map { it.text })
        assertEquals(part.id, split.first().id)
        assertEquals(3, split.map { it.id }.toSet().size)
        assertTrue(split.all { !it.sendToModel })
    }

    @Test fun `compression retains whole tool turn even when requested recent size is one`() {
        val c = base().let { it.copy(messageNodes = it.messageNodes +
            MessageNode.of(UIMessage(role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool("call", "lookup", "{}", listOf(UIMessagePart.Text("result"))))))) }
        assertEquals(c.messageNodes.take(4), c.compressionCandidates(1))
    }

    @Test fun `undo latest restores previous edited summary selections without releasing older history`() {
        val first = compressed().let { it.withSummaryEdits(it.compressionRevision,
            it.compressionSummaries.mapIndexed { i, p -> p.copy(text = "edited-$i", sendToModel = i == 0) }, false) }
        val appended = first.copy(messageNodes = first.messageNodes + base().messageNodes.take(2))
        val sources = appended.compressionCandidates(2).filterNot { it.id in appended.activeCompressedMessageNodeIds }
        val second = appended.withCompressionResult(appended, sources, summaryParagraphs("merged-summary"))
        assertEquals(2, second.compressionHistory.size)
        assertEquals(2, second.latestCompressionUndoRestoredCount)
        val withReply = second.updateCurrentMessages(listOf(UIMessage.assistant("reply after compression")))
        val undone = withReply.undoLatestCompression(withReply.compressionRevision)
        assertEquals(first.compressionSummaries, undone.compressionSummaries)
        assertEquals(first.compressedMessageNodeIds, undone.compressedMessageNodeIds)
        assertFalse(undone.sendCompressedSummary)
        assertEquals(withReply.messageNodes, undone.messageNodes)
        assertEquals(1, undone.compressionHistory.size)
        assertEquals(withReply.compressionRevision + 1, undone.compressionRevision)
        assertEquals(withReply.currentMessages.drop(4), undone.messagesForGeneration())
        val allRestored = undone.undoLatestCompression(undone.compressionRevision)
        assertEquals(withReply.messageNodes, allRestored.messageNodes)
        assertTrue(allRestored.compressionSummaries.isEmpty())
        assertTrue(allRestored.compressionHistory.isEmpty())
    }

    @Test fun `old compression without checkpoints never silently undoes everything`() {
        val old = compressed().copy(compressionHistory = emptyList())
        assertNull(old.latestCompressionSnapshot)
        assertThrows(IllegalStateException::class.java) { old.undoLatestCompression(old.compressionRevision) }
        val newer = old.copy(messageNodes = old.messageNodes + base().messageNodes.take(2))
        val sources = newer.compressionCandidates(2).filterNot { it.id in newer.activeCompressedMessageNodeIds }
        val second = newer.withCompressionResult(newer, sources, summaryParagraphs("new compression"))
        val undone = second.undoLatestCompression(second.compressionRevision)
        assertEquals(old.compressionSummaries, undone.compressionSummaries)
        assertEquals(old.compressedMessageNodeIds, undone.compressedMessageNodeIds)
        assertNull(undone.latestCompressionSnapshot)
    }

    @Test fun `checkpoints survive restart and later forks remap every snapshot coverage`() {
        val first = compressed()
        val more = first.copy(messageNodes = first.messageNodes + base().messageNodes.take(2))
        val second = more.withCompressionResult(more, more.compressionCandidates(2).filterNot {
            it.id in more.activeCompressedMessageNodeIds
        }, summaryParagraphs("second summary"))
        val restarted = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(second))
        assertEquals(second, restarted)
        assertEquals(first.compressionSummaries, restarted.undoLatestCompression(restarted.compressionRevision).compressionSummaries)
        val copies = restarted.messageNodes.map { it.copy(id = Uuid.random()) }
        val fork = restarted.compressionStateForFork(restarted.messageNodes, copies)
        val undoneFork = fork.undoLatestCompression(fork.compressionRevision)
        assertEquals(copies.take(4).map { it.id }.toSet(), undoneFork.compressedMessageNodeIds)
        assertEquals(copies, undoneFork.messageNodes)
        val early = restarted.messageNodes.take(3)
        assertTrue(restarted.compressionStateForFork(early, copies.take(3)).compressionHistory.isEmpty())
    }

    @Test fun `stale or corrupt single undo is rejected and undo all clears checkpoints`() {
        val c = compressed()
        val edited = c.withSummaryEdits(c.compressionRevision, c.compressionSummaries, false)
        assertThrows(IllegalStateException::class.java) { edited.undoLatestCompression(c.compressionRevision) }
        val corrupted = c.copy(compressionHistory = listOf(ConversationCompressionSnapshot(
            summaries = summaryParagraphs("unknown"), compressedNodeCount = 1,
            lastCompressedNodeId = Uuid.random(), sendCompressedSummary = true)))
        assertNull(corrupted.latestCompressionSnapshot)
        assertThrows(IllegalStateException::class.java) { corrupted.undoLatestCompression(corrupted.compressionRevision) }
        val undone = c.withoutCompression(c.compressionRevision)
        assertTrue(undone.compressionHistory.isEmpty())
        assertTrue(undone.compressedMessageNodeIds.isEmpty())
    }
}
