package io.github.asagnc.sta.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.asagnc.sta.data.model.CustomProviderSetting
import io.github.asagnc.sta.data.model.ProviderSetting
import io.github.asagnc.sta.ui.components.PreferenceIcon
import io.github.asagnc.sta.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import io.github.asagnc.sta.ui.theme.StaRadius
import io.github.asagnc.sta.ui.theme.StaSpacing
import top.yukonga.miuix.kmp.basic.Card
import io.github.asagnc.sta.ui.components.StaCard
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.asagnc.sta.ui.theme.StaColors

/** 分组标题 + 卡片的标准组合，Provider 相关页面统一使用。 */
@Composable
internal fun ProviderSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            SmallTitle(title)
        }
        StaCard(modifier = Modifier.fillMaxWidth().padding(horizontal = StaSpacing.md)) {
            content()
        }
    }
}

/** 厂商品牌原色图标；资源已经包含适合圆形裁剪的背景与安全区。 */
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .padding(end = StaSpacing.xs)
            .size(24.dp)
            .clip(CircleShape),
    )
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    sharedProviderBrandLogoRes(provider)

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    sharedProviderBrandLogoRes(sourceType)

/** 已知厂商使用品牌图标，未知来源继续按协议类型使用通用图标。 */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }

    when (provider) {
        is CustomProviderSetting -> PreferenceIcon(
            icon = Icons.Rounded.Dns,
            modifier = modifier,
        )
        else -> PreferenceIcon(
            icon = Icons.Rounded.Language,
            modifier = modifier,
        )
    }
}

internal enum class TagChipTone { Normal, Emphasized }

/** 小胶囊标签，用于能力标签与状态标记。 */
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    val background: Color
    val foreground: Color
    when (tone) {
        TagChipTone.Normal -> {
            background = StaColors.neutralContainer
            foreground = StaColors.onNeutralContainer
        }
        TagChipTone.Emphasized -> {
            background = StaColors.accentContainer
            foreground = StaColors.onAccentContainer
        }
    }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(StaRadius.xs))
            .padding(horizontal = StaSpacing.xs, vertical = StaSpacing.hair),
    )
}
