package io.github.asagnc.sta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import io.github.asagnc.sta.ui.theme.StaSpacing
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.popup.WindowDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsDropdownPreference(
    title: String,
    items: List<DropdownItem>,
    selectedIndex: Int,
    startAction: @Composable () -> Unit,
    enabled: Boolean = true,
    useWindow: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val available = enabled && items.isNotEmpty()
    LaunchedEffect(available) {
        if (!available) expanded = false
    }
    val haptics = LocalHapticFeedback.current
    val color = if (available) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSurface
    val entry = DropdownEntry(items.mapIndexed { index, item ->
        item.copy(selected = index == selectedIndex, onClick = {
            onSelectedIndexChange(index)
            item.onClick?.invoke()
        })
    })
    SettingsPreferenceRow(
        title = title,
        startAction = startAction,
        enabled = available,
        holdDownState = expanded && available,
        interaction = Modifier.clickable(enabled = available, role = Role.DropdownList) {
            expanded = !expanded
            if (expanded) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        },
    ) {
        items.getOrNull(selectedIndex)?.text?.let { value ->
            Text(
                text = value,
                modifier = Modifier.weight(1f, fill = false).padding(end = StaSpacing.sm),
                style = MiuixTheme.textStyles.body2,
                color = color,
                textAlign = TextAlign.End,
            )
        }
        Row {
            DropdownArrowEndAction(actionColor = color)
            if (useWindow) {
                WindowDropdownPopup(
                    entry = entry,
                    show = expanded && available,
                    onDismiss = { expanded = false },
                    onDismissFinished = {},
                    maxHeight = null,
                    dropdownColors = DropdownDefaults.dropdownColors(),
                )
            } else {
                OverlayDropdownPopup(
                    entry = entry,
                    show = expanded && available,
                    onDismiss = { expanded = false },
                    onDismissFinished = {},
                    maxHeight = null,
                    dropdownColors = DropdownDefaults.dropdownColors(),
                    renderInRootScaffold = true,
                )
            }
        }
    }
}
