package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文件路径到模型视觉输入的通用能力，不依赖任何个人数据 Provider。 */
internal object AgentFileVisionToolCatalog {
    /** 单次调用携带的图片数量上限：一次读太多会同时撑爆上下文和工具结果。 */
    const val MAX_IMAGES_PER_CALL = 4

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "读取用户指定路径或系统相册 URI 中的图片，并作为视觉输入提供给模型。" +
                    "单张用 path，多张用 paths（一次最多 $MAX_IMAGES_PER_CALL 张）；需要更多时再发起下一次调用。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "path",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 1_024)
                                    .put("description", "任意绝对图片路径、file URI 或系统相册 content URI；本机路径由 Root 读取"),
                            )
                            .put(
                                "paths",
                                JSONObject()
                                    .put("type", "array")
                                    .put("maxItems", MAX_IMAGES_PER_CALL)
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", 1_024),
                                    )
                                    .put("description", "一次读取多张图片时使用，按顺序读取；与 path 同时给出时以 paths 为准。"),
                            ),
                    )
                    .put("required", JSONArray()),
            ),
        )
    }
}
