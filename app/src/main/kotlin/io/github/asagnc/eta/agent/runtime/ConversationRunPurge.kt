package io.github.asagnc.eta.agent.runtime

import android.content.Context

/**
 * 删除会话时清理该会话名下的运行数据。运行归档、检查点与运行结果都按 runId 存储，
 * 会话标识只内嵌在 handoff 的 payload 里（见 [AgentUiHandoffPayload]），因此按 payload
 * 反查归属后逐个删除。后台任务与日志按任务存储、不隶属任何会话，不在这里处理。
 */
internal object ConversationRunPurge {
    fun purge(context: Context, conversationId: String): Int {
        if (conversationId.isBlank()) return 0
        val appContext = context.applicationContext
        var removed = 0
        AgentRunCheckpointStore.list(appContext)
            .filter { it.handoff.belongsTo(conversationId) }
            .forEach { checkpoint ->
                AgentRunCheckpointStore.remove(appContext, checkpoint.runId)
                removed += 1
            }
        AgentRunArchiveStore.list(appContext)
            .filter { it.handoff.belongsTo(conversationId) }
            .forEach { archived ->
                AgentRunArchiveStore.remove(appContext, archived.result.runId.ifBlank { archived.handoff.id })
                removed += 1
            }
        AgentRuntimeResultStore.list(appContext)
            .filter { it.handoff.belongsTo(conversationId) }
            .forEach { completed ->
                AgentRuntimeResultStore.remove(appContext, completed.result.runId)
                removed += 1
            }
        return removed
    }

    /**
     * 旧版本的 payload 直接就是会话 id，[AgentUiHandoffPayload.from] 对这种输入按原样取用，
     * 因此历史数据与 JSON 格式的新数据用同一条比较规则就能覆盖。
     */
    private fun AgentRuntimeWire.EntryHandoff.belongsTo(conversationId: String): Boolean =
        AgentUiHandoffPayload.from(payload).conversationId == conversationId
}
