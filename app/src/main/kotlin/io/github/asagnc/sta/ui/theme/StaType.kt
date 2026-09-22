package io.github.asagnc.sta.ui.theme

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Sta 字阶。
 *
 * 改造前的实测问题：全项目用了 18 个不同的 `.sp` 取值（10~29），且 markdown 标题是
 * 在 `ChatMessageItem` 里按 tone 分支写死的两组魔法值：
 * 助手正文 21/19/18/17/16/15，思考等次要内容 17/16/15/14/14/14。
 * 字号没有阶梯，只有一堆手调数字。
 *
 * 这里先**原样保留现有生效字号**（避免在没看到渲染结果前改视觉），但把它集中成
 * 可整体替换的数据，后续要收紧阶梯只需改一处。
 */
object StaType {

    // ── 通用 UI 字阶 ────────────────────────────────────────────────

    /** 12：说明文字、时间戳、徽标。 */
    val caption = 12.sp

    /** 13：行内代码、等宽内容。 */
    val code = 13.sp

    /** 14：正文默认，与 Miuix body2 对齐。 */
    val body = 14.sp

    /** 15：正文放大档。 */
    val bodyLarge = 15.sp

    /** 16：列表项标题。 */
    val title = 16.sp

    /** 20：页面标题。 */
    val headline = 20.sp

    /** 24：空状态标题。 */
    val display = 24.sp

    // ── 行高倍数 ────────────────────────────────────────────────────

    /** 正文行高倍数。 */
    const val bodyLineHeight = 1.5f

    /** 标题行高倍数。 */
    const val headingLineHeight = 1.3f

    /** 代码块行高倍数。 */
    const val codeLineHeight = 1.45f
}

/**
 * 一组完整的正文+标题字号阶梯。
 *
 * 做成数据类而不是散落的常量，是为了让「助手正文」与「思考等次要内容」两套密度
 * 各自成组、可整体替换，而不是在渲染代码里到处写 `if (tone == ...) 21.sp else 17.sp`。
 */
data class StaTypeScale(
    val h1: TextUnit,
    val h2: TextUnit,
    val h3: TextUnit,
    val h4: TextUnit,
    val h5: TextUnit,
    val h6: TextUnit,
    val body: TextUnit,
    val code: TextUnit,
    val caption: TextUnit,
) {
    /** 按 markdown 标题级别取字号，越界时收敛到最接近的一端。 */
    fun heading(level: Int): TextUnit = when (level) {
        1 -> h1
        2 -> h2
        3 -> h3
        4 -> h4
        5 -> h5
        else -> h6
    }
}

/** 助手正文：字号偏大，强调可读性。数值沿用改造前实际生效值。 */
val StaAnswerTypeScale = StaTypeScale(
    h1 = 21.sp,
    h2 = 19.sp,
    h3 = 18.sp,
    h4 = 17.sp,
    h5 = 16.sp,
    h6 = 15.sp,
    body = 15.sp,
    code = 13.sp,
    caption = 12.sp,
)

/** 思考等次要内容：整体小一档，密度更高。数值沿用改造前实际生效值。 */
val StaCompactTypeScale = StaTypeScale(
    h1 = 17.sp,
    h2 = 16.sp,
    h3 = 15.sp,
    h4 = 14.sp,
    h5 = 14.sp,
    h6 = 14.sp,
    body = 14.sp,
    code = 13.sp,
    caption = 12.sp,
)
