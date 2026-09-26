package io.github.asagnc.sta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProcessSupervisorTest {
    @Test
    fun missingSetsidFailsClosedWhenTreeFallbackIsDisabled() {
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = "sta-test-missing-setsid",
        )

        val process = supervisor.startShellProcess(
            identity = "user",
            command = "echo should-not-run",
            mergeStderr = false,
        )

        assertNull(process)
    }

    @Test
    fun rootAndroidPayloadUsesDiscoveredBusyBoxWithoutChangingUserShell() {
        val supervisor = ShellProcessSupervisor()

        val rootPayload = supervisor.buildAndroidPayload("root", "command -v xz")
        val userPayload = supervisor.buildAndroidPayload("user", "id")

        assertTrue(rootPayload.contains("/data/adb/magisk/busybox"))
        assertTrue(rootPayload.contains("ASH_STANDALONE=1"))
        assertEquals("sh -c 'id'", userPayload)
    }

    @Test
    fun linuxPayloadKeepsShellQuotesAndMountsPrivateExchangeDirectory() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.asagnc.sta/files/terminal/debian/rootfs",
            command = "printf '%s' \"hello\"",
        )

        assertFalse(payload.contains("\\\""))
        assertTrue(payload.contains("unshare -m --propagation private"))
        assertTrue(payload.contains("mount -t proc"))
        assertTrue(payload.contains("sta_mount_required /data/local/tmp"))
        // 沙箱根路径经 shellQuote 后带转义引号，按无引号片段断言；默认视图可写。
        assertTrue(payload.contains("/data/local/tmp/sta"))
        assertTrue(payload.contains("\$sta_rootfs/workspace\" bind"))
        assertTrue(payload.contains("sta_rootfs/workspace"))
        assertTrue(payload.contains("chroot"))
        assertTrue(payload.contains(LinuxEnvironmentPaths.READY_MARKER))
        assertTrue(payload.contains("/bin/busybox env -i"))
        // rootfs 里的 /bin/sh 是绝对符号链接，Android 侧就绪检查必须放行符号链接。
        assertTrue(payload.contains("[ -h \"\$sta_rootfs/bin/sh\" ]"))

        val debianPayload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.asagnc.sta/files/terminal/debian/rootfs",
            command = "python3 --version",
        )
        assertTrue(debianPayload.contains("/usr/bin/env -i"))
    }

    @Test
    fun linuxPayloadMountsTheSandboxRootReadOnlyForRestrictedViews() {
        val supervisor = ShellProcessSupervisor()

        val readOnly = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.asagnc.sta/files/terminal/debian/rootfs",
            command = "ls",
            sandbox = LinuxSandboxView("/data/local/tmp/sta", rootReadOnly = true),
        )

        // 只读必须两步：先按可写绑定，再 remount,ro,bind；一步写 rbind,ro 会被内核忽略。
        assertTrue(readOnly.contains("remount,ro,bind"))
        // Android 形态是同一份数据的另一个入口，必须一起只读，否则换个路径写法就绕过去了。
        assertTrue(readOnly.contains("\$sta_rootfs/workspace\" remount,ro,bind"))
        assertTrue(readOnly.contains("\$sta_rootfs/data/local/tmp\" remount,ro,bind"))
    }

    @Test
    fun linuxPayloadRemountsWritableSubtreesInsideAReadOnlyRoot() {
        val supervisor = ShellProcessSupervisor()

        val worktree = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.asagnc.sta/files/terminal/debian/rootfs",
            command = "ls",
            sandbox = LinuxSandboxView(
                rootPath = "/data/local/tmp/sta",
                rootReadOnly = true,
                writableSubPaths = listOf("/data/local/tmp/sta/sta-worktree-a"),
            ),
        )

        // 仓库不可改（remount 只读）、自己的 worktree 随后 bind 进来仍然可写。
        assertTrue(worktree.contains("\$sta_rootfs/workspace\" remount,ro,bind"))
        assertTrue(worktree.contains("\$sta_rootfs/data/local/tmp/sta/sta-worktree-a\" rbind"))
    }

    @Test
    fun ptyLauncherWrapsPayloadWithScriptAndSetsSize() {
        val supervisor = ShellProcessSupervisor()
        val launcher = supervisor.buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "sta-pty-test.owner"),
            ownershipToken = "token123",
            command = null,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
            pty = true,
            ptyCols = 120,
            ptyRows = 40,
        )

        assertTrue(launcher.contains("script -qfc"))
        assertTrue(launcher.contains("stty rows 40 cols 120"))
        assertTrue(launcher.contains("TERM=xterm-256color"))
        assertTrue(launcher.contains("/dev/null"))
    }
}
