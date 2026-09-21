package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
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
    private val toolExecutorFor: (Set<String>, SubAgentWorkspace?) -> AgentModelClient.ToolExecutor,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val maxRounds: Int = MAX_SUB_AGENT_ROUNDS,
    private val tokenBudget: Int = SUB_AGENT_TOKEN_BUDGET,
    private val parallelLimit: Int = SUB_AGENT_PARALLEL_LIMIT,
    /**
     * worktree 命令执行器，由宿主注入（Android 侧接 `RootShellTerminalController`）。
     * 缺省 null 时写入模式拿不到 diff 统计，但不会崩——单测与只读模式都不需要它。
     */
    private val worktreeShellExecutor: ((String) -> String)? = null,
    /**
     * 共享信箱。为 null 时信箱工具不出现——单角色委派没有同伴可分享，
     * 开着只会多占一个工具位。
     */
    private val mailbox: SubAgentMailbox? = null,
    /** 信箱工具的执行入口：由宿主把 `mailbox_post` 的调用接到 [SubAgentMailbox.post]。 */
    private val mailboxPost: (suspend (author: String, runId: String, summary: String, body: String) -> String)? = null,
    /**
     * 轨迹落盘：把这次委派的完整消息流交给宿主。
     *
     * 子智能体的过程默认不进主上下文（这正是它省 token 的方式），代价是主 loop 只能看到摘要、
     * 无法核验。落盘把「信任」变成「可追溯」：需要抽查时读回来，用户也能自己翻。
     *
     * 参数带上节点自身的信息（id、父节点、起止时间、结果），宿主才能把它挂进委派树，
     * 而不是只能按角色名平铺着存。缺省 null 时不落盘——单测不需要，也不该在测试里写磁盘。
     */
    private val traceSink: ((TraceRecord) -> Unit)? = null,
) {
    /**
     * 一次委派的完整记录：树的节点身份 + 内容。
     *
     * [parentId] 是发起这次委派的节点 id（主循环直接派出时为空）；[id] 是本次委派自身的节点 id，
     * 它同时是未来嵌套委派的父 id——一次 run 就是一棵以 [id] 为根的子树。
     */
    data class TraceRecord(
        val id: String,
        val parentId: String,
        val role: String,
        val startedAt: Long,
        val endedAt: Long,
        val status: String,
        val summary: String,
        val content: String,
    )
    /**
     * [plan] 由调用方按任务档位与历史消耗算好；缺省时按 compare 档默认值执行，
     * 保证单独调用 run() 也不会因为没传预算而失控。
     *
     * [workspace] 非空表示这次委派带写权限：子智能体在独立 worktree 里改文件，
     * 产出以 diff 形式交回主 loop，由主 loop 决定是否合并。
     *
     * [mailboxRunId] 非空表示这次委派接入共享信箱：开工前读同伴的发现，
     * 过程中可以投递自己的发现。只有多角色并行时才值得开——单角色没有同伴可分享。
     */
    data class Request(
        val role: String,
        val brief: String,
        val context: String = "",
        val plan: SubAgentPlan? = null,
        val workspace: SubAgentWorkspace? = null,
        val mailboxRunId: String = "",
        /**
         * 是否允许这次委派联网读网页。默认关闭——共享浏览器是进程级单例，
         * 多个角色并行时后一个 navigate 会顶掉前一个的页面，只有确认没有同伴抢页面
         * （单角色委派）时，调用方才显式打开。
         */
        val allowWebRead: Boolean = false,
        /** 触发这次委派的 delegate 工具调用 id；事件带着它，界面才能把进度挂到对应那一步。 */
        val toolCallId: String = "",
        /**
         * 发起这次委派的节点 id。主循环直接派出时为空（本次委派就是根的子节点）；
         * 由另一个子智能体派生时填那个委派自身的节点 id，委派树才能长出层级。
         */
        val parentId: String = "",
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
        /** 写入模式下子智能体改动的文件数；只读模式恒为 0。 */
        val changedFiles: Int = 0,
        /** 改动摘要（`git diff --stat` 形式），主 loop 据此判断要不要合并。 */
        val diffStat: String = "",
    )

    private val sequence = AtomicInteger()

    fun run(request: Request): Outcome {
        val role = request.role.trim().ifBlank { DEFAULT_ROLE }
        val plan = request.plan
            ?: SubAgentPlan(SubAgentScope.COMPARE, maxRounds, tokenBudget, 0, fromHistory = false)
        val id = "sub-$role-${sequence.incrementAndGet()}"
        val startedAt = System.currentTimeMillis()
        val toolCallId = request.toolCallId.trim()
        onEvent(
            AgentEvent.SubAgentUpdated(
                id = id,
                role = role,
                phase = PHASE_STARTED,
                summaryChars = 0,
                toolCallId = toolCallId,
            ),
        )

        val workspace = request.workspace
        val writable = workspace != null
        val mailboxRunId = request.mailboxRunId.trim()
        val mailboxEnabled = mailboxRunId.isNotEmpty() && mailbox != null
        val webRead = request.allowWebRead
        val allowedTools = buildSet {
            addAll(READ_ONLY_TOOL_NAMES)
            if (writable) addAll(WRITE_TOOL_NAMES)
            if (mailboxEnabled) addAll(MAILBOX_TOOL_NAMES)
            if (webRead) add(BROWSER_TOOL_NAME)
        }
        val tools = restrictedTools(allowedTools)
        // 开工前先读同伴已有的发现：不读的话，并行的意义就只剩下"各查一遍再汇总"。
        // 游标从 0 开始，拿到的是这一轮 run 到此刻为止的全部留言。
        val peerFindings = if (mailboxEnabled) {
            runCatching { runBlocking { mailbox!!.readSince(mailboxRunId, 0).text } }.getOrDefault("")
        } else {
            ""
        }
        val messages = JSONArray()
            .put(systemMessage(role, request.context + peerFindings, workspace?.worktreePath.orEmpty()))
            .put(AgentConversationCodec.userTextMessage(request.brief))
        val executor = toolExecutorFor(allowedTools, workspace)
        val wrappedExecutor = AgentModelClient.ToolExecutor { call ->
            val blockedAction = if (webRead) blockedBrowserAction(call.name, call.argumentsJson) else null
            when {
                blockedAction != null -> AgentModelClient.ToolResult(browserReadOnlyResult(blockedAction))
                call.name == AgentSubAgentToolCatalog.MAILBOX_POST && mailboxEnabled && mailboxPost != null -> {
                    val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
                    val summary = args?.optString("summary").orEmpty()
                    val body = args?.optString("body").orEmpty()
                    AgentModelClient.ToolResult(
                        runBlocking {
                            runCatching { mailboxPost.invoke(role, mailboxRunId, summary, body) }
                                .getOrDefault("""{"ok":false,"code":"MAILBOX_FAILED","message":"投递失败"}""")
                        },
                    )
                }
                else -> executor.execute(call)
            }
        }

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
                val result = runCatching { wrappedExecutor.execute(call) }.getOrElse { throwable ->
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
        // 改动统计只在写入模式下取：它是一条 shell 命令，只读模式没有 worktree 可查。
        // 取不到不算失败——摘要是主产出，diff 只是给主 loop 的合并线索。
        val diffStat = if (workspace != null) collectDiffStat(workspace.worktreePath) else DiffStat(0, "")
        onEvent(
            AgentEvent.SubAgentUpdated(
                id = id,
                role = role,
                phase = if (ok) PHASE_FINISHED else PHASE_FAILED,
                summaryChars = content.length,
                errorCode = errorCode,
                toolCallId = toolCallId,
            ),
        )
        // 完整消息流交给宿主：摘要之外留一份可核验的原始记录。写失败不影响本次委派的结果，
        // 它只是可追溯性的补充，不是产出本身。节点身份（id / 父节点 / 起止）一并交出，
        // 宿主才能把这次委派挂进委派树。
        runCatching {
            traceSink?.invoke(
                TraceRecord(
                    id = id,
                    parentId = request.parentId.trim(),
                    role = role,
                    startedAt = startedAt,
                    endedAt = System.currentTimeMillis(),
                    status = if (ok) TRACE_STATUS_OK else errorCode.ifBlank { TRACE_STATUS_FAILED },
                    summary = content,
                    content = buildTrace(role, request, messages, content, errorCode),
                ),
            )
        }
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
            changedFiles = diffStat.changedFiles,
            diffStat = diffStat.stat,
        )
    }

    /**
     * 统计 worktree 里的改动。`--stat` 的末行形如 `3 files changed, 42 insertions(+)`，
     * 只解析这一行的文件数；解析不到就当 0，不去猜。
     */
    private fun collectDiffStat(worktree: String): DiffStat {
        val command = AgentWorktreeManager.diffStatCommand(worktree)
        val output = runCatching { worktreeShell(command) }.getOrNull()?.trim().orEmpty()
        if (output.isBlank()) return DiffStat(0, "")
        val files = FILE_COUNT_PATTERN.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return DiffStat(files, output.take(SUB_AGENT_DIFF_STAT_CHARS))
    }

    /**
     * 轨迹文本：元信息 + 完整消息流。
     *
     * 截断只针对工具输出撑爆的极端情况——留不住全部也比只剩一段摘要强，
     * 但也不能让单次委派写出一个几十 MB 的文件。
     */
    private fun buildTrace(
        role: String,
        request: Request,
        messages: JSONArray,
        summary: String,
        errorCode: String,
    ): String = buildString {
        append("role: ").append(role).append('\n')
        append("brief: ").append(request.brief).append('\n')
        if (request.context.isNotBlank()) append("context: ").append(request.context).append('\n')
        append("error: ").append(errorCode.ifBlank { "-" }).append('\n')
        append("\n--- summary ---\n").append(summary).append('\n')
        append("\n--- messages ---\n")
        append(messages.toString().take(SUB_AGENT_TRACE_CHARS))
    }

    /** worktree 命令统一走宿主注入的执行器；没有注入时（单测）退化为不执行。 */
    private fun worktreeShell(command: String): String =
        worktreeShellExecutor?.invoke(command) ?: ""

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

    private fun restrictedTools(allowed: Set<String>): JSONArray = JSONArray().also { result ->
        for (index in 0 until parentTools.length()) {
            val tool = parentTools.getJSONObject(index)
            val name = tool.getJSONObject("function").getString("name")
            if (name !in allowed) continue
            // 浏览器工具的 schema 按只读能力裁剪：模型看得见的能力应当就是它真能用的，
            // 写动作不出现在枚举里，就不会“先试一次再被拒”。参数层还有一道拦截。
            result.put(if (name == BROWSER_TOOL_NAME) restrictBrowserTool(tool) else tool)
        }
    }

    /** 返回第一个越权的浏览器动作名；没有越权（或不是浏览器调用）时返回 null。 */
    private fun blockedBrowserAction(name: String, argumentsJson: String): String? {
        if (name != BROWSER_TOOL_NAME) return null
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull() ?: return null
        val single = args.optString("action").trim()
        if (single.isNotEmpty() && single !in READ_ONLY_BROWSER_ACTIONS) return single
        val batch = args.optJSONArray("actions") ?: return null
        for (index in 0 until batch.length()) {
            val action = batch.optJSONObject(index)?.optString("action")?.trim().orEmpty()
            if (action.isNotEmpty() && action !in READ_ONLY_BROWSER_ACTIONS) return action
        }
        return null
    }

    private fun browserReadOnlyResult(action: String): String = JSONObject()
        .put("ok", false)
        .put("code", CODE_BROWSER_READ_ONLY)
        .put(
            "message",
            "子智能体只能读取网页：$action 不在允许的动作里。允许：" +
                "${READ_ONLY_BROWSER_ACTIONS.joinToString("、")}。" +
                "需要点击、输入、执行 JS 或下载时，把这一步交回主智能体。",
        )
        .toString()

    /** 裁剪浏览器工具 schema：动作枚举只留只读子集，说明里点明子智能体的能力范围。 */
    private fun restrictBrowserTool(tool: JSONObject): JSONObject {
        val clone = runCatching { JSONObject(tool.toString()) }.getOrNull() ?: return tool
        val function = clone.optJSONObject("function") ?: return clone
        val properties = function.optJSONObject("parameters")?.optJSONObject("properties")
        properties?.optJSONObject("action")?.let { action ->
            action.put("enum", JSONArray().apply { READ_ONLY_BROWSER_ACTIONS.forEach { put(it) } })
            action.put("description", "本次唯一执行的浏览器动作（子智能体只有只读动作）。")
        }
        function.put("description", function.optString("description") + BROWSER_READ_ONLY_NOTE)
        return clone
    }

    private fun systemMessage(role: String, context: String, worktree: String = ""): JSONObject = JSONObject()
        .put("role", "system")
        .put(
            "content",
            buildString {
                append("你是本次任务中的「").append(role).append("」角色，只处理交给你的这一部分。")
                append("不要推测其它角色或主智能体的结论，也不要请求工具以外的能力。\n")
                AgentSubAgentRoles.instructionFor(role)?.let { append(it).append('\n') }
                if (worktree.isNotBlank()) append(worktreeInstruction(worktree))
                append("结论写成可直接交给主智能体的摘要，严格按三段格式：\n")
                append("结论：一句话回答交给你的问题。\n")
                append("证据：每条支撑结论的事实单独一行，写成 `路径:行号` 或 `命令 → 关键输出片段`。\n")
                append("不确定：你没验证到的部分单独列出，不要用推测补齐。\n")
                append(
                    "主智能体会按证据逐条回读原文核对，没有证据的结论会被当作未验证——宁少勿虚。" +
                        "不要复述探索过程。\n",
                )
                if (context.isNotBlank()) append("\n背景：\n").append(context)
            },
        )

    /**
     * 写入模式的额外约束。三条都是硬要求，不是建议：
     * 限定目录（避免改到主工作区或别的地方）、改完自验证（否则主 loop 还得重跑一遍）、
     * 报告改了什么（主 loop 要按这份清单决定合并策略）。
     */
    private fun worktreeInstruction(worktree: String): String = buildString {
        append("\n【隔离工作区】你这次带写权限，但只能改这一个目录里的文件：\n")
        append("    ").append(worktree).append('\n')
        append("主工作区不在这个目录里，改那里不会被接受；所有路径都写上面这个前缀。\n")
        append("改完必须自己验证（编译、跑单测、或执行脚本），把验证命令与结果写进结论；没有验证的改动视为未完成。\n")
        append("结论里要列出你改了哪些文件、每个文件改了什么，主智能体会按这份清单决定是否合并。\n")
    }

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

        /** 轨迹节点的结束状态：成功、或带上具体错误码的失败。 */
        const val TRACE_STATUS_OK = "ok"
        const val TRACE_STATUS_FAILED = "failed"
        const val DEFAULT_ROLE = AgentSubAgentRoles.DEFAULT

        /** `3 files changed, 42 insertions(+)` 里的文件数；单文件时是 `1 file changed`。 */
        val FILE_COUNT_PATTERN = Regex("""([0-9]+) files? changed""")

        /** diff 统计进主上下文的长度上限：够看清改了哪些文件，又不至于把 diff 整个搬过去。 */
        const val SUB_AGENT_DIFF_STAT_CHARS = 2_000

        /** 单次委派轨迹落盘的长度上限。 */
        const val SUB_AGENT_TRACE_CHARS = 400_000
    }
}

