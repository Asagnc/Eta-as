package io.github.asagnc.sta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubAgentToolScopeTest {

    private val workspace = SubAgentWorkspace(
        repoPath = "/workspace/Sta-src",
        worktreePath = "/workspace/sta-worktree-a",
    )

    private fun scope(name: String, json: String) =
        AgentSubAgentToolScope.scope(name, json, workspace)

    private fun ok(name: String, json: String): JSONObject {
        val scoped = scope(name, json)
        assertTrue("应通过：$scoped", scoped is AgentSubAgentToolScope.Scoped.Ok)
        return JSONObject((scoped as AgentSubAgentToolScope.Scoped.Ok).argumentsJson)
    }

    private fun rejected(name: String, json: String): AgentSubAgentToolScope.Scoped.Rejected {
        val scoped = scope(name, json)
        assertTrue("应被拒：$scoped", scoped is AgentSubAgentToolScope.Scoped.Rejected)
        return scoped as AgentSubAgentToolScope.Scoped.Rejected
    }

    @Test
    fun `相对路径挂到 worktree 下`() {
        assertEquals("/workspace/sta-worktree-a/app/src/A.kt", AgentSubAgentToolScope.mapPath("app/src/A.kt", workspace))
        assertEquals("/workspace/sta-worktree-a/A.kt", AgentSubAgentToolScope.mapPath("./A.kt", workspace))
    }

    @Test
    fun `主工作区路径映射到 worktree 同名位置`() {
        assertEquals(
            "/workspace/sta-worktree-a/app/src/A.kt",
            AgentSubAgentToolScope.mapPath("/workspace/Sta-src/app/src/A.kt", workspace),
        )
        assertEquals(
            "/workspace/sta-worktree-a",
            AgentSubAgentToolScope.mapPath("/workspace/Sta-src", workspace),
        )
    }

    @Test
    fun `空白路径落到 worktree 根`() {
        assertEquals("/workspace/sta-worktree-a", AgentSubAgentToolScope.mapPath("", workspace))
    }

    @Test
    fun `无关绝对路径原样保留`() {
        assertEquals("/etc/hosts", AgentSubAgentToolScope.mapPath("/etc/hosts", workspace))
    }

    @Test
    fun `越界判定认两种命名空间`() {
        assertTrue(AgentSubAgentToolScope.isInsideWorktree("/workspace/sta-worktree-a/x", workspace))
        assertTrue(AgentSubAgentToolScope.isInsideWorktree("/workspace/sta-worktree-a", workspace))
        assertTrue(
            AgentSubAgentToolScope.isInsideWorktree("/data/local/tmp/sta/sta-worktree-a/x", workspace),
        )
        assertFalse(AgentSubAgentToolScope.isInsideWorktree("/workspace/Sta-src/x", workspace))
        assertFalse(AgentSubAgentToolScope.isInsideWorktree("/etc/x", workspace))
        assertFalse(AgentSubAgentToolScope.isInsideWorktree("/workspace/sta-worktree-ab/x", workspace))
    }

    @Test
    fun `重复斜杠不会造成假越界`() {
        assertTrue(AgentSubAgentToolScope.isInsideWorktree("/workspace//sta-worktree-a//x", workspace))
    }

    @Test
    fun `提及主工作区的命令被判越界`() {
        assertTrue(AgentSubAgentToolScope.mentionsRepo("rm -rf /workspace/Sta-src/build", workspace))
        assertTrue(AgentSubAgentToolScope.mentionsRepo("sed -i s/a/b/ /data/local/tmp/sta/Sta-src/x", workspace))
        assertFalse(AgentSubAgentToolScope.mentionsRepo("ls -la", workspace))
        assertFalse(AgentSubAgentToolScope.mentionsRepo("git -C /workspace/sta-worktree-a status", workspace))
    }

    @Test
    fun `写文件相对路径被收进 worktree`() {
        val args = ok("write_file", """{"path":"app/src/A.kt","content":"x"}""")
        assertEquals("/workspace/sta-worktree-a/app/src/A.kt", args.getString("path"))
        assertEquals("x", args.getString("content"))
    }

    @Test
    fun `写文件指向主工作区时被映射进 worktree 而不是被拒`() {
        // 模型常照抄主工作区路径。映射到 worktree 同名位置比拒绝更符合预期：
        // 它想改的还是那个文件，只是落在隔离副本里。
        val args = ok("write_file", """{"path":"/workspace/Sta-src/app/src/A.kt","content":"x"}""")
        assertEquals("/workspace/sta-worktree-a/app/src/A.kt", args.getString("path"))
    }

    @Test
    fun `写文件指向 worktree 之外被拒`() {
        val result = rejected("write_file", """{"path":"/etc/hosts","content":"x"}""")
        assertEquals(AgentSubAgentToolScope.CODE_ESCAPE, result.code)
    }

    @Test
    fun `写文件指向别处被拒`() {
        assertEquals(
            AgentSubAgentToolScope.CODE_ESCAPE,
            rejected("edit_file", """{"path":"/etc/hosts","old_text":"a","new_text":"b"}""").code,
        )
    }

    @Test
    fun `写文件指向 worktree 绝对路径通过`() {
        val args = ok("edit_file", """{"path":"/workspace/sta-worktree-a/A.kt","old_text":"a","new_text":"b"}""")
        assertEquals("/workspace/sta-worktree-a/A.kt", args.getString("path"))
    }

    @Test
    fun `读文件允许读主工作区，但路径被映射`() {
        val args = ok("read_file", """{"path":"/workspace/Sta-src/app/src/A.kt"}""")
        assertEquals("/workspace/sta-worktree-a/app/src/A.kt", args.getString("path"))
    }

    @Test
    fun `检索工具同样收口`() {
        val args = ok("search_code", """{"pattern":"foo","path":"app/src"}""")
        assertEquals("/workspace/sta-worktree-a/app/src", args.getString("path"))
    }

    @Test
    fun `命令工具缺省 cwd 落到 worktree`() {
        val args = ok("terminal", """{"action":"open_and_exec","command":"ls"}""")
        assertEquals("/workspace/sta-worktree-a", args.getString("cwd"))
    }

    @Test
    fun `命令里出现主工作区被拒`() {
        val result = rejected("terminal", """{"command":"rm -rf /workspace/Sta-src/build"}""")
        assertEquals(AgentSubAgentToolScope.CODE_ESCAPE, result.code)
    }

    @Test
    fun `cwd 指向主工作区被拒`() {
        val result = rejected("run_command", """{"command":"ls","cwd":"/workspace/Sta-src"}""")
        assertEquals(AgentSubAgentToolScope.CODE_ESCAPE, result.code)
    }

    @Test
    fun `命令工具保留无关 cwd`() {
        val args = ok("terminal", """{"command":"ls","cwd":"/tmp"}""")
        assertEquals("/tmp", args.getString("cwd"))
    }

    @Test
    fun `非文件非命令工具不动参数`() {
        val scoped = scope("delegate", """{"task":"x"}""")
        assertEquals(
            """{"task":"x"}""",
            (scoped as AgentSubAgentToolScope.Scoped.Ok).argumentsJson,
        )
    }

    @Test
    fun `参数不是合法 JSON 时明确报错`() {
        assertEquals(
            AgentSubAgentToolScope.CODE_BAD_ARGUMENTS,
            rejected("write_file", "{not json").code,
        )
    }

    @Test
    fun `空参数按空对象处理`() {
        val args = ok("read_file", "")
        assertEquals("/workspace/sta-worktree-a", args.getString("path"))
    }

    // ---- 数组型路径参数（read_files / edit_files）----

    @Test
    fun `read_files 的 paths 逐条映射到 worktree`() {
        val args = ok("read_files", """{"paths":["app/src/A.kt","/workspace/Sta-src/B.kt"]}""")
        val paths = args.getJSONArray("paths")

        assertEquals("/workspace/sta-worktree-a/app/src/A.kt", paths.getString(0))
        assertEquals("/workspace/sta-worktree-a/B.kt", paths.getString(1))
    }

    @Test
    fun `read_files 允许读 worktree 之外的绝对路径`() {
        // 读别处无害，只有写才需要收口；这条守住「不要顺手把读也锁死」。
        val args = ok("read_files", """{"paths":["/etc/hosts"]}""")
        assertEquals("/etc/hosts", args.getJSONArray("paths").getString(0))
    }

    @Test
    fun `edit_files 的每条 edits 路径都映射到 worktree`() {
        val args = ok(
            "edit_files",
            """{"edits":[{"path":"app/src/A.kt","old_text":"a","new_text":"b"}]}""",
        )
        val entry = args.getJSONArray("edits").getJSONObject(0)

        assertEquals("/workspace/sta-worktree-a/app/src/A.kt", entry.getString("path"))
        assertEquals("a", entry.getString("old_text"))
    }

    @Test
    fun `edit_files 任一条越界就整批拒绝`() {
        // 主工作区路径会被映射进 worktree（这是期望行为），所以真正的越界是
        // 映射后仍落在 worktree 外的路径——那种一条都不能放过。
        val failure = rejected(
            "edit_files",
            """{"edits":[
                {"path":"app/src/A.kt","old_text":"a","new_text":"b"},
                {"path":"/etc/passwd","old_text":"a","new_text":"b"}
            ]}""",
        )

        assertEquals(AgentSubAgentToolScope.CODE_ESCAPE, failure.code)
        assertTrue(failure.message.contains("edits[1].path"))
    }

    @Test
    fun `edit_files 把主工作区路径映射进 worktree 而不是拒绝`() {
        val args = ok(
            "edit_files",
            """{"edits":[{"path":"/workspace/Sta-src/app/src/B.kt","old_text":"a","new_text":"b"}]}""",
        )

        assertEquals(
            "/workspace/sta-worktree-a/app/src/B.kt",
            args.getJSONArray("edits").getJSONObject(0).getString("path"),
        )
    }

    @Test
    fun `edit_files 的 edits 不是数组时明确报错`() {
        assertEquals(
            AgentSubAgentToolScope.CODE_BAD_ARGUMENTS,
            rejected("edit_files", """{"edits":"nope"}""").code,
        )
    }

    @Test
    fun `run_code 不重写参数：读路径由沙箱资源视图限定`() {
        val json = """{"code":"print(1)","language":"python"}"""
        val result = scope("run_code", json)
        assertTrue("应原样放行：$result", result is AgentSubAgentToolScope.Scoped.Ok)
        assertEquals(json, (result as AgentSubAgentToolScope.Scoped.Ok).argumentsJson)
    }

    @Test
    fun `read_files 的 paths 不是数组时明确报错`() {
        assertEquals(
            AgentSubAgentToolScope.CODE_BAD_ARGUMENTS,
            rejected("read_files", """{"paths":"app/src/A.kt"}""").code,
        )
    }
}
