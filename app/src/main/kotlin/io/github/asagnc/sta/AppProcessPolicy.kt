package io.github.asagnc.sta

internal object AppProcessPolicy {
    fun shouldInitializeFullRuntime(processName: String, packageName: String): Boolean =
        processName == packageName
}
