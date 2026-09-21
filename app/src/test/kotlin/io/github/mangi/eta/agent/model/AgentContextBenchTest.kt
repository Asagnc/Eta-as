package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * 上下文组装热路径的基准测量。
 *
 * 不是断言测试，是量尺：`prune` 与 `rawEstimate` 每轮请求都跑，且随对话长度线性增长，
 * 优化前后必须用同一把尺子量。用 `-Deta.bench=true` 才输出数字，平时静默，
 * 不拖慢常规单测。
 */
class AgentContextBenchTest {

    private val benchEnabled =
        System.getProperty("eta.bench") == "true" || System.getenv("ETA_BENCH") == "true"

    /** 构造一段接近真实的对话：n 轮，每轮一个助手工具调用 + 一条工具结果。 */
    private fun conversation(rounds: Int, resultChars: Int): JSONArray {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", "系统提示".repeat(200)),
        )
        messages.put(JSONObject().put("role", "user").put("content", "开始任务"))
        for (round in 1..rounds) {
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONArray().put(
                        JSONObject().put("type", "text").put("text", "第 $round 轮推理".repeat(30)),
                    ))
                    .put("tool_calls", JSONArray().put(
                        JSONObject()
                            .put("id", "call-$round")
                            .put("type", "function")
                            .put("function", JSONObject()
                                .put("name", "run_command")
                                .put("arguments", """{"command":"echo round-$round"}""")),
                    )),
            )
            messages.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call-$round")
                    .put("content", "结果".repeat(resultChars)),
            )
        }
        return messages
    }

    private fun measure(label: String, iterations: Int, block: () -> Unit): Long {
        // 预热：JIT 未编译时的数字没有参考价值。
        repeat(3) { block() }
        val start = System.nanoTime()
        repeat(iterations) { block() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        if (benchEnabled) {
            println("[bench] $label: ${elapsedMs}ms / $iterations 次 = ${elapsedMs.toDouble() / iterations}ms 每次")
        }
        return elapsedMs
    }

    @Test
    fun measureRequestViewAssembly() {
        // 三轮不同规模，覆盖"短对话无所谓、长对话才痛"的区间。
        listOf(20 to 200, 60 to 1000, 120 to 4000).forEach { (rounds, resultChars) ->
            val messages = conversation(rounds, resultChars)
            val tools = JSONArray().put(
                JSONObject().put("type", "function").put("function", JSONObject().put("name", "run_command")),
            )
            val label = "${rounds} 轮 / 每条结果 ${resultChars} 字"

            measure("deepCopy(旧实现) $label", 50) {
                // 对照项：旧实现就是靠整体序列化+解析做深拷贝的。
                JSONArray(messages.toString())
            }
            measure("prune(新实现) $label", 50) {
                AgentContextPruner.prune(messages, 6)
            }
            measure("rawEstimate $label", 50) {
                AgentContextBudget.rawEstimate(messages, tools)
            }
        }
    }
}
