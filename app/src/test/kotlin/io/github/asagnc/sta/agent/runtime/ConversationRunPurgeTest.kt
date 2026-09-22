package io.github.asagnc.sta.agent.runtime

import android.content.Context
import io.github.asagnc.sta.agent.model.AgentModelClient
import io.github.asagnc.sta.data.db.StaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationRunPurgeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        StaDatabase.closeForTests()
        context.deleteDatabase("sta.db")
    }

    @Test
    fun purgeRemovesEveryRunOfTheDeletedConversationAndKeepsOthers() {
        val tag = System.nanoTime()
        val deletedConversation = "conv-a-$tag"
        val keptRunId = "kept-$tag"
        addRun(conversationId = deletedConversation, runId = "deleted-1-$tag")
        addRun(conversationId = deletedConversation, runId = "deleted-2-$tag")
        addRun(conversationId = "conv-b-$tag", runId = keptRunId)

        assertEquals(6, ConversationRunPurge.purge(context, deletedConversation))

        assertEquals(listOf(keptRunId), AgentRunArchiveStore.list(context).map { it.result.runId })
        assertEquals(listOf(keptRunId), AgentRunCheckpointStore.list(context).map { it.runId })
        assertEquals(listOf(keptRunId), AgentRuntimeResultStore.list(context).map { it.result.runId })
    }

    @Test
    fun purgeMatchesLegacyPlainPayloadAndIgnoresUnknownConversation() {
        val tag = System.nanoTime()
        val conversation = "conv-a-$tag"
        val keptRunId = "kept-$tag"
        addRun(conversationId = conversation, runId = "legacy-$tag", encodePayload = false)
        addRun(conversationId = "conv-b-$tag", runId = keptRunId, encodePayload = false)

        assertEquals(0, ConversationRunPurge.purge(context, "conv-missing-$tag"))
        assertEquals(3, ConversationRunPurge.purge(context, conversation))

        assertEquals(listOf(keptRunId), AgentRuntimeResultStore.list(context).map { it.result.runId })
        assertEquals(0, ConversationRunPurge.purge(context, "   "))
    }

    /**
     * runId 必须逐次唯一：[AgentRuntimeResultStore] 会把已确认的 runId 记在进程级缓存里，
     * 同一 runId 再次写入会被拒绝，复用会让测试之间互相干扰。
     */
    private fun addRun(conversationId: String, runId: String, encodePayload: Boolean = true) {
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = if (encodePayload) {
                AgentUiHandoffPayload(conversationId = conversationId).toJson()
            } else {
                conversationId
            },
        )
        val result = AgentRuntimeWire.RunResult(runId = runId, ok = true, content = "ok")
        val request = AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "prompt",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "",
                model = "model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = handoff,
        )
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(handoff, emptyList(), result, 1L))
        AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(handoff, result, 1L))
    }
}
