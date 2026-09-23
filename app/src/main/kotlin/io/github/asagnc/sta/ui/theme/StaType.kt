package io.github.asagnc.sta.ui.theme

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Sta 字阶。
 *
 * 改造前的实测问题：全项目用了 19 个不同的 `.sp` 取值（0~29），且 markdown 标题是
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

    /** 13：行内代码、等宽内容、浮窗状态文字。 */
    val code = 13.sp

    /** 14：正文默认，与 Miuix body2 对齐。 */
    val body = 14.sp

    /** 15：正文放大档。 */
    val bodyLarge = 15.sp

    /** 16：列表项标题、浮窗结果的三级标题。 */
    val title = 16.sp

    /** 18：浮窗结果的二级标题。 */
    val titleLarge = 18.sp

    /** 20：页面标题、浮窗结果的一级标题。 */
    val headline = 20.sp

    /** 24：空状态标题。 */
    val display = 24.sp

    // ── 行高：绝对值 ────────────────────────────────────────────────
    // markdown 各级的配对行高见 [StaAnswerTypeScale] / [StaCompactTypeScale]；
    // 这里的档位给不属于任何 markdown 字阶的零散场景（浮窗状态文字、输入框）。

    /** 17：13sp 字号的紧凑行高。 */
    val lineHeightTight = 17.sp

    /** 18：14sp 字号的紧凑行高。 */
    val lineHeightCompact = 18.sp

    /** 22：16sp 字号与输入框的默认行高。 */
    val lineHeightBody = 22.sp

    // ── 行高倍数 ────────────────────────────────────────────────────

    /** 正文行高倍数。 */
    const val bodyLineHeight = 1.5f

    /** 标题行高倍数。 */
    const val headingLineHeight = 1.3f

    /** 代码块行高倍数。 */
    const val codeLineHeight = 1.45f
}

/**
 * 一级排版的字号与行高。
 *
 * 做成一对而不是两个平行字段，是因为 markdown 的每一级都是「字号 + 行高」成对生效的：
 * 改造前 [StaTypeScale] 只承载字号，行高散落在 `chatMarkdownTypography` 的
 * `if (tone == Answer) 29.sp else 25.sp` 里，等于一半的排版信息没有出处。
 */
data class StaTextMetrics(
    val size: TextUnit,
    val lineHeight: TextUnit = TextUnit.Unspecified,
)

/**
 * 一组完整的正文+标题字阶。
 *
 * 做成数据类而不是散落的常量，是为了让「助手正文」「思考等次要内容」「浮窗结果」三套
 * 密度各自成组、可整体替换，而不是在渲染代码里到处写 `if (tone == ...) 21.sp else 17.sp`。
 */
data class StaTypeScale(
    val h1: StaTextMetrics,
    val h2: StaTextMetrics,
    val h3: StaTextMetrics,
    val h4: StaTextMetrics,
    val h5: StaTextMetrics,
    val h6: StaTextMetrics,
    val body: StaTextMetrics,
    val quote: StaTextMetrics,
    val code: StaTextMetrics,
    val inlineCode: StaTextMetrics,
    val table: StaTextMetrics,
    val caption: StaTextMetrics,
) {
    /** 按 markdown 标题级别取字号与行高，越界时收敛到最接近的一端。 */
    fun heading(level: Int): StaTextMetrics = when (level) {
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
    h1 = StaTextMetrics(21.sp, 29.sp),
    h2 = StaTextMetrics(19.sp, 27.sp),
    h3 = StaTextMetrics(18.sp, 26.sp),
    h4 = StaTextMetrics(17.sp, 25.sp),
    h5 = StaTextMetrics(16.sp, 24.sp),
    h6 = StaTextMetrics(15.sp, 23.sp),
    body = StaTextMetrics(16.sp, 26.sp),
    quote = StaTextMetrics(15.sp, 24.sp),
    code = StaTextMetrics(13.sp, 20.sp),
    inlineCode = StaTextMetrics(14.sp, 26.sp),
    table = StaTextMetrics(14.sp, 20.sp),
    caption = StaTextMetrics(12.sp),
)

/** 思考等次要内容：整体小一档，密度更高。数值沿用改造前实际生效值。 */
val StaCompactTypeScale = StaTypeScale(
    h1 = StaTextMetrics(17.sp, 25.sp),
    h2 = StaTextMetrics(16.sp, 24.sp),
    h3 = StaTextMetrics(15.sp, 23.sp),
    h4 = StaTextMetrics(14.sp, 22.sp),
    h5 = StaTextMetrics(14.sp, 22.sp),
    h6 = StaTextMetrics(14.sp, 22.sp),
    body = StaTextMetrics(14.sp, 22.sp),
    quote = StaTextMetrics(14.sp, 22.sp),
    code = StaTextMetrics(13.sp, 20.sp),
    inlineCode = StaTextMetrics(13.sp, 22.sp),
    table = StaTextMetrics(14.sp, 20.sp),
    caption = StaTextMetrics(12.sp),
)

/**
 * markdown 块之间的垂直间距。
 *
 * 用 sp 而不是 dp：块间距要跟着正文字号一起缩放才能保持排版比例，改造前就是 sp，
 * 这里保持原单位与原值。
 */
object StaMarkdownGap {

    /** 0：首个块之前。 */
    val none = 0.sp

    /** 12：标题紧跟标题。 */
    val headingToHeading = 12.sp

    /** 24：一级/二级标题之前。 */
    val beforeMajorHeading = 24.sp

    /** 20：三级及以下标题之前。 */
    val beforeMinorHeading = 20.sp

    /** 10：标题之后。 */
    val afterHeading = 10.sp

    /** 16：段落之间、结构化块周围。 */
    val block = 16.sp

    /** 14：默认块间距。 */
    val fallback = 14.sp
}
