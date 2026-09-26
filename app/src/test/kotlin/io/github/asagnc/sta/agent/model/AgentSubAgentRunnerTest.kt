package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.runtime.AgentEvent
import io.github.asagnc.sta.agent.runtime.AgentRunController
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubAgentRunnerTest {
    @Test
    fun subAgentSeesOnlyReadOnlyToolsAndForbiddenCallsAreReportedBack() {
        val provider = FakeProvider(
            mutableListOf(
                toolCallMessage("terminal", """{"action":"open_and_exec","command":"rm -rf /"}"""),
                finalMessage("结论：并发上限常量在 AgentLoop 里。"),
            ),
        )
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())

        val outcome = runner(provider, events).run(
            AgentSubAgentRunner.Request(role = "检索", brief = "找出并发上限常量"),
        )

        val offered = toolNames(provider.requests.first().tools)
        assertEquals(setOf("read_file", "search_code", "list_directory"), offered)
        val followUp = provider.requests.last().messages.toString()
        assertTrue("被禁用的工具调用要作为工具结果回写", followUp.contains("SUB_AGENT_TOOL_FORBIDDEN"))
        assertTrue(outcome.ok)
        assertTrue(outcome.summary.contains("并发上限常量"))
        assertEquals(
            listOf("started", "finished"),
            events.filterIsInstance<AgentEvent.SubAgentUpdated>().map { it.phase },
        )
    }

    @Test
    fun exhaustedBudgetStillAsksOnceForAConclusionWithoutTools() {
        // 预算在第一轮之前就用完时，不再发起带工具的正式轮次，
        // 但仍会追一次「收回工具、只要结论」的收尾请求，避免整次委派零产出。
        val provider = FakeProvider(mutableListOf(finalMessage("预算内能给出的结论")))
        val outcome = runner(provider, mutableListOf(), tokenBudget = 1).run(
            AgentSubAgentRunner.Request(role = "检索", brief = "任意任务"),
        )

        assertTrue(outcome.ok)
        assertEquals(1, provider.requests.size)
        assertTrue("收尾请求不应再带工具", provider.requests.single().tools.length() == 0)
    }

    @Test
    fun rolesRunInIsolatedContextsAndFailuresDoNotBreakOtherRoles() {
        val provider = object : FakeProvider(mutableListOf()) {
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests += request
                if (request.sessionId.contains("攻击")) throw IllegalStateException("boom")
                val role = request.messages.getJSONObject(0).getString("content")
                return ProviderResponse(finalMessage("摘要：$role"))
            }
        }

        val outcomes = runner(provider, mutableListOf()).runAll(
            listOf(
                AgentSubAgentRunner.Request(role = "攻击视角", brief = "评估这个漏洞"),
                AgentSubAgentRunner.Request(role = "防御视角", brief = "评估这个漏洞"),
            ),
        )

        assertEquals(listOf("攻击视角", "防御视角"), outcomes.map { it.role })
        assertFalse(outcomes[0].ok)
        assertEquals("SUB_AGENT_ERROR", outcomes[0].errorCode)
        assertTrue(outcomes[1].ok)
        assertTrue("防御视角" in outcomes[1].summary)
        val firstMessageOfDefence = provider.requests
            .first { it.sessionId.contains("防御") }
            .messages.getJSONObject(0).getString("content")
        assertFalse("角色之间不能互相看到对方的提示", firstMessageOfDefence.contains("攻击视角"))
    }

    @Test
    fun writeModeOffersWriteToolsAndInjectsIsolatedWorkspace() {
        val provider = FakeProvider(mutableListOf(finalMessage("改完了，编译通过。")))
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val workspace = SubAgentWorkspace(
            repoPath = "/workspace/Sta-src",
            worktreePath = "/workspace/sta-worktree-x",
        )

        val outcome = runner(provider, events) { command ->
            commands += command
            if (command.contains("diff --stat")) "1 file changed, 2 insertions(+)" else ""
        }.run(
            AgentSubAgentRunner.Request(
                role = "实现",
                brief = "把并发上限改成 4",
                workspace = workspace,
            ),
        )

        val offered = toolNames(provider.requests.first().tools)
        assertEquals(
            setOf("read_file", "search_code", "list_directory", "write_file", "edit_file", "terminal"),
            offered,
        )
        val system = provider.requests.first().messages.getJSONObject(0).getString("content")
        assertTrue("系统提示要写明隔离目录", system.contains("/workspace/sta-worktree-x"))
        assertTrue("系统提示要要求自验证", system.contains("必须自己验证"))
        assertTrue(outcome.ok)
        assertEquals(1, outcome.changedFiles)
        assertTrue(outcome.diffStat.contains("1 file changed"))
        assertTrue("收尾要取 diff 统计", commands.any { it.contains("diff --stat") })
    }

    @Test
    fun readModeDoesNotOfferWriteTools() {
        val provider = FakeProvider(mutableListOf(finalMessage("查到了")))
        runner(provider, mutableListOf()).run(
            AgentSubAgentRunner.Request(role = "检索", brief = "找常量"),
        )
        assertEquals(
            setOf("read_file", "search_code", "list_directory"),
            toolNames(provider.requests.first().tools),
        )
    }

    @Test
    fun writeModeWithoutDiffOutputStillSucceeds() {
        // 取不到 diff 不算失败：摘要是主产出，diff 只是给主 loop 的合并线索。
        val provider = FakeProvider(mutableListOf(finalMessage("结论")))
        val outcome = runner(provider, mutableListOf()) { "" }.run(
            AgentSubAgentRunner.Request(
                role = "实现",
                brief = "改点东西",
                workspace = SubAgentWorkspace("/repo", "/repo-wt"),
            ),
        )
        assertTrue(outcome.ok)
        assertEquals(0, outcome.changedFiles)
        assertEquals("", outcome.diffStat)
    }

    private fun runner(
        provider: AgentProviderClient,
        events: MutableList<AgentEvent>,
        tokenBudget: Int = 30_000,
        worktreeShell: ((String) -> String)? = null,
    ): AgentSubAgentRunner = AgentSubAgentRunner(
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "k",
            model = "m",
            systemPrompt = "",
        ),
        provider = provider,
        runController = AgentRunController(),
        onEvent = { event -> events += event },
        parentTools = parentTools(),
        toolExecutorFor = { allowed, _, _ ->
            AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    JSONObject()
                        .put("ok", allowed.contains(call.name))
                        .put("code", if (allowed.contains(call.name)) "OK" else "SUB_AGENT_TOOL_FORBIDDEN")
                        .put("message", "子智能体不能使用 ${call.name}")
                        .toString(),
                )
            }
        },
        tokenBudget = tokenBudget,
        worktreeShellExecutor = worktreeShell,
    )
}

