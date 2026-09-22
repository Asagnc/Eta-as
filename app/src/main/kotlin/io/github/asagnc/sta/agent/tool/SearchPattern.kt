package io.github.asagnc.sta.agent.tool

/**
 * BusyBox 的 `grep -E` 是 POSIX ERE，没有 PCRE 的简写字符类。
 *
 * 模型（以及人）习惯写 `\s`、`\d`、`\w`，直接传下去只会换来一句 `grep: bad regex`，
 * 白白浪费一轮。这里把它们翻成等价的 POSIX 字符类，其余语法原样保留。
 *
 * 约定：
 * - 已经转义的反斜杠不动，例如 `\\s` 表示"字面量的反斜杠 + s"，不会被改写；
 * - 方括号表达式内部改用不带外层方括号的写法（`[\s]` → `[[:space:]]`）；
 * - 否定简写（`\S`/`\D`/`\W`）在方括号内部无法直接表达，保持原样，由工具的
 *   BusyBox 提示兜底。
 */
internal object SearchPattern {

    fun toPosix(pattern: String): String {
        if (pattern.isEmpty()) return pattern
        val out = StringBuilder(pattern.length + 16)
        var index = 0
        var inBracket = false
        while (index < pattern.length) {
            val current = pattern[index]
            if (current == '\\' && index + 1 < pattern.length) {
                val next = pattern[index + 1]
                val replacement = replacementFor(next, inBracket)
                if (replacement != null) {
                    out.append(replacement)
                } else {
                    out.append(current).append(next)
                }
                index += 2
                continue
            }
            when (current) {
                '[' -> inBracket = true
                ']' -> inBracket = false
            }
            out.append(current)
            index++
        }
        return out.toString()
    }

    private fun replacementFor(letter: Char, inBracket: Boolean): String? = when (letter) {
        's' -> if (inBracket) "[:space:]" else "[[:space:]]"
        'd' -> if (inBracket) "0-9" else "[0-9]"
        'w' -> if (inBracket) "[:alnum:]_" else "[[:alnum:]_]"
        'S' -> if (inBracket) null else "[^[:space:]]"
        'D' -> if (inBracket) null else "[^0-9]"
        'W' -> if (inBracket) null else "[^[:alnum:]_]"
        else -> null
    }
}
