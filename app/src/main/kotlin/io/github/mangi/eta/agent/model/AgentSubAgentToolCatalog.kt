package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 子智能体工具（`delegate`）的 schema。
 *
 * 单一入口，多角色是它的一个参数——对齐两家主流实现：
 * - Claude Code 的 subagents 只做"派一个角色干活"，多角色协作另设 agent teams（实验性、明确标注 token 更贵）；
 * - Codex 的 subagent workflows 连独立工具都没有，"一个点派一个 agent"靠一次 spawn 多个 agent 表达。
 *
 * 共同点是：编排集中在主 loop、子代理跑在独立上下文、只回摘要（避免 context pollution / context rot）、
 * 角色必须窄而明确（Codex: narrow and opinionated），否则多角色会退化成同一份意见的不同措辞。
 */
internal object AgentSubAgentToolCatalog {
    const val DELEGATE = "delegate"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = DELEGATE,
                description = "把可以独立完成的工作交给受限子智能体：它有自己的上下文，只能用只读检索工具，" +
                    "只回一份摘要，过程与工具输出都不进入当前上下文。它的价值是隔离上下文与并行取证，不是加速。" +
                    "roles 只给一个时是单角色子任务，任务要窄到能直接回答（例如“某个常量定义在哪个文件哪一行”）；" +
                    "给多个角色时它们各自独立作答、由你汇总对照，适合需要不同立场交叉验证的判断类问题" +
                    "（例如 攻击/防御/合规、正确性/性能/可维护性）——此时必须在 task 里写清统一输出格式，" +
                    "否则各角色无法逐条对照。内置角色 检索、审查、验证 各自带专门的取证要求，优先用它们。" +
                    "写文件、跑命令、操作设备这类改动型工作不要派发：子智能体没有写权限，并行改动只会互相冲突。" +
                    "任务写宽了，子智能体会把预算耗在探索上，最后拿不出结论。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "task",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 2_000)
                                    .put(
                                        "description",
                                        "子任务与验收标准；多角色时还要写清每个角色共用的输出格式。规格越具体，产出越可靠。",
                                    )
                            )
                            .put(
                                "roles",
                                JSONObject()
                                    .put("type", "array")
                                    .put("items", JSONObject().put("type", "string").put("maxLength", 60))
                                    .put("minItems", 1)
                                    .put("maxItems", 3)
                                    .put("uniqueItems", true)
                                    .put(
                                        "description",
                                        "角色列表，最多 3 个（与并行上限一致）。省略或只给一个 = 单角色子任务，" +
                                            "默认角色 检索；给 2-3 个时并行派发、各自独立取证。" +
                                            "内置角色：检索（定位取证）、审查（找问题与风险）、验证（独立复核找反例）；" +
                                            "也可以用自定义角色名，但自定义角色没有额外的取证要求。",
                                    )
                            )
                            .put(
                                "context",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 4_000)
                                    .put("description", "可选背景（已知路径、约束），所有角色共用。")
                            )
                            .put(
                                "scope",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray().put("quick").put("compare").put("deep"),
                                    )
                                    .put(
                                        "description",
                                        "规模档位，决定每个角色的轮数与 token 预算：quick 单点查找；" +
                                            "compare 多方向对比（默认）；deep 大范围检索。" +
                                            "能给一个文件解决的事就别派子智能体，也别给简单任务选 deep。",
                                    )
                            )
                    )
                    .put("required", JSONArray().put("task"))
            )
        )
    }
}
