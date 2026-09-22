package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 屏幕手势与节点交互工具 schema。 */
internal object AgentGestureToolCatalog {
    /**
     * 序列步骤的公共结构。
     *
     * run_sequence 与 save_flow 接受同一组字段，定义在这里避免两边漂移；
     * 两者的差异只在各自 description 说明的限制（save_flow 不允许 index/observation_id）。
     */
    private fun stepItems(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("action", JSONObject().put("type", "string"))
                .put("index", JSONObject().put("type", "integer"))
                .put("observation_id", JSONObject().put("type", "string"))
                .put("text", JSONObject().put("type", "string"))
                .put("button", JSONObject().put("type", "string"))
                .put("duration_ms", JSONObject().put("type", "integer"))
                .put("timeout_ms", JSONObject().put("type", "integer"))
                .put("direction", JSONObject().put("type", "string"))
                .put("x", JSONObject().put("type", "integer"))
                .put("y", JSONObject().put("type", "integer"))
                .put("x1", JSONObject().put("type", "integer"))
                .put("y1", JSONObject().put("type", "integer"))
                .put("x2", JSONObject().put("type", "integer"))
                .put("y2", JSONObject().put("type", "integer")),
        )
        .put("required", JSONArray().put("action"))

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tap",
                    description = "点击坐标。默认使用最近一次 observe_screen 截图里的像素坐标；如果坐标来自 ui_nodes 的 center，请设置 coordinate_space=screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_area",
                    description = "点击矩形区域中心。默认使用最近一次 observe_screen 截图里的像素坐标；大按钮、大列表项和可见文字区域优先用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_element",
                    description = "点击指定观察快照中的 UI 节点。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Sta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press",
                    description = "长按坐标。默认使用最近一次 observe_screen 截图里的像素坐标；如果坐标来自 ui_nodes 的 center，请设置 coordinate_space=screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press_element",
                    description = "长按指定观察快照中的 UI 节点。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Sta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "swipe",
                    description = "从一个坐标滑动到另一个坐标。默认使用最近一次 observe_screen 截图里的像素坐标。向上滑动会让列表向下滚动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "滑动时长，100 到 2000，默认 500")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "drag",
                    description = "长按起点后拖到终点，用于拖动排序、滑块和拖放。默认使用最近一次 observe_screen 截图里的像素坐标。只是滑动、不需要先按住的场景用 swipe。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "hold_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "按住起点的时长，100 到 2000，默认 500")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "拖动时长，100 到 3000，默认 600")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll",
                    description = "按内容浏览方向滚动当前屏幕：down 显示下方内容，up 显示上方内容，left 显示左侧内容，right 显示右侧内容。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll_element",
                    description = "按内容浏览方向滚动指定观察快照中的可滚动 UI 节点：down 显示下方内容，up 显示上方内容，left 显示左侧内容，right 显示右侧内容。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Sta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的可滚动 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                        .put("description", "内容浏览方向；down 显示下方内容，up 显示上方内容。")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id").put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "run_sequence",
                    description = "按顺序执行一组屏幕操作，一次调用覆盖多步、减少模型往返。每个 step 用对应工具的入参表达：{action: 'tap_element', index, observation_id}、{action: 'tap_text', text}（按可见文本点击，适合流程复用）、{action: 'input', text}、{action: 'replace', text}、{action: 'press', button}、{action: 'wait', duration_ms}、{action: 'wait_text', text, timeout_ms}、{action: 'swipe', x1, y1, x2, y2}、{action: 'scroll', direction}。任何一步失败立即停止，返回 failed_step、已执行步骤与当前屏幕摘要；只放确定性步骤，需要现场判断的分支操作不要放进序列。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "steps",
                                    JSONObject()
                                        .put("type", "array")
                                        .put(
                                            "description",
                                            "按顺序执行的步骤数组；action 与对应工具一致：tap / tap_area / tap_element / long_press / swipe / drag / scroll / input / replace / clear / press / wait / wait_text / wait_package"
                                        )
                                        .put("items", stepItems())
                                )
                        )
                        .put("required", JSONArray().put("steps"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "save_flow",
                    description = "把一组稳定的屏幕操作保存为流程，供以后同类任务直接复用。steps 只允许语义化动作（tap_text/input/replace/clear/press/wait/wait_text/wait_package/swipe/scroll），不允许带 index/observation_id。只有确定会重复的操作才保存。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "流程名，仅字母数字 _-，不超过 80 字符"))
                                .put("description", JSONObject().put("type", "string").put("description", "用途说明，帮助下次匹配"))
                                .put(
                                    "steps",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("description", "语义化步骤数组，与 run_sequence 的 steps 相同但只允许语义动作")
                                        .put("items", stepItems())
                                )
                        )
                        .put("required", JSONArray().put("name").put("steps"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "use_flow",
                    description = "按名称执行已保存的流程，一次完成整段操作；找不到流程或执行失败时返回错误并附当前屏幕。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "已保存的流程名"))
                        )
                        .put("required", JSONArray().put("name"))
                )
            )
    }
}