/** worktree 改动统计。 */
internal data class DiffStat(val changedFiles: Int, val stat: String)

/**
 * 子智能体允许使用的工具：只检索、不写文件、不碰设备。
 *
 * 写入模式下额外放行 [WRITE_TOOL_NAMES]，但那些工具只作用于子智能体自己的 worktree
 * （由 [AgentWorktreeManager] 隔离），主工作区不会被直接改动。
 */
internal val READ_ONLY_TOOL_NAMES =
    setOf("read_file", "read_files", "search_code", "find_files", "list_directory")

/** 写入模式下额外放行的工具。`terminal` 是子智能体自验证（编译、跑单测）的唯一途径。 */
internal val WRITE_TOOL_NAMES = setOf("write_file", "edit_file", "edit_files", "terminal")

/** 子智能体唯一能用的浏览器工具名。 */
internal const val BROWSER_TOOL_NAME = "browser_use"

/**
 * 子智能体允许的浏览器只读动作。
 *
 * 排除项都有理由：`get_cookies` 是凭据读取（子智能体的产出会回流到主上下文与摘要里，
 * 不该把会话 Cookie 带进去）、`evaluate_js` 能发任意请求、`download` 会落盘，
 * `click` / `type` / `set_cookie` 直接改页面状态。
 */
internal val READ_ONLY_BROWSER_ACTIONS = linkedSetOf(
    "navigate",
    "get_readable",
    "get_text",
    "get_page_info",
    "find_elements",
    "scroll",
    "screenshot",
    "go_back",
    "go_forward",
    "reload",
    "wait_for_selector",
)

private const val CODE_BROWSER_READ_ONLY = "SUB_AGENT_BROWSER_READ_ONLY"

private const val BROWSER_READ_ONLY_NOTE =
    " 注意：子智能体只能使用只读动作（navigate / get_readable / get_text / get_page_info / " +
        "find_elements / scroll / screenshot / go_back / go_forward / reload / wait_for_selector）；" +
        "点击、输入、执行 JS、读写 Cookie、下载都需要交回主智能体。"

/** 信箱工具：并行角色之间共享发现。 */
internal val MAILBOX_TOOL_NAMES = setOf("mailbox_post")

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
