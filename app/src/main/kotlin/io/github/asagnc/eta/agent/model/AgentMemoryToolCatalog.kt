package io.github.asagnc.eta.agent.model

import io.github.asagnc.eta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/** 声明持久记忆的有界读取与原子局部更新工具。 */
internal object AgentMemoryToolCatalog {
    fun appendTo(tools: JSONArray, writable: Boolean = true) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_get",
                    description = "Read persistent cross-conversation memory from MEMORY.md. The run-start context already carries the memory file (or, when it exceeds the injection budget, the core section plus a heading index with line ranges), so call this only to inspect a detail, answer what is remembered, or refresh after a conflict. Use query for just-in-time retrieval instead of reading the whole file.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Optional case-insensitive text to search for in the full memory file."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "1-based first line for paged reading when query is omitted; default 1."),
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentMemoryStore.MIN_READ_CHARS)
                                        .put("maximum", AgentMemoryStore.MAX_READ_CHARS)
                                        .put("description", "Maximum returned characters; default 12000, maximum 32000."),
                                ),
                        ),
                ),
            )
        if (!writable) return
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_write",
                    description = "Atomically update persistent MEMORY.md. Store only durable cross-conversation facts, preferences, relationships, and ongoing project context; never store secrets, credentials, verification codes, or transient requests. Keep '# 核心记忆' concise and correct stale facts. To change an existing section use mode=upsert_section with its heading: the matching section is replaced in place, so this never creates duplicates. Use replace_range only for line-level edits, append for a genuinely new topic, and clear only to wipe the whole file. mode and revision may both be omitted: mode is inferred from the fields you pass, and an omitted revision writes against the latest content without a concurrency check.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("upsert_section")
                                                .put("replace_range")
                                                .put("append")
                                                .put("clear"),
                                        )
                                        .put(
                                            "description",
                                            "Optional. upsert_section replaces the section carrying the same heading (created at the end of the file when absent); replace_range edits inclusive 1-based lines; append adds new Markdown at the end; clear removes all memory. When omitted it is inferred: heading -> upsert_section, start_line/end_line -> replace_range, content -> append.",
                                        ),
                                )
                                .put(
                                    "heading",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put(
                                            "description",
                                            "Target section title for upsert_section, for example \"## 设备\" or just \"设备\". '#' markers are ignored while matching, so a hit keeps its original level; a new section without '#' becomes a '##' heading.",
                                        ),
                                )
                                .put(
                                    "revision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 64)
                                        .put("maxLength", 64)
                                        .put(
                                            "description",
                                            "Optional SHA-256 revision from the run-start memory context or the latest memory_get result. Omit it to write against the latest content; pass it only when the write must be rejected if someone else changed the file first.",
                                        ),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based first line."),
                                )
                                .put(
                                    "end_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based last line."),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put(
                                            "description",
                                            "Markdown payload: the section body for upsert_section (heading line excluded), the replacement text for replace_range (empty deletes those lines), or the appended block for append. At most " +
                                                "${AgentMemoryStore.MAX_WRITE_CONTENT_CHARS} characters.",
                                        ),
                                ),
                        ),
                ),
            )
    }
}
