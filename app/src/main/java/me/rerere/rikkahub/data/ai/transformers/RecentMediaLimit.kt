/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 请求里最多带几条原始语音 / 几张上传图片；更早的只在请求中降级，存储的消息不动。 */
const val RECENT_AUDIO_LIMIT = 5
const val RECENT_IMAGE_LIMIT = 5

const val OLD_AUDIO_PLACEHOLDER = "[较早的语音，未附带原音频，也没有转写文本]"
const val OLD_IMAGE_PLACEHOLDER = "[较早的图片，为节省内存未附带原图]"

/**
 * 从最新往前数，保留 [keep] 个满足 [isTarget] 的顶层 part，更早的交给 [downgrade] 替换。
 * 只处理消息的顶层 parts；工具结果里的图片属于工具观察，不在这里计数或替换。
 */
fun limitRecentParts(
    messages: List<UIMessage>,
    keep: Int,
    isTarget: (UIMessagePart) -> Boolean,
    downgrade: (UIMessagePart) -> UIMessagePart,
): List<UIMessage> {
    val total = messages.sumOf { message -> message.parts.count(isTarget) }
    if (total <= keep) return messages
    var toDowngrade = total - keep.coerceAtLeast(0)
    // 消息按时间正序，前 toDowngrade 个目标就是最旧的那些
    return messages.map { message ->
        if (toDowngrade == 0 || message.parts.none(isTarget)) return@map message
        message.copy(parts = message.parts.map { part ->
            if (toDowngrade > 0 && isTarget(part)) {
                toDowngrade--
                downgrade(part)
            } else {
                part
            }
        })
    }
}

fun isHistoryAudio(part: UIMessagePart): Boolean =
    part is UIMessagePart.VoiceMessage || part is UIMessagePart.Audio

/** 较早的语音：有转写就发转写，没有就发占位说明，避免模型瞎猜。 */
fun downgradeOldAudio(part: UIMessagePart): UIMessagePart = when (part) {
    is UIMessagePart.VoiceMessage ->
        if (part.transcript.isNotBlank()) UIMessagePart.Text(part.transcript)
        else UIMessagePart.Text(OLD_AUDIO_PLACEHOLDER)
    else -> UIMessagePart.Text(OLD_AUDIO_PLACEHOLDER)
}

/** 只限制本地上传的图片（file:），网络图片不经过本地编码，不占内存。 */
fun isHistoryUploadImage(part: UIMessagePart): Boolean =
    part is UIMessagePart.Image && part.url.startsWith("file:")

fun downgradeOldImage(part: UIMessagePart): UIMessagePart = UIMessagePart.Text(OLD_IMAGE_PLACEHOLDER)

fun limitRecentAudio(messages: List<UIMessage>, keep: Int = RECENT_AUDIO_LIMIT): List<UIMessage> =
    limitRecentParts(messages, keep, ::isHistoryAudio, ::downgradeOldAudio)

fun limitRecentUploadImages(messages: List<UIMessage>, keep: Int = RECENT_IMAGE_LIMIT): List<UIMessage> =
    limitRecentParts(messages, keep, ::isHistoryUploadImage, ::downgradeOldImage)
