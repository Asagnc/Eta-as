package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 代码执行工具的 schema。
 *
 * 只暴露一个入口，而不是让模型从几十个文件与检索工具里逐个挑：代码沙箱里可以连续做
 * 「读 → 过滤 → 计算 → 只输出结论」，一次调用替代多轮往返，且只有结论进入上下文。
 * 这与 Anthropic、Cloudflare 各自独立得出的结论一致（模型在「写代码调用 API」上比在
 * 「构造结构化工具调用」上更强），Red Hat 的实测把 schema 开销从每轮 15k token 压到恒定约 220。
 */
internal object AgentCodeExecutionToolCatalog {
    const val RUN_CODE = "run_code"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = RUN_CODE,
                description = "在沙箱里执行代码，只把 stdout 带回上下文。适合批量读取、检索、过滤、"
                    + "计算这类需要在沙箱里连续多步完成、又不想把中间结果带进上下文的工作："
                    + "一次调用替代多轮「读文件 → 再读 → 再算」的往返。"
                    + "沙箱是独立 Linux 环境，工作目录 /workspace，可见 /sdcard 与共享目录；"
                    + "看不到 Android 应用私有数据，也不能操作用户界面——界面与设备操作仍用对应工具。"
                    + "只输出结论，不要把大段原文打到 stdout。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "code",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "要执行的代码本体。"),
                            )
                            .put(
                                "language",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("python").put("shell"))
                                    .put("description", "python 或 shell；默认 python。"),
                            )
                            .put(
                                "timeout_seconds",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", 600)
                                    .put("description", "超时秒数，默认 60。"),
                            ),
                    )
                    .put("required", JSONArray().put("code")),
            ),
        )
    }
}
