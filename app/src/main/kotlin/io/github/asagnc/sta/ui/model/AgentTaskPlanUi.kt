package io.github.asagnc.sta.ui.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** 任务清单单项的状态；与 task_plan 工具接受的字符串一一对应。 */
@Immutable
internal enum class AgentTaskPlanStatus { PENDING, IN_PROGRESS, COMPLETED }

/** 任务清单快照里的一项，由 task_plan 工具事件投影而来。 */
@Immutable
internal data class AgentTaskPlanItemUi(
    val id: String,
    val content: String,
    val status: AgentTaskPlanStatus,
    val toolCalls: Int = 0,
    val elapsedMillis: Long = 0L,
    val failure: String? = null,
)

internal object AgentTaskPlanCodec {
    /**
     * 解析事件里的清单快照。缺 id 或缺内容的项直接跳过——这些项在写入前已被工具拒绝，
     * 出现即说明数据来自更早的版本，此处不补默认值。
     */
    fun decode(planJson: String): List<AgentTaskPlanItemUi> = runCatching {
        val array = JSONArray(planJson)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val content = item.optString("content").trim()
            if (id.isEmpty() || content.isEmpty()) return@mapNotNull null
            AgentTaskPlanItemUi(
                id = id,
                content = content,
                status = when (item.optString("status")) {
                    "completed" -> AgentTaskPlanStatus.COMPLETED
                    // "interrupted" 是旧版本写入的第四态：它只表示「运行结束时这一项还在进行中」，
                    // 现在统一按 in_progress 读回，断点信息不会丢。
                    "in_progress", "interrupted" -> AgentTaskPlanStatus.IN_PROGRESS
                    else -> AgentTaskPlanStatus.PENDING
                },
                toolCalls = item.optInt("tool_calls", 0),
                elapsedMillis = item.optLong("elapsed_ms", 0L),
                failure = item.optString("failure").takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 写回存档：空清单返回空串，免得在库里躺一个没意义的 "[]"。
     *
     * 每项的计数与失败原因一并写回：它们由 task_plan 事件带下来，只读不写会让重启后的
     * 面板把「3 次工具调用 · 失败：超时」这类信息全丢掉。默认值不写，存档保持紧凑。
     */
    fun encode(items: List<AgentTaskPlanItemUi>): String {
        if (items.isEmpty()) return ""
        val array = JSONArray()
        items.forEach { item ->
            val json = JSONObject()
                .put("id", item.id)
                .put("content", item.content)
                .put("status", item.status.wireValue)
            if (item.toolCalls > 0) json.put("tool_calls", item.toolCalls)
            if (item.elapsedMillis > 0L) json.put("elapsed_ms", item.elapsedMillis)
            item.failure?.takeIf { it.isNotBlank() }?.let { json.put("failure", it) }
            array.put(json)
        }
        return array.toString()
    }

    private val AgentTaskPlanStatus.wireValue: String
        get() = when (this) {
            AgentTaskPlanStatus.PENDING -> "pending"
            AgentTaskPlanStatus.IN_PROGRESS -> "in_progress"
            AgentTaskPlanStatus.COMPLETED -> "completed"
        }
}
