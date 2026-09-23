package io.github.asagnc.sta.ui.screens.terminal

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.asagnc.sta.R
import io.github.asagnc.sta.agent.terminal.ApkAnalysisInstallProgress
import io.github.asagnc.sta.agent.terminal.ApkAnalysisInstallResult
import io.github.asagnc.sta.agent.terminal.ApkAnalysisInstallStage
import io.github.asagnc.sta.agent.terminal.AptEnvironmentInstaller
import io.github.asagnc.sta.agent.terminal.AptEnvironmentState
import io.github.asagnc.sta.agent.terminal.AptInstallProgress
import io.github.asagnc.sta.agent.terminal.AptInstallResult
import io.github.asagnc.sta.agent.terminal.AptInstallStage
import io.github.asagnc.sta.agent.terminal.LinuxApkAnalysisInstaller
import io.github.asagnc.sta.agent.terminal.LinuxDistribution
import io.github.asagnc.sta.agent.terminal.LinuxExecutionBackend
import io.github.asagnc.sta.agent.terminal.LinuxPackageProfile
import io.github.asagnc.sta.agent.terminal.LinuxPackageProfileInstaller
import io.github.asagnc.sta.agent.terminal.LinuxPackageProfiles
import io.github.asagnc.sta.agent.terminal.PackageProfileInstallProgress
import io.github.asagnc.sta.agent.terminal.PackageProfileInstallResult
import io.github.asagnc.sta.agent.terminal.PackageProfileInstallStage
import io.github.asagnc.sta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.asagnc.sta.ui.app.launchForegroundExecution
import io.github.asagnc.sta.ui.app.rememberDeviceCapabilities
import io.github.asagnc.sta.ui.app.rememberExecutionNotificationRequest
import io.github.asagnc.sta.ui.components.MiuixDialogActions
import io.github.asagnc.sta.ui.components.MiuixScaffoldPage
import io.github.asagnc.sta.ui.components.PreferenceIcon
import io.github.asagnc.sta.ui.navigation.AppRoute
import io.github.asagnc.sta.ui.theme.StaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class InstallTarget {
    BASE,
    TOOLS,
    APK_ANALYSIS,
    PYTHON,
    NODE,
    SSH,
    GIT,
    CLI_TOOLS,
    BUILD_TOOLS,
    SECURITY_TOOLS,
    CTF_TOOLS,
    UNINSTALL,
}

private data class PackageProfileUi(
    val target: InstallTarget,
    val profile: LinuxPackageProfile,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    @param:StringRes val readyRes: Int,
    @param:StringRes val aptSummaryRes: Int = summaryRes,
    @param:StringRes val aptReadyRes: Int = readyRes,
)

