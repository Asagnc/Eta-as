package io.github.asagnc.sta.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.asagnc.sta.ui.theme.StaColors
import io.github.asagnc.sta.ui.theme.StaSpacing
import top.yukonga.miuix.kmp.basic.Icon

@Composable
internal fun PreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier
            .padding(end = StaSpacing.xs)
            .size(StaSpacing.xxl),
        tint = if (enabled) StaColors.textPrimary else StaColors.textDisabled,
    )
}
