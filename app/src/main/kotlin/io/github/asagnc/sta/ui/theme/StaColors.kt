package io.github.asagnc.sta.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Sta 语义色。
 *
 * 分工原则：
 * - **固定色**（success/warning/info）：不跟主题走。否则用户换到 Rainbow 或 Monochrome
 *   调色板时，「成功」可能变成紫色或灰色，语义就丢了。
 * - **派生色**（其余全部）：从主题取，跟随动态取色／调色板／深色模式。
 *
 * 为什么要有这一层：业务代码里 369 处直接写 `MiuixTheme.colorScheme.xxx`，而 Miuix 的
 * 字段名是它的实现细节。最典型的两个——
 *
 * - `onSurfaceVariantSummary`（次要文字：说明、时间戳）
 * - `onSurfaceVariantActions`（操作文字：可点的次级动作）
 *
 * 这两者是**不同的视觉层级**，但差别只体现在字段名里；Material 3 只有 `onSurfaceVariant`
 * 一个角色，一旦按 M3 机械映射，这两个层级就会塌成同一个颜色。所以这里把语义固定在
 * Sta 自己的命名上，底层换不换、Miuix 有没有这 53 个角色，业务代码都不用动。
 */
object StaColors {

    // ── 固定语义色 ──────────────────────────────────────────────────

    /** 成功。 */
    val success = Color(0xFF00BD13)

    /** 警告。 */
    val warning = Color(0xFFFFB200)

    /** 信息／链接。 */
    val info = Color(0xFF3482FF)

    // ── 主题派生色 ──────────────────────────────────────────────────

    /** 失败／错误，跟随主题的 error 角色。 */
    val danger: Color @Composable get() = MiuixTheme.colorScheme.error

    /** 错误色容器上的文字，与 [danger] 配对使用。 */
    val onDanger: Color @Composable get() = MiuixTheme.colorScheme.onError

    /** 主文字。 */
    val textPrimary: Color @Composable get() = MiuixTheme.colorScheme.onSurface

    /** 次要文字（说明、时间戳、状态描述）。用 100 处的角色。 */
    val textSecondary: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary

    /** 操作文字（可点的次级动作）。用 29 处的角色。 */
    val textAction: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantActions

    /** 禁用态文字。 */
    val textDisabled: Color @Composable get() = MiuixTheme.colorScheme.disabledOnSurface

    /** 页面底色。 */
    val background: Color @Composable get() = MiuixTheme.colorScheme.background

    /** 卡片／面板底色。 */
    val surface: Color @Composable get() = MiuixTheme.colorScheme.surface

    /** 抬升一层的容器（分组卡片、次级面板）。 */
    val surfaceRaised: Color @Composable get() = MiuixTheme.colorScheme.surfaceContainer

    /** 再抬升一层（浮层、选中项）。 */
    val surfaceRaisedHigh: Color @Composable get() = MiuixTheme.colorScheme.surfaceContainerHigh

    /** 抬升容器上的文字。 */
    val onSurfaceRaised: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceContainer

    /** 描边／分隔线。 */
    val outline: Color @Composable get() = MiuixTheme.colorScheme.outline

    /** 强调色，用于运行中状态与主操作。 */
    val accent: Color @Composable get() = MiuixTheme.colorScheme.primary

    /** 强调色容器。 */
    val accentContainer: Color @Composable get() = MiuixTheme.colorScheme.primaryContainer

    /** 中性强调容器，用于次级选中态。 */
    val neutralContainer: Color @Composable get() = MiuixTheme.colorScheme.secondaryContainer
}