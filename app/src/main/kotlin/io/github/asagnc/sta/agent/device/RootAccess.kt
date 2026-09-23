package io.github.asagnc.sta.agent.device

import android.content.Context
import android.content.SharedPreferences
import io.github.asagnc.sta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal enum class RootAccessStatus { UNKNOWN, UNAVAILABLE, NOT_GRANTED, GRANTED, DENIED, TIMED_OUT }

internal data class RootAccessState(
    val status: RootAccessStatus = RootAccessStatus.UNKNOWN,
    val suPresent: Boolean = false,
    val isChecking: Boolean = false,
) {
    val isGranted: Boolean get() = status == RootAccessStatus.GRANTED
}

/** Root 探测归 App 进程所有；读取能力不会执行 su 或弹出授权。 */
internal object RootAccess {
    private const val PREFERENCES = "sta_root_access"
    private const val AUTOMATIC_REQUEST_ATTEMPTED = "automatic_request_attempted"
    private const val LAST_GRANTED = "last_granted"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RootAccessState())
    val state: StateFlow<RootAccessState> = mutableState.asStateFlow()
    val isGranted: Boolean get() = mutableState.value.isGranted
    private var preferences: SharedPreferences? = null
    private var probeJob: Job? = null

    fun initialize(context: Context) {
        synchronized(this) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
        refresh(context)
    }

    /** 首次发现 su 时请求一次；拒绝后只有显式 request 才会再次申请。 */
    fun refresh(context: Context): Job = startProbe(context, explicit = false)

    fun request(context: Context): Job = startProbe(context, explicit = true)

    /** 仅由执行器确认 su 拒绝后调用；普通命令失败不代表授权被撤销。 */
    fun markDenied() {
        preferences?.edit()?.putBoolean(LAST_GRANTED, false)?.apply()
        mutableState.value = mutableState.value.copy(status = RootAccessStatus.DENIED)
    }

    private fun startProbe(context: Context, explicit: Boolean): Job = synchronized(this) {
        probeJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
        scope.launch {
            mutableState.value = mutableState.value.copy(isChecking = true)
            try {
                val present = suExists()
                if (!present) {
                    mutableState.value = RootAccessState(RootAccessStatus.UNAVAILABLE)
                    return@launch
                }
                val attempted = prefs.getBoolean(AUTOMATIC_REQUEST_ATTEMPTED, false)
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                if (!shouldRequestRoot(explicit, attempted, wasGranted)) {
                    mutableState.value = mutableState.value.copy(
                        status = if (mutableState.value.status == RootAccessStatus.UNKNOWN) {
                            RootAccessStatus.NOT_GRANTED
                        } else {
                            mutableState.value.status
                        },
                        suPresent = true,
                    )
                    return@launch
                }
                // 先记住尝试，进程在授权弹窗期间退出也不会在下次启动再次打扰用户。
                if (!prefs.edit().putBoolean(AUTOMATIC_REQUEST_ATTEMPTED, true).commit()) {
                    mutableState.value = RootAccessState(RootAccessStatus.NOT_GRANTED, suPresent = true)
                    AndroidAgentLogger.warn("Root access probe outcome=preferences_unavailable")
                    return@launch
                }
                val result = runInterruptible {
                    BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { true }).use {
                        it.execute("id -u", timeoutMillis = 30_000L, maxOutputBytes = 256)
                    }
                }
                val granted = result.ok && result.stdout.trim() == "0"
                prefs.edit().putBoolean(LAST_GRANTED, granted).apply()
                mutableState.value = RootAccessState(
                    status = when {
                        granted -> RootAccessStatus.GRANTED
                        result.timedOut -> RootAccessStatus.TIMED_OUT
                        else -> RootAccessStatus.DENIED
                    },
                    suPresent = true,
                )
            } finally {
                mutableState.value = mutableState.value.copy(isChecking = false)
            }
        }.also { probeJob = it }
    }

    /**
     * 只判断 su 文件是否存在：部分 root 方案（例如基于 KernelPatch 的实现）会给 su 设置
     * 自定义 SELinux 类型，access(X_OK) 失败，用 canExecute() 会把可用环境误判成没有 root。
     * su 是否真的可用，交给后面实际执行 `id -u` 的结果决定。
     *
     * 候选来源包含 App 进程的 PATH 与 root 方案的标准目录 —— 只看 getenv("PATH") 会漏掉
     * su 只安装在 /data/adb/{ap,ksu}/bin 的配置（App 进程的 PATH 里没有这些目录）。
     */
    private fun suExists(): Boolean = SuBinary.exists()
}

internal fun shouldRequestRoot(explicit: Boolean, attempted: Boolean, wasGranted: Boolean): Boolean =
    explicit || !attempted || wasGranted
