package io.github.asagnc.sta.data.repository

import org.json.JSONObject

/**
 * 解析 memory_write / character_memory_write 的模型参数。
 *
 * 工具 schema 故意不把 mode、revision 标成必填：模型漏字段时不该让整次调用被参数校验直接拒掉。
 * 这里按实际提供的字段推断意图，只有真正无法推断或会产生破坏性后果时才返回可执行的错误说明。
 */
internal object AgentMemoryWriteRequest {
    const val UPSERT_SECTION = "upsert_section"
    const val REPLACE_RANGE = "replace_range"
    const val APPEND = "append"
    const val CLEAR = "clear"

    val MODES = listOf(UPSERT_SECTION, REPLACE_RANGE, APPEND, CLEAR)

    private const val REVISION_LENGTH = 64
    private const val MAX_HEADING_CHARS = 120

    sealed interface Parsed {
        data class Mutation(
            val mode: String,
            val mutation: AgentMemoryMutation,
            val revisionIgnored: Boolean = false,
        ) : Parsed

        data class Invalid(val message: String) : Parsed
    }

    fun parse(args: JSONObject): Parsed {
        val heading = args.optString("heading").trim()
        val hasContent = args.has("content")
        val content = args.optString("content")
        val hasStart = args.has("start_line")
        val hasEnd = args.has("end_line")
        val startLine = args.optInt("start_line", 0)
        val endLine = args.optInt("end_line", 0)
        val rawRevision = args.optString("revision").trim()
        val revision = rawRevision.takeIf { it.length == REVISION_LENGTH }

        val requested = args.optString("mode").trim()
        val mode = when {
            requested.isNotEmpty() && requested !in MODES ->
                return Parsed.Invalid("mode=\"$requested\" 无效；可用值：${MODES.joinToString("、")}")
            requested.isNotEmpty() -> requested
            heading.isNotEmpty() -> UPSERT_SECTION
            hasStart || hasEnd -> REPLACE_RANGE
            hasContent -> APPEND
            // 完全没给字段时不能推断成 clear：那会把整份记忆清空。
            else -> return Parsed.Invalid(
                "没有可执行的写入意图：请给出 heading（改章节）、start_line+end_line（改行）、content（追加）" +
                    "，或显式 mode=$CLEAR。",
            )
        }
        val ignored = rawRevision.isNotEmpty() && revision == null

        return when (mode) {
            UPSERT_SECTION -> when {
                heading.isEmpty() -> Parsed.Invalid(
                    "$UPSERT_SECTION 需要 heading（章节标题，例如 \"## 设备\" 或 \"设备\"；命中同名章节会整体替换，未命中则在文末新增）",
                )
                heading.length > MAX_HEADING_CHARS -> Parsed.Invalid("heading 最长 $MAX_HEADING_CHARS 个字符")
                !hasContent -> Parsed.Invalid(
                    "$UPSERT_SECTION 需要 content（章节正文，不含标题行）；确实要清空该章节正文时传空字符串",
                )
                else -> Parsed.Mutation(mode, AgentMemoryMutation.UpsertSection(revision, heading, content), ignored)
            }

            REPLACE_RANGE -> when {
                !hasStart || !hasEnd -> Parsed.Invalid(
                    "$REPLACE_RANGE 需要 start_line 与 end_line（1 起的闭区间行号，可取自 <memory_headings> 的 [Lx-y] 或 memory_get 结果）",
                )
                startLine < 1 || endLine < startLine -> Parsed.Invalid("行范围无效：start_line=$startLine, end_line=$endLine")
                else -> Parsed.Mutation(
                    mode,
                    AgentMemoryMutation.ReplaceRange(revision, startLine, endLine, content),
                    ignored,
                )
            }

            APPEND -> if (content.isBlank()) {
                Parsed.Invalid("$APPEND 需要非空 content；要更新已有章节请用 mode=$UPSERT_SECTION + heading")
            } else {
                Parsed.Mutation(mode, AgentMemoryMutation.Append(revision, content), ignored)
            }

            else -> Parsed.Mutation(CLEAR, AgentMemoryMutation.Clear(revision), ignored)
        }
    }
}
