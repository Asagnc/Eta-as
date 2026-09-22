package io.github.asagnc.eta.agent.model

/**
 * 子智能体的内置角色。
 *
 * 对齐 Codex 的自定义 agent 实践：每个 agent 一份 developer_instructions，原则是
 * "narrow and opinionated"——职责明确，并用约束防止它漂移到相邻工作；也参考了
 * Claude Code 内置 Explore/Plan 的做法（角色本身就是一段打磨过的行为约束）。
 *
 * 子智能体只有只读工具，所以内置角色都是取证类，不放执行/修改类角色。
 * 角色名仍允许自定义：未命中预设时只给通用指令，不假装它有专门约束。
 */
internal object AgentSubAgentRoles {
    const val DEFAULT = "检索"

    private val presets: Map<String, String> = linkedMapOf(
        "检索" to
            "只做定位与取证：给出文件路径、行号、符号名或命令输出要点，不要给修复建议，也不要评价设计好坏。" +
            "找不到就明说找不到，并给出你已经排除的范围。",
        "审查" to
            "只找问题：每个疑点都要给出触发条件、影响范围和依据（代码位置或可复现步骤）。" +
            "找不到问题就明说，不要为了凑数硬报。",
        "验证" to
            "独立复核交给你的结论：主动找反例与边界情况，最后明确回答“成立 / 不成立 / 证据不足”，" +
            "并给出支持或推翻它的具体证据。不要因为结论看起来合理就默认它成立。",
    )

    /** 内置角色名，用于工具描述与结果回执。 */
    val names: List<String> = presets.keys.toList()

    /** 命中内置角色时返回它额外的取证要求；自定义角色返回 null（只有通用指令）。 */
    fun instructionFor(role: String): String? = presets[role.trim()]
}
