package io.github.asagnc.sta.agent.runtime

import android.content.SharedPreferences
import io.github.asagnc.sta.agent.model.AgentModelClient
import io.github.asagnc.sta.config.Prefs
import io.github.asagnc.sta.data.model.ReasoningEffort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** Runtime 自己裁决可选能力，不能把入口进程提交的布尔值当作授权。 */
internal object AgentRuntimePolicy {
    data class Permissions(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val deviceDirectTools: Boolean = false,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val thinking: Boolean,
        /** 子智能体开关；关闭时不向模型暴露 delegate。 */
        val subAgents: Boolean = false,
        /** 同批只读工具的并发上限，取值由偏好决定，实际执行时仍会夹到允许区间。 */
        val maxParallelToolCalls: Int = Prefs.Keys.INT_DEFAULTS.getValue(Prefs.Keys.AGENT_PARALLEL_TOOL_LIMIT),
        /** 请求视图里单个工具结果的字符上限，超过即做确定性截断。 */
        val toolResultMaxChars: Int = Prefs.Keys.INT_DEFAULTS.getValue(Prefs.Keys.AGENT_TOOL_RESULT_MAX_CHARS),
        /** 上下文占用提示的百分比阈值，0 表示关闭。 */
        val contextNoticePercent: Int = Prefs.Keys.INT_DEFAULTS.getValue(Prefs.Keys.AGENT_CONTEXT_NOTICE_PERCENT),
    )

    fun permissions(preferences: SharedPreferences?): Permissions =
        Permissions(
            terminalTools = preferences.allowed(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = preferences.allowed(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = preferences.allowed(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                preferences.allowed(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                preferences.allowed(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinking = preferences.allowed(Prefs.Keys.AGENT_THINKING_ENABLED),
            subAgents = preferences.allowed(Prefs.Keys.AGENT_SUBAGENTS_ENABLED),
            maxParallelToolCalls = preferences.intValue(Prefs.Keys.AGENT_PARALLEL_TOOL_LIMIT),
            toolResultMaxChars = preferences.intValue(Prefs.Keys.AGENT_TOOL_RESULT_MAX_CHARS),
            contextNoticePercent = preferences.intValue(Prefs.Keys.AGENT_CONTEXT_NOTICE_PERCENT),
        )

    fun constrain(
        config: AgentModelClient.ModelConfig,
        permissions: Permissions,
    ): AgentModelClient.ModelConfig {
        val requestedEffort = config.effectiveReasoningEffort
        val effectiveEffort = if (permissions.thinking) requestedEffort else ReasoningEffort.OFF
        val thinkingEnabled = effectiveEffort.enablesReasoning
        val constrained = config.copy(
            terminalTools = config.terminalTools && permissions.terminalTools,
            browserTools = config.browserTools && permissions.browserTools,
            deviceDirectTools = config.deviceDirectTools && permissions.deviceDirectTools,
            deviceSensitiveReadTools =
                config.deviceSensitiveReadTools && permissions.deviceSensitiveReadTools,
            deviceSensitiveActionTools =
                config.deviceSensitiveActionTools && permissions.deviceSensitiveActionTools,
            maxParallelToolCalls = permissions.maxParallelToolCalls,
            toolResultMaxChars = permissions.toolResultMaxChars,
            contextNoticePercent = permissions.contextNoticePercent,
            subAgentTools = config.subAgentTools && permissions.subAgents,
            thinkingEnabled = thinkingEnabled,
            reasoningEffort = effectiveEffort,
        )
        if (thinkingEnabled) return constrained
        return constrained.copy(
            extraBodyJson = stripThinkingOverrides(constrained.extraBodyJson),
            customBody = constrained.customBody.mapNotNull { body ->
                if (body.key.isThinkingKey()) return@mapNotNull null
                body.copy(value = body.value.stripThinkingOverrides())
            },
        )
    }

    private fun SharedPreferences?.allowed(key: String): Boolean {
        if (this == null) return false
        val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: false
        return runCatching { getBoolean(key, default) }.getOrDefault(false)
    }

    private fun SharedPreferences?.intValue(key: String): Int {
        val default = Prefs.Keys.INT_DEFAULTS[key] ?: 0
        if (this == null) return default
        return runCatching { getInt(key, default) }.getOrDefault(default)
    }

    private fun stripThinkingOverrides(raw: String): String {
        if (raw.isBlank()) return raw
        return runCatching {
            JSONObject(raw).also { it.stripThinkingOverrides() }.toString()
        }.getOrDefault(raw)
    }

    private fun JSONObject.stripThinkingOverrides() {
        val keys = keys().asSequence().toList()
        keys.forEach { key ->
            if (key.isThinkingKey()) {
                remove(key)
                return@forEach
            }
            when (val child = opt(key)) {
                is JSONObject -> child.stripThinkingOverrides()
                is org.json.JSONArray -> {
                    for (index in 0 until child.length()) {
                        (child.opt(index) as? JSONObject)?.stripThinkingOverrides()
                    }
                }
            }
        }
    }

    private fun JsonElement.stripThinkingOverrides(): JsonElement =
        when (this) {
            is JsonObject -> JsonObject(
                entries
                    .filterNot { (key, _) -> key.isThinkingKey() }
                    .associate { (key, value) -> key to value.stripThinkingOverrides() }
            )
            is JsonArray -> JsonArray(map { it.stripThinkingOverrides() })
            else -> this
        }

    private fun String.isThinkingKey(): Boolean =
        lowercase().replace('-', '_') in THINKING_OVERRIDE_KEYS

    private val THINKING_OVERRIDE_KEYS = setOf(
        "thinking",
        "thinking_budget",
        "reasoning",
        "reasoning_effort",
        "reasoning_budget",
        "reasoning_max_tokens",
        "enable_thinking",
    )
}
