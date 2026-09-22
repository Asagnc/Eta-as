package io.github.asagnc.sta.agent.model

import android.content.Context
import android.content.SharedPreferences

/**
 * 记住「服务端实际接受过的上下文规模」。
 *
 * 中转站与自建网关经常虚标窗口：模型配置写着 1M，实际几百 K 就返回上下文超限。
 * 只按声明值算，进度条永远到不了触发线，每次新开会话都要先撞一次墙才知道真实上限。
 * 这里把实测上限按 provider + 模型存下来，下次开局就按更小的窗口判断与提示。
 *
 * 只存内存或只存单次 run 都不够：撞墙发生在会话开头，而会话结束进程状态就没了。
 * 声明窗口被改动过时旧值作废——用户很可能正是去把窗口改小了，此时要以新声明为准。
 */
internal object AgentContextCeilingStore {
    private const val PREFS_NAME = "eta_context_ceiling"
    private var preferences: SharedPreferences? = null

    fun init(context: Context) {
        if (preferences == null) {
            preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun keyFor(providerId: String, model: String): String = "$providerId|$model"

    /** 返回上次学到的实测上限；声明窗口与记录不一致（用户改过配置）时返回 null。 */
    fun ceilingFor(key: String, declaredWindow: Int?): Int? {
        val store = preferences ?: return null
        val raw = store.getString(key, null) ?: return null
        val declared = raw.substringBefore('|').toIntOrNull() ?: return null
        val ceiling = raw.substringAfter('|', "").toIntOrNull() ?: return null
        // 声明窗口为空的模型（provider 没给窗口）同样要能读回实测上限：否则它每次都退回
        // 「没有窗口」状态，不显示占用、也不按比例压缩，只能等服务端拒收才发现超限。
        if ((declaredWindow ?: 0) != declared) return null
        return ceiling.takeIf { it > 0 }
    }

    fun record(key: String, declaredWindow: Int?, tokens: Int) {
        val store = preferences ?: return
        if (tokens <= 0) return
        store.edit().putString(key, "${declaredWindow ?: 0}|$tokens").apply()
    }
}
