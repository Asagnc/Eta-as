package io.github.asagnc.sta.agent.terminal

/** 一组 apt 源；按顺序尝试，成功者写回 sources.list。 */
internal data class AptMirror(
    val id: String,
    val sources: List<String>,
)

/**
 * apt 系发行版的版本、软件源与 rootfs 制品。
 *
 * Sta 只保留 Debian 一个用户态发行版：Android 内核恒由宿主提供，发行版只替换 rootfs，
 * 因此发行版之间不存在内核能力差异。安全工具（nmap / sqlmap / radare2 等）在 Debian
 * 官方源中均已收录，由 security/ctf 工具集按需安装，无需另立发行版。
 */
internal object AptDistributionSpecs {
    fun versionOf(distribution: LinuxDistribution): String = when (distribution) {
        LinuxDistribution.DEBIAN -> "13"
    }

    /** 真机链路只保留一个国内镜像和官方源，避免慢镜像串行拖长安装。 */
    fun mirrorsOf(distribution: LinuxDistribution): List<AptMirror> = when (distribution) {
        LinuxDistribution.DEBIAN -> listOf(
            AptMirror(
                id = "ustc",
                sources = listOf(
                    "deb https://mirrors.ustc.edu.cn/debian trixie main",
                    "deb https://mirrors.ustc.edu.cn/debian trixie-updates main",
                    "deb https://mirrors.ustc.edu.cn/debian-security trixie-security main",
                ),
            ),
            AptMirror(
                id = "official",
                sources = listOf(
                    "deb https://deb.debian.org/debian trixie main",
                    "deb https://deb.debian.org/debian trixie-updates main",
                    "deb https://security.debian.org/debian-security trixie-security main",
                ),
            ),
        )
    }

    fun artifactOf(distribution: LinuxDistribution, abi: String): VerifiedArtifact? = when (distribution) {
        LinuxDistribution.DEBIAN -> when (abi) {
            "arm64-v8a" -> prootDistroArtifact(
                id = "debian-trixie-aarch64-pd-v4.29.0",
                fileName = "debian-trixie-aarch64-pd-v4.29.0.tar.xz",
                sha256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
                sizeBytes = 35_409_704L,
                version = versionOf(distribution),
            )
            "x86_64" -> prootDistroArtifact(
                id = "debian-trixie-x86_64-pd-v4.29.0",
                fileName = "debian-trixie-x86_64-pd-v4.29.0.tar.xz",
                sha256 = "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716",
                sizeBytes = 36_728_936L,
                version = versionOf(distribution),
            )
            else -> null
        }
    }

    private fun prootDistroArtifact(
        id: String,
        fileName: String,
        sha256: String,
        sizeBytes: Long,
        version: String,
    ): VerifiedArtifact {
        val officialUrl = "https://github.com/termux/proot-distro/releases/download/v4.29.0/$fileName"
        return VerifiedArtifact(
            id = id,
            version = version,
            fileName = fileName,
            url = officialUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            preferredUrls = GITHUB_PROXY_PREFIXES.map { prefix -> prefix + officialUrl },
        )
    }

    private val GITHUB_PROXY_PREFIXES = listOf(
        "https://gh-proxy.com/",
    )
}
