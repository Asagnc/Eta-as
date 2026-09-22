package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 本次 run 的度量自查工具 schema。 */
internal object AgentRunStatsToolCatalog {
    const val NAME = "run_stats"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = NAME,
                description = "读取本次 run 的度量汇总：轮次数、按工具分类的调用次数与失败数、耗时、" +
                    "并发批次数与 token 用量。用于自查这次任务是否低效（例如同一工具反复调用）。没有参数。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()),
            ),
        )
    }
}
