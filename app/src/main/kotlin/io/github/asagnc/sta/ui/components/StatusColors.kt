package io.github.asagnc.sta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.asagnc.sta.R
import io.github.asagnc.sta.ui.model.PermissionStatusUi
import io.github.asagnc.sta.ui.model.RunStatusUi
import io.github.asagnc.sta.ui.theme.StaColors

// 语义状态色统一以 StaColors 为准，不再在这里重复定义取值。
val StatusSuccess: Color = StaColors.success
val StatusWarning: Color = StaColors.warning
val StatusError: Color @Composable get() = StaColors.danger
val StatusRunning: Color @Composable get() = StaColors.accent
val StatusIdle: Color @Composable get() = StaColors.textSecondary

// ── RunStatusUi 映射 ──────────────────────────────────────────────────

@Composable
fun RunStatusUi.color(): Color = when (this) {
    RunStatusUi.Running -> StatusRunning
    RunStatusUi.Success -> StatusSuccess
    RunStatusUi.Failed -> StatusError
    RunStatusUi.Cancelled -> StatusIdle
}

@Composable
fun RunStatusUi.label(): String = stringResource(when (this) {
    RunStatusUi.Running -> R.string.tool_status_running
    RunStatusUi.Success -> R.string.tool_status_success
    RunStatusUi.Failed -> R.string.tool_status_failed
    RunStatusUi.Cancelled -> R.string.status_cancelled
})

// ── PermissionStatusUi 映射 ───────────────────────────────────────────

@Composable
fun PermissionStatusUi.color(): Color = when (this) {
    PermissionStatusUi.Available -> StatusIdle
    PermissionStatusUi.Warning -> StatusWarning
    PermissionStatusUi.Missing -> StatusError
    PermissionStatusUi.Disabled -> StatusIdle
}

@Composable
fun PermissionStatusUi.label(): String = stringResource(when (this) {
    PermissionStatusUi.Available -> R.string.status_ready
    PermissionStatusUi.Warning -> R.string.status_needs_attention
    PermissionStatusUi.Missing -> R.string.status_unauthorized
    PermissionStatusUi.Disabled -> R.string.status_disabled
})
