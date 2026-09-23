package io.github.asagnc.sta.hook.system

import io.github.asagnc.sta.core.HookInstallation
import io.github.asagnc.sta.core.ModuleLogger

import io.github.libxposed.api.XposedModule

internal object SystemServerHooks {

    // HyperOS（小米）电源策略 hook 已移除：本机是 realme/ColorOS，那段代码在本机是死代码。
    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation = HookInstallation.combine(
        group = "SystemServer",
        installations = listOf(
            AccessibilityProtectionHooks.install(module, logger, classLoader)
        )
    )
}
