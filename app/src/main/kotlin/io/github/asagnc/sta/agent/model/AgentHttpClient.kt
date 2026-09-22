package io.github.asagnc.sta.agent.model

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 模型流与普通 HTTP 请求共享连接池，但独立设置读取等待与重试策略。
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 保留 OkHttp 默认的连接层重试（原为 false）。
            // 连接池里的空闲连接可能已被对端静默回收且不发 Connection: close，
            // 实测部分中转网关空闲约 150s 后即单向关闭连接，复用时首次写入/读取
            // 直接抛 IOException，于是模型请求表现为「暂时中断」并被应用层重试，
            // 用户看到无谓的重试提示。OkHttp 仅在请求尚未被服务端受理时换新连接
            // 重试（陈旧连接、连接失败、HTTP/2 REFUSED_STREAM），不会重复触发推理。
            .retryOnConnectionFailure(true)
            .build()
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 网络切换（Wi‑Fi ↔ 移动数据）后，连接池里的空闲连接仍绑定在旧网络上，
     * 复用时会直接抛「连接被关闭/重置」类异常。系统切换网络不会通知 OkHttp，
     * 由调用方在网络变化时主动清空连接池。
     * [modelClient] 由 [client] 派生、共享同一个连接池，清一次即可。
     */
    fun evictConnections() {
        runCatching { client.connectionPool.evictAll() }
    }
}
