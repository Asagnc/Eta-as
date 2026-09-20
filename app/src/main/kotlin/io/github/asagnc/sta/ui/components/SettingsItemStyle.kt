package io.github.asagnc.sta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.asagnc.sta.ui.app.LocalAppearanceSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object SettingsIconColors {
    val Blue = Color(0xFF0080FF)
    val Green = StatusSuccess
    val Orange = Color(0xFFFF7700)
    val Yellow = StatusWarning
}

internal object SettingsItemLayout {
    val SidePadding = 16.dp
    val IconSize = 24.dp
    val IconTextGap = 16.dp
    val ContentStart = SidePadding + IconSize + IconTextGap
    val RowMinHeight = 52.dp
}

@Composable
internal fun SettingsPageTheme(content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val appearance = LocalAppearanceSettings.current
    val pageColors = if (!appearance.monetEnabled && colors.background.luminance() > 0.5f) {
        colors.copy(background = Color(0xFFF0F1F2))
    } else {
        colors
    }
    MiuixTheme(colors = pageColors, content = content)
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    StaCard(
        modifier = Modifier.padding(horizontal = SettingsItemLayout.SidePadding).padding(bottom = 16.dp),
        content = content,
    )
}

@Composable
internal fun SettingsGroupTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SettingsPreferenceIcon(
    icon: ImageVector,
    tint: Color,
    enabled: Boolean = true,
) {
    // 满幅轮廓稍作光学校正，但所有图标都占相同宽度，保证正文和分割线对齐。
    val glyphSize = when (icon) {
        Icons.Rounded.Extension, Icons.Rounded.TheaterComedy, Icons.AutoMirrored.Rounded.MenuBook -> 22.dp
        else -> SettingsItemLayout.IconSize
    }
    Box(Modifier.size(SettingsItemLayout.IconSize), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSurface,
        )
    }
}

@Composable
internal fun SettingsItemDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SettingsItemLayout.ContentStart, end = SettingsItemLayout.SidePadding),
        thickness = 0.33.dp,
        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f),
    )
}
