package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 将 Sta 会话消息投影为 OpenAI-compatible 请求所需的系统指令结构。
 *
 * 投影要丢掉 Sta 自己的内部字段（`_eta_*` 与 `provider_blocks`），所以每条消息都得是新对象；
 * 但新对象用逐字段浅拷贝构造，不走 `JSONObject(message.toString())`——那是"为了复制而先序列化
 * 一遍再解析回来"，在每轮请求都跑、且消息数随对话增长的路径上，开销远大于逐字段复制。
 */
internal object OpenAiRequestMessages {
    fun forChatCompletions(source: JSONArray): JSONArray {
        val system = collectInstructions(source, SYSTEM_ROLES)
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") in SYSTEM_ROLES) continue
                messages.put(copyWithoutInternalFields(message))
            }
        }
    }

    /**
     * 复制一条消息并去掉内部字段。
     *
     * 嵌套结构（`content` 分片数组、`tool_calls`）直接沿用原引用：本方法只读它们，
     * 后续也不会改写，复制一份纯属浪费。这要求调用方不修改返回值里的嵌套对象。
     */
    private fun copyWithoutInternalFields(message: JSONObject): JSONObject {
        val copy = JSONObject()
        message.keys().forEach { key ->
            if (key in INTERNAL_KEYS) return@forEach
            copy.put(key, message.get(key))
        }
        return copy
    }

    /**
     * 只属于 Sta 内部、不能发给 provider 的字段。
     *
     * `provider_blocks` 是 Anthropic 渠道回放思考块用的，OpenAI 格式没有对应结构。
     */
    private val INTERNAL_KEYS = setOf(
        "provider_blocks",
        "_eta_context_summary",
        "_eta_compacted_users",
        "_eta_summary_through_user",
        "_eta_observation",
        "_eta_message_id",
        "_eta_character_profile",
    )

    fun responsesInstructions(source: JSONArray): String =
        collectInstructions(source, RESPONSES_INSTRUCTION_ROLES)

    private fun collectInstructions(source: JSONArray, roles: Set<String>): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")
}
