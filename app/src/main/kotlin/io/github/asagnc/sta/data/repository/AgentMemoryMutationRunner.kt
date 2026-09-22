package io.github.asagnc.sta.data.repository

internal data class AgentMemoryWriteOutcome(
    val result: AgentMemoryWriteResult,
    val retried: Boolean,
) {
    val snapshot: AgentMemorySnapshot
        get() = when (result) {
            is AgentMemoryWriteResult.Success -> result.snapshot
            is AgentMemoryWriteResult.Conflict -> result.snapshot
        }

    val section: AgentMemorySectionOutcome?
        get() = (result as? AgentMemoryWriteResult.Success)?.section
}

/**
 * 执行一次写入。append / upsert_section 不依赖行号，冲突时按最新 revision 重试一次，
 * 省掉调用方「再读一遍、再写一遍」的往返；replace_range / clear 依赖具体内容与行号，冲突必须交回调用方重新定位。
 */
internal fun runMemoryMutation(
    mutation: AgentMemoryMutation,
    mutate: (AgentMemoryMutation) -> AgentMemoryWriteResult,
): AgentMemoryWriteOutcome {
    val first = mutate(mutation)
    if (first !is AgentMemoryWriteResult.Conflict) return AgentMemoryWriteOutcome(first, retried = false)
    val retriable = mutation is AgentMemoryMutation.Append || mutation is AgentMemoryMutation.UpsertSection
    if (!retriable) return AgentMemoryWriteOutcome(first, retried = false)
    return AgentMemoryWriteOutcome(mutate(mutation.withRevision(first.snapshot.revision)), retried = true)
}
