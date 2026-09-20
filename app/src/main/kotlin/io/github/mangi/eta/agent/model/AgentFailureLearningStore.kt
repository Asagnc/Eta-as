package io.github.mangi.eta.agent.model

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 失败学习记录的落盘入口。
 *
 * 写在 Eta 自己的 files 目录（files/learnings/ERRORS.md），不进任何技能目录：
 * 技能属于用户内容，运行时不该往里写东西。文件用"追加一行"的方式增长，超过
 * [MAX_BYTES] 时只保留尾部，避免长期使用后无限膨胀。
 *
 * 记录失败不改变本轮行为，所以这里所有异常都吞掉——记录本身出问题不该影响正在跑的 run。
 */
internal object AgentFailureLearningStore {

    const val DIRECTORY_NAME = "learnings"
    const val FILE_NAME = "ERRORS.md"
    const val MAX_BYTES = 256 * 1024L

    private val writing = AtomicBoolean(false)

    fun record(filesDir: File?, entry: AgentFailureLearningRecord.Entry) {
        if (filesDir == null) return
        if (!writing.compareAndSet(false, true)) return
        try {
            val directory = File(filesDir, DIRECTORY_NAME)
            if (!directory.exists() && !directory.mkdirs()) return
            val file = File(directory, FILE_NAME)
            val existing = if (file.exists()) file.readText() else ""
            val recent = AgentFailureLearningRecord.parseRecentSignatures(existing, entry.timestampMs)
            if (!AgentFailureLearningRecord.shouldRecord(entry, recent)) return
            val prefix = if (existing.isBlank()) HEADER else ""
            val updated = prefix + existing + entry.toMarkdown()
            val trimmed = if (updated.length > MAX_BYTES) {
                updated.takeLast(MAX_BYTES.toInt())
            } else {
                updated
            }
            file.writeText(trimmed)
        } catch (_: Throwable) {
            // 记录失败不影响本轮运行
        } finally {
            writing.set(false)
        }
    }

    private const val HEADER =
        "# 失败学习记录\n\n" +
            "工具失败时由 runtime 自动追加（同一签名 10 分钟内只记一次）。\n" +
            "用途：排查同类问题时先在这里找有没有现成的复现与结论。\n\n"
}
