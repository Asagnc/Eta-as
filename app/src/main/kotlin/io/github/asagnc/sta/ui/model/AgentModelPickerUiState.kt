package io.github.asagnc.sta.ui.model

import androidx.compose.runtime.Immutable
import io.github.asagnc.sta.data.model.Model
import io.github.asagnc.sta.data.model.ProviderSetting
import io.github.asagnc.sta.data.provider.ProviderSourceRegistry
import java.text.NumberFormat
import java.util.Locale

@Immutable
internal data class AgentModelPickerUiState(
    val providerGroups: List<AgentModelProviderGroupUi> = emptyList(),
    val selectedModel: AgentModelOptionUi? = null,
    val isChanging: Boolean = false,
    /**
     * 运行期实测的有效上下文窗口（声明窗口与服务端实际接受规模的较小值）。
     * 声明窗口虚标时，用它算进度条才能和压缩触发口径一致。
     */
    val contextWindowHint: Int? = null,
)

@Immutable
internal data class AgentModelProviderGroupUi(
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val models: List<AgentModelOptionUi>,
)

@Immutable
internal data class AgentModelOptionUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
)

@Immutable
internal data class AgentContextUsageUi(
    val contextTokens: Int?,
    val contextWindow: Int?,
    val estimated: Boolean = false,
    /**
     * 本会话累计输入与输出 token。
     *
     * 与 [contextTokens] 的口径完全不同：后者是「最近一次请求占了多少窗口」，这里是
     * 「一共发出去多少」。两者差异可以很大——同一份上下文每轮重发一次，窗口占用不变，
     * 累计输入却在成倍增长；只看窗口占用会误以为 token 没怎么消耗。
     */
    val cumulativeInputTokens: Long = 0,
    val cumulativeOutputTokens: Long = 0,
    val cumulativeCachedTokens: Long = 0,
) {
    val progress: Float?
        get() = contextUsageProgress(contextTokens, contextWindow)

    /** 累计口径只展示确实发生过的消耗；整个会话还没发过请求时为空。 */
    val hasCumulative: Boolean
        get() = cumulativeInputTokens > 0 || cumulativeOutputTokens > 0
}

internal object AgentModelPickerProjector {
    fun project(
        providers: List<ProviderSetting>,
        selectedProviderId: String?,
        selectedModelId: String?,
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider
            ?.models
            ?.firstOrNull { it.id == selectedModelId && it.isEnabled }
            ?.let { model -> selectedProvider.toOption(model) }
            ?: enabledProviders.asSequence()
                .flatMap { provider ->
                    provider.models.asSequence()
                        .filter { it.isEnabled }
                        .map { model -> provider.toOption(model) }
                }
                .firstOrNull { it.id == selectedModelId }
        val groups = enabledProviders
            .asSequence()
            .filter { it.apiKey.isNotBlank() }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = provider.models
                    .asSequence()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
                    .map { model -> provider.toOption(model) }
                    .toList()
                models.takeIf(List<*>::isNotEmpty)?.let {
                    AgentModelProviderGroupUi(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceType = sourceType,
                        models = models,
                    )
                }
            }
            .toList()
        return AgentModelPickerUiState(
            providerGroups = groups,
            selectedModel = selectedModel,
        )
    }

    private fun ProviderSetting.toOption(model: Model): AgentModelOptionUi =
        AgentModelOptionUi(
            id = model.id,
            providerId = id,
            providerName = name,
            providerSourceType = ProviderSourceRegistry.resolve(this),
            modelId = model.modelId,
            displayName = model.displayName.ifBlank { model.modelId },
            contextWindow = model.effectiveContextWindow,
        )
}

internal fun defaultExpandedModelProviderIds(selectedModel: AgentModelOptionUi?): Set<String> =
    selectedModel?.providerId?.let(::setOf).orEmpty()

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
    windowHint: Int? = null,
): AgentContextUsageUi {
    val lastUsage = messages.asReversed().asSequence().mapNotNull { message ->
        when (message) {
            is AgentMessageUi -> message.usage?.contextTokens?.let { it to false }
            is SystemNoticeMessageUi -> message.contextTokens?.let { it to true }
            else -> null
        }
    }.firstOrNull()
    // 窗口优先用运行期实测值：模型声明的窗口可能远大于服务端实际允许的规模，
    // 只按声明值算会让进度条永远到不了压缩触发线。
    val window = windowHint ?: selectedModel?.contextWindow
    // 累计口径把每条带用量的事件相加：窗口占用看不出重复发送，累计能。
    var input = 0L
    var output = 0L
    var cached = 0L
    messages.forEach { message ->
        val usage = (message as? AgentMessageUi)?.usage ?: return@forEach
        input += (usage.inputTokens ?: 0).toLong()
        output += (usage.outputTokens ?: 0).toLong()
        cached += (usage.cachedTokens ?: 0).toLong()
    }
    return AgentContextUsageUi(
        contextTokens = lastUsage?.first,
        contextWindow = window,
        estimated = lastUsage?.second ?: false,
        cumulativeInputTokens = input,
        cumulativeOutputTokens = output,
        cumulativeCachedTokens = cached,
    )
}

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No usage data yet",
    noLimitText: String = "This model has no context limit",
    locale: Locale = Locale.getDefault(),
): String = when {
    usage.contextTokens == null -> noUsageText
    usage.contextWindow == null || usage.contextWindow <= 0 ->
        "${formatCompactTokenCount(usage.contextTokens, locale)} tokens\n$noLimitText"
    else -> {
        val percent = usage.contextTokens.toDouble() / usage.contextWindow.toDouble() * 100.0
        val percentFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        "${formatCompactTokenCount(usage.contextTokens, locale)} / " +
            "${formatCompactTokenCount(usage.contextWindow, locale)} tokens · " +
            "${percentFormat.format(percent)}%"
    }
}

internal fun formatCompactTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val divisor = when {
        absolute >= 1_000_000 -> 1_000_000.0
        absolute >= 1_000 -> 1_000.0
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}
