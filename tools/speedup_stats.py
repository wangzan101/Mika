#!/usr/bin/env python3
"""Compare two Mika event exports without printing prompts, pages, or credentials."""
import argparse
import json
import math
import statistics
from pathlib import Path


def read_events(path):
    text = Path(path).read_text(encoding="utf-8")
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        value = [json.loads(line) for line in text.splitlines() if line.strip()]
    if isinstance(value, dict):
        value = value.get("events", [value])
    if not isinstance(value, list) or any(not isinstance(x, dict) for x in value):
        raise ValueError("Expected an event array, an events object, or JSONL")
    return value


def distribution(values):
    values = sorted(x for x in values if isinstance(x, (int, float)) and not isinstance(x, bool) and x >= 0)
    if not values:
        return {"count": 0, "median_ms": None, "p95_ms": None}
    return {"count": len(values), "median_ms": statistics.median(values), "p95_ms": values[math.ceil(len(values) * .95) - 1]}


def summarize(events):
    replies = [e for e in events if e.get("type") == "model_reply"]
    reported = [e for e in replies if e.get("cacheReported") is True]
    number = lambda e, k: e.get(k) if isinstance(e.get(k), (int, float)) and not isinstance(e.get(k), bool) and e[k] >= 0 else 0
    denominator = sum(number(e, "inputTokens") for e in reported)
    cached = sum(number(e, "cachedTokens") for e in reported)
    starts = [e["atMs"] for e in events if e.get("type") == "task_started" and isinstance(e.get("atMs"), (int, float))]
    ends = [e["atMs"] for e in events if e.get("type") == "task_ended" and isinstance(e.get("atMs"), (int, float))]
    waits = []
    ratios = []
    for event in events:
        if event.get("type") != "tool_result" or event.get("tool") != "phone_wait":
            continue
        waits.append(event.get("durationMs"))
        try:
            result = json.loads(event.get("resultJson", "{}"))
        except (json.JSONDecodeError, TypeError):
            result = {}
        if isinstance(result, dict) and number(result, "declaredTimeoutMs") > 0:
            ratios.append(number(result, "actualDurationMs") / result["declaredTimeoutMs"])
    return {
        "model_calls": len(replies), "cache_reported_calls": len(reported),
        "cache_unreported_calls": len(replies) - len(reported),
        "cache_reported_input_tokens": denominator, "cached_tokens": cached,
        "cache_hit_rate": cached / denominator if denominator else None,
        "model_latency": distribution([e.get("latencyMs") for e in replies]),
        "phone_wait": distribution(waits), "phone_wait_max_timeout_ratio": max(ratios) if ratios else None,
        "wall_clock_ms": max(ends) - min(starts) if starts and ends else None,
        "prefix_resets": sum(e.get("type") == "compressed" and e.get("reason") not in {None, "append", "initial", "context_size_append"} for e in events),
        "providers": sorted({e["provider"] for e in replies if isinstance(e.get("provider"), str) and e["provider"]}),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before")
    parser.add_argument("after")
    args = parser.parse_args()
    before, after = (summarize(read_events(p)) for p in (args.before, args.after))
    print(json.dumps({"before": before, "after": after}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()