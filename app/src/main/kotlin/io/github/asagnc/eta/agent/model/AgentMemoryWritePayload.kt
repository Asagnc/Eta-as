package io.github.asagnc.eta.agent.model

import io.github.asagnc.eta.data.repository.AgentMemorySectionOutcome
import io.github.asagnc.eta.data.repository.AgentMemoryWriteOutcome
import org.json.JSONObject

/** memory_write / character_memory_write 的统一返回体。 */
internal object AgentMemoryWritePayload {
    fun success(outcome: AgentMemoryWriteOutcome, mode: String, revisionIgnored: Boolean = false): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("mode", mode)
            .put("retried", outcome.retried)
            .put("revision", outcome.snapshot.revision)
            .put("bytes", outcome.snapshot.byteSize)
            .put("line_count", outcome.snapshot.lineCount)
            .put("revision_ignored", revisionIgnored)
            .apply { outcome.section?.let { section -> put("section", section(section)) } }

    fun conflict(outcome: AgentMemoryWriteOutcome): JSONObject = JSONObject()
        .put("ok", false)
        .put("code", "MEMORY_CONFLICT")
        .put(
            "message",
            "传入的 revision 已过期（当前 ${outcome.snapshot.byteSize} 字节 / ${outcome.snapshot.lineCount} 行）。" +
                "内容不依赖行号时可以直接省略 revision 重试；replace_range / clear 请先用本次响应里的 revision 重新定位。",
        )
        .put("revision", outcome.snapshot.revision)
        .put("bytes", outcome.snapshot.byteSize)
        .put("line_count", outcome.snapshot.lineCount)

    private fun section(section: AgentMemorySectionOutcome): JSONObject = JSONObject()
        .put("heading", section.heading)
        .put("action", if (section.replaced) "replaced" else "inserted")
        .put("start_line", section.startLine)
        .put("end_line", section.endLine)
}
