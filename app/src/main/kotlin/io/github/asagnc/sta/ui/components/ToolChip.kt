package io.github.asagnc.sta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.github.asagnc.sta.ui.theme.StaColors
import io.github.asagnc.sta.ui.theme.StaRadius
import io.github.asagnc.sta.ui.theme.StaSpacing
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ToolChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(StaRadius.sm))
            .background(StaColors.surfaceRaisedHigh),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = StaSpacing.sm, vertical = StaSpacing.xxs),
            style = MiuixTheme.textStyles.footnote1,
            color = StaColors.textSecondary,
        )
    }
}
