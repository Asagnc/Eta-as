package io.github.asagnc.sta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * su 定位策略的回归测试。
 *
 * 背景：KernelPatch 系 root 方案（APatch / FolkPatch）只对授权名单内的进程暴露 su。
 * 走 PATH 查找时 execvp 会因命中隐藏而返回 ENOENT，表现为 "su: inaccessible or not found"，
 * 即使应用其实已获授权。绝对路径 execve 能绕过这条判定，所以解析必须以绝对路径优先。
 */
class SuBinaryTest {
    @Test fun absolutePathWinsOverPathLookup() {
        val resolved = SuBinary.resolve { it == "/system/bin/su" }
        assertEquals("/system/bin/su", resolved)
    }

    @Test fun candidatesAreTriedInDeclaredOrder() {
        // 同时存在多个时，先声明的优先。
        val present = setOf("/system/xbin/su", "/data/adb/ap/bin/su")
        assertEquals("/system/xbin/su", SuBinary.resolve { it in present })
    }

    @Test fun kernelPatchInstallLocationIsReachable() {
        // su 只装在 APatch/FolkPatch 目录时也要能解析出绝对路径。
        val resolved = SuBinary.resolve { it == "/data/adb/ap/bin/su" }
        assertEquals("/data/adb/ap/bin/su", resolved)
    }

    @Test fun fallsBackToBareNameWhenNothingExists() {
        // 所有绝对路径都不存在时回退裸名，交给 PATH；行为与旧实现一致，不制造新的失败模式。
        assertEquals(SuBinary.FALLBACK_NAME, SuBinary.resolve { false })
        assertEquals("su", SuBinary.resolve { false })
    }

    @Test fun existsDetectsSuOutsideAppPath() {
        // App 进程的 PATH 通常不含 /data/adb/ap/bin；只看 getenv PATH 会漏判。
        val found = SuBinary.exists(pathEnv = "/system/bin:/vendor/bin") { it == "/data/adb/ap/bin/su" }
        assertTrue(found)
    }

    @Test fun existsConsultsExtraSearchDirsWhenPathIsEmpty() {
        assertTrue(SuBinary.exists(pathEnv = null) { it == "/system/bin/su" })
        assertTrue(SuBinary.exists(pathEnv = "") { it == "/debug_ramdisk/su" })
    }

    @Test fun existsUsesPathEnvEntries() {
        assertTrue(SuBinary.exists(pathEnv = "/opt/custom/bin") { it == "/opt/custom/bin/su" })
    }

    @Test fun existsIsFalseWhenSuIsAbsentEverywhere() {
        assertFalse(SuBinary.exists(pathEnv = null) { false })
    }
}