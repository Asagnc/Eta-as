package io.github.asagnc.eta.agent.model

import io.github.asagnc.eta.agent.runtime.AgentRunController
import io.github.asagnc.eta.agent.runtime.AgentTokenUsage
import java.io.InputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object AnthropicMessagesProvider : AgentProviderClient {
    private const val DEFAULT_MAX_TOKENS = 4096
    private const val CONTEXT_MANAGEMENT_BETA = "context-management-2025-06-27"
    private const val CLEAR_TOOL_USES_STRATEGY = "clear_tool_uses_20250919"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "anthropic_messages"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.ANTHROPIC_MESSAGES,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.effectiveConfig
        val headerBuilder = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .add("anthropic-version", config.anthropicVersion)
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("x-api-key", config.apiKey)
                }
                ProviderRequestHeaders.mergeInto(this, config.baseUrl, config.customHeaders, request.sessionId)
            }
        if (config.contextEditingEnabled) {
            // 与自定义头合并成单个 anthropic-beta，重复的同名头会被部分网关丢弃。
            headerBuilder.set(
                "anthropic-beta",
                (headerBuilder.build()["anthropic-beta"].orEmpty().split(',') + CONTEXT_MANAGEMENT_BETA)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString(","),
            )
        }
        val headers = headerBuilder.build()
        val httpRequest = Request.Builder()
            .url(ProviderUrls.anthropicMessagesUrl(config.baseUrl))
            .headers(headers)
            .post(
                buildRequestJson(
                    config = config,
                    messages = request.messages,
                    tools = request.effectiveTools,
                    purpose = request.purpose,
                    dropThinkingBlocks = request.dropThinkingBlocks,
                )
                    .dropRejectedFields(request.dropFields)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        val call = AgentHttpClient.modelClient.newCall(httpRequest)
        val binding = runController.register { call.cancel() }
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                if (!response.isSuccessful) {
                    val errorBody = response.peekBody(16_384).string()
                    throw AgentModelFailure.http(response.code, errorBody)
                }
                val assistant = readStreamingAssistantMessage(response.body.byteStream(), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistant)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        purpose: ProviderRequestPurpose,
        dropThinkingBlocks: Boolean,
    ): JSONObject {
        val systemParts = mutableListOf<String>()
        val anthropicMessages = JSONArray()
        var toolResultBatch: JSONObject? = null
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system" -> {
                    toolResultBatch = null
                    providerMessageText(message.opt("content"))
                        .takeIf { it.isNotBlank() }
                        ?.let(systemParts::add)
                }
                "user" -> {
                    toolResultBatch = null
                    anthropicMessages.put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", convertUserContent(message.opt("content")))
                    )
                }
                "assistant" -> {
                    toolResultBatch = null
                    anthropicMessages.put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", convertAssistantContent(message, dropThinkingBlocks))
                    )
                }
                "tool" -> {
                    // 同一轮 assistant 的多个 tool_result 必须合并在紧随其后的一条 user 消息里，
                    // 每个结果各占一条 user 消息会被严格的渠道判 400：
                    // tool_use ids were found without tool_result blocks immediately after
                    //（并行调用工具时命中）。
                    val batch = toolResultBatch
                        ?: JSONObject()
                            .put("role", "user")
                            .put("content", JSONArray())
                            .also {
                                anthropicMessages.put(it)
                                toolResultBatch = it
                            }
                    batch.getJSONArray("content").put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", message.optString("tool_call_id"))
                            .put("content", message.optString("content"))
                    )
                }
            }
        }

        return JSONObject()
            .put("model", config.model)
            .put("max_tokens", DEFAULT_MAX_TOKENS)
            .put("stream", true)
            .put("messages", anthropicMessages)
            .also { request ->
                val system = systemParts.joinToString("\n\n").trim()
                if (system.isNotBlank()) request.put("system", system)
                convertTools(tools)?.let { request.put("tools", it) }
                // 顶层 cache_control 把缓存断点交给服务端自动落在最后一个可缓存块上，随对话增长自行前移。
                if (config.promptCacheEnabled) {
                    request.put("cache_control", JSONObject().put("type", "ephemeral"))
                }
                // 服务端按官方默认阈值（10 万输入 token）清理较早的工具结果、保留最近 3 次；
                // 清理发生在服务端，客户端持有的历史不变。
                if (config.contextEditingEnabled) {
                    request.put(
                        "context_management",
                        JSONObject().put(
                            "edits",
                            JSONArray().put(JSONObject().put("type", CLEAR_TOOL_USES_STRATEGY)),
                        ),
                    )
                }
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
                ProviderReasoning.applyAnthropicRequest(request, config, purpose, messages)
            }
    }

    private fun convertUserContent(content: Any?): JSONArray =
        when (content) {
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    when (item.optString("type")) {
                        "text" -> item.optString("text")
                            .takeIf { it.isNotBlank() }
                            ?.let { out.put(JSONObject().put("type", "text").put("text", it)) }
                        "image_url" -> convertImageBlock(item)?.let(out::put)
                    }
                }
                if (out.length() == 0) out.put(JSONObject().put("type", "text").put("text", ""))
            }
            else -> JSONArray().put(JSONObject().put("type", "text").put("text", providerMessageText(content)))
        }

    /**
     * 回放 assistant 轮次的内容块。
     *
     * Anthropic 要求历史里的 thinking / redacted_thinking 块原样回传：改写、丢块、只留第一个
     * 签名都会被拒（`Invalid \`signature\` in \`thinking\` block`、`thinking blocks cannot be
     * modified`）。所以优先按响应阶段记录的块序列回放；没有块序列的旧消息按常规字段重建，且
     * 签名缺失的思考块整块不发——占位签名对官方与严格渠道是必然 400，adaptive thinking 下省略
     * 历史思考块是允许的。[dropThinkingBlocks] 是上游已拒收回放思考块时的兜底：剥掉全部思考块
     * 再试一次。
     */
    private fun convertAssistantContent(message: JSONObject, dropThinkingBlocks: Boolean): JSONArray {
        val layout = message.optJSONArray(PROVIDER_BLOCKS_KEY)
            ?: return rebuiltAssistantContent(message, dropThinkingBlocks)
        return replayedAssistantContent(message, layout, dropThinkingBlocks)
            .takeIf { it.length() > 0 }
            ?: rebuiltAssistantContent(message, dropThinkingBlocks)
    }

    /** 块序列完整时按原顺序回放；text / tool_use 的载荷仍取常规字段。 */
    private fun replayedAssistantContent(
        message: JSONObject,
        layout: JSONArray,
        dropThinkingBlocks: Boolean,
    ): JSONArray {
        val blocks = mutableListOf<JSONObject>()
        val text = providerMessageText(message.opt("content")).takeIf { it.isNotBlank() && it != "null" }
        val toolCalls = message.optJSONArray("tool_calls")
        val usedCallIds = mutableSetOf<String>()
        var textPlaced = false
        for (index in 0 until layout.length()) {
            val entry = layout.optJSONObject(index) ?: continue
            when (entry.optString("type")) {
                "thinking" -> thinkingContentBlock(
                    thinking = entry.optString("thinking"),
                    signature = entry.optString("signature"),
                    dropThinkingBlocks = dropThinkingBlocks,
                )?.let(blocks::add)
                "redacted_thinking" -> redactedThinkingContentBlock(
                    data = entry.optString("data"),
                    dropThinkingBlocks = dropThinkingBlocks,
                )?.let(blocks::add)
                "text" -> if (!textPlaced && text != null) {
                    textPlaced = true
                    blocks.add(textContentBlock(text))
                }
                "tool_use" -> toolUseContentBlock(
                    toolCalls = toolCalls,
                    usedCallIds = usedCallIds,
                    callId = entry.optString("id"),
                )?.let(blocks::add)
            }
        }
        // 块序列被改写时补齐载荷：思考块后补文本，缺失的 tool_use 追加在末尾，避免 tool_result 找不到对应的工具调用。
        if (!textPlaced && text != null) {
            val firstPayload = blocks.indexOfFirst { it.optString("type") !in THINKING_BLOCK_TYPES }
            blocks.add(if (firstPayload < 0) blocks.size else firstPayload, textContentBlock(text))
        }
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                if (toolCall.optJSONObject("function") == null) continue
                val callId = toolCall.optString("id").ifBlank { "tool_call_$index" }
                if (usedCallIds.add(callId)) blocks.add(toolUseContentBlock(toolCall, callId))
            }
        }
        return JSONArray().also { content -> blocks.forEach(content::put) }
    }

    /** 没有块序列的旧消息（或其它渠道写入的历史）：按常规字段重建。 */
    private fun rebuiltAssistantContent(message: JSONObject, dropThinkingBlocks: Boolean): JSONArray {
        val content = JSONArray()
        val signature = message.optString("reasoning_signature")
        if (!dropThinkingBlocks && signature.isNotBlank()) {
            thinkingContentBlock(
                thinking = providerMessageText(message.opt("reasoning_content")).takeIf { it != "null" }.orEmpty(),
                signature = signature,
                dropThinkingBlocks = false,
            )?.let(content::put)
        }
        providerMessageText(message.opt("content"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { content.put(textContentBlock(it)) }
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                if (toolCall.optJSONObject("function") == null) continue
                content.put(toolUseContentBlock(toolCall, toolCall.optString("id").ifBlank { "tool_call_$index" }))
            }
        }
        if (content.length() == 0) {
            content.put(textContentBlock(""))
        }
        return content
    }

    private fun thinkingContentBlock(
        thinking: String,
        signature: String,
        dropThinkingBlocks: Boolean,
    ): JSONObject? =
        if (dropThinkingBlocks || signature.isBlank()) {
            null
        } else {
            JSONObject()
                .put("type", "thinking")
                .put("thinking", thinking)
                .put("signature", signature)
        }

    private fun redactedThinkingContentBlock(data: String, dropThinkingBlocks: Boolean): JSONObject? =
        if (dropThinkingBlocks || data.isBlank()) {
            null
        } else {
            JSONObject().put("type", "redacted_thinking").put("data", data)
        }

    private fun textContentBlock(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    private fun toolUseContentBlock(
        toolCalls: JSONArray?,
        usedCallIds: MutableSet<String>,
        callId: String,
    ): JSONObject? {
        if (toolCalls == null || callId.isBlank()) return null
        for (index in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(index) ?: continue
            val storedId = toolCall.optString("id").ifBlank { "tool_call_$index" }
            if (storedId != callId) continue
            return if (usedCallIds.add(storedId)) toolUseContentBlock(toolCall, storedId) else null
        }
        return null
    }

    private fun toolUseContentBlock(toolCall: JSONObject, callId: String): JSONObject {
        val function = toolCall.optJSONObject("function")
        return JSONObject()
            .put("type", "tool_use")
            .put("id", callId)
            .put("name", function?.optString("name").orEmpty())
            .put("input", parseJsonObject(function?.optString("arguments").orEmpty()))
    }

    private fun convertTools(tools: JSONArray): JSONArray? {
        if (tools.length() == 0) return null
        val converted = JSONArray()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank()) continue
            converted.put(
                JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description"))
                    .put("input_schema", function.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
            )
        }
        return converted.takeIf { it.length() > 0 }
    }

    private fun convertImageBlock(item: JSONObject): JSONObject? {
        val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma <= 5) return null
        val meta = url.substring(5, comma)
        val mediaType = meta.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        return JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", mediaType)
                    .put("data", data)
            )
    }

    private fun readStreamingAssistantMessage(
        stream: InputStream,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val blocks = linkedMapOf<Int, AnthropicBlock>()
        var sawMessageStop = false
        var finishReason: String? = null
        var usage: AgentTokenUsage? = null

        readProviderSse(stream, runController) { event, payload ->
            val result = processEvent(
                event = event,
                payload = payload.trim(),
                blocks = blocks,
                content = content,
                reasoning = reasoning,
                onEvent = onEvent,
            )
            if (result.messageStop) sawMessageStop = true
            result.finishReason?.let { finishReason = it }
            result.usage?.let {
                usage = it
                onEvent(ProviderEvent.Usage(it, result.contextInputTokens ?: it.inputTokens))
            }
            !sawMessageStop
        }
        if (!sawMessageStop) throw AgentModelFailure.incompleteStream("Anthropic SSE 流未正常结束")
        if (blocks.values.any { it.type in setOf("text", "thinking", "redacted_thinking", "tool_use") && !it.stopped }) {
            throw AgentModelFailure.incompleteStream("Anthropic SSE 内容块未正常结束")
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoning.toString())
            .put(
                "reasoning_signature",
                blocks.values.firstOrNull { it.signature.isNotEmpty() }?.signature?.toString().orEmpty(),
            )
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
                val toolCalls = blocks.values
                    .filter { it.type == "tool_use" && it.name.isNotBlank() }
                    .sortedBy { it.index }
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEachIndexed { position, block ->
                                array.put(block.toToolCallJson(position))
                            }
                        }
                    )
                }
                // 记下这一轮的块序列：回放历史时 thinking / redacted_thinking 块必须原样回传，
                // 内容、签名、顺序任何一处不符都会被上游判 400。
                val toolCallIds = toolCalls
                    .mapIndexed { position, block -> block.index to block.id.ifBlank { "tool_call_$position" } }
                    .toMap()
                providerBlockLayout(blocks.values.sortedBy { it.index }, toolCallIds)
                    .takeIf { it.length() > 0 }
                    ?.let { message.put(PROVIDER_BLOCKS_KEY, it) }
            }
    }

    /** 按响应顺序记录块序列：思考块连内容与签名一起存，text / tool_use 只占位（载荷仍走常规字段）。 */
    private fun providerBlockLayout(
        blocks: List<AnthropicBlock>,
        toolCallIds: Map<Int, String>,
    ): JSONArray = JSONArray().also { layout ->
        blocks.forEach { block ->
            val entry = when (block.type) {
                "thinking" -> JSONObject()
                    .put("type", "thinking")
                    .put("thinking", block.thinking.toString())
                    .put("signature", block.signature.toString())
                "redacted_thinking" -> JSONObject()
                    .put("type", "redacted_thinking")
                    .put("data", block.redacted)
                "text" -> JSONObject().put("type", "text")
                "tool_use" -> toolCallIds[block.index]
                    ?.let { callId -> JSONObject().put("type", "tool_use").put("id", callId) }
                else -> null
            }
            entry?.let(layout::put)
        }
    }

    private fun processEvent(
        event: String,
        payload: String,
        blocks: MutableMap<Int, AnthropicBlock>,
        content: StringBuilder,
        reasoning: StringBuilder,
        onEvent: (ProviderEvent) -> Unit
    ): EventResult {
        if (payload == "[DONE]") return EventResult(messageStop = true)
        val json = JSONObject(payload)
        val type = json.optString("type").ifBlank { event }

        fun appendVisibleDelta(block: AnthropicBlock, text: String) {
            if (text.isEmpty()) return
            val kind = when (block.type) {
                "text" -> {
                    block.text.append(text)
                    content.append(text)
                    AssistantBlockKind.TEXT
                }
                "thinking" -> {
                    block.thinking.append(text)
                    reasoning.append(text)
                    AssistantBlockKind.THINKING
                }
                else -> return
            }
            onEvent(ProviderEvent.BlockDelta(kind, block.index, text))
        }

        return when (type) {
            "error" -> throw AgentModelFailure.stream(
                json.optJSONObject("error") ?: JSONObject(),
                "Anthropic SSE 返回错误",
            )
            "message_start" -> {
                val rawUsage = json.optJSONObject("message")?.optJSONObject("usage")
                EventResult(
                    usage = parseUsage(rawUsage),
                    contextInputTokens = rawUsage?.firstInt("input_tokens")?.let { input ->
                        input + (rawUsage.firstInt("cache_read_input_tokens") ?: 0) +
                            (rawUsage.firstInt("cache_creation_input_tokens") ?: 0)
                    },
                )
            }
            "content_block_start" -> {
                val index = json.optInt("index")
                val block = json.optJSONObject("content_block") ?: JSONObject()
                val item = AnthropicBlock(
                    index = index,
                    type = block.optString("type"),
                    id = block.optString("id"),
                    name = block.optString("name")
                )
                block.optJSONObject("input")
                    ?.takeIf { it.length() > 0 }
                    ?.let { item.arguments.append(it.toString()) }
                blocks[index] = item
                when (item.type) {
                    "text" -> {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, index))
                        appendVisibleDelta(item, (block.opt("text") as? String).orEmpty())
                    }
                    "thinking" -> {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, index))
                        block.optString("signature").takeIf { it.isNotEmpty() }
                            ?.let { item.signature.append(it) }
                        appendVisibleDelta(item, (block.opt("thinking") as? String).orEmpty())
                    }
                    "redacted_thinking" -> {
                        // 脱敏过的思考块只有 data、没有内容可见：只做记录，回放时必须原样带上。
                        item.redacted = block.optString("data")
                    }
                    "tool_use" -> onEvent(
                        ProviderEvent.BlockStart(
                            kind = AssistantBlockKind.TOOL_CALL,
                            index = index,
                            blockId = item.id.ifBlank { null },
                            name = item.name.ifBlank { null },
                        )
                    )
                }
                EventResult()
            }
            "content_block_delta" -> {
                val index = json.optInt("index")
                val delta = json.optJSONObject("delta") ?: JSONObject()
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "text") }
                        appendVisibleDelta(block, (delta.opt("text") as? String).orEmpty())
                    }
                    "thinking_delta" -> {
                        val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "thinking") }
                        appendVisibleDelta(block, (delta.opt("thinking") as? String).orEmpty())
                    }
                    "signature_delta" -> {
                        val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "thinking") }
                        block.signature.append(delta.optString("signature"))
                    }
                    "input_json_delta" -> {
                        val partial = delta.optString("partial_json")
                        if (partial.isNotEmpty()) {
                            val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "tool_use") }
                            block.arguments.append(partial)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.TOOL_CALL,
                                    index = index,
                                    delta = partial,
                                )
                            )
                        }
                    }
                }
                EventResult()
            }
            "content_block_stop" -> {
                val index = json.optInt("index")
                val block = blocks[index] ?: return EventResult()
                if (block.stopped) return EventResult()
                block.stopped = true
                val kind = when (block.type) {
                    "text" -> AssistantBlockKind.TEXT
                    "thinking" -> AssistantBlockKind.THINKING
                    "tool_use" -> AssistantBlockKind.TOOL_CALL
                    else -> return EventResult()
                }
                onEvent(
                    ProviderEvent.BlockEnd(
                        kind = kind,
                        index = index,
                        blockId = block.id.ifBlank { null },
                        name = block.name.ifBlank { null },
                        content = block.content(),
                    )
                )
                EventResult()
            }
            "message_delta" -> EventResult(
                finishReason = json.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() },
                usage = parseUsage(json.optJSONObject("usage"))
            )
            "message_stop" -> EventResult(messageStop = true)
            else -> EventResult()
        }
    }

    /** 响应阶段记录下来的内容块序列：回放历史时按这段序列原样回传思考块。 */
    private const val PROVIDER_BLOCKS_KEY = "provider_blocks"
    private val THINKING_BLOCK_TYPES = setOf("thinking", "redacted_thinking")

    private data class AnthropicBlock(
        val index: Int,
        var type: String = "",
        var id: String = "",
        var name: String = "",
        var stopped: Boolean = false,
        var redacted: String = "",
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val signature: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun content(): String =
            when (type) {
                "text" -> text.toString()
                "thinking" -> thinking.toString()
                else -> arguments.toString()
            }

        fun toToolCallJson(position: Int): JSONObject =
            JSONObject()
                .put("id", id.ifBlank { "tool_call_$position" })
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("arguments", arguments.toString().ifBlank { "{}" })
                )
    }

    private data class EventResult(
        val messageStop: Boolean = false,
        val finishReason: String? = null,
        val usage: AgentTokenUsage? = null,
        val contextInputTokens: Int? = null,
    )

    private fun parseUsage(usage: JSONObject?): AgentTokenUsage? {
        usage ?: return null
        return AgentTokenUsage(
            contextTokens = null,
            inputTokens = usage.firstInt("input_tokens"),
            outputTokens = usage.firstInt("output_tokens"),
            reasoningTokens = usage.firstInt("thinking_output_tokens"),
            cachedTokens = usage.firstInt("cache_read_input_tokens")
        ).takeUnless { it.isEmpty }
    }

    private fun parseJsonObject(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            when (val raw = opt(key)) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }


}
