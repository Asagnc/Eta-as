package io.github.asagnc.sta.ui.model

import androidx.compose.runtime.Immutable
import io.github.asagnc.sta.agent.runtime.AgentEvent

/** 子智能体的运行阶段；与事件里的 phase 字符串一一对应。 */
@Immutable
enum class AgentSubAgentPhase { RUNNING, FINISHED, FAILED }

/**
 * 并行子智能体的一项进度，由 [AgentEvent.SubAgentUpdated] 投影而来。
 *
 * public：它作为 `ToolActivityMessageUi.subAgents` 的字段类型出现，而那个消息类是 public。
 */
@Immutable
data class AgentSubAgentItemUi(
    val id: String,
    val role: String,
    val phase: AgentSubAgentPhase,
    val summaryChars: Int = 0,
    val errorCode: String = "",
)

/**
 * 把子智能体事件折叠成界面要展示的列表。
 *
 * 与任务清单不同，这里没有"快照"：事件是增量的（同一 id 先 started 后 finished），
 * 所以按 id 更新已有项——这也是同一角色被重复派发时能各占一行的原因。
 */
internal object AgentSubAgentProjector {

    /**
     * 事件的 id 由运行侧生成，同一角色的多次派发 id 不同，因此空 id 视为无效数据
     * （界面无法按 id 合并，会退化成每次事件都新增一行）。
     */
    fun fromEvent(event: AgentEvent.SubAgentUpdated): AgentSubAgentItemUi? {
        val id = event.id.trim()
        if (id.isEmpty()) return null
        return AgentSubAgentItemUi(
            id = id,
            role = event.role.trim().ifEmpty { DEFAULT_ROLE },
            phase = event.phase.toPhase(),
            summaryChars = event.summaryChars.coerceAtLeast(0),
            errorCode = event.errorCode.trim(),
        )
    }

    fun upsert(
        existing: List<AgentSubAgentItemUi>,
        item: AgentSubAgentItemUi,
    ): List<AgentSubAgentItemUi> {
        val index = existing.indexOfFirst { it.id == item.id }
        if (index < 0) return existing + item
        return existing.toMutableList().also { it[index] = item }
    }

    /** 是否还有角色在跑；面板据此决定要不要自动收起。 */
    fun hasRunning(items: List<AgentSubAgentItemUi>): Boolean =
        items.any { it.phase == AgentSubAgentPhase.RUNNING }

    private fun String.toPhase(): AgentSubAgentPhase = when (this) {
        AgentEvent.SubAgentUpdated.PHASE_STARTED -> AgentSubAgentPhase.RUNNING
        AgentEvent.SubAgentUpdated.PHASE_FINISHED -> AgentSubAgentPhase.FINISHED
        else -> AgentSubAgentPhase.FAILED
    }

    private const val DEFAULT_ROLE = "子智能体"
}
