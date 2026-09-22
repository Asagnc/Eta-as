package io.github.asagnc.eta.data.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldKnowledgeLogicTest {

    private val now = 1_700_000_000_000L

    @Test
    fun encodesAndDecodesDependencies() {
        val dependencies = listOf(
            WorldKnowledgeLogic.Dependency("/a/b.kt", "10:abcd1234"),
            WorldKnowledgeLogic.Dependency("/c/d.kt", "20:ef567890"),
        )

        val decoded = WorldKnowledgeLogic.decodeDependencies(
            WorldKnowledgeLogic.encodeDependencies(dependencies),
        )

        assertEquals(dependencies, decoded)
    }

    @Test
    fun emptyDependenciesEncodeToBlank() {
        // 空串代表"没有依赖"，读回时不必区分空数组与缺失两种情况。
        assertEquals("", WorldKnowledgeLogic.encodeDependencies(emptyList()))
        assertTrue(WorldKnowledgeLogic.decodeDependencies("").isEmpty())
    }

    @Test
    fun skipsMalformedDependencyItems() {
        // 单条坏数据不该让整个结论失效：坏条目跳过，好条目照常读回。
        val decoded = WorldKnowledgeLogic.decodeDependencies(
            """[{"path":"/good.kt","fingerprint":"1:aa"},{"fingerprint":"2:bb"},{"path":""}]""",
        )

        assertEquals(1, decoded.size)
        assertEquals("/good.kt", decoded.single().path)
    }

    @Test
    fun toleratesUnparsableDependencyPayload() {
        assertTrue(WorldKnowledgeLogic.decodeDependencies("not json").isEmpty())
    }

    @Test
    fun fingerprintChangesWithContent() {
        val first = WorldKnowledgeLogic.fingerprint("hello")
        val second = WorldKnowledgeLogic.fingerprint("hello!")
        val same = WorldKnowledgeLogic.fingerprint("hello")

        assertEquals(first, same)
        assertNotEquals(first, second)
    }

    @Test
    fun fingerprintIncludesLength() {
        // 长度前缀让"内容不同但摘要撞车"这类极端情况也能区分。
        assertTrue(WorldKnowledgeLogic.fingerprint("abc").startsWith("3:"))
    }

    @Test
    fun noDependenciesIsAlwaysFresh() {
        val freshness = WorldKnowledgeLogic.checkFreshness(emptyList()) { null }

        assertEquals(WorldKnowledgeLogic.Freshness.FRESH, freshness)
    }

    @Test
    fun unchangedDependencyIsFresh() {
        val content = "file body"
        val dependencies = listOf(
            WorldKnowledgeLogic.Dependency("/x.kt", WorldKnowledgeLogic.fingerprint(content)),
        )

        val freshness = WorldKnowledgeLogic.checkFreshness(dependencies) { content }

        assertEquals(WorldKnowledgeLogic.Freshness.FRESH, freshness)
    }

    @Test
    fun changedDependencyIsStale() {
        val dependencies = listOf(
            WorldKnowledgeLogic.Dependency("/x.kt", WorldKnowledgeLogic.fingerprint("before")),
        )

        val freshness = WorldKnowledgeLogic.checkFreshness(dependencies) { "after" }

        // 文件改过 → 历史结论可能失效，必须让调用方看得出来。
        assertEquals(WorldKnowledgeLogic.Freshness.STALE, freshness)
    }

    @Test
    fun missingDependencyIsMissing() {
        val dependencies = listOf(
            WorldKnowledgeLogic.Dependency("/gone.kt", WorldKnowledgeLogic.fingerprint("x")),
        )

        val freshness = WorldKnowledgeLogic.checkFreshness(dependencies) { null }

        assertEquals(WorldKnowledgeLogic.Freshness.MISSING, freshness)
    }

    @Test
    fun staleBeatsMissingWhenBothPresent() {
        // 先命中已变更的依赖就返回 STALE，不必读完剩下的：两种判定都要阻止注入，
        // 但 STALE 的信息量更高（说明文件还在、只是变了）。
        val dependencies = listOf(
            WorldKnowledgeLogic.Dependency("/changed.kt", WorldKnowledgeLogic.fingerprint("old")),
            WorldKnowledgeLogic.Dependency("/gone.kt", WorldKnowledgeLogic.fingerprint("x")),
        )

        val freshness = WorldKnowledgeLogic.checkFreshness(dependencies) { path ->
            if (path == "/changed.kt") "new" else null
        }

        assertEquals(WorldKnowledgeLogic.Freshness.STALE, freshness)
    }

    @Test
    fun writesWhenNoExistingEntry() {
        assertTrue(WorldKnowledgeLogic.shouldWrite("failure", "sig", "summary", null))
    }

    @Test
    fun skipsIdenticalObservation() {
        val existing = entity(kind = "failure", signature = "sig", summary = "summary")

        // 重复观测不带来新信息，只会挤占查询窗口。
        assertFalse(WorldKnowledgeLogic.shouldWrite("failure", "sig", "summary", existing))
    }

    @Test
    fun writesWhenSummaryChanged() {
        val existing = entity(kind = "failure", signature = "sig", summary = "old")

        assertTrue(WorldKnowledgeLogic.shouldWrite("failure", "sig", "new", existing))
    }

    @Test
    fun writesWhenSignatureOrKindDiffers() {
        val existing = entity(kind = "failure", signature = "sig", summary = "summary")

        assertTrue(WorldKnowledgeLogic.shouldWrite("failure", "other", "summary", existing))
        assertTrue(WorldKnowledgeLogic.shouldWrite("finding", "sig", "summary", existing))
    }

    @Test
    fun normalizesLinuxWorkspacePathToAndroidForm() {
        // 子智能体按提示常写 /workspace/x，读取时要归一成 Android 侧路径，
        // 否则依赖指纹永远算不出来（文件不存在）。
        val normalized = WorldKnowledgeLogic.normalizePath("/workspace/Eta-src/app/Main.kt", ROOT)

        assertEquals("/data/local/tmp/eta/Eta-src/app/Main.kt", normalized)
    }

    @Test
    fun keepsAbsolutePathsOutsideWorkspace() {
        assertEquals("/etc/hosts", WorldKnowledgeLogic.normalizePath("/etc/hosts", ROOT))
    }

    @Test
    fun resolvesRelativePathsAgainstWorkspaceRoot() {
        assertEquals(
            "$ROOT/Eta-src/app/Main.kt",
            WorldKnowledgeLogic.normalizePath("Eta-src/app/Main.kt", ROOT),
        )
    }

    @Test
    fun stripsBackticksFromPaths() {
        // 证据行里路径常被反引号包着，解析出的 target 可能仍带引号。
        assertEquals("$ROOT/a.kt", WorldKnowledgeLogic.normalizePath("`a.kt`", ROOT))
    }

    @Test
    fun rejectsBlankPaths() {
        assertNull(WorldKnowledgeLogic.normalizePath("   ", ROOT))
        assertNull(WorldKnowledgeLogic.normalizePath("", ROOT))
    }

    @Test
    fun dependenciesSkipUnreadableFiles() {
        // 读不到的文件跳过，而不是记一条空指纹：空指纹会让新鲜度永远判 STALE，
        // 那等于把这条结论永久作废，比不记更糟。
        val dependencies = WorldKnowledgeLogic.dependenciesFor(
            paths = listOf("/exists.kt", "/missing.kt"),
        ) { path -> if (path == "/exists.kt") "body" else null }

        assertEquals(1, dependencies.size)
        assertEquals("/exists.kt", dependencies.single().path)
        assertEquals(WorldKnowledgeLogic.fingerprint("body"), dependencies.single().fingerprint)
    }

    @Test
    fun dependenciesRoundTripThroughFreshnessCheck() {
        // 端到端：算出的依赖，在原文件未变时判 FRESH，改过后判 STALE。
        val dependencies = WorldKnowledgeLogic.dependenciesFor(listOf("/a.kt")) { "v1" }

        assertEquals(
            WorldKnowledgeLogic.Freshness.FRESH,
            WorldKnowledgeLogic.checkFreshness(dependencies) { "v1" },
        )
        assertEquals(
            WorldKnowledgeLogic.Freshness.STALE,
            WorldKnowledgeLogic.checkFreshness(dependencies) { "v2" },
        )
    }

    private companion object {
        const val ROOT = "/data/local/tmp/eta"
    }

    private fun entity(kind: String, signature: String, summary: String) = WorldKnowledgeEntity(
        id = "id",
        kind = kind,
        signature = signature,
        createdAt = now,
        expiresAt = 0,
        originRun = "",
        originSession = "",
        originAgent = "",
        summary = summary,
        evidence = "",
        uncertainty = "",
        payload = "",
        dependencies = "",
        sensitive = false,
    )
}
