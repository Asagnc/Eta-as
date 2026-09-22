package io.github.asagnc.sta.agent.tool

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.github.asagnc.sta.StaApp
import io.github.asagnc.sta.agent.accessibility.AgentAccessibilityService
import io.github.asagnc.sta.agent.accessibility.AccessibilityProtectionClient
import io.github.asagnc.sta.agent.device.AgentNotificationHistoryService
import io.github.asagnc.sta.agent.device.RootAccess
import java.util.Locale
import org.json.JSONArray

/** 每轮冻结的运行条件；不包含用户开关，也不触发授权请求。 */
internal data class AgentToolCapabilities(
    val rootAvailable: Boolean,
    val lsposedAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = true,
    val accessibilityRecoveryAvailable: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val usageAllowed: Boolean = true,
    val locationAllowed: Boolean = true,
    val colorOs: Boolean = true,
) {
    fun unavailableCode(name: String): String? {
        val requirement = AgentToolRequirements.find(name) ?: return "UNKNOWN_TOOL"
        if (requirement.rootRequirement == RootRequirement.REQUIRED && !rootAvailable) return "ROOT_REQUIRED"
        if (requirement.lsposedRequirement == LsposedRequirement.REQUIRED && !lsposedAvailable) return "LSPOSED_REQUIRED"
        if (requirement.colorOs && !colorOs) return "DEVICE_UNSUPPORTED"
        // 屏幕类工具在无障碍不可用时可退回 root 通道执行（见 RootShellDeviceController 中的手势实现），
        // 因此只要具备 root 就不判定为不可用，避免有 root 的设备被完全挡住屏幕能力。
        if (requirement.accessibility && !accessibilityAvailable && !accessibilityRecoveryAvailable && !rootAvailable) {
            return "ACCESSIBILITY_UNAVAILABLE"
        }
        return when (requirement.systemAccess) {
            ToolSystemAccess.NONE -> null
            ToolSystemAccess.NOTIFICATIONS -> if (notificationsAllowed ||
                (rootAvailable && (name == "recent_notifications" ||
                    (name == "search_personal_orders" && colorOs)))
            ) null else "NOTIFICATION_ACCESS_REQUIRED"
            ToolSystemAccess.USAGE -> if (usageAllowed) null else "APP_USAGE_ACCESS_REQUIRED"
            ToolSystemAccess.LOCATION -> if (locationAllowed) null else "LOCATION_PERMISSION_REQUIRED"
        }
    }

    fun project(tools: JSONArray): JSONArray {
        val rootProjected = AgentToolRequirements.project(tools, rootAvailable)
        return JSONArray().also { visible ->
            for (index in 0 until rootProjected.length()) {
                val tool = rootProjected.getJSONObject(index)
                val name = tool.getJSONObject("function").getString("name")
                if (unavailableCode(name) == null) visible.put(tool)
            }
        }
    }

    companion object {
        fun isColorOsDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in
            setOf("oppo", "oneplus", "realme")

        /**
         * 服务实例引用只在 onServiceConnected 之后才有值，进程生命周期与系统绑定时机不一致时
         * 可能短时为空，仅凭它判断会把实际已启用的无障碍误判成不可用（进而让屏幕工具整批消失）。
         * 这里补上系统设置里的启用列表作为第二判据；服务是否真的在运行，由调用时的执行结果决定。
         */
        private fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            if (enabled.isEmpty()) return false
            val expected = ComponentName(context, AgentAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun capture(context: Context): AgentToolCapabilities = AgentToolCapabilities(
            rootAvailable = RootAccess.isGranted,
            lsposedAvailable = StaApp.serviceInstance != null,
            accessibilityAvailable = AgentAccessibilityService.isAvailable() ||
                isAccessibilityServiceEnabled(context),
            accessibilityRecoveryAvailable = StaApp.serviceInstance != null &&
                AccessibilityProtectionClient.isEnabled(context),
            notificationsAllowed = AgentNotificationHistoryService.isEnabled(context),
            usageAllowed = AgentPersonalContextTools.hasUsageAccess(context),
            locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
            colorOs = isColorOsDevice(),
        )
    }
}
