package io.github.asagnc.sta.agent.browser

import java.util.Locale

/**
 * 代理规则的归一化。
 *
 * ProxyConfig 只接受 `[scheme://]host[:port]`（scheme 限 HTTP、HTTPS、SOCKS），带路径、查询串、
 * 用户信息或空白的写法会在 setProxyOverride 时抛 IllegalArgumentException，这里先挡掉并统一成
 * 带 scheme 的规范形式。
 */
internal object BrowserProxyRules {
    /** 供 OkHttp 使用的主机与端口；IPv6 在这里不带方括号，其余场景与规范化规则一致。 */
    internal data class ProxyTarget(
        val scheme: String,
        val host: String,
        val port: Int,
    )

    private val HOST = Regex("[A-Za-z0-9._-]+")
    private val IPV6 = Regex("[0-9A-Fa-f:.]+")

    fun parse(rule: String): ProxyTarget? {
        val normalized = normalize(rule) ?: return null
        val scheme = normalized.substringBefore("://")
        val authority = normalized.substringAfter("://")
        val host = authority.substringBeforeLast(':', authority).removeSurrounding("[", "]")
        val port = authority.substringAfterLast(':', "").toIntOrNull() ?: defaultPort(scheme)
        return ProxyTarget(scheme = scheme, host = host, port = port)
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "https" -> 443
        "socks" -> 1080
        else -> 80
    }

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null

        val schemeSeparator = trimmed.indexOf("://")
        val scheme = if (schemeSeparator >= 0) {
            when (trimmed.substring(0, schemeSeparator).lowercase(Locale.ROOT)) {
                "http" -> "http"
                "https" -> "https"
                // ProxyConfig 只认 socks，socks5 是同一套规则的人写法
                "socks", "socks5" -> "socks"
                else -> return null
            }
        } else {
            "http"
        }

        val target = if (schemeSeparator >= 0) trimmed.substring(schemeSeparator + 3) else trimmed
        if (target.isEmpty()) return null

        // host 是输出用的形式：IPv6 字面量必须保留方括号，否则 host:port 会出现歧义。
        val host: String
        val port: Int?
        if (target.startsWith("[")) {
            val closing = target.indexOf(']')
            if (closing <= 1) return null
            val literal = target.substring(1, closing)
            if (!IPV6.matches(literal)) return null
            host = "[$literal]"
            val rest = target.substring(closing + 1)
            port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
        } else {
            val parts = target.split(':')
            if (parts.size > 2) return null
            host = parts[0]
            if (!HOST.matches(host)) return null
            port = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else null
        }

        if (port != null && port !in 1..65_535) return null
        return if (port == null) "$scheme://$host" else "$scheme://$host:$port"
    }
}
