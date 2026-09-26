package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AgentContextPruner` 的行为约束。
 *
 * 最重要的一条是 [theRequestViewIsByteStableAcrossRounds]：工具结果一旦进入历史，后续每轮
 * 算出的内容必须逐字节相同。提示缓存按完整匹配的前缀单元生效，只要有一条历史中部的消息被
 * 改写，从它开始往后的缓存就全部失效，那些 token 要按未命中的全价重算。
 */
class AgentContextPrunerTest {

    @Test
    fun resultsWithinTheLimitAreLeftAlone() {
        val messages = JSONArray()
            .put(toolResult("""{"ok":true}"""))
            .put(toolResult("x".repeat(500)))
            .put(toolResult("y".repeat(AgentContextPruner.MAX_CHARS)))

        val result = AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS)

        assertEquals(0, result.prunedCount)
        assertEquals(
            "y".repeat(AgentContextPruner.MAX_CHARS),
            result.messages.getJSONObject(2).getString("content"),
        )
    }

    @Test
    fun oversizedResultsKeepTheirHeadAndTail() {
        val payload = "y".repeat(150_000)
        val messages = JSONArray().put(
            toolResult("""{"ok":true,"path":"/a.kt","content":"$payload"}"""),
        )

        val result = AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS)

        assertEquals(1, result.prunedCount)
        val content = result.messages.getJSONObject(0).getString("content")
        // 截断后仍是可解析的 JSON，状态字段原样保留，只有载荷被换掉。
        val parsed = JSONObject(content)
        assertTrue(parsed.getBoolean("ok"))
        assertEquals("/a.kt", parsed.getString("path"))
        val text = parsed.getString("content")
        assertTrue(text.startsWith(payload.take(AgentContextPruner.HEAD_CHARS)))
        assertTrue(text.endsWith(payload.takeLast(AgentContextPruner.TAIL_CHARS)))
        assertTrue(text.contains("此处省略"))
        assertEquals(150_000, parsed.getInt("_sta_truncated_chars"))
        // 输出必须落在阈值内，否则下一轮会继续截，稳定性就没了。
        assertTrue(content.length <= AgentContextPruner.MAX_CHARS)
    }

    @Test
    fun truncationIsIdempotent() {
        val messages = JSONArray().put(oversizedToolResult(150_000))
        val once = AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS).messages

        val twice = AgentContextPruner.prune(once, AgentContextPruner.MAX_CHARS)

        // 幂等是稳定性的前提：截断结果再截一次必须得到完全相同的字节。
        assertEquals(0, twice.prunedCount)
        assertEquals(once.toString(), twice.messages.toString())
    }

    @Test
    fun theRequestViewIsByteStableAcrossRounds() {
        val history = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "系统提示"))
            .put(JSONObject().put("role", "user").put("content", "user 输入"))
        repeat(4) { index ->
            history.put(toolCallMessage(index))
            history.put(oversizedToolResult(150_000))
        }

        val firstRound = AgentContextPruner.prune(history, AgentContextPruner.MAX_CHARS).messages

        // 第二轮：又追加了一轮工具调用，历史变长，前面每一条都还在原位。
        val grown = JSONArray(history.toString())
        grown.put(toolCallMessage(4))
        grown.put(oversizedToolResult(150_000))
        val secondRound = AgentContextPruner.prune(grown, AgentContextPruner.MAX_CHARS).messages

        // 第一轮视图里的全部消息都要与第二轮逐字节相同：这才是 append-only，
        // 缓存前缀才不会在每轮被重新计费。
        assertEquals(firstRound.length(), commonPrefixLength(firstRound, secondRound))
    }

    @Test
    fun pruningNeverMutatesTheStoredHistory() {
        val messages = JSONArray().put(oversizedToolResult(150_000))
        val before = messages.toString()

        AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS)

        // 请求视图的构造不能改写历史：会话记录与归档仍然要保留完整工具结果。
        assertEquals(before, messages.toString())
        assertFalse(messages.toString().contains("此处省略"))
    }

    @Test
    fun untouchedMessagesAreSharedAndRewrittenOnesAreCloned() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "系统提示"))
            .put(toolResult("x".repeat(200)))
            .put(oversizedToolResult(150_000))

        val result = AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS)

        // 只有被改写的消息需要克隆，其余共享原对象——这正是省掉整段深拷贝的地方。
        assertSame(messages.getJSONObject(0), result.messages.getJSONObject(0))
        assertSame(messages.getJSONObject(1), result.messages.getJSONObject(1))
        assertNotSame(messages.getJSONObject(2), result.messages.getJSONObject(2))
    }

    @Test
    fun viewIsAlwaysAFreshTopLevelArray() {
        val messages = JSONArray().put(toolResult("x".repeat(200)))

        val result = AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS)

        // 顶层数组必须独立：注入逻辑会在视图上替换元素，共享顶层数组就会改到历史，
        // 而历史对象一旦被替换，按引用相等定位瞬时观察消息的删除就会失效。
        assertEquals(0, result.prunedCount)
        assertNotSame(messages, result.messages)
        assertSame(messages.getJSONObject(0), result.messages.getJSONObject(0))
    }

    @Test
    fun disabledTruncationLeavesEverythingAlone() {
        val messages = JSONArray().put(oversizedToolResult(150_000))

        val disabled = AgentContextPruner.prune(messages, maxChars = -1)

        assertEquals(0, disabled.prunedCount)
        assertNotSame(messages, disabled.messages)
        assertSame(messages.getJSONObject(0), disabled.messages.getJSONObject(0))
    }

    @Test
    fun transientObservationIsClonedIntoTheView() {
        // 瞬时观察（工具截图）在下一轮会被从历史里按引用摘掉，视图若共享同一个对象，
        // 截图就会在请求里多留一轮。
        val transient = JSONObject()
            .put("role", "user")
            .put("content", "观察图")
            .put("_sta_observation", true)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "提示"))
            .put(transient)

        val result = AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS)

        assertNotSame(transient, result.messages.getJSONObject(1))
        assertSame(messages.getJSONObject(0), result.messages.getJSONObject(0))
    }

    @Test
    fun nonStringContentIsLeftAlone() {
        // 分片数组（文本 + 图片）不参与截断：截断会切坏图片数据。
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "y".repeat(150_000)))
        val messages = JSONArray().put(JSONObject().put("role", "tool").put("content", parts))

        val result = AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS)

        assertEquals(0, result.prunedCount)
        assertSame(messages.getJSONObject(0), result.messages.getJSONObject(0))
    }

    @Test
    fun longReasoningIsFoldedToItsHeadOnly() {
        val long = "先确认目录结构，" + "再逐个读取\u3002".repeat(400) + "所以下一步读 AgentLoop"
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", "结论")
            .put("reasoning_content", long)
        val messages = JSONArray().put(message)

        val result = AgentContextPruner.prune(messages, AgentContextPruner.MAX_CHARS)

        val folded = result.messages.getJSONObject(0).getString("reasoning_content")
        // 只保留意图（头部）：推理的尾部是收尾语，随历史回传会成为下一轮的续写对象，
        // 于是「看到自己被截断的收尾语 → 接着说同一段」自我强化。丢的是草稿尾，
        // 正式结论在 assistant content 里，不参与折叠。
        assertTrue(folded.startsWith(long.take(AgentContextPruner.REASONING_HEAD_CHARS)))
        assertFalse("尾部不得进入折叠结果", long.endsWith(folded.takeLast(50)))
        assertTrue(folded.contains("此处省略"))
        assertTrue(folded.length <= AgentContextPruner.MAX_REASONING_CHARS)
        // 历史本身不能被改写：归档里仍要保留完整推理。
        assertEquals(long, message.getString("reasoning_content"))
    }

    @Test
    fun shortReasoningIsLeftAloneAndFoldingIsIdempotent() {
        val short = "一句话的推理"
        val shortMessages = JSONArray().put(
            JSONObject().put("role", "assistant").put("content", "结论")
                .put("reasoning_content", short),
        )

        val untouched = AgentContextPruner.prune(shortMessages, AgentContextPruner.MAX_CHARS)

        // 没超过阈值就不动：折叠也要幂等，否则每轮都会重算成不同字节。
        assertSame(shortMessages.getJSONObject(0), untouched.messages.getJSONObject(0))

        val long = "头" + "中间过程。".repeat(300) + "尾"
        val once = AgentContextPruner.prune(
            JSONArray().put(
                JSONObject().put("role", "assistant").put("content", "结论")
                    .put("reasoning_content", long),
            ),
            AgentContextPruner.MAX_CHARS,
        ).messages
        val twice = AgentContextPruner.prune(once, AgentContextPruner.MAX_CHARS)

        assertEquals(0, twice.prunedCount)
        assertEquals(once.toString(), twice.messages.toString())
    }

    @Test
    fun tinyLimitsStillRespectTheBudget() {
        // 阈值小到连省略标记都放不下时也必须守住长度上限，否则下一轮会继续截，稳定性又没了。
        val messages = JSONArray().put(oversizedToolResult(150_000))

        val result = AgentContextPruner.prune(messages, maxChars = 20)

        assertTrue(result.messages.getJSONObject(0).getString("content").length <= 20)
    }

    private fun commonPrefixLength(first: JSONArray, second: JSONArray): Int {
        var index = 0
        while (
            index < first.length() && index < second.length() &&
            first.get(index).toString() == second.get(index).toString()
        ) {
            index++
        }
        return index
    }

    private fun toolResult(content: String): JSONObject = JSONObject()
        .put("role", "tool")
        .put("tool_call_id", "call")
        .put("content", content)

    private fun oversizedToolResult(chars: Int): JSONObject =
        toolResult("""{"ok":true,"content":"${"y".repeat(chars)}"}""")

    private fun toolCallMessage(index: Int): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", "")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-$index")))
}
