package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.buildCompressionPrompt
import me.rerere.rikkahub.data.datastore.Settings
import java.util.Locale
import kotlin.uuid.Uuid

/** Resolves a stable model/provider once per operation; session revision checks stay with ChatService. */
internal class CompressionEngine(private val providers: ProviderManager) {
    fun request(
        settings: Settings,
        assistantId: Uuid,
        targetTokens: Int,
        additionalPrompt: String,
    ): suspend (String) -> String {
        require(targetTokens > 0)
        val model = settings.compressionModelFor(assistantId) ?: error("没有可用的压缩模型")
        val provider = model.findProvider(settings.providers) ?: error("压缩模型的提供商不存在")
        val handler = providers.getProviderByType(provider)
        val language = Locale.getDefault().displayName
        return { content ->
            val prompt = buildCompressionPrompt(settings.compressPrompt, content, targetTokens, additionalPrompt, language)
            handler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(model = model),
            ).choices.firstOrNull()?.message?.compressionSummaryText() ?: error("模型未返回有效摘要，原文未修改")
        }
    }
}
