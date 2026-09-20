package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 同 run 内的受限子智能体。
 *
 * 约束来自多智能体对照实验的结论：编排必须集中在一处（主 loop 是唯一编排者）、每个角色跑在
 * 独立上下文里、子智能体不能继承主 run 的全部工具、也不允许递归派生；独立并行的多智能体
 * 会放大错误，所以每个子智能体的产出都要回主 loop 汇总校验。
 *
 * 子智能体只回摘要，它自己的工具输出不进入主上下文——否则省下的轮次会以 token 还回去。
 */
internal class AgentSubAgentRunner(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val onEvent: (AgentEvent) -> Unit,
    private val parentTools: JSONArray,
    private val toolExecutorFor: (Set<String>) -> AgentModelClient.ToolExecutor,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val maxRounds: Int = MAX_SUB_AGENT_ROUNDS,
    private val tokenBudget: Int = SUB_AGENT_TOKEN_BUDGET,
    private val parallelLimit: Int = SUB_AGENT_PARALLEL_LIMIT,
) {
    /**
     * [plan] 由调用方按任务档位与历史消耗算好；缺省时按 compare 档默认值执行，
     * 保证单独调用 run() 也不会因为没传预算而失控。
     */
    data class Request(
        val role: String,
        val brief: String,
        val context: String = "",
        val plan: SubAgentPlan? = null,
    )

    data class Outcome(
        val role: String,
        val ok: Boolean,
        val summary: String,
        val rounds: Int,
        val estimatedTokens: Int,
        val errorCode: String = "",
        val errorMessage: String = "",
        val scope: SubAgentScope = SubAgentScope.COMPARE,
        val tokenBudget: Int = 0,
        val budgetFromHistory: Boolean = false,
    )

    private val sequence = AtomicInteger()

    fun run(request: Request): Outcome {
        val role = request.role.trim().ifBlank { DEFAULT_ROLE }
        val plan = request.plan
            ?: SubAgentPlan(SubAgentScope.COMPARE, maxRounds, tokenBudget, 0, fromHistory = false)
        val id = "sub-$role-${sequence.incrementAndGet()}"
        onEvent(AgentEvent.SubAgentUpdated(id = id, role = role, phase = PHASE_STARTED, summaryChars = 0))

        val tools = restrictedTools()
        val messages = JSONArray()
            .put(systemMessage(role, request.context))
            .put(AgentConversationCodec.userTextMessage(request.brief))
        val executor = toolExecutorFor(READ_ONLY_TOOL_NAMES)

        val summary = StringBuilder()
        var round = 1
        var estimatedTokens = 0
        var errorCode = ""
        var errorMessage = ""
        while (round <= plan.maxRounds) {
            if (runController.isCancelled) {
                errorCode = "SUB_AGENT_CANCELLED"
                break
            }
            estimatedTokens = AgentContextBudget.rawEstimate(messages, tools)
            if (estimatedTokens > plan.tokenBudget) {
                errorCode = "SUB_AGENT_BUDGET_EXCEEDED"
                errorMessage = "子智能体上下文约 $estimatedTokens token，超过本次预算 ${plan.tokenBudget}"
                break
            }
            val response = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(
                        config = config,
                        messages = messages,
                        tools = tools,
                        sessionId = id,
                        purpose = ProviderRequestPurpose.CHAT,
                    ),
                    provider = provider,
                    controller = runController,
                    onEvent = {},
                    onProviderEvent = { _, _ -> },
                    discardAttemptReasoning = {},
                ).response
            } catch (failure: Exception) {
                errorCode = failureCode(failure)
                errorMessage = failure.message ?: failure.javaClass.simpleName
                break
            }
            val assistantMessage = response.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (toolCalls.isEmpty()) {
                summary.append(assistantMessage.optString("content").trim())
                break
            }
            messages.put(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ),
            )
            toolCalls.forEach { call ->
                val result = runCatching { executor.execute(call) }.getOrElse { throwable ->
                    AgentModelClient.ToolResult(
                        content = JSONObject()
                            .put("ok", false)
                            .put("code", "TOOL_ERROR")
                            .put("message", throwable.message ?: throwable.javaClass.simpleName)
                            .toString(),
                    )
                }
                messages.put(AgentConversationCodec.toolResultMessage(call, result))
            }
            round += 1
        }
        val exhausted = errorCode.isEmpty() || errorCode == "SUB_AGENT_BUDGET_EXCEEDED"
        if (summary.isBlank() && exhausted) {
            // 轮数/预算用尽前再追一次：收回工具，只要结论。
            // 否则整次委派以 SUB_AGENT_NO_RESULT 收场，已经花掉的轮次和 token 全部没有产出。
            val closing = JSONArray(messages.toString())
                .put(AgentConversationCodec.userTextMessage(CLOSING_PROMPT))
            summary.append(
                runCatching {
                    modelRetry.complete(
                        initialRound = round,
                        request = ProviderRequest(
                            config = config,
                            messages = closing,
                            tools = JSONArray(),
                            sessionId = id,
                            purpose = ProviderRequestPurpose.CHAT,
                        ),
                        provider = provider,
                        controller = runController,
                        onEvent = {},
                        onProviderEvent = { _, _ -> },
                        discardAttemptReasoning = {},
                    ).response.assistantMessage.optString("content").trim()
                }.getOrDefault(""),
            )
            if (summary.isNotBlank()) {
                errorCode = ""
                errorMessage = ""
            } else if (errorCode.isEmpty()) {
                errorCode = "SUB_AGENT_NO_RESULT"
                errorMessage = "子智能体在 ${plan.maxRounds} 轮内没有给出结论"
            }
        }
        val ok = errorCode.isEmpty()
        val content = summary.toString().trim().take(SUB_AGENT_SUMMARY_CHARS)
        onEvent(
            AgentEvent.SubAgentUpdated(
                id = id,
                role = role,
                phase = if (ok) PHASE_FINISHED else PHASE_FAILED,
                summaryChars = content.length,
                errorCode = errorCode,
            ),
        )
        return Outcome(
            role = role,
            ok = ok,
            summary = content,
            rounds = round.coerceAtMost(plan.maxRounds),
            estimatedTokens = estimatedTokens,
            errorCode = errorCode,
            errorMessage = errorMessage,
            scope = plan.scope,
            tokenBudget = plan.tokenBudget,
            budgetFromHistory = plan.fromHistory,
        )
    }

    /** 角色之间不共享中间推理，否则多角色会退化成同一个噪声源的多次采样。 */
    fun runAll(requests: List<Request>): List<Outcome> {
        if (requests.isEmpty()) return emptyList()
        if (requests.size == 1) return listOf(failureIsolated(requests.single()) { run(requests.single()) })
        val pool = Executors.newFixedThreadPool(minOf(requests.size, parallelLimit))
        return try {
            val futures = requests.map { request ->
                pool.submit<Outcome> { failureIsolated(request) { run(request) } }
            }
            futures.mapIndexed { index, future ->
                runCatching { future.get() }.getOrElse { throwable ->
                    failedOutcome(requests[index].role, throwable)
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    private inline fun failureIsolated(request: Request, block: () -> Outcome): Outcome =
        runCatching(block).getOrElse { throwable -> failedOutcome(request.role, throwable) }

    private fun failedOutcome(role: String, throwable: Throwable): Outcome {
        val code = failureCode(throwable)
        onEvent(
            AgentEvent.SubAgentUpdated(
                id = "sub-$role-failed",
                role = role.ifBlank { DEFAULT_ROLE },
                phase = PHASE_FAILED,
                summaryChars = 0,
                errorCode = code,
            ),
        )
        return Outcome(
            role = role.ifBlank { DEFAULT_ROLE },
            ok = false,
            summary = "",
            rounds = 0,
            estimatedTokens = 0,
            errorCode = code,
            errorMessage = throwable.message ?: throwable.javaClass.simpleName,
        )
    }

    private fun restrictedTools(): JSONArray = JSONArray().also { result ->
        for (index in 0 until parentTools.length()) {
            val tool = parentTools.getJSONObject(index)
            val name = tool.getJSONObject("function").getString("name")
            if (name in READ_ONLY_TOOL_NAMES) result.put(tool)
        }
    }

    private fun systemMessage(role: String, context: String): JSONObject = JSONObject()
        .put("role", "system")
        .put(
            "content",
            buildString {
                append("你是本次任务中的「").append(role).append("」角色，只处理交给你的这一部分。")
                append("不要推测其它角色或主智能体的结论，也不要请求工具以外的能力。\n")
                AgentSubAgentRoles.instructionFor(role)?.let { append(it).append('\n') }
                append("结论写成可直接交给主智能体的摘要：先给结论，再给关键证据（文件路径与行号或命令输出要点），不要复述过程。\n")
                if (context.isNotBlank()) append("\n背景：\n").append(context)
            },
        )

    private fun failureCode(throwable: Throwable): String = when {
        throwable is AgentModelFailure && throwable.code.isNotBlank() -> throwable.code
        throwable is AgentRunCancelledException -> "SUB_AGENT_CANCELLED"
        else -> "SUB_AGENT_ERROR"
    }

    private companion object {
        /** 轮数/预算用尽后的收尾追问：收回工具，只要结论。 */
        const val CLOSING_PROMPT =
            "轮次已经用尽。请立刻基于已掌握的信息直接给出最终结论，不要再调用任何工具，也不要复述过程。"

        const val PHASE_STARTED = "started"
        const val PHASE_FINISHED = "finished"
        const val PHASE_FAILED = "failed"
        const val DEFAULT_ROLE = AgentSubAgentRoles.DEFAULT
    }
}

/** 子智能体允许使用的工具：只检索、不写文件、不碰设备。 */
internal val READ_ONLY_TOOL_NAMES = setOf("read_file", "search_code", "list_directory")

/** 构造参数的兜底值：真实预算由 [AgentSubAgentBudget] 按档位与历史消耗算出。 */
internal const val MAX_SUB_AGENT_ROUNDS = 6
internal const val SUB_AGENT_TOKEN_BUDGET = 30_000
internal const val SUB_AGENT_PARALLEL_LIMIT = 3
internal const val SUB_AGENT_SUMMARY_CHARS = 4_000

/**
 * 单次主运行里允许委派子智能体的总次数。多智能体实践里复杂任务可以起 10+ 个子代理，
 * 手机端压到 6：既有覆盖面，又不会把 token 成本放大到不可控。
 */
internal const val SUB_AGENT_INVOCATION_LIMIT = 6
