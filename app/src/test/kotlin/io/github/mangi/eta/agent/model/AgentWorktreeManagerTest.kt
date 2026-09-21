package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorktreeManagerTest {

    @Test
    fun `sanitizeToken 只保留安全字符并压缩连字符`() {
        assertEquals("fix-ui", AgentWorktreeManager.sanitizeToken("Fix UI"))
        assertEquals("a-b", AgentWorktreeManager.sanitizeToken("a///b"))
        assertEquals("x-y", AgentWorktreeManager.sanitizeToken("--x--y--"))
        assertEquals("run", AgentWorktreeManager.sanitizeToken("   "))
        assertEquals("run", AgentWorktreeManager.sanitizeToken("!!!!"))
    }

    @Test
    fun `sanitizeToken 把中文降级成连字符而不报错`() {
        assertEquals("run", AgentWorktreeManager.sanitizeToken("修复界面"))
    }

    @Test
    fun `sanitizeToken 截断超长输入`() {
        assertEquals(40, AgentWorktreeManager.sanitizeToken("a".repeat(200)).length)
    }

    @Test
    fun `sanitizeToken 去掉路径穿越尝试`() {
        val token = AgentWorktreeManager.sanitizeToken("../../etc/passwd")
        assertFalse(token.contains(".."))
        assertFalse(token.contains("/"))
    }

    @Test
    fun `worktreePath 与源仓库同级`() {
        assertEquals(
            "/workspace/eta-worktree-demo",
            AgentWorktreeManager.worktreePath("/workspace/Eta-src", "demo"),
        )
    }

    @Test
    fun `isManagedWorktree 只接受同级目录且带前缀`() {
        val repo = "/workspace/Eta-src"
        assertTrue(AgentWorktreeManager.isManagedWorktree(repo, "/workspace/eta-worktree-a"))
        assertFalse(AgentWorktreeManager.isManagedWorktree(repo, "/workspace/other"))
        assertFalse(AgentWorktreeManager.isManagedWorktree(repo, "/etc/eta-worktree-a"))
        assertFalse(AgentWorktreeManager.isManagedWorktree(repo, "/workspace/Eta-src/eta-worktree-a"))
    }

    @Test
    fun `createCommands 先注册再检出，且不建分支`() {
        val commands = AgentWorktreeManager.createCommands("/repo", "/repo-wt", "HEAD")
        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("worktree add --detach --no-checkout"))
        assertTrue(commands[1].contains("checkout --detach"))
        assertFalse(commands[0].contains("-b "))
    }

    @Test
    fun `bootstrapCommands 不覆盖已存在的 local properties`() {
        val command = AgentWorktreeManager.bootstrapCommands("/repo", "/repo-wt").single()
        assertTrue(command.contains("cp -n "))
        assertTrue(command.contains("/repo/local.properties"))
        assertTrue(command.contains("/repo-wt/local.properties"))
        assertTrue(command.endsWith("|| true"))
    }

    @Test
    fun `applyCommand 用三方合并应用补丁`() {
        val command = AgentWorktreeManager.applyCommand("/repo", "/repo-wt")
        assertTrue(command.contains("git -C '/repo-wt' --no-pager diff HEAD |"))
        assertTrue(command.contains("git -C '/repo' apply --3way"))
    }

    @Test
    fun `removeCommands 删除后清理注册信息`() {
        val commands = AgentWorktreeManager.removeCommands("/repo", "/repo-wt")
        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("worktree remove --force"))
        assertTrue(commands[1].contains("worktree prune"))
    }

    @Test
    fun `shellQuote 转义单引号与特殊字符`() {
        assertEquals("'plain'", AgentWorktreeManager.shellQuote("plain"))
        assertEquals("'it'\\''s'", AgentWorktreeManager.shellQuote("it's"))
        assertEquals("'a b\$c'", AgentWorktreeManager.shellQuote("a b\$c"))
    }
}
