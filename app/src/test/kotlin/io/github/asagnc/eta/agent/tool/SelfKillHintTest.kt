package io.github.asagnc.eta.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfKillHintTest {
    private val killed = JSONObject()
        .put("ok", false)
        .put("exit_code", 143)
        .put("stderr", "Terminated")
        .toString()

    @Test
    fun `pkill command killed by a signal gets the hint`() {
        val hint = SelfKillHint.hintFor(
            "cd /workspace/Eta-src && ./gradlew --stop; pkill -f \"gradle-daemon-main\"",
            killed,
        )

        assertTrue(hint, hint!!.contains("exit=143"))
        assertTrue(hint, hint.contains("pgrep -f"))
    }

    @Test
    fun `sigkill counts as well`() {
        val sigkill = JSONObject().put("ok", false).put("exit_code", 137).toString()

        assertTrue(SelfKillHint.hintFor("killall eta", sigkill).orEmpty().contains("exit=137"))
    }

    @Test
    fun `hint is appended into the result json`() {
        val result = SelfKillHint.append("pkill -f foo", killed)

        assertTrue(result, JSONObject(result).getString("hint").contains("pkill/killall"))
    }

    @Test
    fun `other exit codes are left alone`() {
        val failed = JSONObject().put("ok", false).put("exit_code", 1).toString()

        assertNull(SelfKillHint.hintFor("pkill -f foo", failed))
    }

    @Test
    fun `commands without a kill tool are left alone`() {
        assertNull(SelfKillHint.hintFor("./gradlew --stop", killed))
        assertNull(SelfKillHint.hintFor("", killed))
    }

    @Test
    fun `an existing hint is not overwritten`() {
        val withHint = JSONObject()
            .put("ok", false)
            .put("exit_code", 143)
            .put("hint", "已有提示")
            .toString()

        assertNull(SelfKillHint.hintFor("killall foo", withHint))
        assertEquals(withHint, SelfKillHint.append("killall foo", withHint))
    }

    @Test
    fun `results that are not json come back untouched`() {
        assertEquals("not json", SelfKillHint.append("pkill -f foo", "not json"))
    }
}
