package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    /** 单次 actions 数组允许的步数上限。 */
    const val MAX_BATCH_ACTIONS = 10

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "操作 Eta 共享的离屏 Agent 浏览器，不会切换到外部浏览器。单个动作用 action；已确定无疑问的连续多步可以用 actions 数组一次提交，按顺序执行，某步失败即停止并回报失败步序号；网页浏览通常先 navigate，再用 get_readable 提取正文，或用 find_elements 查找可交互元素。get_readable 的返回里 extractor 标明这次走了哪条提取路径（readability / heuristic / +body-text），非 readability 路径会附 link_density（链接文字占比）与 noise_candidates（疑似导航、广告、推荐位等容器的数量，只统计不删除）：两者偏高说明结果可能混入页面杂项，宜用选择器重取或核对原文。evaluate_js 可在页面里执行 JS 表达式（支持 await），get_cookies 与 set_cookie 读写该浏览器的 Cookie，set_proxy 与 clear_proxy 控制该进程内所有 WebView 的代理（例如指向本机抓包工具），设置后 download 也走同一条代理，download 把 http(s) 文件保存到公共下载目录并返回路径。需要把 URI 显式交给外部应用时使用 open_uri。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "本次唯一执行的浏览器动作。")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                            .put("evaluate_js")
                                            .put("get_cookies")
                                            .put("set_cookie")
                                            .put("set_proxy")
                                            .put("clear_proxy")
                                            .put("download")
                                    )
                            )
                            .put(
                                "url",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "navigate、get_cookies、set_cookie 或 download 要访问的 URL；后三者省略时使用当前页面地址；download 支持 http(s)，也支持页面内的 blob:/data:（由页面读出内容再回传）。")
                            )
                            .put(
                                "user_agent",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "navigate 时使用的 User-Agent；传空字符串恢复系统默认值，省略则沿用当前设置。")
                            )
                            .put(
                                "headers",
                                JSONObject()
                                    .put("type", "object")
                                    .put("additionalProperties", JSONObject().put("type", "string"))
                                    .put("description", "navigate 时附加的请求头：本次导航的主文档请求一定带上；WebView 支持文档开始注入时，页面自己发出的同源 fetch/XHR 也会带上（跨源不加，自定义头会触发预检）。")
                            )
                            .put(
                                "cookie",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "set_cookie 要写入的 cookie，格式与 Set-Cookie 响应头一致，例如 session=abc; Domain=.example.com; Path=/。")
                            )
                            .put(
                                "file_name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "download 保存的文件名，省略时按 Content-Disposition 或 URL 末段命名，落盘目录固定为 Download/Eta。")
                            )
                            .put(
                                "proxy",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "set_proxy 的代理地址，格式 [scheme://]host[:port]，例如 127.0.0.1:8080；scheme 支持 http、https、socks。")
                            )
                            .put(
                                "expression",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "evaluate_js 要执行的单个 JS 表达式，可用 await；多语句请自行包成 (async () => { ... })()。SPA 或还没渲染完的页面结构与预期可能不同，取 querySelector 结果前先判空；脚本自身抛错会以 code=SCRIPT_ERROR 原样返回，先看是不是表达式假设错了。")
                            )
                            .put(
                                "selector",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "click、type、get_text、find_elements 或 wait_for_selector 使用的 CSS selector。")
                            )
                            .put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "type 要输入的文本。只会发送给工具，不会显示在运行摘要中。")
                            )
                            .put(
                                "submit",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "type 输入后是否提交所在表单，默认 false。")
                            )
                            .put(
                                "coordinate_x",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 或 type 的视口 X 坐标，和 coordinate_y 一起使用。")
                            )
                            .put(
                                "coordinate_y",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 或 type 的视口 Y 坐标，和 coordinate_x 一起使用。")
                            )
                            .put(
                                "amount",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "scroll 的滚动像素量。")
                            )
                            .put(
                                "direction",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("up").put("down"))
                                    .put("description", "scroll 的滚动方向。")
                            )
                            .put(
                                "offset",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 或 get_text 的文本起始偏移，默认 0。")
                            )
                            .put(
                                "max_chars",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable、get_text 或 evaluate_js 最多返回的字符数；evaluate_js 默认 2000，上限 2500。")
                            )
                            .put(
                                "read_image",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "screenshot 时是否把截图附给模型直接查看，默认 true。")
                            )
                            .put(
                                "timeout_ms",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "navigate、wait_for_selector 或 evaluate_js 的超时毫秒数。")
                            )
                            .put(
                                "actions",
                                JSONObject()
                                    .put("type", "array")
                                    .put("maxItems", MAX_BATCH_ACTIONS)
                                    .put("items", JSONObject().put("type", "object"))
                                    .put("description", "连续多步时使用：每项是 {action, ...该动作自己的参数}，按顺序执行；某一步失败即停止，并回报该步的 index、code 与 message。与 action 同时给出时以 actions 为准。")
                            )
                    )
                    .put("required", JSONArray())
            )
        )
    }

}
