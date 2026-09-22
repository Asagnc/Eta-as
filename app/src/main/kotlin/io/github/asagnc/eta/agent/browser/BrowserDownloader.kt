package io.github.asagnc.eta.agent.browser

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** 已落盘的文件：远端下载与页面内取回共用同一套命名与扫描逻辑。 */
internal data class BrowserSavedFile(
    val file: File,
    val bytes: Long,
    val mimeType: String,
)

internal data class BrowserDownloadOutcome(
    val sourceUrl: String,
    val finalUrl: String,
    val file: File,
    val bytes: Long,
    val mimeType: String,
    val httpStatus: Int,
)

/**
 * 把下载内容落到 /storage/emulated/0/Download/Eta。
 *
 * 不用 WebView 自己的下载栈：这里要复用当前页面的 Cookie 与 User-Agent，并把落盘路径、字节数
 * 交给 Agent。写入公共下载目录依赖「所有文件访问」权限，是否具备由调用方先判断。
 */
internal object BrowserDownloader {
    private const val CONNECT_TIMEOUT_SECONDS = 20L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun download(
        context: Context,
        url: String,
        fileName: String?,
        cookieHeader: String?,
        userAgent: String?,
        referer: String?,
        proxyRule: String?,
    ): BrowserDownloadOutcome {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .apply {
                cookieHeader?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
            }
            .build()

        clientWithProxy(proxyRule).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败：服务端返回 HTTP ${response.code}")
            }
            val body = response.body
            val suggested = fileName
                ?: BrowserDownloadNaming.contentDispositionFileName(response.header("Content-Disposition"))
                ?: BrowserDownloadNaming.urlFileName(url)
            val saved = save(context, suggested) { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
            val mimeType = body.contentType()?.toString()?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME_TYPE
            MediaScannerConnection.scanFile(context, arrayOf(saved.file.absolutePath), arrayOf(mimeType), null)
            return BrowserDownloadOutcome(
                sourceUrl = url,
                finalUrl = response.request.url.toString(),
                file = saved.file,
                bytes = saved.file.length(),
                mimeType = mimeType,
                httpStatus = response.code,
            )
        }
    }

    /**
     * 代理是浏览器会话级状态：设置后下载与 WebView 走同一条网络路径，
     * 避免出现"浏览器能打开、下载却直连失败"的分叉。
     */
    /** 页面内取回的字节直接落盘，命名与重名处理跟远端下载一致。 */
    fun saveBytes(context: Context, fileName: String?, mimeType: String, bytes: ByteArray): BrowserSavedFile {
        val saved = save(context, fileName) { output -> output.write(bytes) }
        val resolved = mimeType.takeIf { it.isNotBlank() } ?: DEFAULT_MIME_TYPE
        MediaScannerConnection.scanFile(context, arrayOf(saved.file.absolutePath), arrayOf(resolved), null)
        return BrowserSavedFile(file = saved.file, bytes = saved.file.length(), mimeType = resolved)
    }

    private fun save(context: Context, fileName: String?, write: (java.io.OutputStream) -> Unit): BrowserSavedFile {
        val directory = File(
            Environment.getExternalStorageDirectory(),
            BrowserDownloadNaming.RELATIVE_DIRECTORY,
        )
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建下载目录 ${directory.absolutePath}")
        }
        val target = BrowserDownloadNaming.uniqueFile(
            directory,
            BrowserDownloadNaming.sanitizeFileName(fileName),
        )
        target.outputStream().use(write)
        return BrowserSavedFile(file = target, bytes = target.length(), mimeType = DEFAULT_MIME_TYPE)
    }

    private fun clientWithProxy(proxyRule: String?): OkHttpClient {
        val target = proxyRule?.let(BrowserProxyRules::parse) ?: return client
        val type = if (target.scheme == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return client.newBuilder()
            .proxy(Proxy(type, InetSocketAddress(target.host, target.port)))
            .build()
    }
}
