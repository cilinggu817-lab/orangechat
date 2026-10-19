/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.provider.supportsDirectAudio
import me.rerere.rikkahub.data.datastore.findProvider
import java.io.File
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Keep voice bubbles in storage; send audio to capable models and transcripts to other models.
 */
object VoiceMessageTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val directAudio = ctx.model.supportsDirectAudio(ctx.model.findProvider(ctx.settings.providers))
        return messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.VoiceMessage -> {
                            if (directAudio) {
                                val url = if (part.url.startsWith("/")) android.net.Uri.fromFile(File(part.url)).toString() else part.url
                                UIMessagePart.Audio(url = url)
                            } else if (part.transcript.isNotBlank()) {
                                UIMessagePart.Text(text = part.transcript)
                            } else {
                                UIMessagePart.Text(text = "[语音消息：当前模型无法读取此音频，且没有转写文本]")
                            }
                        }
                        else -> part
                    }
                }
            )
        }
    }
}