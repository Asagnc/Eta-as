package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * 请求尾部注入：当前时间 + 当前任务清单。
 *
 * 都放在消息序列的**最后一条**消息上，而不是系统提示里：服务端前缀缓存按前缀匹配，
 * 时间与计划每轮都会变，写进系统提示会让整段历史失去缓存命中。传进来的是本轮请求的副本，
 * 历史消息本身不会被改写。
 *
 * 两段注入各自按自己的前缀去重：同一个消息对象被重复注入时不会叠加（时钟行由
 * [AgentRequestClock] 负责剥掉，计划行在这里剥）。纯文本消息里计划块在时钟行之上；
 * 分片消息里计划块插在时钟分片**之后**——时钟是按「第 0 个分片是不是自己」去重的，
 * 抢它的位置会让时钟行每轮重复注入。
 */
internal object AgentRequestContext {

    fun attach(messages: JSONArray, planJson: String?, now: ZonedDateTime = ZonedDateTime.now()) {
        AgentRequestClock.attach(messages, now)
        val lines = AgentTaskPlanFormat.injectedLines(planJson) ?: return
        val index = messages.length() - 1
        if (index < 0) return
        val message = messages.optJSONObject(index) ?: return
        val block = lines.joinToString("\n")
        when (val content = message.opt("content")) {
            is String -> {
                val stripped = content.lineSequence()
                    .filterNot { isPlanLine(it) }
                    .joinToString("\n")
                    .trimStart('\n')
                message.put("content", if (stripped.isBlank()) block else "$block\n$stripped")
            }

            is JSONArray -> message.put("content", rebuildParts(content, block))
        }
    }

    private fun rebuildParts(content: JSONArray, block: String): JSONArray {
        val planPart = JSONObject().put("type", "text").put("text", block)
        val parts = mutableListOf<JSONObject>()
        var inserted = false
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            val text = part.optString("text")
            if (part.optString("type") == "text" && isPlanLine(text)) continue
            parts += part
            if (!inserted && part.optString("type") == "text" && text.startsWith(AgentRequestClock.PREFIX)) {
                parts += planPart
                inserted = true
            }
        }
        if (!inserted) parts.add(0, planPart)
        return JSONArray(parts)
    }

    private fun isPlanLine(line: String): Boolean =
        line.startsWith(AgentTaskPlanFormat.HEADER_PREFIX) || line.startsWith(AgentTaskPlanFormat.ITEM_PREFIX)
}
