package io.github.asagnc.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import io.github.asagnc.eta.data.world.WorldKnowledgeStore
import java.time.ZonedDateTime

/**
 * 请求尾部注入：当前时间 + 当前方案 + 当前任务清单 + 历史结论。
 *
 * 都放在消息序列的**最后一条**消息上，而不是系统提示里：服务端前缀缓存按前缀匹配，
 * 时间、方案与计划每轮都会变，写进系统提示会让整段历史失去缓存命中。传进来的是本轮请求的
 * 副本，历史消息本身不会被改写。
 *
 * 四段注入各自按自己的前缀去重：同一个消息对象被重复注入时不会叠加（时钟行由
 * [AgentRequestClock] 负责剥掉，方案、计划与历史结论行在这里剥）。方案块在计划块之上——
 * 方向先于进度；历史结论排最后，它是背景而不是本次任务的要求。
 * 纯文本消息里三个块都在时钟行之上；分片消息里都插在时钟分片**之后**——时钟是按「第 0 个
 * 分片是不是自己」去重的，抢它的位置会让时钟行每轮重复注入。
 */
internal object AgentRequestContext {

    fun attach(
        messages: JSONArray,
        taskPlanJson: String?,
        planJson: String?,
        recallEntries: List<WorldKnowledgeStore.Recalled> = emptyList(),
        now: ZonedDateTime = ZonedDateTime.now(),
    ) {
        // 注入只改最后一条消息，所以这里只克隆那一条：视图的其余部分与历史共享对象，
        // 每轮省掉整段历史的深拷贝。克隆必须发生在任何写入之前——[AgentRequestClock.attach]
        // 与下面的方案/计划注入都往同一条消息上写，先克隆一次即可，不必各克隆一次。
        val index = messages.length() - 1
        if (index < 0) return
        val last = messages.optJSONObject(index) ?: return
        messages.put(index, cloneOf(last))
        AgentRequestClock.attach(messages, now)
        // 注入顺序：方案（这次要做什么）→ 清单（做到哪了）→ 历史结论（以前做过什么）。
        // 历史结论放最后：它是背景，不是本次任务的要求，压在方向与进度之上会误导模型。
        val blocks = listOfNotNull(
            AgentPlanFormat.injectedLines(planJson, taskPlanJson)?.joinToString("\n"),
            AgentTaskPlanFormat.injectedLines(taskPlanJson)?.joinToString("\n"),
            AgentRecallFormat.injectedLines(recallEntries, now.toInstant().toEpochMilli())
                ?.joinToString("\n"),
        )
        if (blocks.isEmpty()) return
        val message = messages.optJSONObject(index) ?: return
        val block = blocks.joinToString("\n")
        when (val content = message.opt("content")) {
            is String -> {
                val stripped = content.lineSequence()
                    .filterNot { isInjectedLine(it) }
                    .joinToString("\n")
                    .trimStart('\n')
                message.put("content", if (stripped.isBlank()) block else "$block\n$stripped")
            }

            is JSONArray -> message.put("content", rebuildParts(content, block))
        }
    }

    /** 浅拷贝一条消息：嵌套的 content 分片数组要复制一份，避免后续改动写回历史。 */
    private fun cloneOf(message: JSONObject): JSONObject {
        val copy = JSONObject()
        message.keys().forEach { key ->
            val value = message.get(key)
            copy.put(key, if (key == "content" && value is JSONArray) JSONArray(value.toString()) else value)
        }
        return copy
    }

    private fun rebuildParts(content: JSONArray, block: String): JSONArray {
        val injectedPart = JSONObject().put("type", "text").put("text", block)
        val parts = mutableListOf<JSONObject>()
        var inserted = false
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            val text = part.optString("text")
            if (part.optString("type") == "text" && isInjectedLine(text)) continue
            parts += part
            if (!inserted && part.optString("type") == "text" && text.startsWith(AgentRequestClock.PREFIX)) {
                parts += injectedPart
                inserted = true
            }
        }
        if (!inserted) parts.add(0, injectedPart)
        return JSONArray(parts)
    }

    private fun isInjectedLine(line: String): Boolean =
        line.startsWith(AgentTaskPlanFormat.HEADER_PREFIX) ||
            line.startsWith(AgentTaskPlanFormat.ITEM_PREFIX) ||
            line.startsWith(AgentPlanFormat.HEADER_PREFIX) ||
            line.startsWith(AgentPlanFormat.ITEM_PREFIX) ||
            line.startsWith(AgentRecallFormat.HEADER_PREFIX) ||
            line.startsWith(AgentRecallFormat.ITEM_PREFIX)
}
