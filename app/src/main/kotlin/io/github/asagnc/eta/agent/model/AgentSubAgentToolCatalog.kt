package io.github.asagnc.eta.agent.model

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
    const val MAILBOX_POST = "mailbox_post"

    /** 子智能体可用的信箱工具：投递自己的发现、读同伴的发现。 */
    fun appendMailboxTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = MAILBOX_POST,
                description = "把你的发现投递给同一轮里的其它角色，避免他们重复查同一件事。" +
                    "查到关键事实（某个定义的位置、某个接口的行为、某个坑）就投一条，不要等收尾。" +
                    "同一句话只会存一次；一轮里最多 12 条，别把推理过程写进来，只写结论。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "summary",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 160)
                                    .put("description", "一句话结论，别人扫一眼就知道值不值得看。"),
                            )
                            .put(
                                "body",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 1_200)
                                    .put("description", "支撑结论的关键证据：文件路径与行号、命令输出要点。可省略。"),
                            ),
                    )
                    .put("required", JSONArray().put("summary")),
            ),
        )
    }

    /** `mode` 取值：只读取证（默认）与在隔离 worktree 里改代码。 */
    const val MODE_READ = "read"
    const val MODE_WRITE = "write"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = DELEGATE,
                description = "把可以独立完成的工作交给受限子智能体：它有自己的上下文，只回一份摘要，" +
                    "过程与工具输出都不进入当前上下文。它的价值是隔离上下文与并行取证，不是加速。" +
                    "roles 只给一个时是单角色子任务，任务要窄到能直接回答（例如“某个常量定义在哪个文件哪一行”）；" +
                    "给多个角色时它们各自独立作答、由你汇总对照，适合需要不同立场交叉验证的判断类问题" +
                    "（例如 攻击/防御/合规、正确性/性能/可维护性）——此时必须在 task 里写清统一输出格式，" +
                    "否则各角色无法逐条对照。多角色时子智能体没有联网读（共享浏览器不支持并发），" +
                    "要联网检索就只能派单角色；既需要联网又需要多个立场时，先派单角色把资料取回来，" +
                    "再另派多角色对这些资料做交叉判断。内置角色 检索、审查、验证 各自带专门的取证要求，优先用它们。" +
                    "mode=read（默认）时子智能体只能用只读检索工具，适合查证与判断类工作；" +
                    "mode=write 时它会在独立 worktree 里改文件、跑命令自验证，产出以 diff 形式交回，" +
                    "由你决定是否合并——改动型工作只在这一模式下派发，且一次只派一个角色。" +
                    "操作设备这类不能隔离的工作两种模式都别派发。任务写宽了，子智能体会把预算耗在探索上，最后拿不出结论。",
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
                                            "也可以用自定义角色名，但自定义角色没有额外的取证要求。" +
                                            "mode=write 时只能给一个角色。",
                                    )
                            )
                            .put(
                                "mode",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put(MODE_READ).put(MODE_WRITE))
                                    .put(
                                        "description",
                                        "read（默认）只读检索，子智能体不碰任何文件；" +
                                            "write 在独立 worktree 里改文件并可跑命令自验证，" +
                                            "需要子智能体真正落地改动时用。",
                                    )
                            )
                            .put(
                                "repo_path",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put(
                                        "description",
                                        "mode=write 时的目标仓库根目录（例如 /workspace/Eta-src）。" +
                                            "隔离 worktree 建在它同级目录下；省略时用 /workspace/Eta-src。",
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
