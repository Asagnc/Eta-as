package io.github.asagnc.sta

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.asagnc.sta.config.Prefs
import io.github.asagnc.sta.core.HookInstallation
import io.github.asagnc.sta.core.ModuleConfig
import io.github.asagnc.sta.core.ModuleLogger
import io.github.asagnc.sta.core.safeLogType
import io.github.asagnc.sta.hook.aimemory.ColorOsMemoryHooks
import io.github.asagnc.sta.hook.system.AccessibilityProtectionHooks

class ModuleMain : XposedModule() {

    private val logger = ModuleLogger(this)
    private var currentProcessName: String? = null
    // 当前未启用热重载；保留句柄用于未来显式 unhook/replace，而不是维持 Hook 生效。
    private val hookHandles = mutableListOf<HookHandle>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        if (!shouldKeepLifecycleCallbacks(param)) {
            detach()
            return
        }
        // 缓存框架提供的只读 remote preferences，供所有 hook 拦截回调即时读取。
        // getRemotePreferences 是 XposedInterface 的方法，XposedModule 继承自其 Wrapper 可直接调用。
        // 调用失败时保留历史默认行为，但必须留下可诊断日志，不能伪装成配置同步正常。
        val remotePreferences = try {
            getRemotePreferences(Prefs.GROUP)
        } catch (exception: Exception) {
            logger.warn("RemotePreferences 不可用，将使用兼容默认值: ${exception.safeLogType()}")
            null
        }
        Prefs.attachRemote(remotePreferences)
        logger.debug {
            "模块已加载 process=${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion"
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        recordInstallation(AccessibilityProtectionHooks.install(this, logger, param.classLoader))
    }

    // Sta 只适配 ColorOS 记忆。小爱（XiaoAI）与小布（Breeno）的适配已整体移除：
    // 它们的 hook 目录、ModuleConfig 常量、注入文案与测试一并删除，不再保留空分支。
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == ModuleConfig.COLOROS_MEMORY_PACKAGE &&
            currentProcessName == ModuleConfig.COLOROS_MEMORY_PACKAGE
        ) {
            recordInstallation(ColorOsMemoryHooks.install(this, logger, param.classLoader))
        }
    }

    private fun recordInstallation(installation: HookInstallation) {
        hookHandles += installation.handles
        logger.scoped(installation.report.group).info(installation.report.summary())
    }

    private fun shouldKeepLifecycleCallbacks(param: ModuleLoadedParam): Boolean {
        if (param.isSystemServer) return true
        return param.processName == ModuleConfig.COLOROS_MEMORY_PACKAGE
    }
}
