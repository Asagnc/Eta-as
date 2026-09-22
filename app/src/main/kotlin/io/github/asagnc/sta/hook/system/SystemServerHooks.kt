package io.github.asagnc.sta.hook.system

import io.github.asagnc.sta.hook.hyperos.HyperOsPowerHooks
import io.github.asagnc.sta.core.HookInstallation
import io.github.asagnc.sta.core.ModuleLogger

import io.github.libxposed.api.XposedModule

internal object SystemServerHooks {

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation = HookInstallation.combine(
        group = "SystemServer",
        installations = listOf(
            AccessibilityProtectionHooks.install(module, logger, classLoader),
            AssistantManager.install(module, logger, classLoader),
            PowerHooks.install(module, logger, classLoader),
            HyperOsPowerHooks.install(module, logger, classLoader)
        )
    )
}