private val packageProfileUis = listOf(
    PackageProfileUi(
        target = InstallTarget.PYTHON,
        profile = LinuxPackageProfiles.PYTHON,
        titleRes = R.string.linux_python_tools,
        summaryRes = R.string.linux_python_tools_summary,
        readyRes = R.string.linux_python_tools_ready,
        aptSummaryRes = R.string.linux_python_tools_summary_debian,
        aptReadyRes = R.string.linux_python_tools_ready_debian,
    ),
    PackageProfileUi(
        target = InstallTarget.NODE,
        profile = LinuxPackageProfiles.NODE,
        titleRes = R.string.linux_node_tools,
        summaryRes = R.string.linux_node_tools_summary,
        readyRes = R.string.linux_node_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.SSH,
        profile = LinuxPackageProfiles.SSH,
        titleRes = R.string.linux_ssh_tools,
        summaryRes = R.string.linux_ssh_tools_summary,
        readyRes = R.string.linux_ssh_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.GIT,
        profile = LinuxPackageProfiles.GIT,
        titleRes = R.string.linux_git_tools,
        summaryRes = R.string.linux_git_tools_summary,
        readyRes = R.string.linux_git_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.CLI_TOOLS,
        profile = LinuxPackageProfiles.CLI_TOOLS,
        titleRes = R.string.linux_cli_tools,
        summaryRes = R.string.linux_cli_tools_summary,
        readyRes = R.string.linux_cli_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.BUILD_TOOLS,
        profile = LinuxPackageProfiles.BUILD_TOOLS,
        titleRes = R.string.linux_build_tools,
        summaryRes = R.string.linux_build_tools_summary,
        readyRes = R.string.linux_build_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.SECURITY_TOOLS,
        profile = LinuxPackageProfiles.SECURITY_TOOLS,
        titleRes = R.string.linux_security_tools,
        summaryRes = R.string.linux_security_tools_summary,
        readyRes = R.string.linux_security_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.CTF_TOOLS,
        profile = LinuxPackageProfiles.CTF_TOOLS,
        titleRes = R.string.linux_ctf_tools,
        summaryRes = R.string.linux_ctf_tools_summary,
        readyRes = R.string.linux_ctf_tools_ready,
    ),
)

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val capabilities = rememberDeviceCapabilities()
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val coroutineScope = rememberCoroutineScope()
    val selectionFlow = remember(appContext) {
        LinuxEnvironmentSettingsRepository.selectedFlow(appContext)
    }
    val selectedDistribution by selectionFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.current(appContext),
    )
    val backendFlow = remember(appContext, selectedDistribution, capabilities.root.isGranted) {
        LinuxEnvironmentSettingsRepository.backendFlow(appContext, selectedDistribution)
    }
    val backend by backendFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.backend(appContext, selectedDistribution),
    )
    val requiresRoot = backend == LinuxExecutionBackend.CHROOT && !capabilities.root.isGranted
    val envInstaller = remember(appContext, selectedDistribution, backend) {
        AptEnvironmentInstaller(appContext, selectedDistribution)
    }
    val apkAnalysisInstaller = remember(appContext, selectedDistribution, backend) {
        LinuxApkAnalysisInstaller(appContext, selectedDistribution)
    }
    val profileInstallers = remember(appContext, selectedDistribution, backend) {
        packageProfileUis.associate { profileUi ->
            profileUi.target to LinuxPackageProfileInstaller(
                context = appContext,
                distribution = selectedDistribution,
                profile = profileUi.profile,
            )
        }
    }
    var envStatus by remember(envInstaller) { mutableStateOf(envInstaller.status()) }
    var busyTarget by remember { mutableStateOf<InstallTarget?>(null) }
    var envProgress by remember { mutableStateOf<AptInstallProgress?>(null) }
    var profileProgressSummary by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var profileReady by remember(selectedDistribution, backend) {
        mutableStateOf(packageProfileUis.associate { it.target to profileInstallers.getValue(it.target).isReady() })
    }
    var apkAnalysisReady by remember(selectedDistribution, backend) {
        mutableStateOf(apkAnalysisInstaller.isReady())
    }
    var apkAnalysisProgress by remember { mutableStateOf<ApkAnalysisInstallProgress?>(null) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var uninstallWorking by remember { mutableStateOf(false) }
    val selectedBaseReady = envStatus.state != AptEnvironmentState.NOT_INSTALLED
    val selectedToolsReady = envStatus.state == AptEnvironmentState.READY

    fun launchInstallation(block: suspend () -> Unit) {
        val operation: suspend () -> Unit = {
            try {
                block()
            } finally {
                envProgress = null
                profileProgressSummary = null
                apkAnalysisProgress = null
                busyTarget = null
            }
        }
        if (backend == LinuxExecutionBackend.PROOT) {
            requestExecutionNotifications()
            coroutineScope.launchForegroundExecution(
                context = appContext,
                onUnavailable = {
                    busyTarget = null
                    resultMessage = context.getString(R.string.capability_background_failed)
                },
                block = operation,
            )
        } else {
            coroutineScope.launch { operation() }
        }
    }

    fun installBase() {
        if (busyTarget != null || requiresRoot) return
        busyTarget = InstallTarget.BASE
        resultMessage = null
        launchInstallation {
            resultMessage = envInstaller.installBase { update ->
                withContext(Dispatchers.Main.immediate) { envProgress = update }
            }.toMessage(context)
            envStatus = envInstaller.status()
            envProgress = null
            busyTarget = null
        }
    }

    fun installTools() {
        if (busyTarget != null || requiresRoot) return
        busyTarget = InstallTarget.TOOLS
        resultMessage = null
        launchInstallation {
            resultMessage = envInstaller.installTools { update ->
                withContext(Dispatchers.Main.immediate) { envProgress = update }
            }.toMessage(context)
            envStatus = envInstaller.status()
            profileReady = packageProfileUis.associate {
                it.target to profileInstallers.getValue(it.target).isReady()
            }
            apkAnalysisReady = apkAnalysisInstaller.isReady()
            envProgress = null
            busyTarget = null
        }
    }

    fun uninstallEnvironment() {
        if (busyTarget != null) return
        busyTarget = InstallTarget.UNINSTALL
        uninstallWorking = true
        resultMessage = null
        launchInstallation {
            try {
                val deleted = envInstaller.uninstall()
                envStatus = envInstaller.status()
                profileReady = packageProfileUis.associate {
                    it.target to profileInstallers.getValue(it.target).isReady()
                }
                apkAnalysisReady = apkAnalysisInstaller.isReady()
                resultMessage = context.getString(
                    if (deleted) {
                        R.string.linux_environment_uninstall_done
                    } else {
                        R.string.linux_environment_uninstall_failed
                    },
                )
            } finally {
                uninstallWorking = false
                showUninstallDialog = false
            }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_linux_tool_environment_314d22),
        onBack = onBack,
    ) {
        item(key = "status-card") {
            val version = envStatus.version
            // 卸载与安装共用同一个 busy 状态，文案按当前动作区分。
            val busyLabel = stringResource(
                if (busyTarget == InstallTarget.UNINSTALL) R.string.linux_uninstalling else R.string.linux_installing,
            )
            val activeProgress = when (busyTarget) {
                InstallTarget.BASE, InstallTarget.TOOLS -> envProgress?.summary(context)
                InstallTarget.APK_ANALYSIS -> apkAnalysisProgress?.summary(context)
                else -> profileProgressSummary
            }
            LinuxEnvironmentStatusCard(
                title = listOfNotNull(selectedDistribution.displayName(), version?.takeIf { it.isNotBlank() })
                    .joinToString(" "),
                mode = backend.displayName(),
                summary = when {
                    requiresRoot -> stringResource(R.string.capability_linux_root_lost)
                    busyTarget != null -> activeProgress ?: busyLabel
                    selectedToolsReady -> stringResource(R.string.linux_environment_tools_ready)
                    selectedBaseReady -> stringResource(R.string.linux_environment_base_ready)
                    else -> stringResource(R.string.linux_not_installed) + "\n" + stringResource(
                        when {
                            backend == LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot_requirements
                            else -> R.string.linux_debian_requirements
                        },
                    )
                },
                busy = busyTarget != null,
                message = resultMessage,
                actionText = when {
                    requiresRoot -> stringResource(R.string.capability_enhancements)
                    selectedToolsReady -> null
                    busyTarget != null -> busyLabel
                    selectedBaseReady -> stringResource(R.string.linux_install_base_tools)
                    else -> stringResource(R.string.linux_install_base)
                },
                actionEnabled = busyTarget == null,
                onAction = {
                    if (requiresRoot) onNavigate(AppRoute.SystemEnhance)
                    else if (selectedBaseReady) installTools() else installBase()
                },
            )
        }
        if (selectedBaseReady) {
            item(key = "uninstall-card") {
                Card(modifier = Modifier.padding(horizontal = StaSpacing.md, vertical = StaSpacing.xs)) {
                    BasicComponent(
                        title = stringResource(R.string.linux_environment_uninstall),
                        summary = stringResource(R.string.linux_environment_uninstall_summary),
                        endActions = {
                            TextButton(
                                text = stringResource(R.string.linux_environment_uninstall),
                                enabled = busyTarget == null,
                                onClick = { showUninstallDialog = true },
                            )
                        },
                    )
                }
            }
        }
        item(key = "configuration-title") { SmallTitle(stringResource(R.string.linux_environment_configuration)) }
        item(key = "configuration-card") {
            LinuxEnvironmentConfiguration(
                distribution = selectedDistribution,
                backend = backend,
                rootGranted = capabilities.root.isGranted,
                enabled = busyTarget == null,
                onDistributionSelected = { distribution ->
                    if (busyTarget == null && distribution != selectedDistribution) {
                        resultMessage = null
                        coroutineScope.launch { LinuxEnvironmentSettingsRepository.select(distribution) }
                    }
                },
                onBackendSelected = { selectedBackend ->
                    if (busyTarget == null && selectedBackend != backend &&
                        (selectedBackend == LinuxExecutionBackend.PROOT || capabilities.root.isGranted)
                    ) {
                        resultMessage = null
                        coroutineScope.launch {
                            LinuxEnvironmentSettingsRepository.selectBackend(selectedDistribution, selectedBackend)
                        }
                    }
                },
            )
        }
        item(key = "files-title") { SmallTitle(stringResource(R.string.linux_environment_files)) }
        item(key = "files-card") {
            Card(modifier = Modifier.padding(horizontal = StaSpacing.md, vertical = StaSpacing.xs)) {
                ArrowPreference(
                    title = stringResource(R.string.capability_workspace),
                    summary = stringResource(R.string.capability_workspace_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.Folder) },
                    onClick = { onNavigate(AppRoute.Workspace) },
                )
                if (selectedBaseReady) {
                    ArrowPreference(
                        title = stringResource(R.string.shared_folders_entry_title),
                        summary = stringResource(R.string.linux_environment_shared_folders_summary),
                        startAction = { PreferenceIcon(Icons.Rounded.FolderOpen) },
                        onClick = { onNavigate(AppRoute.SharedFolders) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.linux_files_entry_title),
                        summary = stringResource(R.string.linux_files_entry_summary),
                        startAction = { PreferenceIcon(Icons.Rounded.Description) },
                        onClick = { onNavigate(AppRoute.LinuxFiles(selectedDistribution.wireName)) },
                    )
                }
            }
        }

        if (selectedToolsReady) {
            item(key = "optional-tools-title") { SmallTitle(stringResource(R.string.ui_optional_tools_3097d6)) }
            item(key = "optional-tools-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = StaSpacing.md)
                        .padding(bottom = StaSpacing.md),
                ) {
                    packageProfileUis.forEachIndexed { index, profileUi ->
                        val ready = profileReady[profileUi.target] == true
                        val summaryRes = profileUi.aptSummaryRes
                        val readyRes = profileUi.aptReadyRes
                        if (index > 0) HorizontalDivider()
                        BasicComponent(
                            title = stringResource(profileUi.titleRes),
                            summary = if (busyTarget == profileUi.target) {
                                profileProgressSummary ?: stringResource(summaryRes)
                            } else if (ready) {
                                stringResource(readyRes)
                            } else {
                                stringResource(summaryRes)
                            },
                            endActions = {
                                TextButton(
                                    text = when {
                                        ready -> stringResource(R.string.linux_installed)
                                        busyTarget == profileUi.target -> stringResource(R.string.linux_installing)
                                        else -> stringResource(R.string.linux_install)
                                    },
                                    enabled = !requiresRoot && busyTarget == null && !ready,
                                    onClick = {
                                        if (busyTarget != null || ready) return@TextButton
                                        busyTarget = profileUi.target
                                        resultMessage = null
                                        val profileTitle = context.getString(profileUi.titleRes)
                                        launchInstallation {
                                            val profileInstaller = profileInstallers.getValue(profileUi.target)
                                            val result = profileInstaller.install { update ->
                                                withContext(Dispatchers.Main.immediate) {
                                                    profileProgressSummary = update.summary(context, profileTitle)
                                                }
                                            }
                                            profileReady = profileReady +
                                                (profileUi.target to profileInstaller.isReady())
                                            profileProgressSummary = null
                                            busyTarget = null
                                            resultMessage = result.toMessage(context, profileTitle)
                                        }
                                    },
                                )
                            },
                        )
                    }
                    HorizontalDivider()
                    BasicComponent(
                        title = stringResource(R.string.ui_apk_analysis_95ad17),
                        summary = apkAnalysisProgress?.summary(context) ?: if (apkAnalysisReady) {
                            context.getString(R.string.linux_apk_tools_ready)
                        } else {
                            context.getString(R.string.linux_apk_tools_summary)
                        },
                        endActions = {
                            TextButton(
                                text = when {
                                    apkAnalysisReady -> context.getString(R.string.linux_installed)
                                    busyTarget == InstallTarget.APK_ANALYSIS -> context.getString(R.string.linux_installing)
                                    else -> context.getString(R.string.linux_install)
                                },
                                enabled = busyTarget == null && !requiresRoot && !apkAnalysisReady,
                                onClick = {
                                    if (busyTarget != null || apkAnalysisReady) return@TextButton
                                    busyTarget = InstallTarget.APK_ANALYSIS
                                    resultMessage = null
                                    launchInstallation {
                                        val result = apkAnalysisInstaller.install { update ->
                                            withContext(Dispatchers.Main.immediate) {
                                                apkAnalysisProgress = update
                                            }
                                        }
                                        apkAnalysisReady = apkAnalysisInstaller.isReady()
                                        apkAnalysisProgress = null
                                        busyTarget = null
                                        resultMessage = result.toMessage(context)
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    UninstallConfirmDialog(
        show = showUninstallDialog,
        working = uninstallWorking,
        onDismissRequest = { if (!uninstallWorking) showUninstallDialog = false },
        onConfirm = { uninstallEnvironment() },
    )
}

@Composable
private fun UninstallConfirmDialog(
    show: Boolean,
    working: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.linux_environment_uninstall),
        summary = stringResource(R.string.linux_environment_uninstall_confirm),
        onDismissRequest = onDismissRequest,
    ) {
        MiuixDialogActions(
            confirmText = if (working) {
                stringResource(R.string.status_processing)
            } else {
                stringResource(R.string.action_confirm)
            },
            cancelEnabled = !working,
            confirmEnabled = !working,
            onCancel = onDismissRequest,
            onConfirm = onConfirm,
        )
    }
}

private fun Long.toReadableSize(context: Context): String = Formatter.formatShortFileSize(context, this)

private fun AptInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != AptInstallStage.DOWNLOADING || totalBytes <= 0L) return stageName
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun PackageProfileInstallProgress.summary(context: Context, profileTitle: String): String =
    when (stage) {
        PackageProfileInstallStage.CHECKING -> context.getString(R.string.linux_profile_stage_checking)
        PackageProfileInstallStage.DOWNLOADING -> if (totalBytes > 0L) {
            context.getString(
                R.string.linux_profile_stage_downloading_percent,
                profileTitle,
                (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L),
            )
        } else {
            context.getString(R.string.linux_profile_stage_downloading, profileTitle)
        }
        PackageProfileInstallStage.INSTALLING ->
            context.getString(R.string.linux_profile_stage_installing, profileTitle)
        PackageProfileInstallStage.COMPLETE -> context.getString(R.string.linux_profile_stage_complete)
    }

private fun ApkAnalysisInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != ApkAnalysisInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    val name = when (artifactName) {
        "jadx" -> "JADX"
        "apktool" -> "Apktool"
        "smali" -> "smali"
        "baksmali" -> "baksmali"
        else -> context.getString(R.string.linux_tool)
    }
    return context.getString(R.string.linux_tool_progress_percent, stageName, name, percent)
}

private fun AptInstallResult.toMessage(context: Context): String = when (this) {
    AptInstallResult.AlreadyReady -> context.getString(R.string.linux_debian_already_ready)
    is AptInstallResult.BaseInstalled -> context.getString(R.string.linux_debian_base_install_complete, version)
    is AptInstallResult.ToolsInstalled -> context.getString(R.string.linux_debian_install_complete, version)
    AptInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is AptInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    AptInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    AptInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    AptInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is AptInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun PackageProfileInstallResult.toMessage(
    context: Context,
    profileTitle: String,
): String = when (this) {
    PackageProfileInstallResult.AlreadyReady ->
        context.getString(R.string.linux_profile_already_ready, profileTitle)
    PackageProfileInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is PackageProfileInstallResult.DependencyMissing -> {
        val dependencyTitle = packageProfileUis
            .firstOrNull { it.profile.id == profileId }
            ?.let { context.getString(it.titleRes) }
            ?: profileId
        context.getString(R.string.linux_profile_dependency_missing, dependencyTitle)
    }
    PackageProfileInstallResult.Installed ->
        context.getString(R.string.linux_profile_installed, profileTitle)
    is PackageProfileInstallResult.Failed -> context.getString(
        R.string.linux_profile_stage_failed,
        PackageProfileInstallProgress(stage).summary(context, profileTitle),
    )
}

private fun ApkAnalysisInstallResult.toMessage(context: Context): String = when (this) {
    ApkAnalysisInstallResult.AlreadyReady -> context.getString(R.string.linux_apk_analysis_ready)
    ApkAnalysisInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is ApkAnalysisInstallResult.InsufficientSpace ->
        context.getString(
            R.string.linux_insufficient_space,
            requiredBytes.toReadableSize(context),
            availableBytes.toReadableSize(context),
        )
    ApkAnalysisInstallResult.Installed -> context.getString(R.string.linux_apk_analysis_installed)
    is ApkAnalysisInstallResult.Failed -> context.getString(R.string.linux_apk_stage_failed, stage.displayName(context))
}

private fun AptInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        AptInstallStage.CHECKING -> R.string.linux_stage_checking
        AptInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        AptInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        AptInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        AptInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun ApkAnalysisInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        ApkAnalysisInstallStage.CHECKING -> R.string.linux_apk_stage_checking
        ApkAnalysisInstallStage.DOWNLOADING -> R.string.linux_apk_stage_downloading
        ApkAnalysisInstallStage.PREPARING -> R.string.linux_apk_stage_preparing
        ApkAnalysisInstallStage.INSTALLING_JAVA -> R.string.linux_apk_stage_installing_java
        ApkAnalysisInstallStage.ACTIVATING -> R.string.linux_apk_stage_activating
        ApkAnalysisInstallStage.VERIFYING -> R.string.linux_apk_stage_verifying
        ApkAnalysisInstallStage.COMPLETE -> R.string.linux_apk_stage_complete
    },
)
