package io.github.asagnc.eta

internal object AppProcessPolicy {
    fun shouldInitializeFullRuntime(processName: String, packageName: String): Boolean =
        processName == packageName
}
