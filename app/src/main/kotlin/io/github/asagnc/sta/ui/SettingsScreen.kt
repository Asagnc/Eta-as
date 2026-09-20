package io.github.asagnc.sta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.asagnc.sta.StaApp
import io.github.asagnc.sta.R
import io.github.asagnc.sta.agent.accessibility.AccessibilityProtectionClient
import io.github.asagnc.sta.agent.accessibility.AgentAccessibilityService
import io.github.asagnc.sta.config.PowerAssistantTarget
import io.github.asagnc.sta.config.Prefs
import io.github.asagnc.sta.data.repository.ProviderRepository
import io.github.asagnc.sta.data.repository.RuntimeConfigRepository
import io.github.asagnc.sta.ui.app.EnhancementSettingsHistory
import io.github.asagnc.sta.ui.app.rememberDeviceCapabilities
import io.github.asagnc.sta.ui.components.LanguagePreference
import io.github.asagnc.sta.ui.components.MiuixDialogActions
import io.github.asagnc.sta.ui.components.MiuixScaffoldPage
import io.github.asagnc.sta.ui.components.PreferenceIcon
import io.github.asagnc.sta.ui.navigation.AppRoute
import io.github.asagnc.sta.ui.components.SettingsArrowPreference
import io.github.asagnc.sta.ui.components.SettingsDropdownPreference
import io.github.asagnc.sta.ui.components.SettingsGroup
import io.github.asagnc.sta.ui.components.SettingsGroupTitle
import io.github.asagnc.sta.ui.components.SettingsIconColors
import io.github.asagnc.sta.ui.components.SettingsItemDivider
import io.github.asagnc.sta.ui.components.SettingsPageTheme
import io.github.asagnc.sta.ui.components.SettingsPreferenceIcon
import io.github.asagnc.sta.ui.components.SettingsSwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Sta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageTheme {
        SettingsPageContent(context = context, onNavigate = onNavigate, onBack = onBack)
    }
}

