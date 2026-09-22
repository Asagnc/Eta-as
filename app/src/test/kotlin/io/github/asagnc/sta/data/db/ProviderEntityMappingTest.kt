package io.github.asagnc.sta.data.db

import io.github.asagnc.sta.data.model.AnthropicProviderSetting
import io.github.asagnc.sta.data.model.CustomProviderSetting
import io.github.asagnc.sta.data.model.OpenAiCompatibleProviderSetting
import io.github.asagnc.sta.data.model.ProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示缓存开关必须能在「设置 → 实体 → 设置」往返里存活。
 *
 * 这个字段最初只长在 Anthropic 类型上，于是**非 Anthropic 源两头都漏**：写入路径用
 * `as? AnthropicProviderSetting ?: false` 把它静默写回 false，读取路径干脆没传这个参数、
 * 退回构造默认值。后果是在界面上勾选后毫无效果，而且不报错、不崩、日志无痕迹——
 * 只有往返测试能锁住它。
 */
class ProviderEntityMappingTest {

    private fun roundTrip(setting: ProviderSetting): ProviderSetting =
        ProviderWithModels(provider = setting.toEntity(), models = emptyList()).toDomain()

    @Test
    fun customProviderKeepsPromptCacheEnabled() {
        val restored = roundTrip(
            CustomProviderSetting(
                id = "c1",
                name = "n",
                baseUrl = "https://e.com/v1",
                promptCacheEnabled = true,
            ),
        )
        assertTrue(restored is CustomProviderSetting)
        assertTrue("custom provider 的提示缓存开关在往返中丢失", restored.promptCacheEnabled)
    }

    @Test
    fun openAiCompatibleProviderKeepsPromptCacheEnabled() {
        val restored = roundTrip(
            OpenAiCompatibleProviderSetting(
                id = "o1",
                name = "n",
                baseUrl = "https://e.com/v1",
                promptCacheEnabled = true,
            ),
        )
        assertTrue(restored is OpenAiCompatibleProviderSetting)
        assertTrue("openai 兼容 provider 的提示缓存开关在往返中丢失", restored.promptCacheEnabled)
    }

    @Test
    fun anthropicProviderKeepsPromptCacheEnabled() {
        val restored = roundTrip(
            AnthropicProviderSetting(
                id = "a1",
                name = "n",
                baseUrl = "https://e.com",
                promptCacheEnabled = true,
            ),
        )
        assertTrue(restored is AnthropicProviderSetting)
        assertTrue("anthropic provider 的提示缓存开关在往返中丢失", restored.promptCacheEnabled)
    }

    @Test
    fun promptCacheIsOffByDefaultForEveryProviderType() {
        val providers = listOf(
            CustomProviderSetting(id = "c1", name = "n", baseUrl = "https://e.com/v1"),
            OpenAiCompatibleProviderSetting(id = "o1", name = "n", baseUrl = "https://e.com/v1"),
            AnthropicProviderSetting(id = "a1", name = "n", baseUrl = "https://e.com"),
        )
        providers.forEach { assertFalse(it.promptCacheEnabled) }
    }
}
