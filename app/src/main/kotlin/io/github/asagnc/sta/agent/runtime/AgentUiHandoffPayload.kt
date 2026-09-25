package io.github.asagnc.sta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentUiHandoffPayload(
    val conversationId: String,
    val promptSupplement: Supplement? = null,
    val supplements: List<Supplement> = emptyList(),
    /**
     * 本版本不认识的字段。
     *
     * 为什么必须留着：这条载荷会被读出来、改一下、再写回去（见 AgentContinuationBuilder）。
     * 如果载荷是更新版本写下的，而我们在解析时就把不认识的字段丢掉，写回时就永久弄丢了它们——
     * 用户会看到「新版跑过一次、降级回去信息就没了」。
     */
    val extra: JSONObject = JSONObject(),
) {
    val lastSupplementIndex: Int
        get() = (listOfNotNull(promptSupplement?.index) + supplements.map { it.index }).maxOrNull() ?: 0

    fun promptMessageId(runId: String): String =
        promptSupplement?.let { "user-$runId-supplement-${it.index}" } ?: "user-$runId"

    data class Supplement(
        val index: Int,
        val text: String,
        val createdAt: Long,
    )

    fun toJson(): String =
        JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("conversationId", conversationId)
            .also { json ->
                // 先铺不认识的字段，再写本版本的键：同名时以本版本的语义为准。
                extra.keys().forEach { key -> if (key !in KNOWN_KEYS) json.put(key, extra.get(key)) }
                promptSupplement?.let { json.put("promptSupplement", it.toJson()) }
            }
            .put(
                "supplements",
                JSONArray().also { array ->
                    supplements.forEach { supplement ->
                        array.put(
                            supplement.toJson()
                        )
                    }
                }
            )
            .toString()

    companion object {
        private const val TYPE = "agent_ui_handoff"
        private const val VERSION = 2

        /** 本版本认识的键；其余键解析时收进 [extra]，写回时原样带走。 */
        private val KNOWN_KEYS = setOf("type", "version", "conversationId", "promptSupplement", "supplements")

        fun from(raw: String): AgentUiHandoffPayload {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) {
                return AgentUiHandoffPayload(conversationId = trimmed)
            }
            return runCatching {
                val json = JSONObject(trimmed)
                if (json.optString("type") != TYPE) {
                    return@runCatching AgentUiHandoffPayload(conversationId = trimmed)
                }
                val supplementsJson = json.optJSONArray("supplements") ?: JSONArray()
                AgentUiHandoffPayload(
                    conversationId = json.optString("conversationId"),
                    promptSupplement = json.optJSONObject("promptSupplement")
                        ?.toSupplement(defaultIndex = 1),
                    supplements = (0 until supplementsJson.length()).mapNotNull { index ->
                        supplementsJson.optJSONObject(index)?.toSupplement(index + 1)
                    },
                    extra = JSONObject().also { unknown ->
                        json.keys().forEach { key ->
                            if (!KNOWN_KEYS.contains(key)) unknown.put(key, json.get(key))
                        }
                    },
                )
            }.getOrDefault(AgentUiHandoffPayload(conversationId = trimmed))
        }

        private fun JSONObject.toSupplement(defaultIndex: Int): Supplement? {
            val text = optString("text").trim()
            if (text.isBlank()) return null
            return Supplement(
                index = optInt("index", defaultIndex),
                text = text,
                createdAt = optLong("createdAt"),
            )
        }
    }

    private fun Supplement.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("createdAt", createdAt)
}
