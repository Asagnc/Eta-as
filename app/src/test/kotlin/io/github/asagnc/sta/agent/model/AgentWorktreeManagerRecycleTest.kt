package io.github.asagnc.sta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorktreeManagerRecycleTest {
    private val repo = "/repo/sta"

    private fun porcelain(vararg managedNames: String): String {
        val head = "worktree /repo/sta\nHEAD abc\nbranch refs/heads/sta\n\n"
        val rest = managedNames.joinToString("") { name ->
            "worktree /repo/$name\nHEAD def\ndetached\n\n"
        }
        return head + rest
    }

    @Test
    fun recyclesOldestUntilOneSlotIsFree() {
        val output = porcelain(
            "${AgentWorktreeManager.DIRECTORY_PREFIX}a",
            "${AgentWorktreeManager.DIRECTORY_PREFIX}b",
            "${AgentWorktreeManager.DIRECTORY_PREFIX}c",
        )

        // 上限 3，马上还要再建一个，所以只能留最新的 2 个。
        val recycling = AgentWorktreeManager.worktreesToRecycle(repo, output)

        assertEquals(listOf("/repo/${AgentWorktreeManager.DIRECTORY_PREFIX}a"), recycling)
    }

    @Test
    fun keepsEverythingWhenThereIsRoom() {
        val output = porcelain("${AgentWorktreeManager.DIRECTORY_PREFIX}a")

        assertTrue(AgentWorktreeManager.worktreesToRecycle(repo, output).isEmpty())
    }

    @Test
    fun ignoresWorktreesOutsideTheManagedPrefix() {
        val output = porcelain("sta-worktree-a", "someone-elses-checkout")

        // 别人的工作区一个都不能动：结果里只允许出现自己前缀 + 同级目录的那一个。
        assertEquals(
            listOf("/repo/${AgentWorktreeManager.DIRECTORY_PREFIX}a"),
            AgentWorktreeManager.worktreesToRecycle(repo, output, limit = 1),
        )
    }
}