@Composable
private fun SettingsPageContent(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(isAgentAccessibilityEnabled(context))
    }
    var accessibilityProtectionEnabled by remember {
        mutableStateOf(AccessibilityProtectionClient.isEnabled(context))
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = isAgentAccessibilityEnabled(context)
                accessibilityProtectionEnabled =
                    AccessibilityProtectionClient.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / Model 选中状态展示
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(StaApp.serviceInstance)) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(Unit) {
        val listener = object : StaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                prefs?.let { connected ->
                    enhancementHistory.captureConnected(connected)
                    hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        StaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { StaApp.removeServiceStateListener(listener) }
    }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target ->
        DropdownItem(text = target.displayName(context))
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
    ) {
            // ── LLM 提供商 ──────────────────────────────────────────────
            item(key = "section_agent") {
                SettingsGroupTitle(stringResource(R.string.settings_llm_providers))
                SettingsGroup {
                    SettingsArrowPreference(
                        title = stringResource(R.string.ui_model_provider_e8c7f5),
                        summary = providerSummary,
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.Memory,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )

                    SettingsItemDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = Icons.Rounded.Psychology,
                        iconTint = SettingsIconColors.Blue,
                    )
                }
            }

            // ── 上下文与扩展 ────────────────────────────────────────────
            item(key = "section_context_extensions") {
                SettingsGroupTitle(stringResource(R.string.settings_context_extensions))
                SettingsGroup {
                    SettingsArrowPreference(
                        title = stringResource(R.string.ui_memory_b55ff5),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.AutoMirrored.Rounded.MenuBook,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )

                    SettingsItemDivider()
                    SettingsArrowPreference(
                        title = stringResource(R.string.route_skills),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.Extension,
                                tint = SettingsIconColors.Green,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Skills) },
                    )

                    SettingsItemDivider()
                    SettingsArrowPreference(
                        title = stringResource(R.string.route_mcp_servers),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.AccountTree,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.McpServers) },
                    )

                    SettingsItemDivider()
                    SettingsArrowPreference(
                        title = "角色",
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.TheaterComedy,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Characters) },
                    )
                }
            }

            // ── 工具 ───────────────────────────────────────────────────
            item(key = "section_tools") {
                SettingsGroupTitle(stringResource(R.string.ui_tool_a72ef1))
                SettingsGroup {
                    SettingsArrowPreference(
                        title = stringResource(R.string.settings_tools_list),
                        startAction = { SettingsPreferenceIcon(Icons.Rounded.Dashboard, tint = SettingsIconColors.Green) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )

                    SettingsItemDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_web_browsing_tools_8b6b03),
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = Icons.Rounded.Language,
                        iconTint = SettingsIconColors.Blue,
                    )

                    SettingsItemDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                        key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                        icon = Icons.Rounded.Smartphone,
                        iconTint = SettingsIconColors.Green,
                    )

                    SettingsItemDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                        icon = Icons.Rounded.Visibility,
                        iconTint = SettingsIconColors.Blue,
                    )

                    SettingsItemDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                        icon = Icons.Rounded.GppMaybe,
                        iconTint = SettingsIconColors.Orange,
                    )

                    SettingsItemDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = Icons.Rounded.Terminal,
                        iconTint = SettingsIconColors.Green,
                    )

                    SettingsItemDivider()
                    SettingsArrowPreference(
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.Inventory2,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                }
            }

            item(key = "system_enhancements") {
                SettingsGroup {
                    SettingsArrowPreference(
                        title = stringResource(R.string.capability_enhancements),
                        startAction = { SettingsPreferenceIcon(Icons.Rounded.Security, tint = SettingsIconColors.Blue) },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                }
            }

            // ── 系统助手接管 ──────────────────────────────────────────────
            item(key = "section_assistant_takeover") {
                SettingsGroupTitle(stringResource(R.string.ui_system_assistant_takes_over_f46043))
                SettingsGroup {
                    SettingsArrowPreference(
                        title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.SupportAgent,
                                tint = SettingsIconColors.Green,
                            )
                        },
                        onClick = openAssistantSettings,
                    )
                    if (prefs != null || hasConnectedFramework) {
                        SettingsItemDivider()
                        SettingsDropdownPreference(
                            title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                            items = powerAssistantItems,
                            selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                            onSelectedIndexChange = { index ->
                                val target = powerAssistantTargets.getOrNull(index)
                                    ?: return@SettingsDropdownPreference
                                val targetPrefs = prefs ?: return@SettingsDropdownPreference
                                if (putStringSync(
                                        prefs = targetPrefs,
                                        key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                        value = target.persistedValue,
                                    )
                                ) {
                                    powerAssistantTarget = target
                                    enhancementHistory.recordCommittedTarget(target)
                                } else {
                                    Toast.makeText(
                                        context.applicationContext,
                                        context.getString(R.string.settings_write_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            startAction = {
                                SettingsPreferenceIcon(
                                    icon = Icons.Rounded.PowerSettingsNew,
                                    tint = SettingsIconColors.Yellow,
                                    enabled = prefs != null,
                                )
                            },
                            enabled = prefs != null,
                        )

                        SettingsItemDivider()
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                            key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                            icon = Icons.Rounded.Settings,
                            iconTint = SettingsIconColors.Green,
                        )
                    }
                }
            }

            if (prefs != null || hasConnectedFramework) {
                // ── 厂商助手兼容入口 ──────────────────────────────────────────
                item(key = "section_oem_assistant_compatibility") {
                    SettingsGroupTitle(stringResource(R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a))
                    SettingsGroup {
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                            key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                            icon = Icons.Rounded.Memory,
                            iconTint = SettingsIconColors.Blue,
                        )

                        SettingsItemDivider()
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                            key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                            icon = Icons.Rounded.Code,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                }
            }

            // ── 通用 ────────────────────────────────────────────────────
            item(key = "section_general") {
                SettingsGroupTitle(stringResource(R.string.settings_general))
                SettingsGroup {
                    SettingsArrowPreference(
                        title = stringResource(R.string.appearance_title),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.Palette,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )

                    SettingsItemDivider()
                    LanguagePreference(iconTint = SettingsIconColors.Blue)

                    SettingsItemDivider()
                    SettingsArrowPreference(
                        title = stringResource(R.string.data_backup_title),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.Description,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DataBackup) },
                    )
                }
            }

            // ── 权限 ────────────────────────────────────────────────────
            item(key = "section_permissions") {
                SettingsGroupTitle(stringResource(R.string.ui_permissions_560165))
                SettingsGroup {
                    SettingsArrowPreference(
                        title = stringResource(R.string.ui_floating_window_permissions_076b77),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.Layers,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        endActions = {
                            Text(
                                text = stringResource(
                                    if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (overlayGranted) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    MiuixTheme.colorScheme.error
                                },
                            )
                        },
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )

                    SettingsItemDivider()
                    SettingsArrowPreference(
                        title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.AccessibilityNew,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        endActions = {
                            val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                            Text(
                                text = stringResource(
                                    if (enabled) R.string.status_enabled else R.string.status_disabled,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (enabled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    MiuixTheme.colorScheme.primary
                                },
                            )
                        },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }
                        },
                    )
                    if (prefs != null || hasConnectedFramework) {
                        SettingsItemDivider()
                        SettingsSwitchPreference(
                            title = stringResource(R.string.ui_enforce_accessibility_55e838),
                            checked = accessibilityProtectionEnabled,
                            onCheckedChange = { enabled ->
                                if (accessibilityProtectionPending) {
                                    return@SettingsSwitchPreference
                                }
                                accessibilityProtectionPending = true
                                AccessibilityProtectionClient.setEnabled(
                                    context = context,
                                    enabled = enabled,
                                ) { result ->
                                    accessibilityProtectionPending = false
                                    accessibilityProtectionEnabled = result.enabled
                                    accessibilityGranted = isAgentAccessibilityEnabled(context)
                                    val failureMessage = when (result.status) {
                                        AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                                        AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                                            context.getString(R.string.accessibility_protection_unavailable)
                                        AccessibilityProtectionClient.ControlStatus.REJECTED ->
                                            context.getString(R.string.accessibility_protection_rejected)
                                    }
                                    if (failureMessage != null) {
                                        Toast.makeText(
                                            context.applicationContext,
                                            failureMessage,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            startAction = {
                                SettingsPreferenceIcon(
                                    icon = Icons.Rounded.VerifiedUser,
                                    tint = SettingsIconColors.Blue,
                                    enabled = prefs != null && !accessibilityProtectionPending,
                                )
                            },
                            enabled = prefs != null && !accessibilityProtectionPending,
                        )
                    }
                }
            }

            // ── 关于 ────────────────────────────────────────────────────
            item(key = "section_about") {
                SettingsGroupTitle(stringResource(R.string.ui_about_bed172))
                SettingsGroup {
                    SettingsArrowPreference(
                        title = stringResource(R.string.ui_source_code_740296),
                        startAction = {
                            SettingsPreferenceIcon(
                                icon = Icons.Rounded.Code,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Mangi-11/Sta"),
                            )
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }

}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * 配置来源由调用方按能力边界传入。Hook 开关仍可能因 LSPosed 未连接而禁用；Agent
 * Runtime 开关始终使用 App 本地配置。
 */
@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val enabled = prefs != null
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsSwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@SettingsSwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(StaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            SettingsPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint)
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.ETA -> "Sta"
    }

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java
    ).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}
