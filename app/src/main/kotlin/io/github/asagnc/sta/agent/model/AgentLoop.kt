package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.runtime.AgentEvent
import io.github.asagnc.sta.agent.tool.AgentToolRequirements
import io.github.asagnc.sta.agent.runtime.AgentRunController
import io.github.asagnc.sta.agent.runtime.AgentTokenUsage
import io.github.asagnc.sta.agent.roleplay.RoleplayRunContext
import java.util.concurrent.Executors
import io.github.asagnc.sta.data.world.WorldKnowledgeStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val runStats: AgentRunStats? = null,
    private val maxParallelToolCalls: Int = DEFAULT_MAX_PARALLEL_TOOL_CALLS,
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    /**
     * 当前任务清单（task_plan 的 JSON 快照）。计划只存在对话状态与 UI 里，压缩或跨轮之后
     * 模型就看不到自己排的清单了，于是会出现「按计划继续」却不知道计划是什么的情况。
     */
    private val taskPlanSnapshot: (() -> String?)? = null,

    /**
     * 当前方案（submit_plan 的 JSON 快照）。注入的是它的 digest，不是正文：正文是给人看的，
     * 可能带表格，每轮注入全文会让请求持续膨胀。
     */
    private val planSnapshot: (() -> String?)? = null,
    initialSupplementIndex: Int = 0,
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private companion object {
        /** 并发上限默认值：只读查询大多落到进程外命令或 ContentProvider，再高的并发只会增加资源争用。 */
        const val DEFAULT_MAX_PARALLEL_TOOL_CALLS = 4

        const val MAX_PARALLEL_TOOL_CALLS_LIMIT = 8

        /** 上下文提示阈值与工具结果保留条数都是运行时配置（见 ModelConfig），不在循环里硬编码。 */

        /**
         * 可能被合法连续调用的轮询类工具。
         *
         * 等待与观察本来就会重复（等界面出现、再看一眼屏幕），对它们做重复阻断会直接
         * 打断正常流程，所以这几类不参与重复判定。
         */
        val POLLING_TOOL_NAMES = setOf(
            "wait", "wait_for_text", "wait_for_package", "observe_screen",
        )
    }

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
    )
    private var supplementIndex = initialSupplementIndex
    private val repeatGuard = AgentRepeatGuard()
    private val failureGuard = AgentFailureGuard()

    /**
     * 工具失败时追加一条学习记录（默认关闭）。写成回调而不是直接依赖 Android 的 File，
     * 是为了让 AgentLoop 保持可在 JVM 单测里构造；接线在 AgentModelClient。
     */
    var failureRecorder: ((AgentModelClient.ToolCall, String, Int) -> Unit)? = null

    /**
     * 按失败签名回读历史教训（来自更早的运行），用于失败时回注。
     *
     * 由宿主注入而不是在这里直接读文件：`AgentLoop` 只做流程编排，
     * 文件 IO 与路径规则归 AgentFailureLearningStore，单测里也能换成假实现。
     */
    var learningRecall: ((String) -> String?)? = null

    /**
     * 查一次历史结论，用于随请求注入（默认关闭）。
     *
     * 写成回调而不是在这里直接读观测库：`AgentLoop` 只做流程编排，数据库访问归
     * WorldKnowledgeStore，单测里也能换成假实现。
     *
     * 惰性且只求值一次：注入的历史结论是背景信息，同一轮里不会变，而
     * [requestMessagesFor] 每轮都跑——每轮查一次数据库等于把一次 IO 加到了每一轮上。
     */
    var recallLookup: (() -> List<WorldKnowledgeStore.Recalled>)? = null

    /** [recallLookup] 的缓存；`null` 表示还没查过。 */
    private var recallCache: List<WorldKnowledgeStore.Recalled>? = null

    private val recallSnapshot: List<WorldKnowledgeStore.Recalled>
        get() = recallCache ?: (recallLookup?.invoke() ?: emptyList()).also { recallCache = it }

    /**
     * 扫一次工作区目录拓扑，用于随请求注入（默认为空）。
     *
     * 与 [recallLookup] 同一形状：`AgentLoop` 只做流程编排，目录 IO 归宿主，单测里能换成假实现。
     *
     * 惰性且**只求值一次**：拓扑描述的是环境，同一 run 内不会变；而 [requestMessagesFor] 每轮
     * 都跑，每轮重扫既慢又可能让内容漂移——拓扑属于稳定前缀，逐字节变了会毁掉请求前缀缓存。
     */
    var workspaceTreeLookup: (() -> String?)? = null

    /** [workspaceTreeLookup] 的缓存；[workspaceTreeLoaded] 区分「没查过」与「查过但没有」。 */
    private var workspaceTreeCache: String? = null
    private var workspaceTreeLoaded = false

    private val workspaceTreeSnapshot: String?
        get() {
            if (!workspaceTreeLoaded) {
                workspaceTreeCache = workspaceTreeLookup?.invoke()
                workspaceTreeLoaded = true
            }
            return workspaceTreeCache
        }

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(untilMessageId: String? = null): Result {
        context.compact(
            tools,
            force = true,
            untilMessageId = untilMessageId,
            reasonCode = AgentEvent.ContextCompaction.REASON_MANUAL,
        )
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1

        while (true) {
            runController.throwIfCancelled()
            runStats?.roundStarted()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            val roundTools = if (purpose.allowsTools) toolsForRound?.invoke() ?: tools else JSONArray()
            toolCallValidator = AgentToolCallValidator(roundTools)
            publishTranscript()
            context.compact(roundTools, reasonCode = AgentEvent.ContextCompaction.REASON_TRIGGER_RATIO)
            var requestMessages = requestMessagesFor(roundTools)
            var requestEstimate = AgentContextBudget.rawEstimate(requestMessages, roundTools)
            var roundInputTokens: Int? = null
            var overflowAttempts = 0
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            var completedResponse: AgentModelRetry.Result? = null
            val completedRound = try {
                while (true) {
                    try {
                        val response = modelRetry.complete(
                            initialRound = round,
                            request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose),
                            provider = provider,
                            controller = runController,
                            onEvent = onEvent,
                            onProviderEvent = { attemptRound, providerEvent ->
                                if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                        providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                                    throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
                                }
                                if (providerEvent is ProviderEvent.Usage) {
                                roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                                runStats?.recordUsage(providerEvent.usage)
                            }
                                if (providerEvent is ProviderEvent.BlockDelta &&
                                    providerEvent.kind == AssistantBlockKind.THINKING
                                ) {
                                    accumulatedReasoning.append(providerEvent.delta)
                                }
                                providerEvent.toAgentEvent(attemptRound, roundTools)?.let(onEvent)
                            },
                            discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                        )
                        completedResponse = response
                        break
                    } catch (failure: AgentModelFailure) {
                        if (failure.code != "CONTEXT_OVERFLOW" || !failure.recoveryAllowed ||
                            overflowAttempts >= AgentContextBudget.MAX_OVERFLOW_ATTEMPTS) throw failure
                        overflowAttempts++
                        accumulatedReasoning.setLength(reasoningLengthBeforeRound)
                        context.compact(
                            roundTools,
                            force = true,
                            reasonCode = AgentEvent.ContextCompaction.REASON_OVERFLOW,
                        )
                        requestMessages = roleplayContext?.projectMessages(messages, roundTools) ?: messages
                        requestEstimate = AgentContextBudget.rawEstimate(requestMessages, roundTools)
                        roundInputTokens = null
                        round++
                    }
                }
                checkNotNull(completedResponse)
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            context.budget.observe(
                roundInputTokens?.let { AgentTokenUsage(inputTokens = it) },
                requestEstimate,
            )
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_sta_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                val decision = repeatedToolDecision(toolCalls)
                if (decision == AgentRepeatGuard.Decision.NOTICE) steerRepeatedToolCalls(round, toolCalls)
                val outcomes = if (decision == AgentRepeatGuard.Decision.BLOCK) {
                    blockedToolOutcomes(toolCalls)
                } else {
                    executeToolCalls(round, providerResponse.stopReason, toolCalls)
                }
                val notice = contextPressureNotice(roundTools)
                val nudges = failureNudges(outcomes)
                outcomes.forEachIndexed { index, outcome ->
                    val result = outcome.result
                        .let { current ->
                            nudges[index]?.let { hint -> current.withField("failure_notice", hint) } ?: current
                        }
                        .let { current ->
                            if (notice != null && index == outcomes.lastIndex) {
                                current.withContextNotice(notice)
                            } else {
                                current
                            }
                        }
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, result))
                }
                publishTranscript()
                appendToolImages(round, outcomes)
                publishTranscript()
                compactOnRequest(outcomes, roundTools)
                round += 1
                continue
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            return finishRun(round, content, roundTools)
        }
    }

    /**
     * 本轮收尾：发布最终 transcript、做一次最终压缩、汇报统计并返回结果。
     *
     * 自然结束与「方案提交后提前结束」共用它——两条路径的收尾必须一致，各写一份迟早会漂移。
     */
    private fun finishRun(round: Int, content: String, roundTools: JSONArray): Result {
        publishTranscript()
        if (purpose.allowsTools) {
            context.compact(
                roundTools,
                final = true,
                reasonCode = AgentEvent.ContextCompaction.REASON_FINAL,
            )
        }
        runStats?.takeIf { !it.isEmpty }?.let { stats ->
            onEvent(AgentEvent.RunStatsReported(stats.snapshot().toString()))
            stats.selfReview()?.let { review -> onEvent(AgentEvent.SelfReview(review)) }
        }
        onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
        return Result(
            content = content,
            reasoningContent = reasoningSnapshot(),
            sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
        )
    }

    /**
     * 判定这批调用要不要介入。
     *
     * 整批签名完全相同才算重复：模型换一个参数就是新尝试，不该被当成空转。
     * 纯轮询批次直接放行，理由见 [POLLING_TOOL_NAMES]。
     */
    private fun repeatedToolDecision(toolCalls: List<AgentModelClient.ToolCall>): AgentRepeatGuard.Decision {
        if (toolCalls.all { it.name in POLLING_TOOL_NAMES }) return AgentRepeatGuard.Decision.CONTINUE
        val signature = toolCalls.joinToString("|") { call ->
            call.name + ":" + call.argumentsJson.trim()
        }
        return repeatGuard.observe(signature)
    }

    /**
     * 被阻止的调用也要回一条结果：模型看不到任何反馈就会把同一批调用再发一次，
     * 阻止本身就没意义了。提示语写清「已阻止」与下一步该怎么做。
     */
    private fun blockedToolOutcomes(
        toolCalls: List<AgentModelClient.ToolCall>,
    ): List<ToolOutcome> = toolCalls.map { call ->
        ToolOutcome(
            call = call,
            result = AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "REPEATED_CALL_BLOCKED")
                    .put(
                        "message",
                        "这批调用与前几轮完全相同，已阻止执行：重复调用不会得到新信息。" +
                            "请改参数、换工具，或直接根据已有信息给出结论。",
                    )
                    .toString(),
            ),
        )
    }

    /** 提醒模型换策略；到提醒档时调用，到阻断档时也同样先提醒。 */
    private fun steerRepeatedToolCalls(round: Int, toolCalls: List<AgentModelClient.ToolCall>) {
        val names = toolCalls.joinToString("、") { it.name }
        runController.steer(
            "注意：第 $round 轮的这次工具调用与前几轮完全相同（$names），再调用一次也不会得到新信息。" +
                "请改参数、换工具，或直接根据已有信息给出结论。",
        )
    }

    /**
     * 处理模型转达的压缩请求（`compact_context`）。
     *
     * 压缩是宿主行为——要发一次摘要请求并重写历史——所以放在本批工具结果并入历史之后执行：
     * 这样刚拿到的结果也会进入摘要，下一轮请求立刻用上压缩后的上下文。
     */
    private fun compactOnRequest(outcomes: List<ToolOutcome>, roundTools: JSONArray) {
        var requested: String? = null
        for (outcome in outcomes) {
            if (outcome.call.name == AgentConversationToolCatalog.COMPACT_CONTEXT) {
                requested = AgentConversationToolCatalog.instructionsOf(outcome.call.argumentsJson)
            }
        }
        val instructions = requested ?: return
        try {
            context.compact(
                roundTools,
                force = true,
                reasonCode = AgentEvent.ContextCompaction.REASON_REQUESTED,
                extraInstructions = instructions,
            )
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            // 用户主动要求的压缩失败不该拖垮整个运行：failed 事件已经发出，上下文压力下一轮
            // 仍会按阈值正常处理；只有取消要继续传播。
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_sta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validationError,
            )
        }
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )

        val startedAt = System.nanoTime()
        val result = try {
            toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        runStats?.recordTool(toolCall.name, elapsedMs(startedAt), traceFormatter.isSuccessResult(result))
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    /**
     * 执行一个工具批次。
     *
     * 批次内全部是只读且无副作用的工具时并发执行：这些调用互不共享状态、没有先后依赖，
     * 并发只发生在工具实现内部，事件、消息与敏感标记仍按原顺序处理。
     * 其余情况（含参数校验失败、有副作用工具、异常终止状态）保持逐个顺序执行。
     */
    /**
     * 请求视图：工具结果按长度截断、历史助手的推理归一，会话历史与归档保持完整。
     *
     * 两条改写都只取决于内容本身，与轮次和位置无关——位置相关的规则会随轮次移动，
     * 每轮改写一条历史中部的消息，提示缓存的前缀就跟着失效。
     *
     * 视图的构造按「谁要改、谁才克隆」来做：只有被改写的消息才克隆，
     * 而 `attach` 只克隆它要写入的最后一条。这样每轮省掉整段历史的深拷贝，
     * 同时历史对象本身仍然不被改写。
     */
    private fun requestMessagesFor(roundTools: JSONArray): JSONArray {
        val projected = roleplayContext?.projectMessages(messages, roundTools)
        val result = AgentContextPruner.prune(projected ?: messages, config.toolResultMaxChars)
        runStats?.updatePrunedToolResults(result.prunedCount)
        // 每轮请求都把当前时间、当前方案、当前任务清单与历史结论重新附到最后一条消息上：
        // 时间让模型据此判断「现在」，计划让它在压缩或跨轮之后仍然知道自己排了什么。
        // 四者都按前缀去重，不会叠加。attach 会克隆最后一条消息再写，
        // 因此共享进来的历史对象不会被改到。
        //
        // 历史结论只在首次组装时查一次（见 recallSnapshot）：它是背景信息，
        // 同一轮里不会变，而这是每轮都跑的路径，不该把一次数据库查询加到每一轮上。
        AgentRequestContext.attach(
            result.messages,
            taskPlanSnapshot?.invoke(),
            planSnapshot?.invoke(),
            recallEntries = recallSnapshot,
            workspaceTree = workspaceTreeSnapshot,
        )
        // 统计的是真正发出去的那份视图：attach 写进去的时间与计划也算在内。
        runStats?.updateRequestComposition(AgentContextBudget.compositionOf(result.messages, roundTools))
        return result.messages
    }

    /**
     * 上下文接近窗口上限时返回一句提示，附在批次最后一条工具结果上。
     * 只在越线时出现，平时不占用上下文；阈值由配置决定，设为 0 即关闭该策略。
     */
    private fun contextPressureNotice(roundTools: JSONArray): String? {
        val percent = config.contextNoticePercent
        if (percent <= 0) return null
        // 用有效窗口：模型声明的窗口可能远大于服务端实际允许的规模（中转站虚标），
        // 只按声明值提示会让「显示不到一半就压缩」反复出现。
        val window = context.budget.effectiveWindow ?: return null
        val used = context.budget.estimate(messages, roundTools)
        if (used < window * percent / 100) return null
        runStats?.recordContextNotice()
        return "上下文估算已用约 ${used * 100 / window}%（估算 $used／窗口 $window token），后续请精简输出与工具调用。"
    }

    private fun AgentModelClient.ToolResult.withContextNotice(notice: String): AgentModelClient.ToolResult =
        withField("context_notice", notice)

    /** 往工具结果 JSON 里补一个字段；结果不是 JSON 时原样返回，不改写内容。 */
    private fun AgentModelClient.ToolResult.withField(key: String, value: String): AgentModelClient.ToolResult {
        val content = runCatching { JSONObject(this.content) }.getOrNull() ?: return this
        content.put(key, value)
        return copy(content = content.toString())
    }

    /**
     * 连续同因失败的纠偏提示。
     *
     * 错误来自真实执行，属于可靠的外部反馈，所以在运行中就附在结果旁边提醒换做法，
     * 而不是等运行结束再写一句总结——那时候唯一还能改变行为的是用户，提示已经晚了。
     * 只认显式失败（结果里 ok=false）；签名归一化见 AgentFailureSignature。
     */
    private fun failureNudges(outcomes: List<ToolOutcome>): Map<Int, String> {
        val nudges = mutableMapOf<Int, String>()
        // 同一轮里同一签名只回注一次历史：第 2、3 个同因失败不需要再看一遍同样的旧记录。
        val recalledSignatures = mutableSetOf<String>()
        outcomes.forEachIndexed { index, outcome ->
            val failure = AgentFailureSignature.of(outcome.call.name, outcome.result.content)
            if (failure == null) {
                failureGuard.reset()
                return@forEachIndexed
            }
            val parts = mutableListOf<String>()
            val verdict = failureGuard.observe(failure.signature)
            if (verdict.shouldNudge) {
                val reason = when (verdict.kind) {
                    AgentFailureGuard.Kind.CONSECUTIVE ->
                        "已连续 ${verdict.consecutive} 次以相同错误失败"
                    AgentFailureGuard.Kind.REPEATED ->
                        "本次运行里已第 ${verdict.total} 次以相同错误失败（中间换过别的方式，但问题没解决）"
                    AgentFailureGuard.Kind.NONE -> ""
                }
                if (reason.isNotEmpty()) {
                    parts += "同一个工具（${outcome.call.name}）$reason：" +
                        "${failure.detail}。原样重试不会成功，请先核对前置条件，或换一种做法。"
                }
            }
            // 历史回注与"连续失败"提示独立：本轮的第一次失败也值得知道上一轮踩过同样的坑。
            if (recalledSignatures.add(failure.signature)) {
                val history = runCatching { learningRecall?.invoke(failure.signature) }.getOrNull()
                if (!history.isNullOrBlank()) {
                    parts += "同一个失败在更早的运行里出现过，当时的记录是：\n$history"
                }
            }
            if (parts.isNotEmpty()) nudges[index] = parts.joinToString("\n")
        }
        return nudges
    }

    private fun executeToolCalls(
        round: Int,
        stopReason: AssistantStopReason,
        toolCalls: List<AgentModelClient.ToolCall>,
    ): List<ToolOutcome> {
        val parallel = stopReason == AssistantStopReason.TOOL_USE &&
            toolCalls.size > 1 &&
            toolCalls.all { call -> AgentToolRequirements.isParallelSafe(call.name, call.argumentsJson) } &&
            toolCalls.all { call -> toolCallValidator.validate(call) == null }
        if (parallel) return executeToolCallsInParallel(round, toolCalls)
        return toolCalls.map { call ->
            when (stopReason) {
                AssistantStopReason.TOOL_USE -> executeTool(round, call)
                AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                    round, call, "TRUNCATED_TOOL_CALL",
                    "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                )
                else -> rejectedToolOutcome(
                    round, call, "UNEXPECTED_TOOL_CALL",
                    "模型在 ${stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
                )
            }
        }
    }

    private fun executeToolCallsInParallel(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
    ): List<ToolOutcome> {
        toolCalls.forEach { call ->
            onEvent(
                AgentEvent.ToolStarted(
                    round = round,
                    toolCallId = call.id,
                    name = call.name,
                    argsPreview = traceFormatter.summarizeArguments(call),
                    command = traceFormatter.displayCommand(call),
                )
            )
        }
        runStats?.recordParallelBatch(toolCalls.size)
        val pool = Executors.newFixedThreadPool(
            minOf(toolCalls.size, maxParallelToolCalls.coerceIn(1, MAX_PARALLEL_TOOL_CALLS_LIMIT)),
        )
        val results = try {
            val futures = toolCalls.map { call ->
                pool.submit<AgentModelClient.ToolResult> { runToolCall(call) }
            }
            futures.map { future ->
                runCatching { future.get() }.getOrElse { throwable -> toolFailureResult(throwable) }
            }
        } finally {
            pool.shutdown()
        }
        runController.throwIfCancelled()
        return toolCalls.mapIndexed { index, call ->
            val result = results[index]
            if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(call.name)) {
                sensitiveToolCallIds += call.id
            }
            emitToolFinished(round, call, result)
            ToolOutcome(call, result)
        }
    }

    private fun runToolCall(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val startedAt = System.nanoTime()
        val result = try {
            toolExecutor.execute(call)
        } catch (throwable: Exception) {
            toolFailureResult(throwable)
        }
        runStats?.recordTool(call.name, elapsedMs(startedAt), traceFormatter.isSuccessResult(result))
        return result
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun toolFailureResult(throwable: Throwable): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", "TOOL_ERROR")
                .put("message", throwable.message ?: throwable.javaClass.simpleName)
                .toString(),
        )

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        runStats?.recordTool(toolCall.name, durationMs = 0, success = false)
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        val success = traceFormatter.isSuccessResult(result)
        if (!success) {
            runCatching { failureRecorder?.invoke(toolCall, result.content, round) }
        }
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = success,
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 工具结果消息按源码顺序统一追加；图片观察排在完整批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_sta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int, roundTools: JSONArray): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(
                round = round,
                usage = usage,
                // 服务端回报的 total_tokens 可能明显小于实际发出的规模（提示缓存、网关改写），
                // 只按它显示会让进度条长期偏低，这里把本地估算一并带上供展示与统计取较大值。
                estimatedContextTokens = context.budget.estimate(messages, roundTools).takeIf { it > 0 },
                windowTokens = context.budget.effectiveWindow,
            )
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
