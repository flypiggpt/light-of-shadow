"""Prometheus 指标：QPS、延迟 P50/P95/P99、LLM token 用量、工具成功率。

自实现 Prometheus 文本暴露格式（/metrics 可直接抓取），不依赖三方包；
直方图分位数按 Prometheus histogram_quantile 的桶内线性插值估算。
"""

from __future__ import annotations

import math
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Iterable

DEFAULT_BUCKETS: tuple[float, ...] = (
    0.005,
    0.01,
    0.025,
    0.05,
    0.1,
    0.25,
    0.5,
    1.0,
    2.5,
    5.0,
    10.0,
)


def _format_labels(labels: dict[str, str]) -> str:
    if not labels:
        return ""
    parts = []
    for key in sorted(labels):
        value = (
            str(labels[key])
            .replace("\\", "\\\\")
            .replace('"', '\\"')
            .replace("\n", "\\n")
        )
        parts.append(f'{key}="{value}"')
    return "{" + ",".join(parts) + "}"


@dataclass
class Histogram:
    buckets: tuple[float, ...] = DEFAULT_BUCKETS
    counts: list[int] = field(init=False)
    sum: float = 0.0
    count: int = 0

    def __post_init__(self) -> None:
        if not self.buckets or self.buckets != tuple(sorted(self.buckets)):
            raise ValueError("buckets 必须为升序非空序列")
        self.counts = [0] * (len(self.buckets) + 1)

    def observe(self, value: float) -> None:
        self.sum += value
        self.count += 1
        placed = False
        for i, bound in enumerate(self.buckets):
            if value <= bound:
                self.counts[i] += 1
                placed = True
                break
        if not placed:
            self.counts[-1] += 1

    def cumulative(self) -> list[tuple[float, int]]:
        out: list[tuple[float, int]] = []
        running = 0
        for bound, c in zip(self.buckets, self.counts):
            running += c
            out.append((bound, running))
        out.append((math.inf, running + self.counts[-1]))
        return out

    def quantile(self, q: float) -> float | None:
        """按 Prometheus histogram_quantile 线性插值估算分位数。"""
        if self.count == 0:
            return None
        if not 0.0 <= q <= 1.0:
            raise ValueError("q 必须在 [0, 1] 内")
        rank = q * self.count
        cum = self.cumulative()
        prev_bound = 0.0
        prev_count = 0
        for bound, count in cum:
            if count >= rank:
                if bound == math.inf:
                    return self.buckets[-1] if self.buckets else self.sum / self.count
                span = bound - prev_bound
                gap = count - prev_count
                if gap <= 0:
                    return bound
                return prev_bound + span * ((rank - prev_count) / gap)
            prev_bound = bound
            prev_count = count
        return self.sum / self.count


