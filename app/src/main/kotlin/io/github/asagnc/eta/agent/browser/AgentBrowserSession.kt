package io.github.asagnc.eta.agent.browser

import io.github.asagnc.eta.agent.model.AgentBrowserToolCatalog
import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.ScriptHandler
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class BrowserSessionSnapshot(
    val available: Boolean = false,
    val url: String = "",
    val displayUrl: String = "",
    val host: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isPageVisible: Boolean = false,
    val hasCommittedPage: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    val isUserControlling: Boolean = false,
    val lastAgentRunId: String? = null,
    val lastAgentToolCallId: String? = null,
    val proxy: String? = null,
)

internal data class BrowserImage(
    val dataUrl: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
)

internal data class BrowserToolResult(
    val content: String,
    val images: List<BrowserImage> = emptyList(),
)

/**
 * Eta 的共享 Agent 浏览器。
 *
 * WebView 可以离屏工作，也可以临时挂到 App 的浏览器页面供用户接管。
 */
// 共享 WebView 必须跨工具调用存活；Activity 容器只在浏览器页面可见时持有，并在 dispose 时解绑。
@SuppressLint("StaticFieldLeak")
internal object AgentBrowserSession {
    private const val TOOL_NAME = "browser_use"
    private const val DEFAULT_TEXT_CHARS = 8_000
    private const val MAX_TEXT_CHARS = 12_000

    /** 低于这个字符数的正文提取视为失败：与其交给调用方一个空字符串，不如明确报错。 */
    private const val MIN_READABLE_CHARS = 200
    private const val NAVIGATION_TIMEOUT_MS = 25_000L
    private const val JAVASCRIPT_TIMEOUT_MS = 8_000L
    private const val DEFAULT_SCRIPT_CHARS = 2_000
    private const val MAX_SCRIPT_CHARS = 2_500
    private const val SCRIPT_POLL_INTERVAL_MS = 120L
    private const val MAX_COOKIE_ITEMS = 100
    private const val MAX_COOKIE_HEADER_CHARS = 4_000
    private const val REPEATED_TEXT_PREVIEW_CHARS = 200
    private const val SCRIPT_BRIDGE_NAME = "etaBridge"
    private const val BRIDGE_WAIT_INTERVAL_MS = 200L
    private const val BLOB_DOWNLOAD_TIMEOUT_MS = 30_000L
    private const val POST_ACTION_TIMEOUT_MS = 10_000L
    private const val SCREENSHOT_MAX_WIDTH = 1_280
    private const val SCREENSHOT_MAX_HEIGHT = 2_400
    private const val SCREENSHOT_QUALITY = 75
    private const val PREVIEW_MAX_WIDTH = 480
    private const val PREVIEW_MAX_HEIGHT = 900
    private const val PREVIEW_QUALITY = 60

    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationLock = ReentrantLock()
    private val interrupted = AtomicBoolean(false)
    private val operationEpoch = AtomicLong(0L)
    private val navigationGeneration = AtomicLong(0L)

    private val mutableSnapshots = MutableStateFlow(BrowserSessionSnapshot())
    val snapshots: StateFlow<BrowserSessionSnapshot> = mutableSnapshots.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var contextWrapper: MutableContextWrapper? = null

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var attachedContainer: ViewGroup? = null

    @Volatile
    private var currentLoadWaiter: LoadWaiter? = null

    @Volatile
    private var currentUrl: String = ""

    @Volatile
    private var currentHost: String = ""

    @Volatile
    private var currentTitle: String = ""

    @Volatile
    private var currentError: String? = null

    @Volatile
    private var currentHttpStatus: Int? = null

    @Volatile
    private var currentProgress: Int = 0

    @Volatile
    private var currentLoading: Boolean = false

    @Volatile
    private var currentPageVisible: Boolean = false

    @Volatile
    private var committedMainFrameUrl: String = ""

    @Volatile
    private var userControlActive: Boolean = false

    @Volatile
    private var activeActionIsUserInitiated: Boolean = false

    @Volatile
    private var activeOperationEpoch: Long = 0L

    @Volatile
    private var lastAgentToolCallId: String? = null

    @Volatile
    private var lastAgentRunId: String? = null

    @Volatile
    private var activeAgentRunId: String? = null

    @Volatile
    private var pendingDownload: BrowserDownloadRequest? = null

    @Volatile
    private var activeProxy: String? = null

    @Volatile
    private var lastReadMark: BrowserReadMark? = null

    @Volatile
    private var headerScript: ScriptHandler? = null

    private val scriptResults = HashMap<String, CompletableFuture<String>>()
    private val pendingBlobs = HashMap<String, CompletableFuture<BrowserBridgeBlob>>()
    private val scriptResultsLock = Any()

    @Volatile
    private var scriptBridgeInstalled = false

