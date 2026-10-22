package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecentMediaLimitTest {
    private fun voice(i: Int, transcript: String = "转写$i") =
        UIMessagePart.VoiceMessage(url = "/data/voice/$i.m4a", duration = 1000, transcript = transcript)

    private fun user(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.USER, parts = parts.toList())

    @Test
    fun `under the limit returns the same list untouched`() {
        val messages = (1..5).map { user(voice(it)) }
        assertSame(messages, limitRecentAudio(messages))
    }

    @Test
    fun `keeps newest five voices and turns older ones into transcripts`() {
        val messages = (1..8).map { user(UIMessagePart.Text("第$it句"), voice(it)) }
        val result = limitRecentAudio(messages)

        (0 until 3).forEach { index ->
            assertEquals(UIMessagePart.Text("转写${index + 1}"), result[index].parts[1])
            assertEquals(UIMessagePart.Text("第${index + 1}句"), result[index].parts[0])
        }
        (3 until 8).forEach { index -> assertSame(messages[index], result[index]) }
    }

    @Test
    fun `old voice without transcript becomes an honest placeholder`() {
        val messages = listOf(user(voice(0, transcript = ""))) + (1..5).map { user(voice(it)) }
        val result = limitRecentAudio(messages)
        assertEquals(UIMessagePart.Text(OLD_AUDIO_PLACEHOLDER), result[0].parts.single())
    }

    @Test
    fun `counts multiple voices inside one message from oldest first`() {
        val messages = listOf(user(voice(1), voice(2), voice(3)), user(voice(4), voice(5), voice(6)))
        val result = limitRecentAudio(messages)
        assertEquals(UIMessagePart.Text("转写1"), result[0].parts[0])
        assertEquals(voice(2), result[0].parts[1])
        assertSame(messages[1], result[1])
    }

    @Test
    fun `plain audio attachments share the same budget`() {
        val messages = listOf(user(UIMessagePart.Audio("file:///a/old.mp3"))) + (1..5).map { user(voice(it)) }
        val result = limitRecentAudio(messages)
        assertEquals(UIMessagePart.Text(OLD_AUDIO_PLACEHOLDER), result[0].parts.single())
    }

    @Test
    fun `keeps newest five uploaded images and replaces older ones`() {
        val messages = (1..7).map { user(UIMessagePart.Image("file:///upload/$it.png")) }
        val result = limitRecentUploadImages(messages)
        (0 until 2).forEach { assertEquals(UIMessagePart.Text(OLD_IMAGE_PLACEHOLDER), result[it].parts.single()) }
        (2 until 7).forEach { assertSame(messages[it], result[it]) }
    }

    @Test
    fun `remote images and tool result images are not counted or replaced`() {
        val toolImage = UIMessagePart.Image("file:///cache/screenshot.png")
        val tool = UIMessagePart.Tool("t1", "take_screenshot", "{}", listOf(toolImage))
        val remote = UIMessagePart.Image("https://example.com/a.png")
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
            user(remote),
        ) + (1..5).map { user(UIMessagePart.Image("file:///upload/$it.png")) }

        val result = limitRecentUploadImages(messages)
        assertSame(messages, result)
    }

    @Test
    fun `stored history is not mutated`() {
        val messages = (1..7).map { user(voice(it)) }
        val snapshot = messages.map { it.copy() }
        limitRecentAudio(messages)
        assertEquals(snapshot, messages)
    }
}
