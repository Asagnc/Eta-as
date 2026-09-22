package io.github.asagnc.eta.agent.terminal

/** 一组 apt 源；按顺序尝试，成功者写回 sources.list。 */
internal data class AptMirror(
    val id: String,
    val sources: List<String>,
)

/** apt 系发行版（Debian / Ubuntu / Kali）的版本、软件源与 rootfs 制品。 */
internal object AptDistributionSpecs {
    fun versionOf(distribution: LinuxDistribution): String = when (distribution) {
        LinuxDistribution.DEBIAN -> "13"
        LinuxDistribution.UBUNTU -> "26.04"
        LinuxDistribution.KALI -> "2026.2"
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
        LinuxDistribution.UBUNTU -> listOf(
            AptMirror(
                id = "ustc",
                sources = listOf(
                    "deb https://mirrors.ustc.edu.cn/ubuntu resolute main restricted universe multiverse",
                    "deb https://mirrors.ustc.edu.cn/ubuntu resolute-updates main restricted universe multiverse",
                    "deb https://mirrors.ustc.edu.cn/ubuntu resolute-security main restricted universe multiverse",
                    "deb https://mirrors.ustc.edu.cn/ubuntu resolute-backports main restricted universe multiverse",
                ),
            ),
            AptMirror(
                id = "official",
                sources = listOf(
                    "deb http://archive.ubuntu.com/ubuntu resolute main restricted universe multiverse",
                    "deb http://archive.ubuntu.com/ubuntu resolute-updates main restricted universe multiverse",
                    "deb http://security.ubuntu.com/ubuntu resolute-security main restricted universe multiverse",
                    "deb http://archive.ubuntu.com/ubuntu resolute-backports main restricted universe multiverse",
                ),
            ),
        )
        LinuxDistribution.KALI -> listOf(
            AptMirror(
                id = "ustc",
                sources = listOf(
                    "deb https://mirrors.ustc.edu.cn/kali kali-rolling main contrib non-free non-free-firmware",
                ),
            ),
            AptMirror(
                id = "official",
                sources = listOf(
                    "deb http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware",
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
        LinuxDistribution.UBUNTU -> when (abi) {
            "arm64-v8a" -> ubuntuCloudArtifact(
                id = "ubuntu-resolute-minimal-arm64",
                fileName = "ubuntu-26.04-minimal-cloudimg-arm64-root.tar.xz",
                sha256 = "a6a90ffe1cabfd721b3f0472c1f3db7eeb4a16d5c00e7874510a72308088bc8e",
                sizeBytes = 102_536_744L,
                version = versionOf(distribution),
            )
            "x86_64" -> ubuntuCloudArtifact(
                id = "ubuntu-resolute-minimal-amd64",
                fileName = "ubuntu-26.04-minimal-cloudimg-amd64-root.tar.xz",
                sha256 = "580f77431750d95cc130df35e5c089d447aeaba7e6609ac8b9b445129758411a",
                sizeBytes = 139_914_932L,
                version = versionOf(distribution),
            )
            else -> null
        }
        LinuxDistribution.KALI -> when (abi) {
            "arm64-v8a" -> kaliArtifact(
                id = "kali-nethunter-2026.2-minimal-arm64",
                fileName = "kali-nethunter-rootfs-minimal-arm64.tar.xz",
                sha256 = "d6403a5da175df325611d23af4b92330856059c45454eced7f4cdf3ca6df2e4e",
                sizeBytes = 137_313_840L,
                version = versionOf(distribution),
            )
            "x86_64" -> kaliArtifact(
                id = "kali-nethunter-2026.2-minimal-amd64",
                fileName = "kali-nethunter-rootfs-minimal-amd64.tar.xz",
                sha256 = "4c0847c6409be9b65c9fe3bb7a8a6af9d265ab56837323e2e319bb733f1ed86a",
                sizeBytes = 144_746_948L,
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

    /**
     * Ubuntu 官方 cloud 镜像的 rootfs 归档。proot-distro 承接的制品停在 25.04，该版本已停止支持、
     * 包索引从各镜像撤下，因此固定改用仍在支持期内的 LTS 官方制品。
     */
    private fun ubuntuCloudArtifact(
        id: String,
        fileName: String,
        sha256: String,
        sizeBytes: Long,
        version: String,
    ): VerifiedArtifact {
        val path = "minimal/releases/resolute/release/${fileName}"
        return VerifiedArtifact(
            id = id,
            version = version,
            fileName = fileName,
            url = "https://cloud-images.ubuntu.com/${path}",
            sha256 = sha256,
            sizeBytes = sizeBytes,
            // 官方源在部分网络下带宽很低，接近 100 MB 的归档会耗尽下载总时长；
            // 中科大镜像同步了同一份归档，体积与摘要都与官方一致。
            preferredUrls = listOf("https://mirrors.ustc.edu.cn/ubuntu-cloud-images/${path}"),
        )
    }

    /** Kali 的 rootfs 由官方 NetHunter 项目发布；镜像目录按版本固定，保证校验值稳定。 */
    private fun kaliArtifact(
        id: String,
        fileName: String,
        sha256: String,
        sizeBytes: Long,
        version: String,
    ): VerifiedArtifact = VerifiedArtifact(
        id = id,
        version = version,
        fileName = fileName,
        url = "https://kali.download/nethunter-images/kali-$version/rootfs/$fileName",
        sha256 = sha256,
        sizeBytes = sizeBytes,
        preferredUrls = emptyList(),
    )

    private val GITHUB_PROXY_PREFIXES = listOf(
        "https://gh-proxy.com/",
    )
}
