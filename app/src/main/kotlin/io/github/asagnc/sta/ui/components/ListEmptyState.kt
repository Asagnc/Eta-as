package io.github.asagnc.sta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.github.asagnc.sta.ui.theme.StaColors
import io.github.asagnc.sta.ui.theme.StaSpacing
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ListEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = StaSpacing.huge, vertical = StaSpacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(StaSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2,
            color = StaColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        summary?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = StaColors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke()
    }
}
