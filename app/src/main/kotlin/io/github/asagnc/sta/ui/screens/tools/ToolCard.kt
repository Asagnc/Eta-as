package io.github.asagnc.sta.ui.screens.tools

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.asagnc.sta.R
import io.github.asagnc.sta.agent.tool.AgentToolCapabilities
import io.github.asagnc.sta.agent.tool.RootRequirement
import io.github.asagnc.sta.ui.components.ItemDescriptionDialog
import io.github.asagnc.sta.ui.components.StaCard
import io.github.asagnc.sta.ui.components.iconForTool
import io.github.asagnc.sta.ui.model.AgentToolsAction
import io.github.asagnc.sta.ui.model.ToolItemUi
import io.github.asagnc.sta.ui.model.actualToolName
import io.github.asagnc.sta.ui.model.toolCardAction
import io.github.asagnc.sta.ui.model.toolCardRequirement
import io.github.asagnc.sta.ui.theme.StaColors
import io.github.asagnc.sta.ui.theme.StaIconSize
import io.github.asagnc.sta.ui.theme.StaSize
import io.github.asagnc.sta.ui.theme.StaSpacing
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun ToolCard(
    tool: ToolItemUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDescription by remember(tool.id) { mutableStateOf(false) }
    val description = if (!rootGranted && tool.id == "terminal") {
        stringResource(R.string.capability_terminal_ordinary_summary)
    } else {
        tool.summary
    }
    val requirementText = toolRequirementText(tool.id, rootGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionText = when (action) {
        AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
        AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
        AgentToolsAction.OpenEnhancements -> stringResource(R.string.tools_view_enhancements)
        else -> stringResource(R.string.ui_view_description)
    }
    StaCard(
        modifier = modifier.heightIn(min = 136.dp),
        insideMargin = PaddingValues(StaSpacing.lg),
        colors = CardDefaults.defaultColors(
            color = StaColors.surfaceRaised,
            contentColor = StaColors.onSurfaceRaised,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = {
            if (action != null) onAction(action) else showDescription = true
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = StaSize.s40),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconForTool(tool.id),
                contentDescription = null,
                modifier = Modifier.size(StaIconSize.xl),
                tint = StaColors.onSurfaceRaised,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (action != null) {
                IconButton(onClick = { showDescription = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.ui_description_named, tool.title),
                        modifier = Modifier.size(StaIconSize.lg),
                        tint = StaColors.textAction,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(StaSpacing.sm))
        Text(
            text = tool.title,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = StaColors.onSurfaceRaised,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(StaSpacing.hair))
        Text(
            text = description,
            style = MiuixTheme.textStyles.footnote1,
            color = StaColors.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        requirementText?.let {
            Spacer(modifier = Modifier.height(StaSpacing.sm))
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = StaColors.textAction,
            )
        }
        Spacer(modifier = Modifier.height(StaSpacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = actionText,
                style = MiuixTheme.textStyles.footnote1,
                color = if (action != null) StaColors.accent
                    else StaColors.textAction,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(StaIconSize.md),
                    tint = StaColors.accent,
                )
            }
        }
    }
    if (showDescription) {
        ItemDescriptionDialog(
            title = tool.title,
            description = listOfNotNull(description, requirementText).joinToString("\n\n"),
            onDismiss = { showDescription = false },
        )
    }
}

@Composable
private fun toolRequirementText(id: String, rootGranted: Boolean, capabilities: AgentToolCapabilities): String? {
    val requirement = toolCardRequirement(id)
    val unavailableCode = capabilities.unavailableCode(actualToolName(id))
    return when {
        !rootGranted && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
        !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
        unavailableCode == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
        unavailableCode == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
        unavailableCode == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
        requirement.colorOs -> stringResource(R.string.capability_coloros_required)
        !rootGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
        else -> null
    }
}
