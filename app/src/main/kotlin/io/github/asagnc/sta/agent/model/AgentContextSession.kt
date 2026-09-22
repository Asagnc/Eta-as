package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.runtime.AgentEvent
import io.github.asagnc.sta.agent.runtime.AgentRunController
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
) {
    private val ceilingKey = AgentContextCeilingStore.keyFor(config.providerId, config.model)
    val budget = AgentContextBudget(
        window = config.contextWindow,
        initialCeiling = AgentContextCeilingStore.ceilingFor(ceilingKey, config.contextWindow),
        onCeilingLearned = { tokens ->
            AgentContextCeilingStore.record(ceilingKey, config.contextWindow, tokens)
        },
    )
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(
        roundTools: JSONArray,
        force: Boolean = false,
        final: Boolean = false,
        untilMessageId: String? = null,
        reasonCode: String = "",
        extraInstructions: String? = null,
    ) {
        val before = budget.estimate(messages, roundTools)
        // 服务端已经拒收过这个规模：记下实测上限，之后按更小的窗口触发，避免反复撞墙。
        if (reasonCode == AgentEvent.ContextCompaction.REASON_OVERFLOW) budget.noteOverflow(before)
        if (!force && !budget.shouldCompact(before)) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(
            AgentEvent.ContextCompaction(
                operation, AgentEvent.ContextCompaction.PHASE_STARTED, before, reasonCode = reasonCode,
            ),
        )
        try {
            var candidate = messages
            var attempts = 0
            do {
                candidate = AgentContextCompactor(config, provider, runController, roleplay = roleplay).compact(
                    candidate, systemCount, sensitiveIds(), force, untilMessageId, extraInstructions,
                )
                attempts++
                // 用不带地板的估算判断「是否已经缩小」：estimate() 的下界是上一次服务端回报的真实
                // input，拿它做收敛判断会让循环永远判定还超线（见 estimateForReduction 的说明）。
                val tokens = budget.estimateForReduction(candidate, roundTools)
                if (!budget.shouldCompact(tokens)) break
                if (attempts >= AgentContextBudget.MAX_OVERFLOW_ATTEMPTS) {
                    throw AgentContextCompactor.failure("CONTEXT_NO_REDUCTION", "摘要后上下文仍超过容量预算。")
                }
            } while (true)
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_COMPLETED, before,
                budget.estimate(messages, roundTools), reasonCode = reasonCode))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final && (force || budget.exceedsWindow(before))) throw failure
            if (final) {
                // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
                committedSnapshot = createSnapshot(messages)
            }
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }
}
