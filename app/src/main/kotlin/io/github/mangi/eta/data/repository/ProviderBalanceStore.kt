package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.canQueryBalance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按需查询各 provider 余额并缓存结果，供供应商界面显示。
 *
 * 只在界面主动触发时拉取（进入页面 / 手动刷新），不做后台定时轮询，
 * 避免常年对中转站接口发请求造成浪费或被限流。
 */
internal object ProviderBalanceStore {
    private val balancesState = MutableStateFlow<Map<String, String>>(emptyMap())
    val balances: StateFlow<Map<String, String>> = balancesState.asStateFlow()
    private val refreshMutex = Mutex()

    /** 刷新全部启用余额查询的 provider（读取一次当前列表）。 */
    fun requestRefresh(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            refresh(ProviderRepository.allProviders())
        }
    }

    /** 只刷新单个 provider（用于详情页保存后即时更新）。 */
    fun refreshOne(scope: CoroutineScope, provider: ProviderSetting) {
        scope.launch(Dispatchers.IO) {
            if (!provider.canQueryBalance()) {
                balancesState.update { it - provider.id }
                return@launch
            }
            val value = ProviderBalanceFetcher.fetch(provider)
                .getOrNull()
                ?.let(::formatBalanceDisplay)
            if (value != null) {
                balancesState.update { it + (provider.id to value) }
            }
        }
    }

    private suspend fun refresh(providers: List<ProviderSetting>) {
        refreshMutex.withLock {
            val enabled = providers.filter(ProviderSetting::canQueryBalance)
            val enabledIds = enabled.map(ProviderSetting::id).toSet()
            coroutineScope {
                enabled.map { provider ->
                    async {
                        provider.id to ProviderBalanceFetcher.fetch(provider)
                            .getOrNull()
                            ?.let(::formatBalanceDisplay)
                    }
                }.awaitAll().forEach { (id, value) ->
                    if (value != null) {
                        balancesState.update { current -> current + (id to value) }
                    }
                }
            }
            balancesState.update { current -> current.filterKeys { it in enabledIds } }
        }
    }
}
