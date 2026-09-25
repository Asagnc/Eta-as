package io.github.asagnc.sta

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.asagnc.sta.agent.skill.SkillRuntime
import io.github.asagnc.sta.agent.device.RootAccess
import io.github.asagnc.sta.agent.model.AgentNetworkWatcher
import io.github.asagnc.sta.agent.terminal.TerminalRuntime
import io.github.asagnc.sta.config.Prefs
import io.github.asagnc.sta.core.AndroidAgentLogger
import io.github.asagnc.sta.core.safeLogType
import io.github.asagnc.sta.data.datastore.SettingsDataStore
import io.github.asagnc.sta.data.repository.AgentMemoryRepository
import io.github.asagnc.sta.data.repository.AppearanceSettingsRepository
import io.github.asagnc.sta.agent.model.AgentContextCeilingStore
import io.github.asagnc.sta.data.repository.McpServerRepository
import io.github.asagnc.sta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.asagnc.sta.data.repository.ProviderRepository
import io.github.asagnc.sta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class StaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        AgentContextCeilingStore.init(this)
        // 网络切换后连接池里的旧连接会直接失效，注册监听以便主动清空。
        AgentNetworkWatcher.register(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            runCatching { SettingsDataStore.incrementLaunchCount() }
            LinuxEnvironmentSettingsRepository.initialize(this@StaApp)
            runCatching {
                SkillRuntime.createIndexService(this@StaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                notifyListener(listener, service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        notifyListener(listener, service)
                    }
                }
            }
        }

        /**
         * 分发逐个隔离：一个订阅者抛异常，不能让后面的订阅者收不到通知，
         * 也不能让异常顺着 post 逃到主线程的 Handler 上。
         */
        private fun notifyListener(listener: ServiceStateListener, service: XposedService?) {
            runCatching { listener.onServiceStateChanged(service) }
        }
    }
}
