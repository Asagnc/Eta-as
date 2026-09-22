package io.github.asagnc.sta.agent.model

import org.json.JSONObject

/**
 * 工具失败的可比较摘要。
 *
 * 从工具结果的 JSON 里提取一个稳定的签名，用来判断"是不是在用同一方式反复失败"。
 * 抽成纯逻辑是为了能单测：不同工具写失败结果的字段并不统一——终端命令只有 exit_code / stdout / stderr，
 * 本地工具一般有 code / message。签名必须把这些都归一化，否则不同的失败会退化成同一个签名而被误判为重复。
 */
internal object AgentFailureSignature {

    data class Failure(val signature: String, val detail: String)

    fun of(toolName: String, content: String): Failure? {
        val payload = runCatching { JSONObject(content) }.getOrNull() ?: return null
        if (payload.optBoolean("ok", true)) return null
        val exitCode = payload.optInt("exit_code", MISSING_EXIT_CODE)
        val code = payload.optString("code").ifBlank {
            if (exitCode != MISSING_EXIT_CODE) "EXIT_$exitCode" else "error"
        }
        val message = firstMessage(payload)
        return Failure(
            signature = "$toolName:$code:$message:$exitCode",
            detail = if (message.isBlank()) code else "$code：$message",
        )
    }

    /** 取第一条有内容的输出作为说明；工具各自的字段名不同，按可用性依次回退。 */
    private fun firstMessage(payload: JSONObject): String =
        listOf("message", "stderr", "stdout", "error")
            .asSequence()
            .mapNotNull { key -> payload.optString(key).takeIf { it.isNotBlank() } }
            .flatMap { value -> value.lineSequence() }
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MESSAGE_LIMIT)
            .orEmpty()

    private const val MISSING_EXIT_CODE = Int.MIN_VALUE
    private const val MESSAGE_LIMIT = 120
}
