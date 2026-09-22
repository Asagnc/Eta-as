package io.github.asagnc.eta.ui.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** 方案文档的状态：pending 是「已提交、等用户确认」，approved 是「用户已批准、进入执行期」。 */
@Immutable
internal enum class AgentPlanStatus { PENDING, APPROVED }

/**
 * 方案里的一步。
 *
 * 它存在的理由只有一个：批准时由它**直接**初始化任务清单。让模型把方案步骤再转写一遍清单
 * 是两处状态的双写，迟早对不上；这里一次映射，之后清单自己走。
 */
@Immutable
internal data class AgentPlanStepUi(val id: String, val content: String)

/** 备选方案：只记思路与取舍，完整正文只对推荐方案写，控制审阅与注入成本。 */
@Immutable
internal data class AgentPlanAlternativeUi(
    val title: String,
    val summary: String,
    val tradeoff: String,
)

/**
 * 方案文档快照，由 submit_plan 工具事件投影而来，随会话存档。
 *
 * 与任务清单的分工是刻意的：方案回答「做什么、为什么」，一次产出、确认后固定；清单回答
 * 「做到哪」，每轮都在变。所以这里**不存任何进度状态**——那属于清单。
 */
@Immutable
internal data class AgentPlanUi(
    val title: String,
    val digest: String,
    val content: String,
    val steps: List<AgentPlanStepUi> = emptyList(),
    val alternatives: List<AgentPlanAlternativeUi> = emptyList(),
    val status: AgentPlanStatus = AgentPlanStatus.PENDING,
)

internal object AgentPlanCodec {

    /** 解析存档里的方案快照。标题为空即视为没有方案（写入前已被工具拒绝，此处不补默认值）。 */
    fun decode(planJson: String): AgentPlanUi? {
        if (planJson.isBlank()) return null
        val json = runCatching { JSONObject(planJson) }.getOrNull() ?: return null
        val title = json.optString("title").trim()
        if (title.isEmpty()) return null
        return AgentPlanUi(
            title = title,
            digest = json.optString("digest").trim(),
            content = json.optString("content"),
            steps = json.optJSONArray("steps").toSteps(),
            alternatives = json.optJSONArray("alternatives").toAlternatives(),
            status = when (json.optString("status").trim()) {
                "approved" -> AgentPlanStatus.APPROVED
                else -> AgentPlanStatus.PENDING
            },
        )
    }

    /** 写回存档：没有方案时返回空串，免得在库里躺一个没意义的 "null"。 */
    fun encode(plan: AgentPlanUi?): String {
        if (plan == null) return ""
        val json = JSONObject()
            .put("title", plan.title)
            .put("content", plan.content)
            .put("status", plan.status.wireValue)
        if (plan.digest.isNotBlank()) json.put("digest", plan.digest)
        if (plan.steps.isNotEmpty()) {
            json.put(
                "steps",
                JSONArray().also { array ->
                    plan.steps.forEach { step ->
                        array.put(JSONObject().put("id", step.id).put("content", step.content))
                    }
                },
            )
        }
        if (plan.alternatives.isNotEmpty()) {
            json.put(
                "alternatives",
                JSONArray().also { array ->
                    plan.alternatives.forEach { alternative ->
                        array.put(
                            JSONObject()
                                .put("title", alternative.title)
                                .put("summary", alternative.summary)
                                .put("tradeoff", alternative.tradeoff),
                        )
                    }
                },
            )
        }
        return json.toString()
    }

    private fun JSONArray?.toSteps(): List<AgentPlanStepUi> = this?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val content = item.optString("content").trim()
            if (id.isEmpty() || content.isEmpty()) return@mapNotNull null
            AgentPlanStepUi(id = id, content = content)
        }
    }.orEmpty()

    private fun JSONArray?.toAlternatives(): List<AgentPlanAlternativeUi> = this?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            AgentPlanAlternativeUi(
                title = title,
                summary = item.optString("summary").trim(),
                tradeoff = item.optString("tradeoff").trim(),
            )
        }
    }.orEmpty()

    private val AgentPlanStatus.wireValue: String
        get() = when (this) {
            AgentPlanStatus.PENDING -> "pending"
            AgentPlanStatus.APPROVED -> "approved"
        }
}
