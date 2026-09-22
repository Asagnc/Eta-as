package io.github.asagnc.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文本输入、等待与系统操作工具 schema。 */
internal object AgentTextSystemToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "input_text",
                    description = "向真正获得输入焦点的输入框键入不超过 1000 字符的文本。默认 mode=append，会在当前光标插入或替换选区；密码等不可读输入框会拒绝重建，请用 replace_text 提供完整值。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "要输入的文本，最多 1000 字符；需要无障碍服务确认真实输入焦点。")
                                )
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("append").put("replace").put("paste"))
                                        .put("description", "append 在光标键入或替换选区，replace 替换文本，paste 使用粘贴路径；本工具三种模式都限 1000 字符。默认 append。")
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "mode=replace 时可指定 editable 节点 index；必须同时传入同一次 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "mode=replace 且指定 index 时必传，必须与 index 来自同一次最近 observe_screen。")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "replace_text",
                    description = "把当前聚焦输入框或指定 editable 节点的文本替换为给定内容。指定 index 时，index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。需要启用无障碍服务。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 4_000),
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "可选，最近一次 observe_screen 的 editable 节点 index；传入时必须同时传入同一次观察的 observation_id，不传则使用当前聚焦输入框。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "指定 index 时必传，且必须与 index 来自同一次最近 observe_screen；不指定 index 时省略。")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "clear_text",
                    description = "清空当前聚焦输入框或指定 editable 节点。指定 index 时，index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。需要启用无障碍服务。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "可选，最近一次 observe_screen 的 editable 节点 index；传入时必须同时传入同一次观察的 observation_id，不传则使用当前聚焦输入框。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "指定 index 时必传，且必须与 index 来自同一次最近 observe_screen；不指定 index 时省略。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "set_clipboard",
                    description = "把文本写入系统剪贴板。适合准备粘贴长文本、中文、emoji 或特殊字符。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "get_clipboard",
                    description = "读取系统剪贴板文本。Android 版本或后台限制可能导致读取失败。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "paste_text",
                    description = "确认真实输入焦点后按当前选区输入长文本；目标不支持直接设置时才回退系统剪贴板粘贴。无焦点时不会覆盖剪贴板；密码等不可读字段请改用 replace_text 提供完整值。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "press_key",
                    description = "按系统按键或全局动作。BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS/MENU/DPAD_UP/DPAD_DOWN/DPAD_LEFT/DPAD_RIGHT/DPAD_CENTER 优先走无障碍全局动作；ENTER 优先走输入法回车。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "button",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("BACK")
                                                .put("HOME")
                                                .put("ENTER")
                                                .put("RECENTS")
                                                .put("PASTE")
                                                .put("NOTIFICATIONS")
                                                .put("QUICK_SETTINGS")
                                                .put("MENU")
                                                .put("DPAD_UP")
                                                .put("DPAD_DOWN")
                                                .put("DPAD_LEFT")
                                                .put("DPAD_RIGHT")
                                                .put("DPAD_CENTER")
                                        )
                                )
                        )
                        .put("required", JSONArray().put("button"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait",
                    description = "等待一段时间，让动画、网络加载或页面跳转完成。不要用它代替 wait_for_text/wait_for_package 的可验证等待。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "等待时长，100 到 30000，默认 1000。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_text",
                    description = "等待当前屏幕出现指定文本或描述，适合点击后确认页面已到达、列表加载完成、弹窗出现。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最长等待时间，500 到 60000，默认 10000。")
                                )
                                .put(
                                    "include_desc",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否匹配 content-desc，默认 true。")
                                )
                                .put(
                                    "match",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("contains").put("exact").put("prefix").put("regex"))
                                        .put("description", "匹配方式，默认 contains。")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_package",
                    description = "等待指定 Android package 到前台，适合 launch_app/open_uri 后确认目标应用已打开。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("package_name", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最长等待时间，500 到 60000，默认 10000。")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_system_panel",
                    description = "打开通知栏或快捷设置面板。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "panel",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("notifications").put("quick_settings"))
                                )
                        )
                        .put("required", JSONArray().put("panel"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "world_trace",
                    description = "查历史委派树：某次任务里派了哪些子智能体、它们各自看了什么、结论是什么。" +
                        "world_recall 查的是「结论」，这里查的是「现场」——当你想知道某条结论是怎么得出的、" +
                        "或者想确认子智能体当时确实查了某个东西时用它。" +
                        "不给参数时列最近几次委派；给 trace 看整棵树；给 node 看某个节点的完整过程。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "trace",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要查看的委派树的 trace id；省略则列最近的委派。"),
                                )
                                .put(
                                    "node",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要看完整过程的节点 id（树里会显示）。"),
                                ),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "world_recall",
                    description = "检索观测库里的历史结论：之前跑过的任务、子智能体调研出的结论、" +
                        "工具失败教训都在里面。开始一项新任务前，或遇到看起来以前处理过的问题时，" +
                        "先用它查一查——命中就省掉重新调研。每条结果都带「多久之前」和新鲜度标注，" +
                        "写着「依赖的文件已变更」的结论不要直接当事实用，要重新核实。" +
                        "查不到不代表没做过，可以换个关键词再查。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "检索关键词，例如文件路径、报错码、功能名。"),
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回多少条，默认 8，上限 20。"),
                                ),
                        )
                        .put("required", JSONArray().put("query")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "task_plan",
                    description = "维护当前任务清单。需要多个步骤才能完成的任务先用它列出计划，" +
                        "每开始一步把它标为 in_progress、确认完成后标为 completed；" +
                        "简单的一两步任务不需要清单。每次调用提交完整清单，不是增量修改；" +
                        "要去掉某一项就不再提交它，传空数组即清空整份清单。" +
                        "同一时间只能有一项 in_progress，不要从 pending 直接跳到 completed。" +
                        "结束这一轮之前，清单里不能留下没做完的项：要么标为 completed，" +
                        "要么从清单里去掉；确实要留到以后做的，在回复里说明原因。" +
                        "调整计划时也说明为什么改。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "todos",
                                JSONObject()
                                    .put("type", "array")
                                    .put("description", "当前完整任务清单；传空数组表示清空。")
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject()
                                                    .put("id", JSONObject().put("type", "string")
                                                        .put("description", "稳定标识，例如 inspect-project；同一任务复用同一 id。"))
                                                    .put("content", JSONObject().put("type", "string")
                                                        .put("description", "可执行、可验证的具体步骤，不要写成空泛目标。"))
                                                    .put(
                                                        "status",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("enum", JSONArray().put("pending").put("in_progress").put("completed")),
                                                    ),
                                            )
                                            .put("required", JSONArray().put("id").put("content").put("status")),
                                    ),
                            ),
                        )
                        .put("required", JSONArray().put("todos")),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "submit_plan",
                    description = "在动手之前提交一份方案，等用户确认。复杂任务（多文件改动、需求有" +
                        "歧义、要动系统设置、方案有多种走法）先用它：先派子智能体检索、把细节搞清楚，" +
                        "再把方案写清楚提交。提交后本轮就结束了，写类和改设备的工具会被拦下，" +
                        "所以不要提交完还接着干活。方案与任务清单分工不同：方案回答「做什么、为什么」" +
                        "（一次产出、确认后固定），清单回答「做到哪」（每轮更新）。" +
                        "digest 是给后续轮次注入的方向摘要，必须精简；content 是给人看的完整正文，" +
                        "可以详细，支持 markdown。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "title",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "一句话说清要做什么。"),
                                )
                                .put(
                                    "digest",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "方向摘要（建议 200 字以内）：目标、约束、关键决策。" +
                                                "后续每轮都靠它注入，正文再长也不影响。",
                                        ),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "完整方案正文（markdown）：背景、调研结论、方案对比与选择" +
                                                "理由、实施步骤、风险与回退。",
                                        ),
                                )
                                .put(
                                    "steps",
                                    JSONObject()
                                        .put("type", "array")
                                        .put(
                                            "description",
                                            "结构化步骤，每项一句话。用户点「按此执行」时直接用它们" +
                                                "初始化任务清单，所以要和正文里的步骤一致。",
                                        )
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "object")
                                                .put(
                                                    "properties",
                                                    JSONObject()
                                                        .put(
                                                            "id",
                                                            JSONObject()
                                                                .put("type", "string")
                                                                .put("description", "稳定标识，例如 step-1。"),
                                                        )
                                                        .put(
                                                            "content",
                                                            JSONObject()
                                                                .put("type", "string")
                                                                .put("description", "这一步要做什么。"),
                                                        ),
                                                )
                                                .put("required", JSONArray().put("id").put("content")),
                                        ),
                                )
                                .put(
                                    "alternatives",
                                    JSONObject()
                                        .put("type", "array")
                                        .put(
                                            "description",
                                            "备选方案（可选）：只写思路与取舍，完整正文只对推荐方案写。" +
                                                "用户想换思路时会照它重新规划。",
                                        )
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "object")
                                                .put(
                                                    "properties",
                                                    JSONObject()
                                                        .put(
                                                            "title",
                                                            JSONObject()
                                                                .put("type", "string")
                                                                .put("description", "备选方案名。"),
                                                        )
                                                        .put(
                                                            "summary",
                                                            JSONObject()
                                                                .put("type", "string")
                                                                .put("description", "一句话思路。"),
                                                        )
                                                        .put(
                                                            "tradeoff",
                                                            JSONObject()
                                                                .put("type", "string")
                                                                .put("description", "取舍：好在哪、代价是什么。"),
                                                        ),
                                                )
                                                .put("required", JSONArray().put("title").put("summary")),
                                        ),
                                ),
                        )
                        .put(
                            "required",
                            JSONArray().put("title").put("digest").put("content").put("steps"),
                        ),
                )
            )
    }
}
