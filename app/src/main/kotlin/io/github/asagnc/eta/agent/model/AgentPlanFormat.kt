package io.github.asagnc.eta.agent.model

import org.json.JSONObject

/**
 * 方案文档的纯文本渲染，用于把「当前方案」随请求注入（见 [AgentRequestContext]）。
 *
 * 注入的是 digest 而不是正文：正文是写给人看的，可能带表格与长段落，每轮注入全文会让请求
 * 持续膨胀；方向性的内容（目标、约束、关键决策）由 digest 承担，细节留在会话里的方案卡片。
 *
 * 与任务清单的分工写在两处：方案行不含任何进度状态，清单行不含任何方案内容；前缀也不同
 * （`[plan]` / `[task_plan]`），重复注入时各按各的前缀逐行剥掉。
 */
internal object AgentPlanFormat {

    /** 表头行前缀：重复注入时按它整行剥掉。 */
    const val HEADER_PREFIX = "当前方案"

    /** 条目行前缀：唯一到不可能出现在正常内容里。 */
    const val ITEM_PREFIX = "[plan] "

    /** digest 的注入上限：方案写长了也不该把请求撑爆，超出按字符截断。 */
    const val MAX_DIGEST_CHARS = 800

    /** 步骤概览的注入上限。 */
    const val MAX_STEPS = 12

    private const val APPROVED = "approved"

    data class Plan(
        val title: String,
        val digest: String,
        val steps: List<String>,
        val status: String,
    )

    /** 解析方案快照；格式不对或没有标题时返回 null。 */
    fun parse(planJson: String?): Plan? {
        val raw = planJson?.takeIf { it.isNotBlank() } ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val title = json.optString("title").trim()
        if (title.isEmpty()) return null
        return Plan(
            title = title,
            digest = json.optString("digest").trim(),
            steps = json.optJSONArray("steps")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)
                        ?.optString("content")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            }.orEmpty(),
            status = json.optString("status").trim().ifBlank { "pending" },
        )
    }

    /**
     * 注入用的行。
     *
     * 待确认的方案永远注入——模型得知道自己正停在等确认这一步，否则会当成用户没回话而继续
     * 动手。已确认的方案只在清单还没做完时注入：做完之后它不再是方向，继续占上下文只是噪音。
     */
    fun injectedLines(planJson: String?, taskPlanJson: String?): List<String>? {
        val plan = parse(planJson) ?: return null
        if (plan.status == APPROVED && isTaskPlanSettled(taskPlanJson)) return null
        return buildList {
            add("$HEADER_PREFIX（${statusLabel(plan.status)}）：")
            add("${ITEM_PREFIX}标题：${plan.title}")
            plan.digest.take(MAX_DIGEST_CHARS).lines()
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .forEach { add("$ITEM_PREFIX$it") }
            if (plan.steps.isNotEmpty()) {
                val shown = plan.steps.take(MAX_STEPS)
                add(
                    "${ITEM_PREFIX}步骤：" +
                        shown.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("；"),
                )
                if (plan.steps.size > shown.size) {
                    add("$ITEM_PREFIX…其余 ${plan.steps.size - shown.size} 步略")
                }
            }
            add("${ITEM_PREFIX}提示：" + hint(plan.status))
        }
    }

    /** 清单存在且全部完成，才算做完；清单为空说明执行还没开始，方案仍是方向。 */
    private fun isTaskPlanSettled(taskPlanJson: String?): Boolean {
        val items = AgentTaskPlanFormat.parse(taskPlanJson)
        return items.isNotEmpty() && items.all { it.status == "completed" }
    }

    private fun statusLabel(status: String): String =
        if (status == APPROVED) "已确认，执行中" else "待确认"

    private fun hint(status: String): String = if (status == APPROVED) {
        "方案已确认，按步骤执行，进度写进任务清单；清单是进度的唯一来源，方案不再改。"
    } else {
        "方案尚未确认，正在等用户回复。这一步不要修改任何文件。"
    }
}
