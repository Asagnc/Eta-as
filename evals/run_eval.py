#!/usr/bin/env python3
"""Provider 稳定性与工具调用合规性评测。

这份脚本只负责"外部"部分：把仓库里的工具快照和任务集原样发给一个 OpenAI 兼容的
`/chat/completions` 端点，检查模型是否选中了正确的工具、参数是否符合 Schema，并统计
轮次、token 与失败类型。它不执行任何工具，也不会碰设备——harness 侧的行为由 App 内的
`run_stats` 与归档事件衡量，两者共用同一份任务集。

用法：
    export STA_EVAL_API_KEY=...           # 只从环境变量读取，脚本不落盘任何凭据
    python3 evals/run_eval.py --provider-url https://example.com/v1 --model deepseek-v4-flash
    python3 evals/run_eval.py --dry-run   # 用本地桩模型跑通流程，不联网

关键参数：
    --tasks / --tools   默认取同目录的 tasks.json 与 tools.json
    --baseline          给一份历史结果，输出与它的差值
    --limit             只跑前 N 条
    --repeat            每条任务重复次数，用于观察同一 provider 的波动
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

MAX_ROUNDS = 3
REQUEST_TIMEOUT = 120


class Failure(Exception):
    def __init__(self, kind: str, detail: str = ""):
        super().__init__(kind if not detail else f"{kind}: {detail}")
        self.kind = kind
        self.detail = detail


def load_json(path: Path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def build_messages(task: dict) -> list[dict]:
    return [
        {
            "role": "system",
            "content": (
                "你是运行在 Android 手机上的助手 Sta。只能通过声明的工具获取信息或执行操作，"
                "不要凭记忆回答设备状态或文件内容。先选出最合适的工具，参数必须符合 Schema。"
            ),
        },
        {"role": "user", "content": task["prompt"]},
    ]


def select_tools(tools: list[dict], names: list[str]) -> list[dict]:
    by_name = {tool["name"]: tool for tool in tools}
    missing = [name for name in names if name not in by_name]
    if missing:
        raise SystemExit(f"tools.json 里缺少任务需要的工具：{', '.join(missing)}")
    return [
        {
            "type": "function",
            "function": {
                "name": by_name[name]["name"],
                "description": by_name[name]["description"],
                "parameters": by_name[name]["parameters"],
            },
        }
        for name in names
    ]


def type_matches(expected: str, value) -> bool:
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "array":
        return isinstance(value, list)
    if expected == "object":
        return isinstance(value, dict)
    return True


def validate_arguments(schema: dict, arguments: dict) -> list[str]:
    """只校验快照里实际声明的约束，未声明的关键字不猜。"""
    problems: list[str] = []
    if schema.get("type") == "object":
        for name in schema.get("required", []):
            if name not in arguments:
                problems.append(f"缺少必需参数 {name}")
        properties = schema.get("properties", {})
        for name, value in arguments.items():
            rule = properties.get(name)
            if rule is None:
                problems.append(f"未知参数 {name}")
                continue
            expected = rule.get("type")
            if expected and not type_matches(expected, value):
                problems.append(f"{name} 类型应为 {expected}")
                continue
            if "enum" in rule and value not in rule["enum"]:
                problems.append(f"{name} 取值不在枚举内")
            if isinstance(value, str):
                if "maxLength" in rule and len(value) > rule["maxLength"]:
                    problems.append(f"{name} 超过 maxLength")
            if isinstance(value, int) and not isinstance(value, bool) and expected == "integer":
                if "minimum" in rule and value < rule["minimum"]:
                    problems.append(f"{name} 小于 minimum")
                if "maximum" in rule and value > rule["maximum"]:
                    problems.append(f"{name} 大于 maximum")
            if isinstance(value, list):
                if "minItems" in rule and len(value) < rule["minItems"]:
                    problems.append(f"{name} 少于 minItems")
                if "maxItems" in rule and len(value) > rule["maxItems"]:
                    problems.append(f"{name} 超过 maxItems")
                item_rule = rule.get("items")
                if isinstance(item_rule, dict) and item_rule.get("type"):
                    if any(not type_matches(item_rule["type"], item) for item in value):
                        problems.append(f"{name} 的数组元素类型应为 {item_rule['type']}")
    return problems


RETRY_LIMIT = 6


def rate_limit_delay(detail: str, attempt: int) -> float:
    """限流/服务端错误的等待时间：优先用服务端给的 retryAfterSeconds，否则指数退避。"""
    wait = 0.0
    try:
        payload = json.loads(detail)
        data = payload.get("data") if isinstance(payload, dict) else None
        if isinstance(data, dict):
            raw = data.get("retryAfterSeconds")
            if isinstance(raw, (int, float)):
                wait = float(raw)
    except json.JSONDecodeError:
        pass
    return max(wait, 2.0 ** attempt, 5.0) + 0.5


def post_chat(provider_url: str, model: str, messages: list[dict], tools: list[dict], api_key: str) -> dict:
    body = json.dumps(
        {
            "model": model,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "stream": False,
            "temperature": 0,
        }
    ).encode()
    request = urllib.request.Request(
        provider_url.rstrip("/") + "/chat/completions",
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    for attempt in range(RETRY_LIMIT + 1):
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")[:400]
            if error.code in (401, 403):
                raise Failure("auth_error", detail)
            if error.code in (429, 500, 502, 503, 504):
                if attempt < RETRY_LIMIT:
                    time.sleep(rate_limit_delay(detail, attempt))
                    continue
                kind = "rate_limited" if error.code == 429 else "server_error"
                raise Failure(kind, detail if error.code == 429 else f"{error.code} {detail}")
            raise Failure("http_error", f"{error.code} {detail}")
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt < RETRY_LIMIT:
                time.sleep(rate_limit_delay("", attempt))
                continue
            raise Failure("network_error", str(error))
        except json.JSONDecodeError as error:
            raise Failure("invalid_response", str(error))
    raise Failure("rate_limited", "重试次数用尽")


def dry_run_chat(task: dict, messages: list[dict], tools: list[dict], mapping: dict[str, str] | None = None) -> dict:
    """本地桩：第一轮按期望工具产出调用，第二轮给出结论，用来验证脚本本身。"""
    reverse = {canonical: exposed for exposed, canonical in (mapping or {}).items()}
    offered = {tool["function"]["name"] for tool in tools}
    if sum(1 for message in messages if message["role"] == "tool") == 0:
        canonical = task["expect_first_tool"][0]
        exposed = reverse.get(canonical, canonical)
        assert exposed in offered, f"桩模型被要求调用未声明的工具 {exposed}"
        arguments = task.get("expect_arguments", {})
        return {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [
                            {
                                "id": "call-1",
                                "type": "function",
                                "function": {"name": exposed, "arguments": json.dumps(arguments)},
                            }
                        ],
                    },
                    "finish_reason": "tool_calls",
                }
            ],
            "usage": {"prompt_tokens": 120, "completion_tokens": 20},
        }
    return {
        "choices": [{"message": {"role": "assistant", "content": "已完成。"}, "finish_reason": "stop"}],
        "usage": {"prompt_tokens": 160, "completion_tokens": 8},
    }


def apply_name_style(tools: list[dict], style: str) -> tuple[list[dict], dict[str, str]]:
    """命名空间实验：同一批工具按前缀改名后再发，看模型选工具的准确率是否变化。"""
    if style == "plain":
        return tools, {}
    mapping: dict[str, str] = {}
    renamed = []
    for tool in tools:
        canonical = tool["function"]["name"]
        exposed = f"sta_{canonical}"
        mapping[exposed] = canonical
        renamed.append({"type": "function", "function": {**tool["function"], "name": exposed}})
    return renamed, mapping


def run_task(task: dict, tools: list[dict], client, name_mapping: dict[str, str] | None = None) -> dict:
    mapping = name_mapping or {}
    messages = build_messages(task)
    offered = [mapping.get(tool["function"]["name"], tool["function"]["name"]) for tool in tools]
    schema_by_name = {
        mapping.get(tool["function"]["name"], tool["function"]["name"]): tool["function"]["parameters"]
        for tool in tools
    }
    expected = set(task["expect_first_tool"])
    record = {
        "id": task["id"],
        "category": task.get("category", ""),
        "held_out": bool(task.get("held_out")),
        "rounds": 0,
        "tool_calls": [],
        "unknown_tools": [],
        "argument_problems": [],
        "prompt_tokens": 0,
        "completion_tokens": 0,
        "latency_ms": 0,
        "ok": False,
        "failure": "",
    }
    started = time.monotonic()
    try:
        for round_index in range(1, MAX_ROUNDS + 1):
            record["rounds"] = round_index
            payload = client(messages, tools)
            usage = payload.get("usage") or {}
            record["prompt_tokens"] += int(usage.get("prompt_tokens") or 0)
            record["completion_tokens"] += int(usage.get("completion_tokens") or 0)
            choices = payload.get("choices") or []
            if not choices:
                raise Failure("invalid_response", "响应没有 choices")
            message = choices[0].get("message") or {}
            tool_calls = message.get("tool_calls") or []
            if not tool_calls:
                if round_index == 1:
                    raise Failure("no_tool_call", (message.get("content") or "")[:120])
                break
            names = []
            for call in tool_calls:
                function = call.get("function") or {}
                exposed_name = function.get("name") or ""
                name = mapping.get(exposed_name, exposed_name)
                names.append(name)
                raw_arguments = function.get("arguments") or "{}"
                if name not in offered:
                    record["unknown_tools"].append(exposed_name)
                    continue
                try:
                    arguments = json.loads(raw_arguments) if isinstance(raw_arguments, str) else raw_arguments
                except json.JSONDecodeError:
                    record["argument_problems"].append(f"{name}: arguments 不是合法 JSON")
                    continue
                schema = schema_by_name.get(name)
                if schema is None:
                    record["unknown_tools"].append(exposed_name)
                    continue
                record["argument_problems"].extend(
                    f"{name}: {problem}" for problem in validate_arguments(schema, arguments)
                )
            record["tool_calls"].extend(names)
            if round_index == 1:
                if not expected.intersection(names):
                    raise Failure("wrong_tool", "、".join(names) or "空")
            messages.append({"role": "assistant", "content": message.get("content") or "", "tool_calls": tool_calls})
            for call in tool_calls:
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call.get("id") or "call-1",
                        "content": json.dumps(task.get("tool_result", {"ok": True}), ensure_ascii=False),
                    }
                )
        record["ok"] = not record["unknown_tools"] and not record["argument_problems"]
        if not record["ok"]:
            record["failure"] = "unknown_tool" if record["unknown_tools"] else "bad_arguments"
    except Failure as failure:
        record["failure"] = failure.kind
        record["failure_detail"] = failure.detail
    finally:
        record["latency_ms"] = int((time.monotonic() - started) * 1000)
    return record


def summarize(records: list[dict]) -> dict:
    def block(items: list[dict]) -> dict:
        if not items:
            return {"tasks": 0, "pass_rate": 0.0, "avg_rounds": 0.0, "total_tokens": 0, "failures": {}}
        passed = sum(1 for item in items if item["ok"])
        return {
            "tasks": len(items),
            "pass_rate": round(passed / len(items), 3),
            "avg_rounds": round(sum(item["rounds"] for item in items) / len(items), 2),
            "avg_latency_ms": int(sum(item["latency_ms"] for item in items) / len(items)),
            "total_tokens": sum(item["prompt_tokens"] + item["completion_tokens"] for item in items),
            "failures": dict(Counter(item["failure"] for item in items if item["failure"])),
        }

    return {
        "all": block(records),
        "held_out": block([item for item in records if item["held_out"]]),
    }


def print_report(result: dict, baseline: dict | None) -> None:
    summary = result["summary"]["all"]
    print(f"任务 {summary['tasks']} 条：通过率 {summary['pass_rate']:.1%}，平均 {summary['avg_rounds']} 轮，"
          f"平均 {summary['avg_latency_ms']} ms，token {summary['total_tokens']}")
    if summary["failures"]:
        print("失败类型：" + "、".join(f"{name}×{count}" for name, count in summary["failures"].items()))
    held_out = result["summary"]["held_out"]
    if held_out["tasks"]:
        print(f"留出集 {held_out['tasks']} 条：通过率 {held_out['pass_rate']:.1%}")
    for item in result["records"]:
        mark = "通过" if item["ok"] else f"失败({item['failure']})"
        print(f"  [{mark}] {item['id']}：{item['rounds']} 轮，{'/'.join(item['tool_calls']) or '无工具调用'}")
    if baseline:
        print("与基线对比：")
        for key in ("pass_rate", "avg_rounds", "total_tokens", "avg_latency_ms"):
            before = baseline["summary"]["all"].get(key)
            after = summary.get(key)
            if isinstance(before, (int, float)) and isinstance(after, (int, float)):
                print(f"  {key}: {before} → {after}（{after - before:+.3f}）")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", default=str(Path(__file__).with_name("tasks.json")))
    parser.add_argument("--tools", default=str(Path(__file__).with_name("tools.json")))
    parser.add_argument("--provider-url", default="")
    parser.add_argument("--model", default="")
    parser.add_argument("--out", default="")
    parser.add_argument("--baseline", default="")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--pause", type=float, default=4.0, help="任务之间的间隔秒数，避免触发中转站限流")
    parser.add_argument(
        "--tool-name-style",
        choices=("plain", "prefixed"),
        default="plain",
        help="prefixed 把工具名改成 sta_<name> 后再发，用于比较命名空间对选工具准确率的影响",
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    tasks = load_json(Path(args.tasks))["tasks"]
    if args.limit:
        tasks = tasks[: args.limit]
    tools_snapshot = load_json(Path(args.tools))["tools"]

    api_key = os.environ.get("STA_EVAL_API_KEY", "")
    if not args.dry_run:
        if not args.provider_url or not args.model:
            parser.error("真实评测需要 --provider-url 与 --model")
        if not api_key:
            parser.error("请通过环境变量 STA_EVAL_API_KEY 提供密钥（脚本不会保存它）")

    records: list[dict] = []
    for index, task in enumerate(tasks):
        if index and args.pause > 0:
            time.sleep(args.pause)
        selected, mapping = apply_name_style(select_tools(tools_snapshot, task["tools"]), args.tool_name_style)
        for attempt in range(args.repeat):
            if args.dry_run:
                client = lambda messages, tools, task=task: dry_run_chat(task, messages, tools, mapping)
            else:
                client = lambda messages, tools: post_chat(args.provider_url, args.model, messages, tools, api_key)
            record = run_task(task, selected, client, mapping)
            if args.repeat > 1:
                record["attempt"] = attempt + 1
            records.append(record)

    result = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "provider_url": args.provider_url,
        "model": args.model,
        "tool_name_style": args.tool_name_style,
        "dry_run": args.dry_run,
        "summary": summarize(records),
        "records": records,
    }
    baseline = load_json(Path(args.baseline)) if args.baseline else None
    print_report(result, baseline)

    out_path = Path(args.out) if args.out else Path(__file__).with_name("results") / (
        f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{args.model or 'dry-run'}.json"
    )
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
    print(f"结果已写入 {out_path}")
    return 0 if result["summary"]["all"]["pass_rate"] >= 1.0 else 1


if __name__ == "__main__":
    sys.exit(main())
