package io.github.asagnc.sta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.asagnc.sta.ui.theme.StaSpacing
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.asagnc.sta.ui.theme.StaColors

@Composable
internal fun SettingsArrowPreference(
    title: String,
    summary: String? = null,
    startAction: @Composable () -> Unit,
    endActions: @Composable RowScope.() -> Unit = {},
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    onClick: () -> Unit,
) {
    SettingsPreferenceRow(
        title = title,
        summary = summary,
        startAction = startAction,
        enabled = enabled,
        holdDownState = holdDownState,
        interaction = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(modifier = Modifier.weight(1f, fill = false), content = endActions)
        Spacer(Modifier.width(StaSpacing.sm))
        val direction = LocalLayoutDirection.current
        Icon(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(8.dp, 14.dp).graphicsLayer {
                scaleX = if (direction == LayoutDirection.Rtl) -1f else 1f
            },
            tint = if (enabled) StaColors.onBackground.copy(alpha = 0.3f)
                else StaColors.textDisabled,
        )
    }
}

@Composable
internal fun SettingsSwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    startAction: @Composable () -> Unit,
    enabled: Boolean = true,
) {
    SettingsPreferenceRow(
        title = title,
        summary = summary,
        startAction = startAction,
        enabled = enabled,
        interaction = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            // 整行提供无障碍状态，开关保留点击和拖动；缩放绘制不缩小行的触摸区域。
            modifier = Modifier.clearAndSetSemantics {}.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val width = 44.dp.roundToPx()
                val height = 24.dp.roundToPx()
                layout(width, height) {
                    placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
                        scaleX = width.toFloat() / placeable.width
                        scaleY = height.toFloat() / placeable.height
                    }
                }
            },
        )
    }
}

@Composable
internal fun SettingsPreferenceRow(
    title: String,
    startAction: @Composable () -> Unit,
    interaction: Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    endActions: @Composable RowScope.() -> Unit,
) {
    // 通用设置组件的最小高度固定为 56dp；首页单独布局，长文本仍可自然增高。
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
            .background(if (holdDownState) StaColors.onBackground.copy(alpha = 0.06f) else Color.Transparent)
            .then(interaction),
    ) {
        val actionMaxWidth = (maxWidth - SettingsItemLayout.ContentStart - SettingsItemLayout.SidePadding) * 0.45f
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = SettingsItemLayout.RowMinHeight)
                .padding(horizontal = SettingsItemLayout.SidePadding, vertical = StaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            startAction()
            Spacer(Modifier.width(SettingsItemLayout.IconTextGap))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) StaColors.onBackground else StaColors.textDisabled,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MiuixTheme.textStyles.body2,
                        color = if (enabled) StaColors.textSecondary else StaColors.textDisabled,
                    )
                }
            }
            Spacer(Modifier.width(StaSpacing.md))
            Row(
                modifier = Modifier.widthIn(max = actionMaxWidth.coerceAtLeast(44.dp)),
                verticalAlignment = Alignment.CenterVertically,
                content = endActions,
            )
        }
    }
}
