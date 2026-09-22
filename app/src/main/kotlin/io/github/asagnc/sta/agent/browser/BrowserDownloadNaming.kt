package io.github.asagnc.sta.agent.browser

import java.io.File
import java.net.URLDecoder

/**
 * 下载文件名与落盘目标的纯逻辑，不碰 Android API。
 *
 * 文件名来自服务端（Content-Disposition）或 URL，必须先去掉目录分隔符与控制字符，否则可能
 * 写到目标目录之外；重名时不覆盖已有文件，追加序号。
 */
internal object BrowserDownloadNaming {
    const val RELATIVE_DIRECTORY = "Download/Sta"
    const val FALLBACK_NAME = "download"

    private const val MAX_FILE_NAME_CHARS = 120
    private const val MAX_DUPLICATE_INDEX = 999
    private val INVALID_CHARS = setOf(':', '*', '?', '"', '<', '>', '|')

    fun sanitizeFileName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in INVALID_CHARS }
            .trim()
            .trimStart('.')
            .take(MAX_FILE_NAME_CHARS)
        return cleaned.ifBlank { FALLBACK_NAME }
    }

    fun uniqueFile(directory: File, fileName: String): File {
        val candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length < fileName.length }
        var index = 1
        while (index <= MAX_DUPLICATE_INDEX) {
            val suffixed = if (extension == null) "$base ($index)" else "$base ($index).$extension"
            val next = File(directory, suffixed)
            if (!next.exists()) return next
            index++
        }
        return candidate
    }

    /** 解析 Content-Disposition，优先 RFC 5987 的 filename*，其次普通 filename。 */
    fun contentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.split(';')
        parts.firstOrNull { it.trim().startsWith("filename*=", ignoreCase = true) }?.let { part ->
            val value = part.substringAfter('=').trim().trim('"')
            // 形如 UTF-8''%E4%B8%AD.txt：charset 与 language 之后才是百分号编码的文件名
            val encoded = value.substringAfter('\'').substringAfter('\'')
            if (encoded.isNotEmpty()) {
                val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded
            }
        }
        return parts.firstOrNull { part ->
            val trimmed = part.trim()
            trimmed.startsWith("filename=", ignoreCase = true) && !trimmed.startsWith("filename*=", ignoreCase = true)
        }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
    }

    /** URL 末段作为兜底文件名，先去掉查询串与锚点。 */
    fun urlFileName(url: String): String? =
        url.substringBefore('#')
            .substringBefore('?')
            .substringAfterLast('/')
            .takeIf { it.isNotEmpty() }
}
