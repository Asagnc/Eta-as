package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.model.AgentSubAgentItemUi
import io.github.mangi.eta.ui.model.AgentSubAgentPhase
import io.github.mangi.eta.ui.model.AgentSubAgentProjector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 并行子智能体的进度面板，点击整块展开或折叠。
 *
 * 与任务清单面板的区别：这里的条目在运行结束即失去意义（结论已经回到对话里），
 * 所以全部角色跑完后自动收起，且不落库。
 */
@Composable
internal fun AgentSubAgentPanel(
    items: List<AgentSubAgentItemUi>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    var autoCollapsed by rememberSaveable { mutableStateOf(false) }
    val running = AgentSubAgentProjector.hasRunning(items)
    // 还有角色在跑时保持展开（那是用户最需要看的时刻）；全部结束后收起一次，把屏幕让回对话。
    if (!autoCollapsed && !running) {
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
                text = if (running) "子智能体 ${items.size} 个 · 进行中" else "子智能体 ${items.size} 个 · 已结束",
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item -> AgentSubAgentRow(item) }
            }
        }
    }
}

@Composable
private fun AgentSubAgentRow(item: AgentSubAgentItemUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.phase) {
            AgentSubAgentPhase.RUNNING -> Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.primary,
            )

            AgentSubAgentPhase.FINISHED -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            AgentSubAgentPhase.FAILED -> Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.role,
                style = MiuixTheme.textStyles.footnote1,
                color = if (item.phase == AgentSubAgentPhase.RUNNING) {
                    MiuixTheme.colorScheme.onSurfaceContainer
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildList {
                add(
                    when (item.phase) {
                        AgentSubAgentPhase.RUNNING -> "进行中"
                        AgentSubAgentPhase.FINISHED -> "已返回摘要"
                        AgentSubAgentPhase.FAILED -> "未完成"
                    },
                )
                if (item.phase == AgentSubAgentPhase.FINISHED && item.summaryChars > 0) {
                    add("${item.summaryChars} 字")
                }
                if (item.errorCode.isNotBlank()) add(item.errorCode)
            }.joinToString(" · ")
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
