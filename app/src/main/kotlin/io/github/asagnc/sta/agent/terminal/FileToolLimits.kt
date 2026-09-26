package io.github.asagnc.sta.agent.terminal

/**
 * 文件工具的公共上限与命名约定。
 *
 * root 通道（shell）与免 root 通道（Kotlin）必须给出同一份契约：同一句 read_file 不该因为
 * 身份不同而截断在不同位置，write_file 也不该在一边原子、另一边留半截文件。
 */
internal object FileToolLimits {
    /** 字节模式一次最多读多少字节。 */
    const val MAX_READ_BYTES = 256 * 1024

    /** 一次写入的字节上限。 */
    const val MAX_WRITE_BYTES = 512 * 1024

    /**
     * 文本类结果（读取/检索/列目录）一次最多返回多少字符。
     *
     * 取 40000 是为了装得下单个源码文件：本仓库最大的 AgentLoop.kt 是 33626 字符，
     * 16000 的旧上限让它物理上读不完一个文件，模型只能分段读、再补读。
     */
    const val MAX_OUTPUT_CHARS = 40_000

    /** 写文件的同目录临时文件后缀：写完立即被 rename 顶替，失败则删掉。 */
    const val WRITE_TEMP_SUFFIX = ".sta-tmp"

    /**
     * 错误文案上限。300 字符装不下 PathHints 的「路径不存在 + 最近可用目录 + 其下条目」，
     * 截掉尾巴等于把唯一能行动的信息截掉。
     */
    const val MAX_ERROR_CHARS = 1_000
}
