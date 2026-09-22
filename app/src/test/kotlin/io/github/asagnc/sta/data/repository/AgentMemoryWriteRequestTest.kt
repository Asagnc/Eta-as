package io.github.asagnc.sta.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryWriteRequestTest {
    @Test
    fun infersUpsertSectionFromHeading() {
        val parsed = parsed(
            JSONObject()
                .put("heading", "## 设备")
                .put("content", "已 root"),
        )

        assertEquals(AgentMemoryWriteRequest.UPSERT_SECTION, parsed.mode)
        val mutation = parsed.mutation as AgentMemoryMutation.UpsertSection
        assertEquals("## 设备", mutation.heading)
        assertEquals("已 root", mutation.content)
        assertEquals(null, mutation.revision)
    }

    @Test
    fun infersReplaceRangeFromLineNumbersAndAppendFromContentOnly() {
        val ranged = parsed(
            JSONObject().put("start_line", 2).put("end_line", 3).put("content", "新内容"),
        )
        assertEquals(AgentMemoryWriteRequest.REPLACE_RANGE, ranged.mode)
        assertEquals(2, (ranged.mutation as AgentMemoryMutation.ReplaceRange).startLine)

        val appended = parsed(JSONObject().put("content", "## 新主题\n内容"))
        assertEquals(AgentMemoryWriteRequest.APPEND, appended.mode)
    }

    @Test
    fun refusesToInferClearFromAnEmptyCall() {
        val parsed = AgentMemoryWriteRequest.parse(JSONObject())

        assertTrue(parsed is AgentMemoryWriteRequest.Parsed.Invalid)
        assertTrue((parsed as AgentMemoryWriteRequest.Parsed.Invalid).message.contains("clear"))
    }

    @Test
    fun explicitClearStillWorksAndReportsMissingFieldsForOtherModes() {
        val cleared = parsed(JSONObject().put("mode", "clear"))
        assertTrue(cleared.mutation is AgentMemoryMutation.Clear)

        val upsertWithoutContent = AgentMemoryWriteRequest.parse(JSONObject().put("mode", "upsert_section").put("heading", "设备"))
        assertTrue(
            (upsertWithoutContent as AgentMemoryWriteRequest.Parsed.Invalid).message.contains("content"),
        )

        val unknownMode = AgentMemoryWriteRequest.parse(JSONObject().put("mode", "replace"))
        assertTrue((unknownMode as AgentMemoryWriteRequest.Parsed.Invalid).message.contains("upsert_section"))
    }

    @Test
    fun malformedRevisionIsIgnoredInsteadOfFailingTheCall() {
        val parsed = parsed(JSONObject().put("content", "追加内容").put("revision", "too-short"))

        assertTrue(parsed.revisionIgnored)
        assertEquals(null, parsed.mutation.revision)
    }

    @Test
    fun retryRunsOnlyForMutationsThatDoNotDependOnLineNumbers() {
        val conflict = AgentMemoryWriteResult.Conflict(snapshot("stale"))

        val appendSeen = mutableListOf<String?>()
        val append = runMemoryMutation(AgentMemoryMutation.Append("a".repeat(64), "内容")) { mutation ->
            appendSeen += mutation.revision
            if (mutation.revision == "a".repeat(64)) conflict else AgentMemoryWriteResult.Success(snapshot("ok"))
        }
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), appendSeen)
        assertTrue(append.retried)

        val rangeSeen = mutableListOf<String?>()
        val range = runMemoryMutation(AgentMemoryMutation.ReplaceRange("a".repeat(64), 1, 1, "内容")) { mutation ->
            rangeSeen += mutation.revision
            conflict
        }
        assertEquals(1, rangeSeen.size)
        assertFalse(range.retried)
        assertTrue(range.result is AgentMemoryWriteResult.Conflict)
    }

    private fun parsed(json: JSONObject): AgentMemoryWriteRequest.Parsed.Mutation =
        AgentMemoryWriteRequest.parse(json) as AgentMemoryWriteRequest.Parsed.Mutation

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "b".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
