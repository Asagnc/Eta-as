package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.model.AgentTaskPlanItemUi
import io.github.mangi.eta.ui.model.AgentTaskPlanStatus
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 会话顶部的任务进度面板，点击整块展开或折叠。
 *
 * 只展示 task_plan 工具提交的清单，不提供手动勾选：清单由模型按完整快照覆盖更新，
 * 界面侧勾上的项会在下一次快照里被改回去，反而让人误以为进度已变。
 *
 * [runActive] 为 false 且清单仍停在某一步时，标题点出断点位置并给出「继续」入口。
 * 状态本身不会被界面改写：界面只负责把「停在哪」说清楚，清单始终是模型提交的那一份。
 */
@Composable
internal fun AgentTaskPlanPanel(
    items: List<AgentTaskPlanItemUi>,
    modifier: Modifier = Modifier,
    runActive: Boolean = false,
    onResume: ((String) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    var autoCollapsed by rememberSaveable { mutableStateOf(false) }
    // 清单区限高：面板挂在会话顶部且自身不滚动，条目多时同样会把「继续」入口顶出屏幕。
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * TASK_LIST_MAX_SCREEN_RATIO).dp
    val completed = items.count { it.status == AgentTaskPlanStatus.COMPLETED }
    // 停在某一步的那一项：运行结束后它就是「继续」的落点。
    val activeIndex = items.indexOfFirst { it.status == AgentTaskPlanStatus.IN_PROGRESS }
    val active = items.getOrNull(activeIndex)
    // 整份清单跑完时自动收起一次，把屏幕让回对话；用户自己展开过就不再强制收起。
    if (!autoCollapsed && items.isNotEmpty() && completed == items.size) {
        autoCollapsed = true
        expanded = false
    }
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
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
                text = if (active != null && !runActive) {
                    "任务进度 $completed/${items.size} · 停在第 ${activeIndex + 1} 步"
                } else {
                    "任务进度 $completed/${items.size}"
                },
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEach { item -> AgentTaskPlanRow(item) }
            }
            // 只有运行已经结束、且确实停在某一步时才给「继续」入口；跑的过程中点它没有意义。
            val resumeTarget = active?.takeIf { !runActive }
            if (onResume != null && resumeTarget != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "从「${resumeTarget.content}」继续",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val doneItems = items.filter { it.status == AgentTaskPlanStatus.COMPLETED }
                            onResume(
                                buildString {
                                    append("继续执行计划里的「${resumeTarget.content}」这一步：")
                                    append("先把这一项标回进行中再往下做。")
                                    if (doneItems.isNotEmpty()) {
                                        append("已经完成、不要再做一遍的是：")
                                        append(doneItems.joinToString("；") { it.content })
                                        append("。")
                                    }
                                },
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun AgentTaskPlanRow(item: AgentTaskPlanItemUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            AgentTaskPlanStatus.COMPLETED -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            AgentTaskPlanStatus.IN_PROGRESS -> Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.primary,
            )

            // 未开始的一项不配图标，留出同宽空位保持各行文字对齐。
            AgentTaskPlanStatus.PENDING -> Spacer(modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.content,
                style = MiuixTheme.textStyles.footnote1,
                color = if (item.status == AgentTaskPlanStatus.IN_PROGRESS) {
                    MiuixTheme.colorScheme.onSurfaceContainer
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildList {
                if (item.toolCalls > 0) add("${item.toolCalls} 次工具调用")
                if (item.elapsedMillis > 0) {
                    val seconds = item.elapsedMillis / 1000
                    add(if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分 ${seconds % 60} 秒")
                }
                item.failure?.let { add("失败：$it") }
            }.joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 任务清单区的最大高度占比：与方案卡片自上而下共存，两张卡一起占满整屏就再也点不到入口。 */
private const val TASK_LIST_MAX_SCREEN_RATIO = 0.3f
