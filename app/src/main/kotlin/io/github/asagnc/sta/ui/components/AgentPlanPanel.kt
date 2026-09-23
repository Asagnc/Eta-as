package io.github.asagnc.sta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.asagnc.sta.ui.model.AgentPlanAlternativeUi
import io.github.asagnc.sta.ui.model.AgentPlanStatus
import io.github.asagnc.sta.ui.model.AgentPlanUi
import io.github.asagnc.sta.ui.theme.StaSpacing
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 会话顶部的方案卡片，只读展示 submit_plan 提交的方案，给出「按此执行」和「重新规划」两个出口。
 *
 * 不提供编辑：方案的消费者是模型（markdown 原文），人要改就在对话里说一句让它重出——在手机
 * 键盘上重写 markdown 既不顺手，也绕过了模型对方案本身的理解。
 *
 * 正文走 [StableMarkdown]，与会话消息同一套 GFM 渲染，表格与任务列表都能正常显示。
 * 它排在任务清单面板之上：方向先于进度。
 */
@Composable
internal fun AgentPlanPanel(
    plan: AgentPlanUi?,
    modifier: Modifier = Modifier,
    onApprove: (() -> Unit)? = null,
    onReplan: ((String) -> Unit)? = null,
) {
    if (plan == null) return
    val pending = plan.status == AgentPlanStatus.PENDING
    // 正文区限高：面板挂在会话顶部且自身不滚动，不限高时一份长方案会把卡片撑到屏幕之外，
    // 排在正文之后的按钮跟着落到看不见也点不到的位置。
    val bodyMaxHeight = (LocalConfiguration.current.screenHeightDp * PLAN_BODY_MAX_SCREEN_RATIO).dp
    // 尚未采纳的方案默认展开：用户还没看过。采纳之后默认收起，把屏幕让回对话。
    var expanded by rememberSaveable(plan.title, plan.status) { mutableStateOf(pending) }
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = StaSpacing.md, vertical = StaSpacing.xs),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = StaSpacing.compact),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "方案 · ${plan.title}",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(StaSpacing.xs))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                StaticMarkdown(content = plan.content, modifier = Modifier.fillMaxWidth())
                if (plan.alternatives.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(StaSpacing.sm))
                    Text(
                        text = "备选方案",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(StaSpacing.xs)) {
                        plan.alternatives.forEach { alternative ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = onReplan != null) {
                                        onReplan?.invoke(replanPrompt(alternative))
                                    },
                            ) {
                                Text(
                                    text = alternative.title,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                val detail = listOfNotNull(
                                    alternative.summary.takeIf { it.isNotBlank() },
                                    alternative.tradeoff.takeIf { it.isNotBlank() }?.let { "取舍：$it" },
                                ).joinToString(" · ")
                                if (detail.isNotBlank()) {
                                    Text(
                                        text = detail,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (pending && onApprove != null) {
                Spacer(modifier = Modifier.height(StaSpacing.compact))
                Text(
                    text = "按此执行",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { onApprove() },
                )
            }
            if (onReplan != null) {
                Spacer(modifier = Modifier.height(StaSpacing.sm))
                Text(
                    text = "重新规划",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().clickable { onReplan(REPLAN_PROMPT) },
                )
            }
        }
    }
}

/** 点备选方案时发出去的话：把那条备选的思路一起带上，模型不用再猜用户想要哪一版。 */
private fun replanPrompt(alternative: AgentPlanAlternativeUi): String = buildString {
    append("按备选方案「${alternative.title}」重新规划：")
    alternative.summary.takeIf { it.isNotBlank() }?.let { append(it) }
    alternative.tradeoff.takeIf { it.isNotBlank() }?.let { append("（取舍：$it）") }
    append("重新提交一份方案，steps 按新思路重排。")
}

private const val REPLAN_PROMPT = "当前方案我不采纳，请重新规划一版，重新提交方案。"

/** 方案正文区的最大高度占比：它与任务清单面板自上而下共存，写死 dp 在矮屏上仍会把按钮顶出屏幕。 */
private const val PLAN_BODY_MAX_SCREEN_RATIO = 0.35f
