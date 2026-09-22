package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.runtime.AgentEvent
import io.github.asagnc.sta.agent.runtime.AgentRunController
import io.github.asagnc.sta.agent.memory.AgentMemoryContext
import io.github.asagnc.sta.agent.skill.SkillContext
import io.github.asagnc.sta.agent.roleplay.RoleplayRunContext
import io.github.asagnc.sta.config.Prefs
import io.github.asagnc.sta.agent.tool.AgentToolCapabilities
import io.github.asagnc.sta.data.model.AnthropicProviderSetting
import io.github.asagnc.sta.data.model.CustomBody
import io.github.asagnc.sta.data.model.CustomHeader
import io.github.asagnc.sta.data.model.OpenAiEndpointMode
import io.github.asagnc.sta.data.model.ModelReasoningCapabilities
import io.github.asagnc.sta.data.model.ProviderTypes
import io.github.asagnc.sta.data.model.ReasoningEffort
import io.github.asagnc.sta.data.provider.BuiltinProviders
import io.github.asagnc.sta.data.provider.ProviderSourceRegistry
import io.github.asagnc.sta.data.world.WorldKnowledgeLogic
import io.github.asagnc.sta.data.world.WorldKnowledgeStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val traceFormatter = AgentTraceFormatter()

    fun loadConfig(): ModelConfig {
        val runtimeJson = Prefs.getString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
        if (runtimeJson.isNotBlank()) {
            runCatching {
                json.decodeFromString<ModelConfig>(runtimeJson)
            }.getOrNull()?.let { runtime ->
                val thinkingAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
                val effort = if (thinkingAllowed) {
                    runtime.effectiveReasoningEffort
                } else {
                    ReasoningEffort.OFF
                }
                return runtime.copy(
                    terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                    browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                    deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                    deviceSensitiveReadTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                    deviceSensitiveActionTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                    thinkingEnabled = effort.enablesReasoning,
                    reasoningEffort = effort,
                )
            }
        }
        return ModelConfig(
            providerId = "builtin-openai",
            providerName = "OpenAI",
            providerType = ProviderTypes.OPENAI_COMPATIBLE,
            providerSourceType = ProviderSourceRegistry.resolve(
                providerId = "builtin-openai",
                baseUrl = "https://api.openai.com/v1",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-5.5",
            modelDisplayName = "GPT-5.5",
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            reasoningEffort = ReasoningEffort.fromLegacy(
                Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
            ),
        )
    }

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        history: List<ConversationMessage> = emptyList(),
        provider: AgentProviderClient = ProviderClientFactory.getClient(config),
        runController: AgentRunController = AgentRunController(),
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        additionalTools: JSONArray = JSONArray(),
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        sessionId: String = java.util.UUID.randomUUID().toString(),
        compactOnly: Boolean = false,
        /** 局部压缩：只压缩到这条消息之前，其余历史保持原样。 */
        compactUntilMessageId: String? = null,
        operationId: String = sessionId,
        initialUserMessageId: String = "user-$operationId",
        initialSupplementIndex: Int = 0,
        roleplayContext: RoleplayRunContext? = null,
        rewriteReply: Boolean = false,
        /** 当前任务清单（task_plan 快照）：每轮请求附在最后一条消息上，压缩后计划也不会丢。 */
        taskPlanSnapshot: (() -> String?)? = null,
        /** 当前方案（submit_plan 快照）：与清单一同注入，注入的是 digest 而不是正文。 */
        planSnapshot: (() -> String?)? = null,
        onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
        onTranscript: (List<ConversationMessage>) -> Unit = {},
        runStats: AgentRunStats? = null,
        onEvent: (AgentEvent) -> Unit = {},
        /**
         * 观测层（世界模型）的写入上下文，通常是 App 的 applicationContext；
         * 为 null 时不记录、不回读。失败教训与子智能体结论都落在这里。
         */
        worldContext: android.content.Context? = null,
    ): ModelResponse.Text {
        config.validate()
        val initialCapabilities = capabilitiesProvider()
        val messages = AgentPromptBuilder.buildInitialMessages(
            config,
            prompt,
            images,
            history,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            roleplayContext = roleplayContext,
        )
        if (rewriteReply) {
            messages.put(messages.length() - 1, AgentConversationCodec.userTextMessage(
                "请只改写下面这条角色回复，保持已有事实与实际工具结果，以当前角色设定改善表达。" +
                    "这不是重新执行任务；不得调用任何工具、重读设备、更新记忆或编造缺失证据。只输出替代正文。\n" +
                    "<reply_to_rewrite>\n$prompt\n</reply_to_rewrite>",
            ))
        } else if (!compactOnly) {
            messages.getJSONObject(messages.length() - 1).put("_eta_message_id", initialUserMessageId)
        }
        if (compactOnly) messages.remove(messages.length() - 1)
        val transcript = JSONArray()
        // 旧 history 中的无效消息可能在组装时被跳过，系统边界不能由 history 条数倒推。
        val systemCount = AgentPromptBuilder.buildSystemMessages(
            config, skillContext, memoryContext, initialCapabilities.rootAvailable, roleplayContext,
        ).length()
        fun toolsFor(capabilities: AgentToolCapabilities): JSONArray {
            if (rewriteReply) return JSONArray()
            val tools = AgentToolCatalog.build(
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                deviceDirectTools = config.deviceDirectTools,
                deviceSensitiveReadTools = config.deviceSensitiveReadTools,
                deviceSensitiveActionTools = config.deviceSensitiveActionTools,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                subAgentTools = config.subAgentTools,
                memoryTools = memoryContext.enabled,
                memoryWritable = roleplayContext == null,
                capabilities = capabilities,
            )
            for (index in 0 until additionalTools.length()) {
                tools.put(additionalTools.opt(index))
            }
            return tools
        }
        val tools = toolsFor(initialCapabilities)
        onEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        var promptRootAvailable = initialCapabilities.rootAvailable
        val loop = AgentLoop(
            transcript = transcript,
            systemCount = systemCount,
            operationId = operationId,
            onContextSnapshot = if (rewriteReply) ({ _ -> }) else onContextSnapshot,
            onTranscript = onTranscript,
            sessionId = sessionId,
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = toolExecutor,
            runController = runController,
            traceFormatter = traceFormatter,
            onEvent = onEvent,
            purpose = if (rewriteReply) ProviderRequestPurpose.REPLY_REWRITE else ProviderRequestPurpose.CHAT,
            roleplayContext = roleplayContext,
            taskPlanSnapshot = taskPlanSnapshot,
            planSnapshot = planSnapshot,
            initialSupplementIndex = initialSupplementIndex,
            runStats = runStats,
            maxParallelToolCalls = config.maxParallelToolCalls,
            toolsForRound = {
                val capabilities = capabilitiesProvider()
                if (capabilities.rootAvailable != promptRootAvailable) {
                    val systemMessages = AgentPromptBuilder.buildSystemMessages(
                        config, skillContext, memoryContext, capabilities.rootAvailable, roleplayContext,
                    )
                    for (index in 0 until systemMessages.length()) {
                        messages.put(index, systemMessages.getJSONObject(index))
                    }
                    promptRootAvailable = capabilities.rootAvailable
                }
                toolsFor(capabilities)
            },
        )
        loop.failureRecorder = failureRecorder(worldContext)
        loop.learningRecall = learningRecall(worldContext)
        loop.recallLookup = recallLookup(worldContext)
        val result = try {
            if (compactOnly) loop.compactOnly(compactUntilMessageId) else loop.run()
        } catch (throwable: Throwable) {
            throw AgentModelExecutionException(
                cause = throwable,
                contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
                reasoningContent = loop.reasoningSnapshot(),
                transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.transcript(
                    transcript,
                    0,
                    loop.sensitiveToolCallIdsSnapshot(),
                )),
            )
        }
        return ModelResponse.Text(
            content = result.content,
            contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
            reasoningContent = result.reasoningContent,
            transcript = AgentConversationCodec.transcript(
                transcript,
                0,
                result.sensitiveToolCallIds,
            ),
        )
    }

    private fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "请先配置 API 地址" }
        require(apiKey.isNotBlank()) { "请先配置 API Key" }
        require(model.isNotBlank()) { "请先配置模型名" }
        require(
            reasoningCapabilities?.mandatory != true ||
                effectiveReasoningEffort != ReasoningEffort.OFF
        ) { "当前模型强制启用思考，不能选择 Off 或禁用思考权限" }
        if (extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(extraBodyJson) }
                .getOrElse { throwable ->
                    error("额外请求体 JSON 无效：${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
    }

    fun buildUserHistoryMessage(
        text: String,
        images: List<ModelImage>,
    ): ConversationMessage =
        AgentConversationCodec.durableMessage(AgentConversationCodec.userMessage(text, images))

    internal fun summarizeOpenUriArguments(argumentsJson: String): String =
        traceFormatter.summarizeOpenUriArguments(argumentsJson)

    internal fun summarizeBrowserToolArguments(argumentsJson: String): String =
        traceFormatter.summarizeBrowserArguments(argumentsJson)

    internal fun summarizeToolResult(toolName: String, result: ToolResult): String =
        traceFormatter.summarizeResult(toolName, result)

    @Serializable
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = ProviderTypes.OPENAI_COMPATIBLE,
        val providerSourceType: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val modelDisplayName: String = "",
        val contextWindow: Int? = null,
        val systemPrompt: String,
        val anthropicVersion: String = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        val promptCacheEnabled: Boolean = false,
        val contextEditingEnabled: Boolean = false,
        val openAiEndpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        val hostedWebSearchEnabled: Boolean = false,
        val terminalTools: Boolean = false,
        val browserTools: Boolean = true,
        val deviceDirectTools: Boolean = true,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        /** 同批只读工具的并发上限；调整它需要用 eval 扫 2/4/8 找性价比拐点，而不是凭感觉改。 */
        val maxParallelToolCalls: Int = 4,
        /** 子智能体是否对模型可见；实际是否启用由 Prefs 的 agent_subagents_enabled 与本字段共同决定。 */
        val subAgentTools: Boolean = true,
        /** 请求视图里保留完整内容的最新工具结果条数；0 表示只保留最后一条，负数表示不清理。 */
        val toolResultKeep: Int = 6,
        /**
         * 压缩时的额外要求，来自记忆文件里的「压缩指令」章节（对齐 Claude Code 的
         * CLAUDE.md `# Compact instructions`）；空表示只用默认摘要要求。
         */
        val compactInstructions: String = "",
        /** 上下文占用提示的触发百分比，0 表示关闭该提示。 */
        val contextNoticePercent: Int = 60,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        val extraBodyJson: String = "",
        /**
         * 单轮输出上限（含思考 token）。null 表示不写进请求体，沿用上游默认值：
         * 只有本轮真的被输出上限截断（stop reason = length）时才由 AgentModelRetry 临时提升，
         * 免得默认值偏小的网关把工具参数截成 TRUNCATED_TOOL_CALL。
         */
        val maxOutputTokens: Int? = null,
        val customHeaders: List<CustomHeader> = emptyList(),
        val customBody: List<CustomBody> = emptyList()
    ) {
        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)
    }

    @Serializable
    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val reasoningSignature: String = "",
        /** 响应阶段收到的内容块序列（Anthropic 渠道）；回放历史时按原样回传，缺失则退回重建。 */
        val providerBlocksJson: String = "",
        val toolCallsJson: String = "",
        val contextSummary: Boolean = false,
        val compactedUserTurns: Int = 0,
        val summaryThroughUserTurn: Int = 0,
        val messageId: String = "",
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    )

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList(),
        /**
         * 敏感结果仍会供当前 Agent loop 使用，但工具参数与原始结果不会进入持久会话。
         * 最终 assistant 自己组织的答复不受此标记影响。
         */
        val sensitive: Boolean = false,
    )

    /** 图片引用：入口侧可为本地 URI/路径，进入模型协议前必须解析为远程 URL 或 data URL。 */
    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown"
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
            val contextSnapshot: AgentContextSnapshot? = null,
        ) : ModelResponse
    }

    /**
     * 把工具失败整理成观测条目并写进观测库。签名与失败详情都来自真实执行结果，
     * 命令取自工具参数（截断保存），不落任何用户内容。
     *
     * 窗口去重由观测库的签名查询负责（见 [WorldKnowledgeLogic.shouldWrite]）：
     * 同签名且内容未变时不重复写。
     */
    private fun failureRecorder(
        context: android.content.Context?,
    ): ((AgentModelClient.ToolCall, String, Int) -> Unit)? {
        if (context == null) return null
        return { call, content, round ->
            val entry = AgentFailureLearningRecord.of(
                toolName = call.name,
                round = round,
                resultContent = content,
                command = traceFormatter.displayCommand(call).orEmpty(),
                timestampMs = System.currentTimeMillis(),
            )
            if (entry != null) {
                WorldKnowledgeStore.write(
                    context,
                    WorldKnowledgeStore.Entry(
                        kind = KIND_FAILURE,
                        signature = entry.signature,
                        summary = entry.summary,
                        evidence = entry.evidence,
                        uncertainty = "",
                        originAgent = entry.toolName,
                        payload = org.json.JSONObject().put("round", entry.round).toString(),
                        // 失败教训没有文件依赖：它描述的是工具行为，不是某个文件的内容。
                        dependencies = emptyList(),
                        createdAt = entry.timestampMs,
                    ),
                )
            }
        }
    }

    /**
     * 按失败签名回读历史教训。与 [failureRecorder] 同源（同一个观测库），
     * 一个写、一个读，共同构成"同类失败下次更快被识别"的闭环。
     *
     * 只取窗口外的条目：窗口内的同签名失败属于"当前这起事故"，那时该说的是
     * "你已经连续失败 N 次"（见 AgentFailureGuard），而不是把刚写下的记录原样念回给模型。
     */
    private fun learningRecall(
        context: android.content.Context?,
    ): ((String) -> String?)? {
        if (context == null) return null
        return { signature ->
            val now = System.currentTimeMillis()
            val recalled = WorldKnowledgeStore.recall(
                context = context,
                kind = KIND_FAILURE,
                signature = signature,
                readContent = { null },
                nowMs = now,
            )
            if (recalled == null || now - recalled.createdAt <= AgentFailureLearningRecord.DEDUPE_WINDOW_MS) {
                null
            } else {
                recalled.summary.take(AgentFailureLearningRecord.MAX_HISTORY_CHARS).trim()
            }
        }
    }

    /** 观测库里失败教训的种类标记。 */
    private const val KIND_FAILURE = "failure"

    /**
     * 查一次历史结论，供启动注入。
     *
     * 只查最近少量（见 [WorldKnowledgeStore.DEFAULT_INJECT_LIMIT]）：注入的职责是让模型
     * 知道「世界里有这些东西」，细节交给它自己用 world_recall 取——预先全量加载会挤占
     * 注意力预算，而模型真正需要的往往只是其中一小部分。
     */
    private fun recallLookup(
        context: android.content.Context?,
    ): (() -> List<WorldKnowledgeStore.Recalled>)? {
        if (context == null) return null
        return {
            WorldKnowledgeStore.recentFindings(
                context = context,
                readContent = { path ->
                    runCatching { java.io.File(path).takeIf { it.isFile }?.readText() }.getOrNull()
                },
            )
        }
    }

}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
    val contextSnapshot: AgentContextSnapshot? = null,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)
