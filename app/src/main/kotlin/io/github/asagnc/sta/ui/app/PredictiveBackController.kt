package io.github.asagnc.sta.ui.app

import android.content.pm.ApplicationInfo
import io.github.asagnc.sta.core.AndroidAgentLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass

internal object PredictiveBackController {
    private const val METHOD_NAME = "setEnableOnBackInvokedCallback"

    // minSdk 36 起必然走 HiddenApiBypass 路径（该隐藏 API 从 API 34 起才存在），
    // 不再需要「低版本直接当成功」的短路。
    fun apply(applicationInfo: ApplicationInfo, enabled: Boolean): Boolean {
        return runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/ApplicationInfo;->$METHOD_NAME",
            )
            ApplicationInfo::class.java.getDeclaredMethod(
                METHOD_NAME,
                Boolean::class.javaPrimitiveType,
            ).apply {
                isAccessible = true
                invoke(applicationInfo, enabled)
            }
        }.fold(
            onSuccess = { true },
            onFailure = {
                AndroidAgentLogger.warn("Predictive back setting could not be applied")
                false
            },
        )
    }
}
