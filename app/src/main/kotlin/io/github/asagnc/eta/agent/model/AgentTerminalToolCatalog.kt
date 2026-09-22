package io.github.asagnc.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "terminal",
                    description = "Manage terminal sessions on the current device. environment=android runs Android system commands and root operations; environment=linux runs the distribution selected in Eta settings, and environment=debian, ubuntu or kali runs that installed distribution directly. Apktool build is unavailable until an ARM64 AAPT2 runtime is installed. Use open_and_exec for one-shot commands. Use open to create a persistent shell session and exec with session_id for multi-step work. Use async=true without session_id for long-running independent commands, then read_async_result with job_id to stream output chunks. Use daemon_start for services that must keep running after the Agent run (listening ports, web panels, watchers): the process detaches from any command shell, logs to a file, and survives until daemon_stop or device reboot. Manage daemons with daemon_list, daemon_logs and daemon_stop by task_id. Use close to stop jobs or close sessions.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("open")
                                                .put("exec")
                                                .put("open_and_exec")
                                                .put("read_async_result")
                                                .put("close")
                                                .put("daemon_start")
                                                .put("daemon_list")
                                                .put("daemon_logs")
                                                .put("daemon_stop")
                                                .put("tasks_list")
                                        )
                                        .put("description", "open creates a session. exec runs command in a session or cwd. open_and_exec runs a one-shot command. read_async_result reads async output by job_id. close closes a session_id or job_id. daemon_start launches a detached long-lived service and returns task_id. daemon_list lists daemon tasks with liveness. daemon_logs tails a task log. daemon_stop terminates and removes a task. tasks_list lists every live session, async job and daemon task in one call.")
                                )
                                .put(
                                    "identity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("user").put("root"))
                                        .put("description", "宿主执行身份。Android 默认使用当前可用身份；Linux 根据已选择的后端使用 user 或 root。PRoot 内模拟 root 不授予 Android 特权。")
                                )
                                .put(
                                    "environment",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray().put("android").put("linux")
                                                .put("debian").put("ubuntu").put("kali")
                                        )
                                        .put("description", "android uses the native Android shell with BusyBox applets when available. linux uses the distribution selected in Eta settings; debian, ubuntu and kali target that distribution, which must be installed first. Default android.")
                                )
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Android shell command to execute. Required for exec/open_and_exec.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory. Defaults to /data/local/tmp/eta for android and /workspace for linux. Relative paths use the environment default. ~/ means /storage/emulated/0.")
                                )
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Command timeout in milliseconds. Default 30000, max 600000. The command is terminated once it expires; use a daemon task for long-running services.")
                                )
                                .put(
                                    "merge_stderr",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether stderr should be appended to stdout in command responses.")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Session id returned by action=open. Use with exec or close.")
                                )
                                .put(
                                    "job_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Async job id returned when async=true. Use with read_async_result or close.")
                                )
                                .put(
                                    "task_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Daemon task id returned by daemon_start. Use with daemon_logs or daemon_stop.")
                                )
                                .put(
                                    "async",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Start command in a separate background shell and return immediately with job_id. Do not combine with session_id. Use read_async_result to stream output.")
                                )
                                .put(
                                    "offset_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, read stdout from this character offset. Default 0.")
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, maximum stdout characters to return. Default 8000, max 16000.")
                                )
                                .put(
                                    "close_if_done",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "For read_async_result, remove the async job when it has completed.")
                                )
                        )
                        .put("required", JSONArray().put("action"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "run_command",
                    description = "在 Android 设备上用非交互 Root Shell 执行单条命令，每次调用都是新 shell，超时上限 180 秒。需要会话复用、异步任务、后台服务或更长超时时改用 terminal。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要执行的 shell 命令，可使用管道和重定向。")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "工作目录，默认 /data/local/tmp/eta。相对路径也按该目录解析；用户存储可用 ~/ 表示 /storage/emulated/0。")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "超时秒数，1 到 180，默认 30。")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "read_file",
                    description = "读取文件内容。给定 start_line/end_line 时按行返回并带真实行号，适合定点查看大文件；否则按 offset_bytes/max_bytes 读取字节。" +
                        "两种模式都会给出续读位置：字节模式看 next_offset_bytes（配合 total_bytes），行模式看 next_start_line；truncated=true 表示还有内容没返回。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put(
                                    "offset_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "从第几个字节开始，默认 0。")
                                )
                                .put(
                                    "max_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多读取字节数，1 到 262144，默认 65536。")
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "按行读取的起始行号，从 1 开始；给定后忽略 offset_bytes。")
                                )
                                .put(
                                    "end_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "按行读取的结束行号；省略表示读到文件末尾。")
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "按行模式一次返回的最大字符数，200 到 32000，默认 16000；被截断时 truncated 为 true。仅行模式使用，字节模式看 max_bytes。")
                                )
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "write_file",
                    description = "写入 Android 文件。可覆盖或追加；会自动创建父目录。用于明确需要修改文件的任务。" +
                        "写入是原子的：先写同目录临时文件并校验（大小 + sha256），通过后才 rename 顶替，并保留原文件的权限/属主/SELinux 上下文；" +
                        "校验不过会直接报错且原文件一个字节都不动。覆盖不会先读原文件，改局部内容请用 edit_file。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("content", JSONObject().put("type", "string"))
                                .put(
                                    "append",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "true 追加，false 覆盖，默认 false。")
                                )
                        )
                        .put("required", JSONArray().put("path").put("content"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "list_directory",
                    description = "列出 Android 目录内容。默认 /data/local/tmp/eta，输出类似 ls -l（不含 . 与 ..）；" +
                        "返回 entry_count（目录内条目总数）与 truncated（是否因 limit 截断）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("show_hidden", JSONObject().put("type", "boolean"))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 200 行，默认 80。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "find_files",
                    description = "按文件名（glob）递归查找文件，只返回匹配的路径：适合先定位有哪些文件，而不是先列目录再逐个看。" +
                        "默认遵守 .gitignore 并跳过隐藏文件；一个都没找到时会回报是否被忽略规则挡掉。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string").put("description", "起始目录，默认 /data/local/tmp/eta。"))
                                .put("glob", JSONObject().put("type", "string").put("description", "文件名匹配，例如 *.kt 或 SKILL.md；不支持引号、分号、管道等 Shell 字符。"))
                                .put("limit", JSONObject().put("type", "integer").put("description", "最多返回 1 到 200 条，默认 80。"))
                                .put("no_ignore", JSONObject().put("type", "boolean").put("description", "true 时不遵守 .gitignore，默认 false。"))
                                .put("hidden", JSONObject().put("type", "boolean").put("description", "true 时连隐藏文件一起找，默认 false。"))
                        )
                        .put("required", JSONArray().put("glob"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_code",
                    description = "在文件或目录里按正则检索内容，返回 文件:行号:内容。适合在代码库或日志目录里定位关键词，输出比在 terminal 里拼 grep 更紧凑可控。" +
                        "命中范围不清楚时先加 files_only=true 看分布，再对目标文件精读，比反复调整 pattern 更快。" +
                        "默认遵守 .gitignore 并跳过隐藏文件（所以 0 命中不等于不存在：真被过滤掉时 hint 会说明，可用 no_ignore/hidden 一起搜）；" +
                        "结果被截断时按 next_offset 翻页。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string").put("description", "文件或目录路径，默认 /data/local/tmp/eta。"))
                                .put("pattern", JSONObject().put("type", "string").put("description", "正则表达式（POSIX ERE 风格，\\d/\\s/\\w 会被自动翻成 [0-9]/[[:space:]]/[[:alnum:]]），按单行内容匹配；不支持反向引用与环视。"))
                                .put("glob", JSONObject().put("type", "string").put("description", "文件名过滤，例如 *.kt；省略表示不过滤。"))
                                .put("max_results", JSONObject().put("type", "integer").put("description", "每页最多返回的匹配行数，1 到 500，默认 50。"))
                                .put("context_lines", JSONObject().put("type", "integer").put("description", "每条匹配附带的上下文行数，0 到 5，默认 0。"))
                                .put("max_chars", JSONObject().put("type", "integer").put("description", "返回内容的最大字符数，200 到 32000，默认 8000；被截断时 truncated 为 true。"))
                                .put("files_only", JSONObject().put("type", "boolean").put("description", "true 时只返回每个文件的命中行数，不返回内容：先用它看命中分布在哪些文件，再对目标文件精读。默认 false。"))
                                .put("ignore_case", JSONObject().put("type", "boolean").put("description", "true 时忽略大小写，默认 false。"))
                                .put("offset", JSONObject().put("type", "integer").put("description", "跳过前 N 条命中（翻页用），默认 0；配合 next_offset。"))
                                .put("no_ignore", JSONObject().put("type", "boolean").put("description", "true 时不遵守 .gitignore，默认 false。"))
                                .put("hidden", JSONObject().put("type", "boolean").put("description", "true 时连隐藏文件一起搜，默认 false。"))
                        )
                        .put("required", JSONArray().put("pattern"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "edit_file",
                    description = "用 old_text 精确替换文件内容，成功后返回改动差异。old_text 必须在文件中唯一命中，否则不修改文件并回报命中行号；改动局部内容时用它代替整文件重写。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("old_text", JSONObject().put("type", "string").put("description", "待替换的原文，需与文件中文本完全一致，含缩进。"))
                                .put("new_text", JSONObject().put("type", "string").put("description", "替换后的文本；传空字符串表示删除该段。"))
                                .put("replace_all", JSONObject().put("type", "boolean").put("description", "true 时替换全部命中；默认 false，只允许唯一命中。"))
                        )
                        .put("required", JSONArray().put("path").put("old_text").put("new_text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "read_files",
                    description = "一次读取多个文件：需要同时看若干个文件时用它，比连续多次 read_file 少很多轮往返。" +
                        "总字符预算在文件之间共享（不像 read_file 每个文件各自一份），每个文件占一段，段头是「=== 路径（共 N 行）===」，段内行号是真实行号。" +
                        "某个路径读不到只记进 failures，不影响其余文件；某段被截断时它的 next_start_line 给出续读位置，可再用 read_file 单独续读。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "paths",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("items", JSONObject().put("type", "string"))
                                        .put("description", "要读取的文件路径列表，建议不超过 8 个。"),
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "所有文件合计的最大字符数，200 到 32000，默认 16000；被截断的段会给 next_start_line。"),
                                )
                        )
                        .put("required", JSONArray().put("paths"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "edit_files",
                    description = "一次对多个文件做定点替换（跨文件重命名、改同一个常量等）。" +
                        "先对全部条目做校验，全部通过才写盘；任一条失败就整批放弃、一个字节都不写，并在 failures 里给出每条失败的原因（未命中会给最接近的原文与首处差异字符，多处命中会列出各行上下文）。" +
                        "同一路径可以出现多次，按顺序叠加；不提供整文件 content，整文件重写请用 write_file。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "edits",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("description", "替换条目列表，按给定顺序校验与写入。")
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "object")
                                                .put(
                                                    "properties",
                                                    JSONObject()
                                                        .put("path", JSONObject().put("type", "string"))
                                                        .put("old_text", JSONObject().put("type", "string").put("description", "待替换的原文，需与文件中文本完全一致，含缩进。"))
                                                        .put("new_text", JSONObject().put("type", "string").put("description", "替换后的文本；传空字符串表示删除该段。"))
                                                        .put("replace_all", JSONObject().put("type", "boolean").put("description", "true 时替换该文件里全部命中；默认 false，只允许唯一命中。")),
                                                )
                                                .put("required", JSONArray().put("path").put("old_text").put("new_text")),
                                        ),
                                )
                        )
                        .put("required", JSONArray().put("edits"))
                )
            )
    }

}