private open class FakeProvider(
    private val responses: MutableList<JSONObject>,
) : AgentProviderClient {
    override val id: String = "fake"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        endpoint = EndpointKind.CHAT_COMPLETIONS,
        streamingText = false,
        streamingToolCalls = false,
        imageInput = false,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = false,
    )
    // runAll 会并发调用 complete，普通 MutableList 并发 add 会丢元素，
    // 表现成「明明发过请求，first {} 却找不到」的偶发失败。
    val requests = java.util.concurrent.CopyOnWriteArrayList<ProviderRequest>()

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        requests += request
        return ProviderResponse(responses.removeFirstOrNull() ?: finalMessage("默认结束"))
    }
}

private fun parentTools(): JSONArray = JSONArray()
    .put(tool("read_file"))
    .put(tool("search_code"))
    .put(tool("list_directory"))
    .put(tool("write_file"))
    .put(tool("edit_file"))
    .put(tool("terminal"))

private fun tool(name: String): JSONObject = AgentToolSchema.function(
    name = name,
    description = name,
    parameters = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", JSONArray()),
)

private fun toolNames(tools: JSONArray): Set<String> = (0 until tools.length())
    .mapTo(linkedSetOf()) { index ->
        tools.getJSONObject(index).getJSONObject("function").getString("name")
    }

private fun toolCallMessage(name: String, arguments: String): JSONObject = JSONObject()
    .put("role", "assistant")
    .put("content", "")
    .put("finish_reason", "tool_calls")
    .put(
        "tool_calls",
        JSONArray().put(
            JSONObject()
                .put("id", "call-1")
                .put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", arguments)),
        ),
    )

private fun finalMessage(text: String): JSONObject = JSONObject()
    .put("role", "assistant")
    .put("content", text)
    .put("finish_reason", "stop")
