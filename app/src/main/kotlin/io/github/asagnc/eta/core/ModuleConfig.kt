package io.github.asagnc.eta.core

internal object ModuleConfig {
    const val TAG = "Eta"
    const val HOT_PATH_LOG_WINDOW_MS = 60_000L

    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    const val ETA_PACKAGE = "io.github.asagnc.eta"
    const val BREENO_PACKAGE = "com.heytap.speechassist"
    const val COLOROS_MEMORY_PACKAGE = "com.oplus.aimemory"
    const val XIAOAI_PACKAGE = "com.miui.voiceassist"
    const val XIAOMI_LAUNCHER_PACKAGE = "com.miui.home"
    const val XIAOMI_GLOBAL_LAUNCHER_PACKAGE = "com.mi.android.globallauncher"
    val XIAOMI_LAUNCHER_PACKAGES = setOf(XIAOMI_LAUNCHER_PACKAGE, XIAOMI_GLOBAL_LAUNCHER_PACKAGE)
    const val XIAOAI_CORE_PROCESS = "$XIAOAI_PACKAGE:core"
    val AGENT_RUNTIME_ENTRY_PACKAGES = setOf(BREENO_PACKAGE, XIAOAI_PACKAGE)
    const val GOOGLE_ASSISTANT_COMPONENT =
        "$GOOGLE_PACKAGE/com.google.android.voiceinteraction.GsaVoiceInteractionService"
    const val ETA_VOICE_INTERACTION_COMPONENT =
        "$ETA_PACKAGE/io.github.asagnc.eta.agent.voice.EtaVoiceInteractionService"
    const val ASSISTANT_ROLE = "android.app.role.ASSISTANT"
    const val SECURE_ASSISTANT = "assistant"
    const val SECURE_VOICE_INTERACTION_SERVICE = "voice_interaction_service"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val TIMINGS_TRACE_AND_SLOG_CLASS = "com.android.server.utils.TimingsTraceAndSlog"
    const val VOICE_INTERACTION_SERVICE = "voiceinteraction"
    const val VOICE_INTERACTION_MANAGER_SERVICE_CLASS =
        "com.android.server.voiceinteraction.VoiceInteractionManagerService"
    const val SYSTEM_SERVER_CLASS = "com.android.server.SystemServer"
    const val PHONE_WINDOW_MANAGER_CLASS = "com.android.server.policy.PhoneWindowManager"
    const val OP_LUS_SPEECH_HANDLER_CLASS =
        "com.android.server.policy.PhoneWindowManagerExtImpl\$OplusSpeechHandler"

    const val OP_LUS_ASSIST_MESSAGE_WHAT = 0x3F3
    const val INTERCEPT_DEDUP_WINDOW_MS = 1_000L

    const val SPOOF_MANUFACTURER = "samsung"
    const val SPOOF_BRAND = "samsung"
    const val SPOOF_MODEL = "SM-S928B"
    const val SPOOF_PRODUCT = "e3s"
    const val SPOOF_DEVICE = "e3s"
}
