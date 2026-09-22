package io.github.asagnc.sta.config

internal enum class PowerAssistantTarget(
    val persistedValue: String,
) {
    ETA("eta"),
    OEM("oem"),
    ;

    companion object {
        fun resolve(
            persistedValue: String?,
        ): PowerAssistantTarget = entries.firstOrNull {
            it.persistedValue == persistedValue
        } ?: OEM
    }
}
