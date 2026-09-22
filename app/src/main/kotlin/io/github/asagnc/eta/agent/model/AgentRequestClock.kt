package io.github.asagnc.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 把当前时间附在请求的最后一条用户消息前，让模型知道自己「此刻」是什么时间。
 *
 * 放在消息序列末尾而不是系统提示里：提示缓存按前缀匹配，时间一旦写进系统提示，
 * 每轮刷新都会让整段历史失去缓存命中；放在最后一条用户消息上，系统提示与历史仍能命中。
 */
internal object AgentRequestClock {

    const val PREFIX = "当前时间："

    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun line(now: ZonedDateTime = ZonedDateTime.now()): String {
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        return "$PREFIX${now.format(FORMAT)}（${now.zone.id}，$weekday）"
    }

    /**
     * 附在请求数组的**最后一条**消息上：序列前面的系统提示与历史保持逐字节不变，
     * 服务端前缀缓存才不会因为「时间」每轮变化而整段失效。
     * 传进来的是本轮请求的副本，历史消息本身不会被改写。
     */
    fun attach(messages: JSONArray, now: ZonedDateTime = ZonedDateTime.now()) {
        val index = messages.length() - 1
        if (index < 0) return
        val message = messages.optJSONObject(index) ?: return
        val text = line(now)
        val content = message.opt("content")
        when {
            content is String -> {
                val stripped = content.lineSequence()
                    .filterNot { it.startsWith(PREFIX) }
                    .joinToString("\n")
                    .trimStart('\n')
                message.put("content", if (stripped.isBlank()) text else "$text\n$stripped")
            }

            content is JSONArray -> {
                val rebuilt = JSONArray().put(JSONObject().put("type", "text").put("text", text))
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text" && part.optString("text").startsWith(PREFIX)) continue
                    rebuilt.put(part)
                }
                message.put("content", rebuilt)
            }
        }
    }
}
