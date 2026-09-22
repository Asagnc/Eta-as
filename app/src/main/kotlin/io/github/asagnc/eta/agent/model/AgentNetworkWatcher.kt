package io.github.asagnc.eta.agent.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * 网络切换（Wi‑Fi ↔ 移动数据、断网重连）时清空模型 HTTP 连接池。
 *
 * 系统切换网络后，连接池里的空闲连接仍绑定在旧网络上，复用时会直接抛
 * 「连接被关闭/重置」类异常；OkHttp 不感知网络变化，也不会主动清理，
 * 所以只能由应用在网络变化时清一次。
 */
internal object AgentNetworkWatcher {

    @Volatile
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AgentHttpClient.evictConnections()
            }

            override fun onLost(network: Network) {
                AgentHttpClient.evictConnections()
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        }
    }
}
