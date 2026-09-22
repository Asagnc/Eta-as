package io.github.asagnc.eta.data.world

/**
 * 观测层的健康度追踪。
 *
 * 本层的读写失败一律降级（记录观测不该让正在跑的 run 失败），但**降级不能静默**：
 * 按 Google SRE 的四个黄金信号，除了显式的失败，还要盯住
 * 「implicitly — an HTTP 200 success response, but coupled with the wrong content」
 * 这类隐式错误。观测层读失败时返回「没有数据」，看起来是一次成功查询，实际是
 * 把「世界坏了」伪装成了「世界上没这东西」——模型会因此重复劳动，而人看不到任何线索。
 *
 * 所以每次降级都在这里留一笔，并让 `world_recall` / `world_trace` 的返回带上警告，
 * 使「观测层是否正常工作」成为可以直接看到的状态，而不必去翻 logcat。
 *
 * 计数器只增不减（进程内），因为它表达的是「本进程里发生过降级」，清空反而会掩盖问题。
 */
internal object WorldHealth {

    /** 一次降级记录。 */
    data class Incident(
        /** 触发降级的操作，例如 `write(failure)`。 */
        val operation: String,
        /** 异常类型名，例如 `SQLiteDiskIOException`。 */
        val errorType: String,
        val message: String,
        val atMs: Long,
        /** 是否属于预期内的失败（锁竞争、磁盘写满）。 */
        val expected: Boolean,
    )

    /** 是否属于「预期内」的失败：这类失败重试或跳过是合理的，不必当作异常信号。 */
    private val EXPECTED_ERROR_TYPES = setOf(
        // 并发写入时的锁竞争：写入方分布在多条路径上，偶发冲突是设计内的情况。
        "SQLiteDatabaseLockedException",
        "SQLiteBusyException",
        // 磁盘写满：本层是可丢弃的观测数据，空间不足时静默跳过是正确取舍。
        "SQLiteDiskIOException",
        "SQLiteFullException",
    )

    @Volatile
    private var degradedCount = 0

    @Volatile
    private var lastIncident: Incident? = null

    /** 只统计「预期外」的降级次数：预期内的降级不构成异常信号。 */
    @Volatile
    private var unexpectedCount = 0

    /**
     * 日志输出通道，默认走 Android 日志。
     *
     * 抽成可替换的通道而不是直接调 `android.util.Log`：那会让本类在纯 JVM 单测里
     * 一调就抛「not mocked」，而它本质上只是状态记录，不该被平台日志绑死。
     * 测试里换成空实现，真机上仍是正常日志。
     */
    @Volatile
    internal var logSink: (String) -> Unit = { message -> android.util.Log.w(LOG_TAG, message) }

    /**
     * 记一次降级。
     *
     * 预期内的失败（锁竞争、磁盘写满）只记日志，不算作异常信号——把它们也报出来会淹没
     * 真正需要关注的失败，SRE 那篇对此的原话是「you should never trigger an alert simply
     * because 'something seems a bit weird'」。预期外的失败（schema 损坏等）会累计到
     * [unexpectedCount]，并出现在给模型看的警告行里。
     */
    fun recordDegradation(operation: String, error: Throwable) {
        val type = error::class.java.simpleName
        degradedCount++
        val expected = type in EXPECTED_ERROR_TYPES
        if (!expected) unexpectedCount++
        lastIncident = Incident(
            operation = operation,
            errorType = type,
            message = error.message.orEmpty().take(MAX_MESSAGE_CHARS),
            atMs = System.currentTimeMillis(),
            expected = expected,
        )
        // 不打完整堆栈：本层的降级是可预期的高频事件，堆栈会把日志冲垮，
        // 留下的应该是「哪个操作、什么类型、什么原因」这三样可检索的信息。
        logSink(
            "观测层降级（${if (expected) "预期内" else "预期外"}）：$operation / $type / " +
                error.message.orEmpty().take(MAX_MESSAGE_CHARS),
        )
    }

    /** 本次进程内发生过多少次降级（含预期内）。 */
    fun degradedCount(): Int = degradedCount

    /** 本次进程内「预期外」的降级次数。 */
    fun unexpectedCount(): Int = unexpectedCount

    /** 最近一次降级；没有则为 null。 */
    fun lastIncident(): Incident? = lastIncident

    /**
     * 生成给调用方（模型或人）看的警告行；没有预期外降级时返回 null。
     *
     * 只在预期外降级时给警告：锁竞争这类预期内失败不影响结果完整性，
     * 每次都报会让警告变成噪声，而噪声会让真正的异常被忽略。
     *
     * 措辞刻意写明「结果可能不完整」而不是「查询失败」：查询确实成功了，
     * 只是它背后少了一部分数据——这正是 SRE 说的那种「看起来成功但内容不对」的情况。
     */
    fun warningLine(nowMs: Long = System.currentTimeMillis()): String? {
        if (unexpectedCount == 0) return null
        val incident = lastIncident ?: return null
        val age = WorldKnowledgeLogic.humanAge(nowMs - incident.atMs)
        return "注意：观测层本进程内已有 $unexpectedCount 次异常降级，" +
            "最近一次是 ${age}前（${incident.operation} / ${incident.errorType}），" +
            "以下结果可能不完整。"
    }

    /** 仅用于测试：把状态清回初始值。 */
    internal fun resetForTests() {
        degradedCount = 0
        unexpectedCount = 0
        lastIncident = null
        logSink = { message -> android.util.Log.w(LOG_TAG, message) }
    }

    /** 单条错误信息的字符上限：日志与警告行都不该被长堆栈撑爆。 */
    private const val MAX_MESSAGE_CHARS = 200

    private const val LOG_TAG = "EtaWorld"
}
