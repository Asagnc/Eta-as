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

    /** 提交方案的工具名；判断「方案之后有没有用户回应」时用。 */
    private const val SUBMIT_PLAN_TOOL = "submit_plan"

    /** 用户消息的角色名。 */
    private const val ROLE_USER = "user"

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
        // 会话历史里已经出现过的结论不再注入：模型已经从工具结果里读到过它，再摆一遍只是
        // 重复占预算。用内容前缀匹配而不是 id/时间，这样会话被压缩后同一条结论会重新注入。
        val historyText = historyTextOf(messages)
        val blocks = listOfNotNull(
            AgentPlanFormat.injectedLines(planJson, taskPlanJson, planSupersededByUser(messages))
                ?.joinToString("\n"),
            AgentTaskPlanFormat.injectedLines(taskPlanJson)?.joinToString("\n"),
            AgentRecallFormat.injectedLines(
                recallEntries,
                now.toInstant().toEpochMilli(),
                alreadyInContext = { probe -> probe.isNotBlank() && historyText.contains(probe) },
            )?.joinToString("\n"),
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

    /**
     * 方案提交之后是否已经出现过用户消息。
     *
     * 有就说明用户已经就方案给过回应（采纳、改要求或另起话题），方案不再需要每轮注入——
     * 它本身仍在会话历史里，模型需要时看得到。
     */
    private fun planSupersededByUser(messages: JSONArray): Boolean {
        var lastPlanIndex = -1
        for (i in 0 until messages.length()) {
            val calls = messages.optJSONObject(i)?.optJSONArray("tool_calls") ?: continue
            for (c in 0 until calls.length()) {
                val name = calls.optJSONObject(c)
                    ?.optJSONObject("function")
                    ?.optString("name")
                    .orEmpty()
                if (name == SUBMIT_PLAN_TOOL) lastPlanIndex = i
            }
        }
        if (lastPlanIndex < 0) return false
        for (i in lastPlanIndex + 1 until messages.length()) {
            if (messages.optJSONObject(i)?.optString("role") == ROLE_USER) return true
        }
        return false
    }

    /**
     * 把消息序列里的文本拼起来。
     *
     * 用于判断某条历史结论是否已经在会话里出现过（见 [AgentRecallFormat.probeOf]）：
     * 早先的工具结果就在历史里，重复注入它没有意义。
     */
    private fun historyTextOf(messages: JSONArray): String = buildString {
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            when (val content = message.opt("content")) {
                is String -> append(content)
                is JSONArray -> for (part in 0 until content.length()) {
                    append(content.optJSONObject(part)?.optString("text").orEmpty())
                }
            }
            append('\n')
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