    /**
     * JS → 宿主的结果通道：页面按 nonce 回传 evaluate_js 的结果，比轮询 window 上的临时键更直接。
     * 任何页面都能 post，所以只认带当前 nonce 的消息，且只取主框架。
     */
    private val scriptBridgeListener = object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(
            view: WebView,
            message: WebMessageCompat,
            sourceOrigin: Uri,
            isMainFrame: Boolean,
            replyProxy: JavaScriptReplyProxy,
        ) {
            if (!isMainFrame) return
            if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                completeBlobFromBridge(message.arrayBuffer)
                return
            }
            if (message.type != WebMessageCompat.TYPE_STRING) return
            val raw = message.data ?: return
            val nonce = runCatching { JSONObject(raw).optString("nonce") }.getOrNull().orEmpty()
            if (nonce.isBlank()) return
            val future = synchronized(scriptResultsLock) { scriptResults.remove(nonce) } ?: return
            future.complete(raw)
        }
    }

    fun initialize(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    fun execute(
        context: Context,
        args: JSONObject,
        runId: String,
        toolCallId: String,
    ): BrowserToolResult {
        val result = executeInternal(
            context = context,
            args = args,
            userInitiated = false,
            agentRunId = runId,
        )
        val succeeded = runCatching { JSONObject(result.content).optBoolean("ok", false) }
            .getOrDefault(false)
        if (succeeded && toolCallId.isNotBlank()) {
            runCatching {
                callOnMain {
                    lastAgentRunId = runId.takeIf(String::isNotBlank)
                    lastAgentToolCallId = toolCallId
                    publishSnapshotOnMain()
                }
            }
        }
        return result
    }

    fun navigateFromUser(context: Context, url: String): BrowserToolResult {
        val target = url.trim().let { value ->
            if (value.isNotBlank() && "://" !in value) "https://$value" else value
        }
        return executeInternal(
            context = context,
            args = JSONObject().put("action", "navigate").put("url", target),
            userInitiated = true,
        )
    }

    fun goBackFromUser(): BrowserToolResult =
        executeFromExistingContext("go_back", userInitiated = true)

    fun goForwardFromUser(): BrowserToolResult =
        executeFromExistingContext("go_forward", userInitiated = true)

    fun reloadFromUser(): BrowserToolResult =
        executeFromExistingContext("reload", userInitiated = true)

    /** 浏览器页的代理开关：用户自己开/关，不受 Agent 动作的用户接管限制。 */
    fun setProxyFromUser(context: Context, proxy: String): BrowserToolResult =
        executeInternal(
            context = context,
            args = JSONObject().put("action", "set_proxy").put("proxy", proxy.trim()),
            userInitiated = true,
        )

    fun clearProxyFromUser(): BrowserToolResult =
        executeFromExistingContext("clear_proxy", userInitiated = true)

    /** 停止必须能越过串行操作锁，才能立刻唤醒正在等待导航的工具调用。 */
    fun stopFromUser(): BrowserToolResult {
        interruptCurrentAction(force = true)
        return toolResult(baseEnvelope("stop", ok = true, status = "ok"))
    }

    fun resetFromUser(): BrowserToolResult {
        val context = appContext
            ?: return errorResult("reset", "BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        return operationLock.withLock {
            interrupted.set(true)
            currentLoadWaiter?.complete(LoadOutcome(false, "CANCELLED", "操作已取消"))
            callOnMain {
                destroyWebViewOnMain()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                clearSessionStateOnMain()
            }
            initialize(context)
            toolResult(baseEnvelope("reset", ok = true, status = "ok"))
        }
    }

    fun interruptAgentAction(runId: String? = null) {
        if (userControlActive) return
        if (!runId.isNullOrBlank() && activeAgentRunId != runId) return
        interruptCurrentAction(force = false)
    }

    private fun interruptCurrentAction(force: Boolean) {
        if (!force && activeActionIsUserInitiated) return
        interrupted.set(true)
        operationEpoch.incrementAndGet()
        activeOperationEpoch = 0L
        navigationGeneration.incrementAndGet()
        currentLoadWaiter?.complete(LoadOutcome(false, "CANCELLED", "操作已取消"))
        mainHandler.post {
            runCatching { webView?.stopLoading() }
            currentLoading = false
            currentPageVisible = committedMainFrameUrl.isNotBlank()
            publishSnapshotOnMain()
        }
    }

    fun attachTo(container: ViewGroup, hostContext: Context) {
        initialize(hostContext)
        val wasAlreadyControlling = userControlActive
        userControlActive = true
        if (!wasAlreadyControlling) interruptCurrentAction(force = true)
        runOnMain {
            attachedContainer?.takeIf { it !== container }?.removeAllViews()
            attachedContainer = container
            contextWrapper?.baseContext = hostContext
            webView?.let { attachWebViewOnMain(it, container) }
            publishSnapshotOnMain()
        }
    }

    fun detachFrom(container: ViewGroup) {
        runOnMain {
            if (attachedContainer === container) {
                val wasControlling = userControlActive
                userControlActive = false
                if (wasControlling) interruptCurrentAction(force = true)
                webView?.takeIf { it.parent === container }?.let(container::removeView)
                attachedContainer = null
                appContext?.let { contextWrapper?.baseContext = it }
                publishSnapshotOnMain()
            }
        }
    }

    /**
     * 聊天页工具卡片的实时预览截图。
     *
     * 只在主线程绘制当前视口，不占用串行操作锁、不中断 Agent 或用户操作；
     * 页面不存在或绘制失败时返回 null，由调用方显示占位。
     */
    fun capturePreview(): BrowserImage? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val view = webView ?: return null
        if (currentUrl.isBlank()) return null
        return runCatching {
            val captured = captureViewport(
                view,
                maxWidth = PREVIEW_MAX_WIDTH,
                maxHeight = PREVIEW_MAX_HEIGHT,
                quality = PREVIEW_QUALITY,
            )
            BrowserImage(
                dataUrl = "data:image/jpeg;base64," +
                    Base64.encodeToString(captured.bytes, Base64.NO_WRAP),
                mimeType = "image/jpeg",
                bytes = captured.bytes.size,
                width = captured.width,
                height = captured.height,
            )
        }.getOrNull()
    }

    private fun executeFromExistingContext(action: String, userInitiated: Boolean): BrowserToolResult {
        val context = appContext
            ?: return errorResult(action, "BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        return executeInternal(
            context = context,
            args = JSONObject().put("action", action),
            userInitiated = userInitiated,
        )
    }

    private fun executeInternal(
        context: Context,
        args: JSONObject,
        userInitiated: Boolean,
        agentRunId: String? = null,
    ): BrowserToolResult {
        initialize(context)
        val batch = args.optJSONArray("actions")
        if (batch != null) return executeBatch(context, batch, userInitiated, agentRunId)
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (action !in SUPPORTED_ACTIONS) {
            return errorResult(action.ifBlank { "unknown" }, "INVALID_ACTION", "浏览器 action 无效或缺失")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return errorResult(action, "MAIN_THREAD_CALL", "浏览器操作不能阻塞主线程")
        }

        return operationLock.withLock {
            if (!userInitiated && userControlActive) {
                return@withLock errorResult(
                    action = action,
                    code = "USER_CONTROL_ACTIVE",
                    message = "用户正在接管浏览器，请等待用户离开浏览器页面后再继续",
                    status = "blocked",
                )
            }
            interrupted.set(false)
            val epoch = operationEpoch.incrementAndGet()
            activeOperationEpoch = epoch
            activeActionIsUserInitiated = userInitiated
            activeAgentRunId = agentRunId.takeUnless { userInitiated }
            callOnMain {
                currentError = null
                publishSnapshotOnMain()
            }
            try {
                runCatching {
                    when (action) {
                        "navigate" -> navigate(args)
                        "get_readable" -> readPage(args, readable = true)
                        "get_text" -> readPage(args, readable = false)
                        "find_elements" -> findElements(args)
                        "click" -> click(args)
                        "type" -> type(args)
                        "scroll" -> scroll(args)
                        "screenshot" -> screenshot(args)
                        "get_page_info" -> pageInfo()
                        "go_back" -> historyNavigation(action, backwards = true)
                        "go_forward" -> historyNavigation(action, backwards = false)
                        "reload" -> reload()
                        "wait_for_selector" -> waitForSelector(args)
                        "evaluate_js" -> evaluateScript(args)
                        "get_cookies" -> getCookies(args)
                        "set_cookie" -> setCookie(args)
                        "set_proxy" -> setProxy(args)
                        "clear_proxy" -> clearProxy()
                        "download" -> download(args)
                        else -> throw BrowserFailure("INVALID_ACTION", "浏览器 action 无效")
                    }
                }.getOrElse { throwable -> failureResult(action, throwable) }
            } finally {
                if (activeOperationEpoch == epoch) activeOperationEpoch = 0L
                activeActionIsUserInitiated = false
                if (activeAgentRunId == agentRunId) activeAgentRunId = null
            }
        }
    }

    /**
     * 一次提交多个动作：按数组顺序执行，某一步返回失败就停止，并回报失败步的序号与原因。
     * 每一步都走 executeInternal，沿用同一把可重入锁，因此用户接管、中断与 epoch 判定和单步调用一致。
     */
    private fun executeBatch(
        context: Context,
        actions: JSONArray,
        userInitiated: Boolean,
        agentRunId: String?,
    ): BrowserToolResult {
        val stepCount = actions.length()
        if (stepCount == 0) return errorResult("batch", "INVALID_ARGUMENT", "actions 不能为空")
        if (stepCount > AgentBrowserToolCatalog.MAX_BATCH_ACTIONS) {
            return errorResult(
                "batch",
                "INVALID_ARGUMENT",
                "actions 一次最多 ${AgentBrowserToolCatalog.MAX_BATCH_ACTIONS} 步",
            )
        }
        val results = JSONArray()
        val images = mutableListOf<BrowserImage>()
        var failedIndex = -1
        var failedAction = ""
        var failedCode = ""
        var failedMessage = ""
        for (index in 0 until stepCount) {
            val step = actions.optJSONObject(index)
            if (step == null) {
                failedIndex = index
                failedCode = "INVALID_ARGUMENT"
                failedMessage = "actions[$index] 必须是对象"
                break
            }
            val stepArgs = JSONObject(step.toString())
            val stepAction = stepArgs.optString("action").trim().lowercase(Locale.ROOT)
            val result = executeInternal(
                context = context,
                args = stepArgs,
                userInitiated = userInitiated,
                agentRunId = agentRunId,
            )
            images += result.images
            val payload = runCatching { JSONObject(result.content) }.getOrNull()
            val succeeded = payload?.optBoolean("ok") == true
            results.put(
                JSONObject()
                    .put("index", index)
                    .put("action", stepAction)
                    .put("ok", succeeded),
            )
            if (!succeeded) {
                failedIndex = index
                failedAction = stepAction
                failedCode = payload?.optString("code").orEmpty()
                failedMessage = payload?.optString("message").orEmpty()
                break
            }
        }
        val envelope = baseEnvelope(
            "batch",
            ok = failedIndex < 0,
            status = if (failedIndex < 0) "ok" else "failed",
        )
            .put("count", results.length())
            .put("results", results)
        if (failedIndex >= 0) {
            envelope
                .put("failed_index", failedIndex)
                .put("failed_action", failedAction)
                .put("code", failedCode)
                .put("message", failedMessage)
        }
        return toolResult(envelope, images)
    }

    private fun navigate(args: JSONObject): BrowserToolResult {
        val rawUrl = args.optString("url").trim()
        if (rawUrl.isBlank()) throw BrowserFailure("INVALID_ARGUMENT", "navigate 缺少 url")
        // 同一地址的重复导航不再重新加载：页面已经是这个状态，要刷新用 reload，要继续读正文用 next_offset。
        // 用户自己提交地址栏（userInitiated）时不受这条影响。
        if (!activeActionIsUserInitiated &&
            rawUrl == currentUrl &&
            snapshots.value.available &&
            !snapshots.value.isLoading
        ) {
            return toolResult(
                baseEnvelope("navigate", ok = true, status = "ok")
                    .put("redirected", false)
                    .put("unchanged", true)
                    .put("message", "页面已经是这个地址，未重新加载；需要刷新用 reload，需要继续读正文请用 get_readable 的 next_offset")
            )
        }
        val headers = customHeaders(args)
        val userAgent = if (args.has("user_agent") && !args.isNull("user_agent")) {
            args.optString("user_agent").trim()
        } else {
            null
        }
        val view = ensureWebView()
        val epoch = activeOperationEpoch
        val waiter = LoadWaiter()
        currentLoadWaiter = waiter
        val timeout = args.optLong("timeout_ms", NAVIGATION_TIMEOUT_MS)
            .coerceIn(500L, NAVIGATION_TIMEOUT_MS)
        val generation = navigationGeneration.incrementAndGet()
        var headerInjected = false
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) {
                throw BrowserFailure("NAVIGATION_SUPERSEDED", "页面导航已被新的操作替代", "cancelled")
            }
            currentError = null
            currentHttpStatus = null
            currentUrl = rawUrl
            currentHost = hostOf(rawUrl)
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            // User-Agent 必须走 WebSettings：附加请求头里的 UA 会被 WebView 自身的默认值覆盖。
            if (userAgent != null) {
                view.settings.userAgentString = userAgent.ifEmpty {
                    WebSettings.getDefaultUserAgent(view.context)
                }
            }
            headerInjected = applyHeaderScriptOnMain(view, headers)
            if (headers == null) view.loadUrl(rawUrl) else view.loadUrl(rawUrl, headers)
        }

        val outcome = try {
            waiter.await(timeout) ?: run {
                navigationGeneration.incrementAndGet()
                callOnMain {
                    view.stopLoading()
                    currentLoading = false
                    currentPageVisible = committedMainFrameUrl.isNotBlank()
                    currentError = "页面加载超时"
                    publishSnapshotOnMain()
                }
                throw BrowserFailure("NAVIGATION_TIMEOUT", "页面加载超时", status = "timeout")
            }
        } finally {
            if (currentLoadWaiter === waiter) currentLoadWaiter = null
        }
        if (!outcome.ok) throw BrowserFailure(outcome.code, outcome.message)
        throwIfInterrupted()

        currentHttpStatus?.takeIf { it >= 400 }?.let { code ->
            throw BrowserFailure("HTTP_$code", "网页返回 HTTP $code")
        }
        val envelope = baseEnvelope("navigate", ok = true, status = "ok")
            .put("redirected", rawUrl != currentUrl)
        if (headers != null) {
            envelope.put("header_names", JSONArray(headers.keys.toList()))
            // 注入没成功时只有主文档请求带这些头：把范围写进结果，别让 header_names 造成
            // 「页面自身发出的同源请求也带了头」的误解。
            envelope.put("header_scope", if (headerInjected) "document_start" else "main_document_only")
        }
        if (userAgent != null) envelope.put("user_agent", callOnMain { view.settings.userAgentString })
        return toolResult(envelope)
    }

    /**
     * Mozilla Readability 的源码，随正文提取脚本一起下发。
     *
     * 不单独注入：注入到页面全局既依赖 Context、又会被导航重置，一旦静默失败就只能靠启发式。
     * 放在同一个脚本里，函数声明与调用同处一个作用域，必定可用。
     */
    private val readabilitySource: String by lazy {
        runCatching {
            appContext?.assets?.open("browser/readability.js")
                ?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
    }

    private fun readabilityPrelude(): String {
        val source = readabilitySource
        if (source.isBlank()) return ""
        // 包一层 IIFE：既把 Readability 的顶层变量限制在自己的作用域里（不与提取脚本撞名），
        // 又把构造函数交回来，供提取脚本直接使用。
        // 内层 try/catch 是降级通道：readability.js 运行期抛错时返回 null，提取脚本的
        // `typeof Readability === 'function'` 判假后走启发式，而不是让整次 readPage 失败。
        // （语法错误在解析期就失败，try 兜不住，只能靠构建期保证资产本身可用。）
        return "var Readability = (function () {\ntry {\n$source\nreturn Readability;\n} catch (error) { return null; }\n})();\n"
    }

    private fun readPage(args: JSONObject, readable: Boolean): BrowserToolResult {
        val view = requirePage()
        val offset = args.optInt("offset", 0).coerceIn(0, 200_000)
        val maxChars = args.optInt("max_chars", DEFAULT_TEXT_CHARS)
            .coerceIn(256, MAX_TEXT_CHARS)
        val selector = if (readable) null else validatedSelector(args, required = false)
        val value = evaluateObject(
            view,
            if (readable) {
                // Readability 与提取脚本放进同一次执行：单独注入依赖 Context、又会被导航重置，
                // 一旦静默失败就只剩启发式。同作用域下函数声明必定可用。
                val body = BrowserDomScripts.readable(offset, maxChars)
                readabilityPrelude() + body
            } else {
                BrowserDomScripts.text(selector, offset, maxChars)
            }
        )
        val action = if (readable) "get_readable" else "get_text"
        // 提取为空时明确报错：返回空字符串会让调用方分不清"页面确实没内容"和"提取失败"，
        // 只能反复换工具重试，白白烧掉几轮。offset > 0 的分页读到末尾是正常的，不报错。
        if (readable && offset == 0) {
            val extracted = value.optInt("text_length", value.optString("text").length)
            if (extracted < MIN_READABLE_CHARS) {
                val path = value.optString("extractor").ifBlank { "-" }
                val where = value.optString("selector_used").ifBlank { "-" }
                val visited = value.optInt("visited_nodes", -1)
                throw BrowserFailure(
                    "EMPTY_EXTRACTION",
                    "正文提取只得到 $extracted 个字符（阈值 $MIN_READABLE_CHARS；路径=$path，目标=$where，" +
                        "遍历节点=$visited）：页面可能尚未渲染，或结构不在提取范围内。可先 wait_for_selector " +
                        "等目标元素出现，再用 find_elements 定位，或改用 get_text 取整页文本。",
                )
            }
        }
        val envelope = mergeValue(baseEnvelope(action, true, "ok"), value)
            .put("content_format", if (readable) "markdown" else "text")
        if (readable) {
            // Readability 资产读不到时脚本会静默退回启发式提取；把这条降级写进结果，
            // 否则调用方分不清「页面本来就没正文」和「Readability 根本没被用上」。
            envelope.put("readability_available", readabilitySource.isNotBlank())
        }
        return toolResult(elideRepeatedRead(action, offset, maxChars, value, withUnreadHint(value, envelope)))
    }

    /**
     * 正文没读完时，把「还有多少、怎么继续」直接写进正文末尾。
     * 这些信息本来只落在 JSON 字段里，只看 text 就开始回答的调用方会漏掉后半篇。
     */
    private fun withUnreadHint(value: JSONObject, envelope: JSONObject): JSONObject {
        val text = value.optString("text")
        val total = value.optInt("text_length", 0)
        val next = if (value.isNull("next_offset")) -1 else value.optInt("next_offset", -1)
        val hint = when {
            next > 0 -> "\n\n[正文共 $total 字符，本次返回 ${text.length} 字符；" +
                "要继续读请再调用本工具并传 offset=$next]"
            value.optBoolean("source_truncated") ->
                "\n\n[正文共 $total 字符，本次已返回全部；页面更长，超出提取上限的部分读不到]"
            else -> null
        } ?: return envelope
        return envelope.put("text", text + hint)
    }

    private fun findElements(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val selector = validatedSelector(args, required = false)
        val value = evaluateObject(view, BrowserDomScripts.findElements(selector))
        return toolResult(
            mergeValue(baseEnvelope("find_elements", true, "ok"), value)
        )
    }

    private fun click(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val target = targetFrom(args)
        val value = evaluateObject(view, BrowserDomScripts.click(target.selector, target.x, target.y))
        waitForPostAction()
        return toolResult(
            mergeValue(baseEnvelope("click", true, "ok"), value)
                .put("side_effect", "possible")
        )
    }

    private fun type(args: JSONObject): BrowserToolResult {
        if (!args.has("text") || args.isNull("text")) {
            throw BrowserFailure("INVALID_ARGUMENT", "type 缺少 text")
        }
        val inputText = args.optString("text")
        val submit = args.optBoolean("submit", false)
        val view = requirePage()
        val target = targetFrom(args)
        val value = evaluateObject(
            view,
            BrowserDomScripts.type(
                selector = target.selector,
                x = target.x,
                y = target.y,
                text = inputText,
                submit = submit,
            )
        )
        waitForPostAction()
        return toolResult(
            mergeValue(baseEnvelope("type", true, "ok"), value)
                .put("side_effect", if (submit) "possible" else "local_input")
        )
    }

    private fun scroll(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val direction = args.optString("direction", "down").lowercase(Locale.ROOT)
            .takeIf { it == "up" || it == "down" }
            ?: throw BrowserFailure("INVALID_ARGUMENT", "direction 仅支持 up 或 down")
        val amount = args.optInt("amount", 600).coerceIn(1, 5_000)
        val selector = validatedSelector(args, required = false)
        val value = evaluateObject(view, BrowserDomScripts.scroll(selector, direction, amount))
        Thread.sleep(200)
        return toolResult(mergeValue(baseEnvelope("scroll", true, "ok"), value))
    }

    private fun screenshot(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val captured = captureViewport(view)
        val includeImage = args.optBoolean("read_image", true)
        val envelope = baseEnvelope("screenshot", true, "ok")
            .put("image_width", captured.width)
            .put("image_height", captured.height)
            .put("image_bytes", captured.bytes.size)
        val image = if (includeImage) {
            BrowserImage(
                dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(captured.bytes, Base64.NO_WRAP),
                mimeType = "image/jpeg",
                bytes = captured.bytes.size,
                width = captured.width,
                height = captured.height,
            )
        } else {
            null
        }
        return toolResult(envelope, listOfNotNull(image))
    }

    private fun pageInfo(): BrowserToolResult {
        val view = requirePage()
        val value = evaluateObject(view, BrowserDomScripts.pageInfo())
        return toolResult(mergeValue(baseEnvelope("get_page_info", true, "ok"), value))
    }

    private fun historyNavigation(action: String, backwards: Boolean): BrowserToolResult {
        val view = requirePage()
        val hasTarget = callOnMain {
            val history = view.copyBackForwardList()
            val targetIndex = history.currentIndex + if (backwards) -1 else 1
            targetIndex in 0 until history.size
        }
        if (!hasTarget) throw BrowserFailure("HISTORY_UNAVAILABLE", "当前没有可用的浏览记录")
        val epoch = activeOperationEpoch
        val generation = navigationGeneration.incrementAndGet()
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) return@callOnMain
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            if (backwards) view.goBack() else view.goForward()
        }
        waitForPostAction()
        return toolResult(baseEnvelope(action, true, "ok"))
    }

    private fun reload(): BrowserToolResult {
        val view = requirePage()
        val epoch = activeOperationEpoch
        val generation = navigationGeneration.incrementAndGet()
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) return@callOnMain
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            view.reload()
        }
        waitForPostAction()
        return toolResult(baseEnvelope("reload", true, "ok"))
    }

    private fun waitForSelector(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val selector = validatedSelector(args, required = true)!!
        val timeout = args.optLong("timeout_ms", 5_000L).coerceIn(500L, 30_000L)
        val deadline = System.currentTimeMillis() + timeout
        var state = JSONObject().put("found", false).put("visible", false)
        while (System.currentTimeMillis() < deadline) {
            throwIfInterrupted()
            state = evaluateObject(view, BrowserDomScripts.selectorState(selector))
            if (state.optBoolean("found")) {
                return toolResult(
                    mergeValue(baseEnvelope("wait_for_selector", true, "ok"), state)
                        .put("selector", selector.take(240))
                )
            }
            Thread.sleep(250L)
        }
        return toolResult(
            mergeValue(baseEnvelope("wait_for_selector", false, "not_found"), state)
                .put("code", "ELEMENT_NOT_FOUND")
                .put("message", "等待的网页元素未出现")
        )
    }

    private fun evaluateScript(args: JSONObject): BrowserToolResult {
        if (!args.has("expression") || args.isNull("expression")) {
            throw BrowserFailure("INVALID_ARGUMENT", "evaluate_js 缺少 expression")
        }
        val expression = args.optString("expression")
        if (expression.isBlank()) {
            throw BrowserFailure("INVALID_ARGUMENT", "evaluate_js 的 expression 不能为空")
        }
        val maxChars = args.optInt("max_chars", DEFAULT_SCRIPT_CHARS).coerceIn(128, MAX_SCRIPT_CHARS)
        val timeout = args.optLong("timeout_ms", JAVASCRIPT_TIMEOUT_MS)
            .coerceIn(500L, NAVIGATION_TIMEOUT_MS)
        val view = requirePage()
        val resultKey = "etaScript" + System.nanoTime().toString(36)
        val urlAtStart = currentUrl
        val payload = try {
            runScript(view, expression, resultKey, maxChars, timeout, urlAtStart)
        } catch (failure: BrowserFailure) {
            // 「一串语句」放在表达式位置会直接语法失败，这里换两种包装各跑一次，取第一个真正
            // 返回 ok 的结果。判据必须是结果，不能是「注入成功」：eval 包装对含 return 的语句块
            // 语法合法但运行期报错，只看注入会误判成功，把真正能用的包装跳过。
            if (failure.code != "SCRIPT_FAILED") throw failure
            retryAsWrappedStatements(view, expression, resultKey, maxChars, timeout, urlAtStart, failure)
        }
        return scriptResult(payload, expression)
    }

    private fun runScript(
        view: WebView,
        expression: String,
        resultKey: String,
        maxChars: Int,
        timeout: Long,
        urlAtStart: String,
    ): String = if (bridgeUsable(view)) {
        awaitBridgePayload(view, expression, resultKey, maxChars, timeout, urlAtStart)
    } else {
        awaitPolledPayload(view, expression, resultKey, maxChars, timeout, urlAtStart)
    }

    /**
     * expression 在表达式位置语法失败时的补救：两种包装各跑一次，谁先真正返回 ok 就用谁。
     *
     * 两种包装各有适用面：`eval` 包装会交出最后一条语句的值，但对含 `return` 的语句块在运行期
     * 报语法错误；语句块包装能跑 `return` / `await`，但返回值是 undefined。所以只能在拿到结果后判断。
     */
    private fun retryAsWrappedStatements(
        view: WebView,
        expression: String,
        resultKey: String,
        maxChars: Int,
        timeout: Long,
        urlAtStart: String,
        cause: BrowserFailure,
    ): String {
        val attempts = listOf(
            "(async () => { return eval(${JSONObject.quote(expression)}); })()",
            "(async () => {\n$expression\n})()",
        )
        for (wrapped in attempts) {
            val raw = runCatching { runScript(view, wrapped, resultKey, maxChars, timeout, urlAtStart) }
                .getOrNull() ?: continue
            if (runCatching { JSONObject(raw).optBoolean("ok") }.getOrDefault(false)) return raw
        }
        throw BrowserFailure(
            "SCRIPT_FAILED",
            buildString {
                append("expression 无法执行：").append(cause.message).append("。")
                append("这里要的是单个表达式——多条语句请包成 (async () => { ... })()，并用 return 交出结果。")
                append("常见两种写法错误：① 直接写一串语句（如 const a = 1; return a;），")
                append("没有包成函数体；② 包了函数体但没写 return，结果拿到 null。")
                append("\n实际收到的 expression（前 160 字符）：\n")
                append(expression.take(160))
                if (expression.length > 160) append("…（共 ${expression.length} 字符）")
            },
        )
    }

    /** 桥对象只对安装监听之后创建的文档生效，所以要在页面里确认一次再决定走哪条路。 */
    private fun bridgeUsable(view: WebView): Boolean {
        if (!scriptBridgeInstalled ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            return false
        }
        return runCatching {
            evaluateObject(view, BrowserDomScripts.bridgeAvailable(SCRIPT_BRIDGE_NAME)).optBoolean("available")
        }.getOrDefault(false)
    }

    private fun startScript(
        view: WebView,
        expression: String,
        resultKey: String,
        maxChars: Int,
        bridgeName: String?,
    ) {
        // 只负责注入。语法失败的重试上移到 evaluateScript：那里能看到结果，而这里只能看到
        // 「注入是否成功」——拿它当判据会把运行期报错的候选误判为成功（见 retryAsWrappedStatements）。
        evaluateObject(view, BrowserDomScripts.evaluateScript(expression, resultKey, maxChars, bridgeName))
    }

    private fun awaitBridgePayload(
        view: WebView,
        expression: String,
        resultKey: String,
        maxChars: Int,
        timeout: Long,
        urlAtStart: String,
    ): String {
        val future = CompletableFuture<String>()
        synchronized(scriptResultsLock) { scriptResults[resultKey] = future }
        try {
            startScript(view, expression, resultKey, maxChars, SCRIPT_BRIDGE_NAME)
            val deadline = System.currentTimeMillis() + timeout
            while (true) {
                throwIfInterrupted()
                try {
                    return future.get(BRIDGE_WAIT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // 这一小段没有消息就继续等，并顺带做与轮询路径一致的判断
                }
                if (currentUrl != urlAtStart) {
                    throw BrowserFailure("SCRIPT_RESULT_LOST", "脚本触发了页面跳转，返回值已丢失")
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw BrowserFailure("SCRIPT_RESULT_TIMEOUT", "脚本没有在超时前返回结果", "timeout")
                }
            }
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BrowserFailure("CANCELLED", "操作已取消", "cancelled")
        } finally {
            synchronized(scriptResultsLock) { scriptResults.remove(resultKey) }
        }
    }

    private fun awaitPolledPayload(
        view: WebView,
        expression: String,
        resultKey: String,
        maxChars: Int,
        timeout: Long,
        urlAtStart: String,
    ): String {
        startScript(view, expression, resultKey, maxChars, null)
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            throwIfInterrupted()
            val outcome = evaluateObject(view, BrowserDomScripts.scriptOutcome(resultKey))
            if (outcome.optBoolean("done")) return outcome.optString("payload")
            if (currentUrl != urlAtStart) {
                throw BrowserFailure("SCRIPT_RESULT_LOST", "脚本触发了页面跳转，返回值已丢失")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw BrowserFailure("SCRIPT_RESULT_TIMEOUT", "脚本没有在超时前返回结果", "timeout")
            }
            Thread.sleep(SCRIPT_POLL_INTERVAL_MS)
        }
    }

    private fun scriptResult(payloadRaw: String, expression: String = ""): BrowserToolResult {
        val payload = runCatching { JSONObject(payloadRaw) }.getOrNull()
            ?: throw BrowserFailure("SCRIPT_FAILED", "脚本返回的结果格式无效")
        if (!payload.optBoolean("ok")) {
            val error = if (payload.has("error") && !payload.isNull("error")) {
                payload.optString("error")
            } else {
                ""
            }
            throw BrowserFailure(
                "SCRIPT_ERROR",
                buildString {
                    append(error.take(400).ifBlank { "脚本执行失败" })
                    if (expression.isNotBlank()) {
                        append("\n出错的 expression（前 160 字符）：\n")
                        append(expression.take(160))
                        if (expression.length > 160) append("…（共 ${expression.length} 字符）")
                    }
                },
            )
        }
        val kind = payload.optString("kind").ifBlank { "value" }
        val text = payload.optString("text")
        val value: Any = when (kind) {
            "object", "array", "number", "boolean" ->
                runCatching { JSONTokener(text).nextValue() }.getOrDefault(text)
            "null", "undefined" -> JSONObject.NULL
            else -> text
        }
        val envelope = baseEnvelope("evaluate_js", ok = true, status = "ok")
            .put("result", value)
            .put("result_kind", kind)
            .put("result_length", payload.optInt("full_length", text.length))
        if (payload.optBoolean("truncated")) envelope.put("truncated", true)
        return toolResult(envelope)
    }

    private fun getCookies(args: JSONObject): BrowserToolResult {
        val url = cookieUrl(args)
        val raw = CookieManager.getInstance().getCookie(url).orEmpty()
        val entries = raw.split(';').map(String::trim).filter(String::isNotEmpty)
        val cookies = JSONArray()
        entries.take(MAX_COOKIE_ITEMS).forEach { entry ->
            val separator = entry.indexOf('=')
            cookies.put(
                JSONObject()
                    .put("name", if (separator > 0) entry.substring(0, separator) else entry)
                    .put("value", if (separator > 0) entry.substring(separator + 1) else "")
            )
        }
        val envelope = baseEnvelope("get_cookies", ok = true, status = "ok")
            .put("url", url)
            .put("cookie_count", entries.size)
            .put("cookies", cookies)
            .put("cookie_header", raw.take(MAX_COOKIE_HEADER_CHARS))
        if (entries.size > MAX_COOKIE_ITEMS || raw.length > MAX_COOKIE_HEADER_CHARS) {
            envelope.put("truncated", true)
        }
        return toolResult(envelope)
    }

    private fun setCookie(args: JSONObject): BrowserToolResult {
        val url = cookieUrl(args)
        val cookie = if (args.has("cookie") && !args.isNull("cookie")) {
            args.optString("cookie").trim()
        } else {
            ""
        }
        if (cookie.isEmpty()) throw BrowserFailure("INVALID_ARGUMENT", "set_cookie 缺少 cookie")
        val separator = cookie.indexOf('=')
        if (separator <= 0) {
            throw BrowserFailure("INVALID_ARGUMENT", "cookie 需要是 Set-Cookie 形式的 name=value[; 属性]")
        }
        val name = cookie.substring(0, separator).trim()
        // 写入回调会投递到调用线程的 Looper，工具线程没有 Looper，所以不带回调写入，写完回读确认。
        CookieManager.getInstance().setCookie(url, cookie, null)
        val applied = CookieManager.getInstance().getCookie(url).orEmpty()
            .split(';')
            .map(String::trim)
            .any { it.startsWith("$name=") }
        val envelope = baseEnvelope("set_cookie", ok = true, status = "ok")
            .put("url", url)
            .put("cookie_name", name)
            .put("applied", applied)
        if (!applied) envelope.put("message", "回读时未发现该 cookie，可能被域名或属性规则忽略")
        return toolResult(envelope)
    }

    private fun cookieUrl(args: JSONObject): String {
        val url = args.optString("url").trim().ifEmpty { currentUrl }
        if (url.isBlank()) throw BrowserFailure("INVALID_ARGUMENT", "缺少 url，且当前没有已打开的网页")
        val scheme = runCatching { Uri.parse(url).scheme.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (scheme != "http" && scheme != "https") {
            throw BrowserFailure("INVALID_ARGUMENT", "url 需要是 http 或 https 地址")
        }
        return url
    }

    private fun setProxy(args: JSONObject): BrowserToolResult {
        val raw = args.optString("proxy").trim()
        if (raw.isEmpty()) throw BrowserFailure("INVALID_ARGUMENT", "set_proxy 缺少 proxy")
        val rule = BrowserProxyRules.normalize(raw) ?: throw BrowserFailure(
            "INVALID_ARGUMENT",
            "proxy 需要写成 [scheme://]host[:port]，scheme 只支持 http、https、socks",
        )
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            throw BrowserFailure("PROXY_UNSUPPORTED", "当前 WebView 不支持进程级代理覆盖")
        }
        // ProxyController 由 WebView provider 提供，先让 WebView 完成初始化再取实例。
        ensureWebView()
        val config = ProxyConfig.Builder().addProxyRule(rule).build()
        awaitProxyChange { executor, listener ->
            ProxyController.getInstance().setProxyOverride(config, executor, listener)
        }
        activeProxy = rule
        return toolResult(baseEnvelope("set_proxy", ok = true, status = "ok"))
    }

    private fun clearProxy(): BrowserToolResult {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            throw BrowserFailure("PROXY_UNSUPPORTED", "当前 WebView 不支持进程级代理覆盖")
        }
        awaitProxyChange { executor, listener ->
            ProxyController.getInstance().clearProxyOverride(executor, listener)
        }
        activeProxy = null
        return toolResult(baseEnvelope("clear_proxy", ok = true, status = "ok"))
    }

    /**
     * 代理覆盖是进程级设置，作用于 Eta 内所有 WebView；官方要求等 listener 回调后才算生效，
     * 这里用 latch 等待，超时按失败处理而不是继续往下走。
     */
    private fun awaitProxyChange(apply: (Executor, Runnable) -> Unit) {
        val latch = CountDownLatch(1)
        val executor = Executor { command -> command.run() }
        val listener = Runnable { latch.countDown() }
        callOnMain { apply(executor, listener) }
        if (!latch.await(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw BrowserFailure("PROXY_TIMEOUT", "代理设置没有在超时前生效", "timeout")
        }
    }

    private fun download(args: JSONObject): BrowserToolResult {
        val rawUrl = args.optString("url").trim().ifEmpty { currentUrl }
        if (rawUrl.isBlank()) throw BrowserFailure("INVALID_ARGUMENT", "download 缺少 url，且当前没有已打开的网页")
        val scheme = runCatching { Uri.parse(rawUrl).scheme.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val fileName = if (args.has("file_name") && !args.isNull("file_name")) {
            args.optString("file_name").trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        // blob:/data: 的内容只存在于页面上下文，交给页面读出来经桥回传
        if (scheme == "blob" || scheme == "data") {
            return downloadFromPage(rawUrl, fileName)
        }
        if (scheme != "http" && scheme != "https") {
            throw BrowserFailure("INVALID_ARGUMENT", "url 需要是 http、https，或页面内的 blob:/data: 地址")
        }
        val context = appContext
            ?: throw BrowserFailure("BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        if (!Environment.isExternalStorageManager()) {
            throw BrowserFailure(
                "DOWNLOAD_PERMISSION_REQUIRED",
                "缺少「所有文件访问」权限，无法写入公共下载目录",
            )
        }
        val view = ensureWebView()
        val outcome = try {
            BrowserDownloader.download(
                context = context,
                url = rawUrl,
                fileName = fileName,
                // 下载走 OkHttp：必须自己带上当前 WebView 的 Cookie 与 User-Agent，否则会掉登录态
                cookieHeader = CookieManager.getInstance().getCookie(rawUrl),
                userAgent = callOnMain { view.settings.userAgentString },
                referer = currentUrl.takeIf { it.isNotBlank() && it != rawUrl },
                proxyRule = activeProxy,
            )
        } catch (failure: IOException) {
            throw BrowserFailure("DOWNLOAD_FAILED", failure.message ?: "下载失败")
        }
        return toolResult(
            baseEnvelope("download", ok = true, status = "ok")
                .put("file_name", outcome.file.name)
                .put("file_path", outcome.file.absolutePath)
                .put("size_bytes", outcome.bytes)
                .put("mime_type", outcome.mimeType)
                .put("http_status", outcome.httpStatus)
                .put("source_url", outcome.sourceUrl)
                .put("final_url", outcome.finalUrl)
        )
    }

    /**
     * 同一 run 内重复读取完全相同的区间时，正文不再整段回传：只给前缀与长度，正文留在更早的结果里。
     * 记账带 runId，跨 run、换区间或用户触发的读取都会照常返回全文。
     */
    private fun elideRepeatedRead(
        action: String,
        offset: Int,
        maxChars: Int,
        value: JSONObject,
        envelope: JSONObject,
    ): JSONObject {
        val key = "$action|$currentUrl|$offset|$maxChars"
        val text = value.optString("text")
        val previous = lastReadMark
        val runId = activeAgentRunId
        lastReadMark = BrowserReadMark(runId, key, text.hashCode())
        // 只去掉“同区间且内容逐字相同”的那种重复：同区间但正文变了（例如读取后又点了下页面）必须照常回传。
        if (runId == null || text.isBlank() || previous == null ||
            previous.key != key || previous.runId != runId || previous.textHash != text.hashCode()
        ) {
            return envelope
        }
        val preview = text.take(REPEATED_TEXT_PREVIEW_CHARS)
        return envelope
            .put("text", preview)
            .put("returned_chars", preview.length)
            .put("text_repeated", true)
            .put("message", "与本次运行内上一次读取（同一地址、同一区间）完全相同，正文已在上一条结果里；继续读请用 next_offset")
    }

    private fun downloadFromPage(url: String, fileName: String?): BrowserToolResult {
        val context = appContext
            ?: throw BrowserFailure("BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        if (!Environment.isExternalStorageManager()) {
            throw BrowserFailure(
                "DOWNLOAD_PERMISSION_REQUIRED",
                "缺少「所有文件访问」权限，无法写入公共下载目录",
            )
        }
        val view = requirePage()
        if (!bridgeUsable(view)) {
            throw BrowserFailure(
                "PAGE_DOWNLOAD_UNAVAILABLE",
                "当前 WebView 不支持页面内取文件（需要 WebMessageListener 特性）",
            )
        }
        val resultKey = "etaBlob" + System.nanoTime().toString(36)
        val blob = awaitBridgeBlob(view, url, resultKey)
        val saved = try {
            BrowserDownloader.saveBytes(context, fileName ?: blob.fileName, blob.mimeType, blob.bytes)
        } catch (failure: IOException) {
            throw BrowserFailure("DOWNLOAD_FAILED", failure.message ?: "下载失败")
        }
        return toolResult(
            baseEnvelope("download", ok = true, status = "ok")
                .put("file_name", saved.file.name)
                .put("file_path", saved.file.absolutePath)
                .put("size_bytes", saved.bytes)
                .put("mime_type", saved.mimeType)
                .put("source_url", url)
        )
    }

    private fun awaitBridgeBlob(view: WebView, url: String, resultKey: String): BrowserBridgeBlob {
        val future = CompletableFuture<BrowserBridgeBlob>()
        synchronized(scriptResultsLock) { pendingBlobs[resultKey] = future }
        try {
            try {
                evaluateObject(view, BrowserDomScripts.blobDownload(url, resultKey, SCRIPT_BRIDGE_NAME))
            } catch (failure: BrowserFailure) {
                if (failure.code != "SCRIPT_FAILED") throw failure
                throw BrowserFailure("DOWNLOAD_FAILED", "页面内取文件的脚本无法执行")
            }
            return future.get(BLOB_DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw BrowserFailure("DOWNLOAD_TIMEOUT", "页面内取文件没有在超时前完成", "timeout")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BrowserFailure("CANCELLED", "操作已取消", "cancelled")
        } finally {
            synchronized(scriptResultsLock) { pendingBlobs.remove(resultKey) }
        }
    }

    private fun targetFrom(args: JSONObject): BrowserTarget {
        val selector = validatedSelector(args, required = false)
        val hasX = args.has("coordinate_x") && !args.isNull("coordinate_x")
        val hasY = args.has("coordinate_y") && !args.isNull("coordinate_y")
        if (hasX != hasY) {
            throw BrowserFailure("INVALID_ARGUMENT", "coordinate_x 与 coordinate_y 必须同时提供")
        }
        if (selector == null && !hasX) {
            throw BrowserFailure("INVALID_ARGUMENT", "需要 selector 或 coordinate_x/coordinate_y")
        }
        val x = if (hasX) args.optInt("coordinate_x") else null
        val y = if (hasY) args.optInt("coordinate_y") else null
        return BrowserTarget(
            selector = selector,
            x = x,
            y = y,
        )
    }

    private fun validatedSelector(args: JSONObject, required: Boolean): String? {
        val selector = args.optString("selector").trim()
        if (selector.isBlank()) {
            if (required) throw BrowserFailure("INVALID_ARGUMENT", "缺少 CSS selector")
            return null
        }
        return selector
    }

    /**
     * 给本次导航装请求头，返回文档开始注入脚本是否真的装上。
     *
     * 把这一批头装进文档开始脚本，让页面自己发出的同源 fetch/XHR 也带上；没有请求头时移除，
     * 保持“请求头只对本次导航生效”的语义。
     *
     * 两条失败路径都是静默的（WebView 不支持该特性、注册抛异常），所以必须把结果回给调用方：
     * 否则返回值里的 header_names 会让模型以为页面自身发出的同源请求也带了这些头。
     */
    private fun applyHeaderScriptOnMain(view: WebView, headers: Map<String, String>?): Boolean {
        val previous = headerScript
        headerScript = null
        if (previous != null) runCatching { previous.remove() }
        if (headers.isNullOrEmpty()) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        headerScript = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                BrowserDomScripts.documentStartHeaders(headers),
                setOf("*"),
            )
        }.getOrNull()
        return headerScript != null
    }

    private fun customHeaders(args: JSONObject): Map<String, String>? {
        val raw = args.optJSONObject("headers") ?: return null
        if (raw.length() == 0) return null
        val headers = linkedMapOf<String, String>()
        raw.keys().forEach { key ->
            val value = raw.opt(key)
            if (value == null || value === JSONObject.NULL || value is JSONObject || value is JSONArray) {
                throw BrowserFailure("INVALID_ARGUMENT", "headers 的值必须是字符串")
            }
            val name = key.trim()
            val text = value.toString()
            if (name.isEmpty() || text.contains('\n') || text.contains('\r')) {
                throw BrowserFailure("INVALID_ARGUMENT", "headers 的名称或值不合法")
            }
            headers[name] = text
        }
        return headers
    }

    private fun requirePage(): WebView {
        val view = ensureWebView()
        if (!snapshots.value.available || currentUrl.isBlank()) {
            throw BrowserFailure("NO_PAGE", "当前没有网页，请先调用 navigate")
        }
        throwIfInterrupted()
        return view
    }

    private fun waitForPostAction() {
        Thread.sleep(250L)
        val deadline = System.currentTimeMillis() + POST_ACTION_TIMEOUT_MS
        while (snapshots.value.isLoading && System.currentTimeMillis() < deadline) {
            throwIfInterrupted()
            Thread.sleep(100L)
        }
        if (snapshots.value.isLoading) {
            navigationGeneration.incrementAndGet()
            mainHandler.post { runCatching { webView?.stopLoading() } }
            throw BrowserFailure("ACTION_TIMEOUT", "网页操作后的页面加载超时", "timeout")
        }
    }

    private fun throwIfInterrupted() {
        if (interrupted.get() || activeOperationEpoch == 0L) {
            throw BrowserFailure("CANCELLED", "操作已取消", "cancelled")
        }
    }

    private fun requireActiveOperation(epoch: Long) {
        if (epoch == 0L || activeOperationEpoch != epoch || interrupted.get()) {
            throw BrowserFailure("CANCELLED", "操作已取消", "cancelled")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return callOnMain {
            webView ?: run {
                val base = appContext ?: error("browser context unavailable")
                val wrapper = MutableContextWrapper(attachedContainer?.context ?: base)
                val view = WebView(wrapper).apply {
                    setBackgroundColor(Color.WHITE)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.safeBrowsingEnabled = false
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = BrowserClient()
                    webChromeClient = BrowserChrome()
                    installScriptBridgeOnMain(this)
                    setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                        pendingDownload = BrowserDownloadRequest(
                            url = url.orEmpty(),
                            fileName = BrowserDownloadNaming.contentDispositionFileName(contentDisposition),
                            mimeType = mimeType.orEmpty(),
                            contentLength = contentLength,
                        )
                        // 下载会接管这次导航，页面不会再走到 onPageFinished：直接结束等待，别让 navigate 白等到超时。
                        currentLoadWaiter?.complete(LoadOutcome(true, "DOWNLOAD_STARTED", ""))
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                contextWrapper = wrapper
                webView = view
                layoutOffscreenOnMain(view)
                attachedContainer?.let { attachWebViewOnMain(view, it) }
                publishSnapshotOnMain()
                view
            }
        }
    }

    /**
     * 二进制消息：前 4 字节是大端头部长度，头部是 UTF-8 JSON，后面是原始字节。
     * 元信息与内容在同一条消息里，不用靠两条消息的先后顺序配对。
     */
    private fun completeBlobFromBridge(packed: ByteArray) {
        if (packed.size < 4) return
        val headerLength = ((packed[0].toInt() and 0xFF) shl 24) or
            ((packed[1].toInt() and 0xFF) shl 16) or
            ((packed[2].toInt() and 0xFF) shl 8) or
            (packed[3].toInt() and 0xFF)
        if (headerLength <= 0 || packed.size < 4 + headerLength) return
        val header = runCatching {
            JSONObject(String(packed, 4, headerLength, Charsets.UTF_8))
        }.getOrNull() ?: return
        val nonce = header.optString("nonce")
        if (nonce.isBlank()) return
        val future = synchronized(scriptResultsLock) { pendingBlobs.remove(nonce) } ?: return
        if (!header.optBoolean("ok", false)) {
            future.completeExceptionally(
                BrowserFailure("DOWNLOAD_FAILED", header.optString("error").ifBlank { "页面内取文件失败" }),
            )
            return
        }
        future.complete(
            BrowserBridgeBlob(
                mimeType = header.optString("mime_type"),
                fileName = header.optString("file_name").takeIf { it.isNotBlank() },
                bytes = packed.copyOfRange(4 + headerLength, packed.size),
            )
        )
    }

    private fun installScriptBridgeOnMain(view: WebView) {
        scriptBridgeInstalled = false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(view, SCRIPT_BRIDGE_NAME, setOf("*"), scriptBridgeListener)
        }.onSuccess {
            scriptBridgeInstalled = true
        }
    }

    private fun attachWebViewOnMain(view: WebView, container: ViewGroup) {
        (view.parent as? ViewGroup)?.takeIf { it !== container }?.removeView(view)
        if (view.parent == null) {
            container.removeAllViews()
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
        }
    }

    private fun layoutOffscreenOnMain(view: WebView) {
        if (view.width > 0 && view.height > 0) return
        val metrics = (appContext ?: return).resources.displayMetrics
        val width = metrics.widthPixels.coerceIn(720, SCREENSHOT_MAX_WIDTH)
        val height = metrics.heightPixels.coerceIn(1_280, SCREENSHOT_MAX_HEIGHT)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun destroyWebViewOnMain() {
        headerScript?.let { handler -> runCatching { handler.remove() } }
        headerScript = null
        val view = webView ?: return
        if (scriptBridgeInstalled) {
            runCatching { WebViewCompat.removeWebMessageListener(view, SCRIPT_BRIDGE_NAME) }
        }
        scriptBridgeInstalled = false
        synchronized(scriptResultsLock) {
            scriptResults.clear()
            pendingBlobs.clear()
        }
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.stopLoading() }
        runCatching { view.clearHistory() }
        runCatching { view.clearCache(true) }
        runCatching { view.clearFormData() }
        runCatching { view.destroy() }
        webView = null
        contextWrapper = null
    }

    private fun clearSessionStateOnMain() {
        currentUrl = ""
        currentHost = ""
        currentTitle = ""
        currentError = null
        currentHttpStatus = null
        currentProgress = 0
        currentLoading = false
        currentPageVisible = false
        committedMainFrameUrl = ""
        lastAgentRunId = null
        lastAgentToolCallId = null
        navigationGeneration.incrementAndGet()
        publishSnapshotOnMain()
    }

    private fun evaluateObject(view: WebView, body: String): JSONObject {
        throwIfInterrupted()
        val epoch = activeOperationEpoch
        val future = CompletableFuture<String>()
        mainHandler.post {
            if (webView !== view || interrupted.get() || activeOperationEpoch != epoch || epoch == 0L) {
                future.completeExceptionally(BrowserFailure("CANCELLED", "操作已取消", "cancelled"))
            } else {
                runCatching {
                    view.evaluateJavascript(BrowserDomScripts.wrap(body)) { raw ->
                        if (!interrupted.get() && activeOperationEpoch == epoch) {
                            future.complete(raw ?: "null")
                        } else {
                            future.completeExceptionally(BrowserFailure("CANCELLED", "操作已取消", "cancelled"))
                        }
                    }
                }.onFailure(future::completeExceptionally)
            }
        }
        val raw = try {
            future.get(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            if (activeOperationEpoch == epoch) {
                interrupted.set(true)
                operationEpoch.incrementAndGet()
                activeOperationEpoch = 0L
            }
            mainHandler.post { runCatching { view.stopLoading() } }
            throw BrowserFailure("SCRIPT_TIMEOUT", "网页响应超时", "timeout")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
        throwIfInterrupted()
        return decodeEvaluation(raw)
    }

    private fun decodeEvaluation(raw: String): JSONObject {
        val outer = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val decoded = when (outer) {
            is String -> outer
            null, JSONObject.NULL -> throw BrowserFailure("SCRIPT_FAILED", "网页没有返回可读结果")
            else -> outer.toString()
        }
        val envelope = runCatching { JSONObject(decoded) }
            .getOrElse { throw BrowserFailure("SCRIPT_FAILED", "网页结果格式无效") }
        if (!envelope.optBoolean("ok", false)) {
            val code = envelope.optString("error")
            val message = when {
                code.contains("TARGET_NOT_FOUND") ||
                    code.contains("TARGET_NOT_VISIBLE") ||
                    code.contains("TARGET_OCCLUDED") -> "目标网页元素不可见或被其他内容遮挡"
                code.contains("TARGET_DISABLED") -> "目标网页元素当前不可操作"
                code.contains("TARGET_NOT_EDITABLE") -> "目标网页元素不可输入"
                code.contains("not a valid selector", ignoreCase = true) -> "CSS selector 无效"
                else -> "网页元素操作失败"
            }
            throw BrowserFailure("SCRIPT_FAILED", message)
        }
        val value = envelope.opt("value")
        if (value == null || value === JSONObject.NULL) return JSONObject()
        if (value is JSONObject) return value
        if (value is JSONArray) return JSONObject().put("items", value)
        return JSONObject().put("value", value)
    }

    private fun captureViewport(
        view: WebView,
        maxWidth: Int = SCREENSHOT_MAX_WIDTH,
        maxHeight: Int = SCREENSHOT_MAX_HEIGHT,
        quality: Int = SCREENSHOT_QUALITY,
    ): CapturedImage = callOnMain {
        layoutOffscreenOnMain(view)
        val sourceWidth = view.width.coerceAtLeast(1)
        val sourceHeight = view.height.coerceAtLeast(1)
        val scale = minOf(
            1f,
            maxWidth.toFloat() / sourceWidth,
            maxHeight.toFloat() / sourceHeight,
        )
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        if (view.windowToken == null) view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val bitmap = createBitmap(width, height)
        Canvas(bitmap).also { canvas ->
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            view.draw(canvas)
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
        val captured = CapturedImage(bytes, bitmap.width, bitmap.height)
        bitmap.recycle()
        captured
    }

    private fun baseEnvelope(action: String, ok: Boolean, status: String): JSONObject {
        val snapshot = snapshots.value
        return JSONObject()
            .put("ok", ok)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("status", status)
            .put("url", currentUrl)
            .put("display_url", currentUrl)
            .put("host", snapshot.host)
            .put("title", snapshot.title)
            .put("is_loading", snapshot.isLoading)
            .put("can_go_back", snapshot.canGoBack)
            .put("can_go_forward", snapshot.canGoForward)
            .also { json ->
                currentHttpStatus?.let { json.put("http_status", it) }
                // 页面自己触发的下载（例如点了带 Content-Disposition 的链接）在这里报一次，
                // 由调用方决定要不要用 download 动作把它取回来。
                // 代理是进程级状态，跟每个动作的结果一起报出来，避免"以为在抓包其实没抓"。
                activeProxy?.let { json.put("proxy", it) }
                pendingDownload?.let { request ->
                    json.put("download_requested", request.toJson())
                    pendingDownload = null
                }
            }
    }

    private fun mergeValue(target: JSONObject, value: JSONObject): JSONObject = target.apply {
        value.keys().forEach { key -> put(key, value.opt(key)) }
    }

    private fun toolResult(
        envelope: JSONObject,
        images: List<BrowserImage> = emptyList(),
    ): BrowserToolResult = BrowserToolResult(
        content = BrowserPayloadLimiter.serialize(envelope),
        images = images,
    )

    private fun failureResult(action: String, throwable: Throwable): BrowserToolResult {
        val failure = throwable as? BrowserFailure
        val message = failure?.message ?: "浏览器操作失败"
        if (failure?.code !in setOf("CANCELLED", "USER_CONTROL_ACTIVE")) {
            runCatching {
                callOnMain {
                    currentLoading = false
                    currentPageVisible = committedMainFrameUrl.isNotBlank()
                    currentError = message
                    publishSnapshotOnMain()
                }
            }
        }
        return errorResult(
            action = action,
            code = failure?.code ?: "BROWSER_ERROR",
            message = message,
            status = failure?.status ?: "error",
        )
    }

    private fun errorResult(
        action: String,
        code: String,
        message: String,
        status: String = "error",
    ): BrowserToolResult = toolResult(
        baseEnvelope(action, ok = false, status = status)
            .put("code", code)
            .put("message", message)
    )

    private fun publishSnapshotOnMain() {
        val view = webView
        val pageAvailable = view != null && currentUrl.isNotBlank()
        mutableSnapshots.value = BrowserSessionSnapshot(
            available = pageAvailable,
            url = if (pageAvailable) currentUrl else "",
            displayUrl = if (pageAvailable) currentUrl else "",
            host = if (pageAvailable) currentHost else "",
            title = safeTitle(currentTitle),
            isLoading = currentLoading,
            isPageVisible = currentPageVisible,
            hasCommittedPage = committedMainFrameUrl.isNotBlank(),
            progress = currentProgress.coerceIn(0, 100),
            canGoBack = runCatching { view?.canGoBack() == true }.getOrDefault(false),
            canGoForward = runCatching { view?.canGoForward() == true }.getOrDefault(false),
            error = currentError,
            isUserControlling = userControlActive,
            lastAgentRunId = lastAgentRunId,
            lastAgentToolCallId = lastAgentToolCallId,
            proxy = activeProxy,
        )
    }

    private fun safeTitle(value: String): String =
        value.filterNot(Char::isISOControl)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

    private fun setNavigationErrorOnMain(code: String, message: String) {
        navigationGeneration.incrementAndGet()
        currentLoading = false
        currentPageVisible = committedMainFrameUrl.isNotBlank()
        currentError = message
        currentLoadWaiter?.complete(LoadOutcome(false, code, message))
        publishSnapshotOnMain()
    }

    private fun <T> callOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val future = CompletableFuture<T>()
        mainHandler.post {
            runCatching(block)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally)
        }
        return try {
            future.get(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw BrowserFailure("MAIN_THREAD_TIMEOUT", "浏览器主线程响应超时", "timeout")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private class BrowserClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            currentTitle = view.title.orEmpty()
            currentError = null
            currentHttpStatus = null
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            committedMainFrameUrl = currentUrl
            currentPageVisible = true
            currentTitle = view.title.orEmpty()
            currentLoading = false
            currentProgress = 100
            publishSnapshotOnMain()
            currentLoadWaiter?.complete(LoadOutcome(true, "OK", ""))
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            committedMainFrameUrl = url.orEmpty()
            currentPageVisible = true
            publishSnapshotOnMain()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            currentTitle = view.title.orEmpty()
            publishSnapshotOnMain()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            setNavigationErrorOnMain("NETWORK_ERROR", "页面加载失败，请检查网络连接")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (!request.isForMainFrame) return
            currentHttpStatus = errorResponse.statusCode
            if (errorResponse.statusCode >= 400) {
                currentError = "网页返回 HTTP ${errorResponse.statusCode}"
            }
            publishSnapshotOnMain()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            mainHandler.post {
                if (webView === view) {
                    destroyWebViewOnMain()
                    currentError = "网页渲染进程已退出，请重新打开页面"
                    currentLoading = false
                    currentPageVisible = false
                    committedMainFrameUrl = ""
                    publishSnapshotOnMain()
                }
            }
            currentLoadWaiter?.complete(LoadOutcome(false, "RENDERER_GONE", "网页渲染进程已退出"))
            return true
        }
    }

    private class BrowserChrome : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            currentTitle = title.orEmpty()
            publishSnapshotOnMain()
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            currentProgress = newProgress.coerceIn(0, 100)
            currentLoading = newProgress < 100
            publishSnapshotOnMain()
        }
    }

    private data class BrowserBridgeBlob(
        val mimeType: String,
        val fileName: String?,
        val bytes: ByteArray,
    )

    private data class BrowserReadMark(
        val runId: String?,
        val key: String,
        val textHash: Int,
    )

    private data class BrowserDownloadRequest(
        val url: String,
        val fileName: String?,
        val mimeType: String,
        val contentLength: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("url", url)
            .put("file_name", fileName ?: JSONObject.NULL)
            .put("mime_type", mimeType)
            .put("content_length", contentLength)
    }

    private data class BrowserTarget(
        val selector: String?,
        val x: Int?,
        val y: Int?,
    )

    private data class CapturedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    private data class LoadOutcome(
        val ok: Boolean,
        val code: String,
        val message: String,
    )

    private class LoadWaiter {
        private val latch = CountDownLatch(1)

        @Volatile
        private var outcome: LoadOutcome? = null

        fun complete(value: LoadOutcome) {
            if (outcome != null) return
            synchronized(this) {
                if (outcome == null) {
                    outcome = value
                    latch.countDown()
                }
            }
        }

        fun await(timeoutMs: Long): LoadOutcome? =
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) outcome else null
    }

    private class BrowserFailure(
        val code: String,
        override val message: String,
        val status: String = "error",
    ) : RuntimeException(message)

    private val SUPPORTED_ACTIONS = setOf(
        "navigate",
        "get_readable",
        "get_text",
        "find_elements",
        "click",
        "type",
        "scroll",
        "screenshot",
        "get_page_info",
        "go_back",
        "go_forward",
        "reload",
        "wait_for_selector",
        "evaluate_js",
        "get_cookies",
        "set_cookie",
        "set_proxy",
        "clear_proxy",
        "download",
    )

}
