package io.github.asagnc.eta.agent.roleplay

import android.content.Context
import io.github.asagnc.eta.agent.model.AgentMemoryToolCatalog
import io.github.asagnc.eta.agent.model.AgentMemoryWritePayload
import io.github.asagnc.eta.agent.model.AgentModelClient
import io.github.asagnc.eta.data.repository.AgentMemoryException
import io.github.asagnc.eta.data.repository.AgentMemoryWriteRequest
import io.github.asagnc.eta.data.repository.AgentMemoryWriteResult
import io.github.asagnc.eta.data.repository.CharacterMemoryRepository
import io.github.asagnc.eta.data.repository.runMemoryMutation
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 角色作用域由 Runtime 绑定，模型参数不能选择其他角色或现实记忆。 */
internal class CharacterMemoryTools(
    private val context: Context,
    private val characterId: String,
    private val enabled: () -> Boolean,
) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (!enabled()) return result(error("MEMORY_DISABLED", "记忆已在设置中关闭"))
        return try {
            val args = JSONObject(call.argumentsJson)
            val value = when (call.name) {
                GET -> {
                    val read = CharacterMemoryRepository.read(
                        context, characterId,
                        query = args.optString("query").takeIf(String::isNotBlank),
                        startLine = args.optInt("start_line", 1),
                        maxChars = args.optInt("max_chars", 12_000),
                    )
                    JSONObject().put("ok", true).put("revision", read.snapshot.revision)
                        .put("bytes", read.snapshot.byteSize).put("line_count", read.snapshot.lineCount)
                        .put("start_line", read.startLine ?: JSONObject.NULL)
                        .put("end_line", read.endLine ?: JSONObject.NULL)
                        .put("matched_lines", read.matchedLines).put("has_more", read.hasMore)
                        .put("content", read.content)
                }
                WRITE -> {
                    val request = when (val parsed = AgentMemoryWriteRequest.parse(args)) {
                        is AgentMemoryWriteRequest.Parsed.Invalid ->
                            return result(error("INVALID_MEMORY_ARGUMENTS", parsed.message))
                        is AgentMemoryWriteRequest.Parsed.Mutation -> parsed
                    }
                    val outcome = runMemoryMutation(request.mutation) { mutation ->
                        CharacterMemoryRepository.mutate(context, characterId, mutation)
                    }
                    when (outcome.result) {
                        is AgentMemoryWriteResult.Success ->
                            AgentMemoryWritePayload.success(outcome, request.mode, request.revisionIgnored)
                        is AgentMemoryWriteResult.Conflict -> AgentMemoryWritePayload.conflict(outcome)
                    }
                }
                else -> error("UNKNOWN_TOOL", "未知角色记忆工具")
            }
            result(value)
        } catch (failure: AgentMemoryException) {
            result(error(failure.code, failure.message ?: "角色记忆操作失败"))
        } catch (_: JSONException) {
            result(error("INVALID_TOOL_ARGUMENTS", "角色记忆工具参数无效"))
        }
    }

    private fun result(value: JSONObject) = AgentModelClient.ToolResult(value.toString(), sensitive = true)

    private fun error(code: String, message: String) =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    companion object {
        const val GET = "character_memory_get"
        const val WRITE = "character_memory_write"
        val NAMES = setOf(GET, WRITE)

        fun appendSchemas(tools: JSONArray) {
            val schemas = JSONArray().also { AgentMemoryToolCatalog.appendTo(it) }
            for (index in 0 until schemas.length()) {
                val schema = schemas.getJSONObject(index)
                val function = schema.getJSONObject("function")
                val read = function.getString("name") == "memory_get"
                function.put("name", if (read) GET else WRITE)
                function.put("description", if (read) {
                    "Read this character's persistent story memory. It contains fictional events, relationships, and scene continuity shared by this character's conversations. Use query or bounded pages. This does not read the user's real-world MEMORY.md."
                } else {
                    "Atomically update this character's persistent story memory with fictional events, relationships, and scene continuity. Update the section that already covers a topic with mode=upsert_section and its heading instead of appending duplicates, and keep '# 核心记忆' concise. Never put story facts in real-world MEMORY.md or treat fictional actions as completed device operations. mode and revision may be omitted."
                })
                if (!read) function.getJSONObject("parameters").getJSONObject("properties")
                    .getJSONObject("revision").put("description", "Optional SHA-256 revision from character_memory_get or the run-start character memory. Omit it to write against the latest content.")
                tools.put(schema)
            }
        }
    }
}