class MetricsRegistry:
    """线程安全的进程内指标注册表，可渲染为 Prometheus 文本。"""

    def __init__(self, buckets: tuple[float, ...] = DEFAULT_BUCKETS) -> None:
        self._lock = threading.RLock()
        self._buckets = buckets
        self._http_requests: dict[tuple[str, str, str], int] = {}
        self._http_latency: dict[str, Histogram] = {}
        self._tool_calls: dict[tuple[str, str], int] = {}
        self._tool_latency: dict[str, Histogram] = {}
        self._llm_tokens: dict[tuple[str, str], int] = {}
        self._llm_cost: dict[str, float] = {}
        self._llm_calls: dict[str, int] = {}
        self._retrieval_requests: dict[tuple[str, str], int] = {}
        self._retrieval_latency: dict[str, Histogram] = {}
        self._index_outcomes: dict[tuple[str, int, int], int] = {}
        self._retrieval_results: dict[str, Histogram] = {}
        self._index_latency: dict[str, Histogram] = {}
        self._request_times: deque[float] = deque()
        self._max_window_events = 200_000

    # ---- 采集入口 -------------------------------------------------

    def observe_http(
        self, method: str, path: str, status: int | str, duration_s: float
    ) -> None:
        key = (method.upper(), path, str(status))
        now = time.monotonic()
        with self._lock:
            self._http_requests[key] = self._http_requests.get(key, 0) + 1
            self._http_latency.setdefault(path, Histogram(self._buckets)).observe(
                duration_s
            )
            self._request_times.append(now)
            if len(self._request_times) > self._max_window_events:
                self._prune_window_locked(now, 60.0)

    def observe_tool(
        self, tool: str, success: bool, duration_s: float | None = None
    ) -> None:
        outcome = "success" if success else "failure"
        with self._lock:
            key = (tool, outcome)
            self._tool_calls[key] = self._tool_calls.get(key, 0) + 1
            if duration_s is not None:
                self._tool_latency.setdefault(tool, Histogram(self._buckets)).observe(
                    duration_s
                )

    def observe_llm(
        self,
        model: str,
        prompt_tokens: int,
        completion_tokens: int,
        cost_usd: float = 0.0,
    ) -> None:
        with self._lock:
            p = (model, "prompt_tokens")
            c = (model, "completion_tokens")
            self._llm_tokens[p] = self._llm_tokens.get(p, 0) + max(0, int(prompt_tokens))
            self._llm_tokens[c] = self._llm_tokens.get(
                c, 0
            ) + max(0, int(completion_tokens))
            self._llm_cost[model] = self._llm_cost.get(model, 0.0) + float(cost_usd)
            self._llm_calls[model] = self._llm_calls.get(model, 0) + 1

    def observe_retrieval(
        self,
        mode: str,
        duration_s: float,
        result_count: int,
        degraded: bool,
    ) -> None:
        outcome = "degraded" if degraded else "ok"
        with self._lock:
            key = (mode, outcome)
            self._retrieval_requests[key] = self._retrieval_requests.get(key, 0) + 1
            self._retrieval_latency.setdefault(mode, Histogram(self._buckets)).observe(
                duration_s
            )
            self._retrieval_results.setdefault(mode, Histogram(self._buckets)).observe(
                float(result_count)
            )

    def observe_index(
        self, outcome: str, documents: int, chunks: int, duration_s: float
    ) -> None:
        with self._lock:
            key = (outcome, int(documents), int(chunks))
            self._index_outcomes[key] = self._index_outcomes.get(key, 0) + 1
            self._index_latency.setdefault(outcome, Histogram(self._buckets)).observe(
                duration_s
            )

    # ---- 查询 -----------------------------------------------------

    def qps(self, window_s: float = 60.0) -> float:
        now = time.monotonic()
        with self._lock:
            self._prune_window_locked(now, window_s)
            hits = len(self._request_times)
        return hits / window_s if window_s > 0 else 0.0

    def percentiles(self, path: str | None = None) -> dict[str, float | None]:
        with self._lock:
            hist = self._http_latency.get(path) if path else self._aggregate_latency_locked()
        if hist is None:
            return {"p50": None, "p95": None, "p99": None}
        return {
            "p50": hist.quantile(0.50),
            "p95": hist.quantile(0.95),
            "p99": hist.quantile(0.99),
        }

    def tool_success_rate(self, tool: str | None = None) -> float | None:
        with self._lock:
            if tool is not None:
                ok = self._tool_calls.get((tool, "success"), 0)
                fail = self._tool_calls.get((tool, "failure"), 0)
            else:
                ok = sum(v for (t, o), v in self._tool_calls.items() if o == "success")
                fail = sum(v for (t, o), v in self._tool_calls.items() if o == "failure")
        total = ok + fail
        return ok / total if total else None

    def token_usage(self) -> dict[str, Any]:
        with self._lock:
            prompt = sum(v for (m, t), v in self._llm_tokens.items() if t == "prompt_tokens")
            completion = sum(
                v for (m, t), v in self._llm_tokens.items() if t == "completion_tokens"
            )
            cost = sum(self._llm_cost.values())
            calls = sum(self._llm_calls.values())
        return {
            "prompt_tokens": prompt,
            "completion_tokens": completion,
            "total_tokens": prompt + completion,
            "cost_usd": cost,
            "llm_calls": calls,
        }

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            http_total = sum(self._http_requests.values())
            tools = {
                f"{tool}:{outcome}": count
                for (tool, outcome), count in sorted(self._tool_calls.items())
            }
            retrieval = {
                f"{mode}:{outcome}": count
                for (mode, outcome), count in sorted(self._retrieval_requests.items())
            }
        return {
            "http_requests_total": http_total,
            "qps_1m": self.qps(60.0),
            "latency": self.percentiles(),
            "tool_calls": tools,
            "tool_success_rate": self.tool_success_rate(),
            "tokens": self.token_usage(),
            "retrieval_requests": retrieval,
        }

    def reset(self) -> None:
        with self._lock:
            self.__init__(self._buckets)  # type: ignore[misc]

    # ---- Prometheus 渲染 ------------------------------------------

    def render_prometheus(self) -> str:
        lines: list[str] = []
        with self._lock:
            lines.extend(self._render_http_locked())
            lines.extend(self._render_tools_locked())
            lines.extend(self._render_llm_locked())
            lines.extend(self._render_retrieval_locked())
            lines.extend(self._render_index_locked())
        return "\n".join(lines) + "\n"

    def _prune_window_locked(self, now: float, window_s: float) -> None:
        cutoff = now - window_s
        while self._request_times and self._request_times[0] < cutoff:
            self._request_times.popleft()

    def _aggregate_latency_locked(self) -> Histogram | None:
        if not self._http_latency:
            return None
        merged = Histogram(self._buckets)
        for hist in self._http_latency.values():
            merged.sum += hist.sum
            merged.count += hist.count
            for i, c in enumerate(hist.counts):
                merged.counts[i] += c
        return merged

    @staticmethod
    def _histogram_lines(
        name: str, help_text: str, hist: Histogram, labels: dict[str, str] | None = None
    ) -> list[str]:
        label_str = _format_labels(labels or {})
        bucket_labels = dict(labels or {})
        lines = [f"# HELP {name} {help_text}", f"# TYPE {name} histogram"]
        running = 0
        for bound, c in zip(hist.buckets, hist.counts):
            running += c
            bucket_labels["le"] = ("g" if math.isinf(bound) else f"{bound:g}")
            lines.append(f"{name}_bucket{_format_labels(bucket_labels)} {running}")
        bucket_labels["le"] = "+Inf"
        lines.append(
            f"{name}_bucket{_format_labels(bucket_labels)} {running + hist.counts[-1]}"
        )
        lines.append(f"{name}_sum{label_str} {hist.sum:.9g}")
        lines.append(f"{name}_count{label_str} {hist.count}")
        return lines

    def _render_http_locked(self) -> list[str]:
        lines = [
            "# HELP http_requests_total Total HTTP requests.",
            "# TYPE http_requests_total counter",
        ]
        for (method, path, status), count in sorted(self._http_requests.items()):
            labels = _format_labels(
                {"method": method, "path": path, "status": status}
            )
            lines.append(f"http_requests_total{labels} {count}")
        for path, hist in sorted(self._http_latency.items()):
            lines.extend(
                self._histogram_lines(
                    "http_request_duration_seconds",
                    "HTTP request latency in seconds.",
                    hist,
                    {"path": path},
                )
            )
        window = self.qps(60.0)
        lines.append("# HELP http_requests_per_second QPS over the last 60 seconds.")
        lines.append("# TYPE http_requests_per_second gauge")
        lines.append(f"http_requests_per_second {window:.6g}")
        return lines

    def _render_tools_locked(self) -> list[str]:
        lines = [
            "# HELP tool_calls_total Total tool calls by tool and outcome.",
            "# TYPE tool_calls_total counter",
        ]
        for (tool, outcome), count in sorted(self._tool_calls.items()):
            lines.append(
                f"tool_calls_total{_format_labels({'tool': tool, 'outcome': outcome})} {count}"
            )
        rates: dict[str, tuple[int, int]] = {}
        for (tool, outcome), count in self._tool_calls.items():
            ok, fail = rates.get(tool, (0, 0))
            if outcome == "success":
                ok += count
            else:
                fail += count
            rates[tool] = (ok, fail)
        lines.append("# HELP tool_success_rate Tool success rate by tool.")
        lines.append("# TYPE tool_success_rate gauge")
        for tool, (ok, fail) in sorted(rates.items()):
            total = ok + fail
            rate = ok / total if total else 0.0
            lines.append(
                f"tool_success_rate{_format_labels({'tool': tool})} {rate:.6g}"
            )
        total_ok = sum(ok for ok, _ in rates.values())
        total_all = sum(ok + fail for ok, fail in rates.values())
        overall = total_ok / total_all if total_all else 0.0
        lines.append(f"tool_success_rate{_format_labels({})} {overall:.6g}")
        for tool, hist in sorted(self._tool_latency.items()):
            lines.extend(
                self._histogram_lines(
                    "tool_call_duration_seconds",
                    "Tool call latency in seconds.",
                    hist,
                    {"tool": tool},
                )
            )
        return lines

    def _render_llm_locked(self) -> list[str]:
        lines = [
            "# HELP llm_tokens_total LLM token usage by model and type.",
            "# TYPE llm_tokens_total counter",
        ]
        for (model, kind), count in sorted(self._llm_tokens.items()):
            lines.append(
                f"llm_tokens_total{_format_labels({'model': model, 'type': kind})} {count}"
            )
        lines.append("# HELP llm_cost_usd_total Estimated LLM cost in USD by model.")
        lines.append("# TYPE llm_cost_usd_total counter")
        for model, cost in sorted(self._llm_cost.items()):
            lines.append(
                f"llm_cost_usd_total{_format_labels({'model': model})} {cost:.9g}"
            )
        lines.append("# HELP llm_calls_total LLM calls by model.")
        lines.append("# TYPE llm_calls_total counter")
        for model, count in sorted(self._llm_calls.items()):
            lines.append(f"llm_calls_total{_format_labels({'model': model})} {count}")
        return lines

    def _render_retrieval_locked(self) -> list[str]:
        lines = [
            "# HELP retrieval_requests_total Retrieval requests by mode and outcome.",
            "# TYPE retrieval_requests_total counter",
        ]
        for (mode, outcome), count in sorted(self._retrieval_requests.items()):
            lines.append(
                f"retrieval_requests_total{_format_labels({'mode': mode, 'outcome': outcome})} {count}"
            )
        for mode, hist in sorted(self._retrieval_latency.items()):
            lines.extend(
                self._histogram_lines(
                    "retrieval_duration_seconds",
                    "Retrieval latency in seconds.",
                    hist,
                    {"mode": mode},
                )
            )
        for mode, hist in sorted(self._retrieval_results.items()):
            lines.extend(
                self._histogram_lines(
                    "retrieval_results_count",
                    "Number of items returned per retrieval.",
                    hist,
                    {"mode": mode},
                )
            )
        return lines

    def _render_index_locked(self) -> list[str]:
        lines = [
            "# HELP index_operations_total Index operations by outcome.",
            "# TYPE index_operations_total counter",
        ]
        for (outcome, docs, chunks), count in sorted(self._index_outcomes.items()):
            labels = _format_labels(
                {"outcome": outcome, "documents": str(docs), "chunks": str(chunks)}
            )
            lines.append(f"index_operations_total{labels} {count}")
        for outcome, hist in sorted(self._index_latency.items()):
            lines.extend(
                self._histogram_lines(
                    "index_duration_seconds",
                    "Indexing latency in seconds.",
                    hist,
                    {"outcome": outcome},
                )
            )
        return lines


_REGISTRY = MetricsRegistry()
_REGISTRY_LOCK = threading.Lock()


def get_registry() -> MetricsRegistry:
    return _REGISTRY


def reset_registry() -> None:
    with _REGISTRY_LOCK:
        _REGISTRY.reset()


def observe_http(method: str, path: str, status: int | str, duration_s: float) -> None:
    get_registry().observe_http(method, path, status, duration_s)


def observe_tool(tool: str, success: bool, duration_s: float | None = None) -> None:
    get_registry().observe_tool(tool, success, duration_s)


def observe_llm(
    model: str, prompt_tokens: int, completion_tokens: int, cost_usd: float = 0.0
) -> None:
    get_registry().observe_llm(model, prompt_tokens, completion_tokens, cost_usd)


def render_prometheus() -> str:
    return get_registry().render_prometheus()
