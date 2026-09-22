package io.github.asagnc.sta.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object ProviderTypes {
    const val OPENAI_COMPATIBLE = "openai_compatible"
    const val ANTHROPIC = "anthropic"
    const val CUSTOM = "custom"
}

internal object OpenAiEndpointMode {
    const val CHAT_COMPLETIONS = "chat_completions"
    const val RESPONSES = "responses"
}

internal object ProviderSourceTypes {
    const val CUSTOM = "custom"
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val BAILIAN = "bailian"
    const val DEEPSEEK = "deepseek"
    const val MOONSHOT = "moonshot"
    const val MIMO = "mimo"
    const val MINIMAX = "minimax"
    const val STEPFUN = "stepfun"
    const val SILICONFLOW = "siliconflow"
    const val OPENROUTER = "openrouter"
}

@Serializable
sealed interface ProviderSetting {
    val id: String
    val name: String
    val baseUrl: String
    val sourceType: String
    val apiKey: String
    val isEnabled: Boolean
    val isBuiltIn: Boolean
    val sortOrder: Int
    val systemPrompt: String?
    val models: List<Model>
    val customHeaders: List<CustomHeader>
    val customBody: List<CustomBody>
    val createdAt: Long
    /**
     * 上游支持提示缓存时才在请求体里放缓存字段：不认识该字段的网关会直接 400。
     * Anthropic 走顶层 `cache_control`，OpenAI 兼容路径走 `prompt_cache_key`。
     *
     * 必须声明在接口上（而不是只长在 Anthropic 上）：否则每处读取都得写
     * `as? AnthropicProviderSetting ?: false`，漏掉任意一处就会把开关静默写回 false。
     */
    val promptCacheEnabled: Boolean
        get() = false
    val hostedWebSearchEnabled: Boolean
        get() = false
}

@Serializable
@SerialName(ProviderTypes.OPENAI_COMPATIBLE)
data class OpenAiCompatibleProviderSetting(
    override val id: String,
    override val name: String,
    override val baseUrl: String,
    override val sourceType: String = ProviderSourceTypes.CUSTOM,
    override val apiKey: String = "",
    override val isEnabled: Boolean = true,
    override val isBuiltIn: Boolean = false,
    override val sortOrder: Int = 0,
    override val systemPrompt: String? = null,
    override val models: List<Model> = emptyList(),
    override val customHeaders: List<CustomHeader> = emptyList(),
    override val customBody: List<CustomBody> = emptyList(),
    override val createdAt: Long = System.currentTimeMillis(),
    val endpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
    /** OpenAI 兼容路径用 `prompt_cache_key`（稳定会话键），见 [ProviderSetting.promptCacheEnabled]。 */
    override val promptCacheEnabled: Boolean = false,
    override val hostedWebSearchEnabled: Boolean = false,
) : ProviderSetting

@Serializable
@SerialName(ProviderTypes.ANTHROPIC)
data class AnthropicProviderSetting(
    override val id: String,
    override val name: String,
    override val baseUrl: String,
    override val sourceType: String = ProviderSourceTypes.CUSTOM,
    override val apiKey: String = "",
    override val isEnabled: Boolean = true,
    override val isBuiltIn: Boolean = false,
    override val sortOrder: Int = 0,
    override val systemPrompt: String? = null,
    override val models: List<Model> = emptyList(),
    override val customHeaders: List<CustomHeader> = emptyList(),
    override val customBody: List<CustomBody> = emptyList(),
    override val createdAt: Long = System.currentTimeMillis(),
    val anthropicVersion: String = DEFAULT_ANTHROPIC_VERSION,
    /** Anthropic 路径走顶层 `cache_control`，见 [ProviderSetting.promptCacheEnabled]。 */
    override val promptCacheEnabled: Boolean = false,
    /** 让服务端按官方默认阈值清理较早的工具结果；需要上游透传 context-management beta 头。 */
    val contextEditingEnabled: Boolean = false,
) : ProviderSetting {
    companion object {
        const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"
    }
}

@Serializable
@SerialName(ProviderTypes.CUSTOM)
data class CustomProviderSetting(
    override val id: String,
    override val name: String,
    override val baseUrl: String,
    override val sourceType: String = ProviderSourceTypes.CUSTOM,
    override val apiKey: String = "",
    override val isEnabled: Boolean = true,
    override val isBuiltIn: Boolean = false,
    override val sortOrder: Int = 0,
    override val systemPrompt: String? = null,
    override val models: List<Model> = emptyList(),
    override val customHeaders: List<CustomHeader> = emptyList(),
    override val customBody: List<CustomBody> = emptyList(),
    override val createdAt: Long = System.currentTimeMillis(),
    val endpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
    /** 与 [OpenAiCompatibleProviderSetting] 同路径，见 [ProviderSetting.promptCacheEnabled]。 */
    override val promptCacheEnabled: Boolean = false,
    override val hostedWebSearchEnabled: Boolean = false,
) : ProviderSetting

internal val ProviderSetting.runtimeProviderType: String
    get() = when (this) {
        is AnthropicProviderSetting -> ProviderTypes.ANTHROPIC
        is OpenAiCompatibleProviderSetting,
        is CustomProviderSetting -> ProviderTypes.OPENAI_COMPATIBLE
    }

internal val ProviderSetting.typeLabel: String
    get() = when (this) {
        is AnthropicProviderSetting -> "Anthropic Messages"
        is OpenAiCompatibleProviderSetting -> "OpenAI-compatible"
        is CustomProviderSetting -> "Custom OpenAI-compatible"
    }

internal val ProviderSetting.displayApiKeySummary: String
    get() = when {
        apiKey.isBlank() -> "未填写"
        apiKey.length <= 8 -> "*".repeat(apiKey.length)
        else -> "${apiKey.take(4)}${"*".repeat(apiKey.length - 8)}${apiKey.takeLast(4)}"
    }

internal fun ProviderSetting.withModels(models: List<Model>): ProviderSetting =
    when (this) {
        is OpenAiCompatibleProviderSetting -> copy(models = models)
        is AnthropicProviderSetting -> copy(models = models)
        is CustomProviderSetting -> copy(models = models)
    }

internal fun ProviderSetting.withSortOrder(sortOrder: Int): ProviderSetting =
    when (this) {
        is OpenAiCompatibleProviderSetting -> copy(sortOrder = sortOrder)
        is AnthropicProviderSetting -> copy(sortOrder = sortOrder)
        is CustomProviderSetting -> copy(sortOrder = sortOrder)
    }

internal fun ProviderSetting.withId(id: String): ProviderSetting =
    when (this) {
        is OpenAiCompatibleProviderSetting -> copy(id = id)
        is AnthropicProviderSetting -> copy(id = id)
        is CustomProviderSetting -> copy(id = id)
    }

internal fun ProviderSetting.withApiKey(apiKey: String): ProviderSetting =
    when (this) {
        is OpenAiCompatibleProviderSetting -> copy(apiKey = apiKey)
        is AnthropicProviderSetting -> copy(apiKey = apiKey)
        is CustomProviderSetting -> copy(apiKey = apiKey)
    }

internal fun ProviderSetting.selectedOrFirstModel(modelId: String?): Model? =
    models.firstOrNull { it.id == modelId && it.isEnabled }
        ?: models.filter { it.isEnabled }.minByOrNull { it.sortOrder }

