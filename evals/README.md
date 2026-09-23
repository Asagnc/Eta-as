# Eval

harness 与环境改动只有两种结局：变好、变差。没有数字就分不清是哪种，所以这里的规则是
**任何改动都要给出与基线的对比数字，没有数字的改动一律回退**。

## 两层，一套任务集

| 层 | 跑在哪 | 衡量什么 | 抓手 |
| --- | --- | --- | --- |
| 外部 | `run_eval.py`（本机 python3） | provider 与模型行为：是否选对工具、参数是否符合 Schema、轮次、token、失败类型分布 | 任务集 + 工具快照 |
| 内部 | App 内的同一次 run | harness 行为：工具调用次数与失败数、耗时、并发批次、token | `run_stats` 工具 + 归档事件 `RunStatsReported` |

两层共用 `tasks.json`：外部层只发提示词与工具声明，不执行工具；内部层由人把同一条提示词交给
App 里的 Sta，再用 `run_stats` 读回度量。跨 provider 比较稳定性看外部的失败类型分布，
判断 harness 改动看内部与外部共用的轮次／token／成功率。

## 运行

```bash
export STA_EVAL_API_KEY=...            # 只从环境变量读取，仓库里不写任何凭据
python3 evals/run_eval.py --provider-url https://example.com/v1 --model deepseek-v4-flash
python3 evals/run_eval.py --dry-run    # 本地桩模型，验证脚本本身，不联网
python3 evals/run_eval.py --baseline evals/results/baseline.json --repeat 3
```

- 结果写在 `evals/results/<UTC 时间>-<模型>.json`，含逐条记录与汇总；通过率低于 100% 时退出码为 1。
- `--repeat` 用来观察同一 provider 的波动：单次通过率不能代表稳定性，失败类型分布更可靠。
- 中转站前面常有 WAF，别做高频探测（会被要求滑动验证并整站返回 HTML）；失败样本按条追加，不要并发轰炸。

## 任务集与工具快照

- `tasks.json`：来自这台设备上的真实使用——设备查询、源码定位与改动、APK 初筛、抓包环境、
  Skill 执行、UI 观察。`held_out` 标记留出子集，只用于检查是否对任务集过拟合，调优时不要看它。
- `tools.json`：外部层发出的工具声明必须是模型真正看到的那一份，否则结论会指错方向。
  这份快照由单测生成与校验：

  ```bash
  STA_EVAL_WRITE_SNAPSHOT=1 ./gradlew :app:testDebugUnitTest --tests "*EvalToolSnapshotTest*"   # 重新生成
  ./gradlew :app:testDebugUnitTest --tests "*EvalToolSnapshotTest*"                             # 校验漂移
  ```

  改动工具 Schema 而没同步快照时，后者会失败并打印两份内容。

## 失败类型

`auth_error`／`rate_limited`／`server_error`／`http_error`／`network_error`／`invalid_response`
来自 HTTP 与解析层；`no_tool_call`／`wrong_tool`／`unknown_tool`／`bad_arguments` 来自模型行为。
后四类才是 harness 与提示词要修的对象，前三类属于 provider 或网络，换一个中转站再测。

## 实验怎么跑

- **命名空间（工具名前缀 vs 后缀）**：`--tool-name-style prefixed` 会把同一批工具改名成 `sta_<name>` 再发，
  其他条件不变，对比两次的通过率与 `wrong_tool` 分布即可。这是纯测量，不改 App 行为。
- **上下文策略**：`agent_context_notice_percent`（默认 60，设 0 关闭提示）与 `agent_tool_result_keep`
  （默认 6，负数关闭清理）都是运行时可配项。跑 App 内任务时先取 `run_stats` 的 `context_notices`
  与 `pruned_tool_results` 作为触发计数，再看轮次与 token：策略没触发过就不该留着。

## 现在还没做的

模型输出被截断时的重试统计、真实设备侧的端到端任务（改代码 → 构建 → 取包）都还没有自动跑；
它们依赖 App 内的完整工具集，等内部层做成可脚本触发的形式再补。
