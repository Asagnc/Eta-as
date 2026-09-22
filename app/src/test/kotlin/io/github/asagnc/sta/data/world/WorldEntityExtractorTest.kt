package io.github.asagnc.sta.data.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实体抽取的规则测试。
 *
 * 抽取规则错了会让整张图建立在意料之外的结构上，而图错了聚类必然错——这类错误在
 * 真机上表现为「聚类结果有点怪」，极难定位。所以这里把每条判据都钉死。
 */
class WorldEntityExtractorTest {

    private val root = "/data/local/tmp/eta"

    @Test
    fun `从证据里抽出文件路径并归一`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "工具描述在 AgentLocalTools.kt 里",
            evidence = "read_file /workspace/Sta-src/app/src/main/kotlin/io/github/asagnc/eta/agent/model/AgentLocalTools.kt:120",
            workspaceRoot = root,
        )
        // Linux 侧路径应被归一成 Android 侧，与 WorldKnowledgeLogic 同一口径。
        assertTrue(bundle.files.any { it.startsWith("$root/Sta-src/") })
    }

    @Test
    fun `文件路径的内部片段不会被当成符号`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "只提到文件，没有单独提到符号",
            evidence = "见 AgentToolRequirements.kt:88",
            workspaceRoot = root,
        )
        // AgentToolRequirements 是文件名里的片段，不是被引用的符号。把它当符号会让
        // 所有提到同一文件的观测都共享这批符号，等于把文件边重复计一遍。
        assertFalse("文件名片段不应进入符号集合", bundle.symbols.contains("AgentToolRequirements"))
    }

    @Test
    fun `驼峰标识符会被抽成符号`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "AgentToolRequirements 里用 registerParallelSafe 注册并发白名单",
            evidence = "",
            workspaceRoot = root,
        )
        assertTrue(bundle.symbols.contains("AgentToolRequirements"))
        assertTrue(bundle.symbols.contains("registerParallelSafe"))
    }

    @Test
    fun `反引号包裹的符号会被抽出`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "字段名是 `reasoning_effort`，注意它不带驼峰",
            evidence = "",
            workspaceRoot = root,
        )
        assertTrue(bundle.symbols.contains("reasoning_effort"))
    }

    @Test
    fun `全大写常量会被抽出`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "上限是 MAX_RECALL_CHARS，别超过它",
            evidence = "",
            workspaceRoot = root,
        )
        assertTrue(bundle.symbols.contains("MAX_RECALL_CHARS"))
    }

    @Test
    fun `常见技术词不会成为符号`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "Android 上用 Kotlin 写 GitHub 的 API 调用",
            evidence = "",
            workspaceRoot = root,
        )
        // 这些词形状像符号但没有区分度：它们在任何技术文本里都出现，加进来等于给
        // 所有节点之间连一条弱边。形状过滤 + 停用词表两道都要挡住。
        assertFalse(bundle.symbols.contains("Android"))
        assertFalse(bundle.symbols.contains("Kotlin"))
        assertFalse(bundle.symbols.contains("GitHub"))
    }

    @Test
    fun `过短的标识符不会成为符号`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "用了 Id 和 Ok 两个变量",
            evidence = "",
            workspaceRoot = root,
        )
        assertFalse(bundle.symbols.contains("Id"))
        assertFalse(bundle.symbols.contains("Ok"))
    }

    @Test
    fun `纯小写单词不会成为符号`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "这里应该用 document 而不是 docs",
            evidence = "",
            workspaceRoot = root,
        )
        assertFalse(bundle.symbols.contains("document"))
    }

    @Test
    fun `空输入返回空结果而不是抛异常`() {
        val bundle = WorldEntityExtractor.extract("", "", "", root)
        assertTrue(bundle.isEmpty)
        assertEquals(0, bundle.all().size)
    }

    @Test
    fun `相对路径按工作区根补全`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "改的是 app/build.gradle",
            evidence = "",
            workspaceRoot = root,
        )
        assertEquals(listOf("$root/app/build.gradle"), bundle.files)
    }

    @Test
    fun `同一路径重复出现只抽一次`() {
        val bundle = WorldEntityExtractor.extract(
            conclusion = "看 AgentLoop.kt，重点还是 AgentLoop.kt",
            evidence = "AgentLoop.kt:1",
            workspaceRoot = root,
        )
        assertEquals(1, bundle.files.count { it.endsWith("AgentLoop.kt") })
    }

    @Test
    fun `实体 id 由种类与名字决定`() {
        // 同一个文件被多条观测提到时必须落到同一行，否则「共享文件」这个判断在库里
        // 根本体现不出来。
        assertEquals(
            WorldGraphStore.entityId(WorldEntityExtractor.EntityKind.FILE, "/a/b.kt"),
            WorldGraphStore.entityId(WorldEntityExtractor.EntityKind.FILE, "/a/b.kt"),
        )
        // 同名但不同种类必须是两个实体。
        assertFalse(
            WorldGraphStore.entityId(WorldEntityExtractor.EntityKind.FILE, "X") ==
                WorldGraphStore.entityId(WorldEntityExtractor.EntityKind.SYMBOL, "X"),
        )
    }
}