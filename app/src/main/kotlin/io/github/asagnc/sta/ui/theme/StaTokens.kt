package io.github.asagnc.sta.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.unit.dp

/**
 * Sta 设计令牌：尺寸、圆角、描边、动效。
 *
 * 存在的理由（改造前实测）：26k 行 UI 里有 586 处硬编码 `.dp`，取值散布在 2~40 之间的
 * 20 多个数值上（5/6/10/11/13/14/15/17/18 都在用），圆角有 6/8/10/12/20 五套。
 * 逐个 eyeball 出来的数值让界面缺少节奏感，也让「统一调一次间距」变成要动几百处。
 *
 * 命名按尺寸而非角色：尺寸名不可能被误用，角色名会随场景漂移。
 * 需要表达「为什么是这个值」时用下面的语义别名。
 * 基准网格 4dp；6/10 是改造前的高频值，保留为具名档位而不是继续散落。
 */
object StaSpacing {

    /** 0：显式清零，比写 `0.dp` 更能表达「这里不要间距」。 */
    val none = 0.dp

    /** 2：描边内缩、徽标与文字的贴合间隙。 */
    val hair = 2.dp

    /** 4：最小常规间隙。 */
    val xxs = 4.dp

    /** 6：芯片内边距、图标与文字间隙（改造前 33 处）。 */
    val xs = 6.dp

    /** 8：列表项之间、卡片之间。 */
    val sm = 8.dp

    /** 10：紧凑容器内边距（改造前 47 处）。 */
    val compact = 10.dp

    /** 12：卡片内边距，全项目最高频（改造前 143 处）。 */
    val md = 12.dp

    /** 16：页面左右留白。 */
    val lg = 16.dp

    /** 20：标题与内容之间。 */
    val xl = 20.dp

    /** 24：分区之间。 */
    val xxl = 24.dp

    /** 32：大分区之间、空状态上下留白。 */
    val xxxl = 32.dp

    /** 40：页面级留白。 */
    val huge = 40.dp

    // ── 语义别名：写新代码优先用这些，它们说明了「为什么是这个值」 ──

    /** 页面内容左右留白。 */
    val screenPadding = lg

    /** 卡片内边距。 */
    val cardPadding = md

    /** 卡片与卡片之间。 */
    val cardGap = sm

    /** 列表项之间。 */
    val listItemGap = sm

    /** 分区标题与分区内容之间。 */
    val sectionGap = xxl

    /** 图标与文字之间。 */
    val iconTextGap = xs
}

/**
 * 图标与控件尺寸。
 *
 * 为什么不复用 [StaSpacing]：间距是「两个元素之间留多少」，尺寸是「元素本身多大」。
 * 两者即使数值相同语义也不同——`padding(24.dp)` 改成 `StaSpacing.xxl` 是准确的，
 * 但 `size(24.dp)` 改成 `StaSpacing.xxl` 会让人误以为图标尺寸受间距体系管辖。
 *
 * 档位取 4dp 网格。**刻意的视觉改动**：接线前图标用了 13/14/15/17/18/19/21 这些
 * 不在网格上的值（15.dp 13 次、18.dp 10 次、14.dp 9 次、17.dp 5 次、13.dp 4 次），
 * 同一屏里相邻图标差 1~2dp 正是「界面看着不齐」的直接来源。统一到网格后
 * 会有 1~2dp 位移——整齐优先于逐像素守恒。
 */
object StaIconSize {

    /** 6：行内状态点、紧凑徽标。 */
    val xxs = 6.dp

    /** 8：内联小指示器。 */
    val xs = 8.dp

    /** 12：紧凑列表内联图标。 */
    val sm = 12.dp

    /** 16：正文内联图标、发送按钮图标。 */
    val md = 16.dp

    /** 20：工具栏图标、设置项图标、段落图标。 */
    val lg = 20.dp

    /** 24：主操作图标、顶栏菜单。 */
    val xl = 24.dp

    /** 32：强调图标、空状态元素。 */
    val xxl = 32.dp

    /** 40：大号操作区、输入栏动作按钮。 */
    val xxxl = 40.dp

    /** Material 无障碍要求的点击目标最小边长。 */
    val touchTarget = 48.dp
}

/**
 * 圆角。改造前实际用到 6/8/10/12/20 五套，这里收敛为具名档位。
 */
object StaRadius {

    val none = 0.dp

    /** 6：小徽标、细条。 */
    val xs = 6.dp

    /** 8：芯片、小按钮。 */
    val sm = 8.dp

    /** 10：改造前最高频（11 处），保留为默认小圆角。 */
    val md = 10.dp

    /** 12：卡片。 */
    val lg = 12.dp

    /** 16：大卡片、面板。 */
    val xl = 16.dp

    /** 20：对话框、底部弹层。 */
    val xxl = 20.dp

    /** 28：全圆角容器。 */
    val xxxl = 28.dp

    /** 足够大即可呈胶囊形，不需要按高度算。 */
    val full = 999.dp

    val card = lg
    val dialog = xxl
    val chip = full
}

/**
 * 描边宽度。改造前描边直接写 1.dp/2.dp/3.dp，没有语义。
 */
object StaStroke {

    /** 0.5：发丝分隔线。改造前直接写 0.5.dp（19 处），比 1.dp 更细且不随密度跳动。 */
    val hair = 0.5.dp

    /** 1：分隔线、卡片描边。 */
    val hairline = 1.dp

    /** 2：强调描边、选中指示。 */
    val thick = 2.dp

    /** 3：警示条。 */
    val bar = 3.dp
}

/**
 * 动效时长与缓动。
 *
 * 改造前各处直接写 `tween(200)` 之类的裸数字，同一个「卡片展开」在不同页面时长不同。
 * 这里只定义时长与缓动，不定义具体动画，避免把动效策略绑死在令牌层。
 */
object StaMotion {

    /** 90ms：状态切换、颜色过渡。 */
    const val instant = 90

    /** 140ms：小范围位移、淡入淡出。 */
    const val fast = 140

    /** 200ms：默认时长，展开/收起、页面元素进出。 */
    const val base = 200

    /** 320ms：大面积过渡、面板滑入。 */
    const val slow = 320

    /** 480ms：需要被注意到的强调过渡。 */
    const val deliberate = 480

    /** 默认缓动，两端都平滑。 */
    val standard: Easing = FastOutSlowInEasing

    /** 进场缓动，起步快、收尾慢。 */
    val decelerate: Easing = LinearOutSlowInEasing

    /** 强调缓动，用于需要「有力」感的过渡。 */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
