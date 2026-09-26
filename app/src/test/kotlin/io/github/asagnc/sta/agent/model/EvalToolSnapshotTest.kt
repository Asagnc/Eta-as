package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.tool.AgentToolCapabilities
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 `evals/tools.json` 与当前工具目录一致。
 *
 * 外部评测脚本只拿到仓库里的这份快照，如果它和模型真正看到的 Schema 有偏差，
 * 评测结论就会指向错误的原因；所以快照必须由这份测试来兜住漂移。
 *
 * 重新生成（仅在校验失败、确认要更新快照时执行）：
 * `STA_EVAL_WRITE_SNAPSHOT=1 ./gradlew :app:testDebugUnitTest --tests "*EvalToolSnapshotTest*"`
 */
class EvalToolSnapshotTest {
    @Test
    fun evalToolSnapshotMatchesTheLiveCatalog() {
        val snapshotFile = snapshotFile()
        val live = snapshotJson()
        if (System.getenv("STA_EVAL_WRITE_SNAPSHOT") == "1") {
            snapshotFile.parentFile?.mkdirs()
            snapshotFile.writeText(live.toString(2) + "\n")
            return
        }
        assertTrue("缺少评测快照：${snapshotFile.path}", snapshotFile.isFile)
        assertEquals(
            "evals/tools.json 与当前工具目录不一致；确认无误后用 STA_EVAL_WRITE_SNAPSHOT=1 重新生成",
            live.toString(2),
            JSONObject(snapshotFile.readText()).toString(2),
        )
    }

    /** 任务集里引用的工具必须都在快照里，否则外部评测会在运行到一半时才报错。 */
    @Test
    fun everyTaskToolIsPresentInTheSnapshot() {
        val tasksFile = File(snapshotFile().parentFile, "tasks.json")
        assertTrue("缺少任务集：${tasksFile.path}", tasksFile.isFile)
        val tasks = JSONObject(tasksFile.readText()).getJSONArray("tasks")
        val referenced = linkedSetOf<String>()
        for (index in 0 until tasks.length()) {
            val tools = tasks.getJSONObject(index).optJSONArray("tools") ?: continue
            for (tool in 0 until tools.length()) referenced += tools.getString(tool)
        }
        assertTrue("任务集为空", referenced.isNotEmpty())
        assertEquals(emptySet<String>(), referenced - EVAL_TOOL_NAMES.toSet())
    }

    /**
     * 任务集选出的工具必须真的能被模型看到：只读文件工具已被 run_code 替代，
     * 若任务集还引用它们，外部评测发出的声明就不是模型实际看到的那一份。
     */
    @Test
    fun taskToolsAreVisibleInTheLiveCatalog() {
        val live = liveToolNames()
        val tasks = JSONObject(File(snapshotFile().parentFile, "tasks.json").readText())
            .getJSONArray("tasks")
        val referenced = linkedSetOf<String>()
        for (index in 0 until tasks.length()) {
            val tools = tasks.getJSONObject(index).optJSONArray("tools") ?: continue
            for (tool in 0 until tools.length()) referenced += tools.getString(tool)
        }
        assertEquals(emptySet<String>(), referenced - live)
    }

    private fun liveToolNames(): Set<String> {
        val tools = liveTools()
        return (0 until tools.length()).mapTo(linkedSetOf()) { index ->
            tools.getJSONObject(index).getJSONObject("function").getString("name")
        }
    }

    private fun liveTools(): JSONArray = AgentToolCatalog.build(
        terminalTools = true,
        browserTools = true,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true,
        skillGitHubInstall = true,
        memoryTools = true,
        capabilities = AgentToolCapabilities(rootAvailable = true),
    )

    private fun snapshotJson(): JSONObject {
        val tools = liveTools()
        val byName = (0 until tools.length()).associate { index ->
            val function = tools.getJSONObject(index).getJSONObject("function")
            function.getString("name") to function
        }
        val selected = JSONArray()
        EVAL_TOOL_NAMES.forEach { name ->
            val function = byName[name]
            assertTrue("工具目录里没有 $name，请更新快照清单", function != null)
            selected.put(
                JSONObject()
                    .put("name", function!!.getString("name"))
                    .put("description", function.getString("description"))
                    .put("parameters", function.getJSONObject("parameters")),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put(
                "generated_by",
                "EvalToolSnapshotTest：外部评测只使用这份快照，改动工具 Schema 时必须同步",
            )
            .put("tools", selected)
    }

    /** 从测试工作目录向上找到仓库根，避免硬编码绝对路径。 */
    private fun snapshotFile(): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                return File(directory, "evals/tools.json")
            }
            directory = directory.parentFile
        }
        error("找不到仓库根目录")
    }

    private val EVAL_TOOL_NAMES = listOf(
        "get_current_context",
        "get_current_location",
        "get_health_summary",
        "get_logcat",
        "device_status",
        "top_storage_apps",
        "search_apps",
        "launch_app",
        "run_code",
        "edit_file",
        "terminal",
        "observe_screen",
        "tap_element",
        "read_image",
        "wait_for_text",
        "open_uri",
        "memory_get",
        "skills_list",
        "skills_read",
        "skills_run",
        "task_plan",
        "run_stats",
    )
}
