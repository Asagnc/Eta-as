package io.github.asagnc.eta.agent.tool

/**
 * 工具输出里的凭据形态字符串过滤。
 *
 * 这是最后一道兜底：无论凭据从哪个通道进入工具输出，都可能被这一层截住。它按形态匹配
 * （各家密钥前缀、Authorization 头、Bearer 令牌），因此既不是完整清单，也不阻止模型
 * 把密钥拆开或转码后输出；真正的修复是让凭据不可读。
 */
internal object CredentialRedactor {
    const val PLACEHOLDER = "[已过滤的凭据]"

    private data class Rule(val pattern: Regex, val keepsPrefix: Boolean = false)

    private val RULES = listOf(
        Rule(Regex("""(?i)\bsk-ant-[A-Za-z0-9_\-]{16,}""")),
        Rule(Regex("""(?i)\b(?:sk|rk|pk)[-_][A-Za-z0-9_\-]{16,}""")),
        Rule(Regex("""\bAIza[0-9A-Za-z_\-]{20,}""")),
        Rule(Regex("""(?i)(authorization\s*[:=]\s*)(?:bearer\s+)?[A-Za-z0-9._\-]{20,}"""), keepsPrefix = true),
        Rule(Regex("""(?i)(x-api-key\s*[:=]\s*)[A-Za-z0-9._\-]{16,}"""), keepsPrefix = true),
        Rule(Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]{20,}""")),
    )

    fun redact(text: String): String {
        if (text.isBlank()) return text
        var result = text
        RULES.forEach { rule ->
            result = rule.pattern.replace(result) { match ->
                if (rule.keepsPrefix && match.groupValues.size > 1) {
                    match.groupValues[1] + PLACEHOLDER
                } else {
                    PLACEHOLDER
                }
            }
        }
        return result
    }
}
