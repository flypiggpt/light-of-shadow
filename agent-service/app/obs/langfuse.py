"""Langfuse 观测：trace / generation / token 成本；SDK 不可用时无损降级为 NoOp。

即使 Langfuse 未配置，token 用量与成本仍会记录到 Prometheus 指标。
"""

from __future__ import annotations

import logging
import random
import threading
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any, Iterator, Mapping

logger = logging.getLogger(__name__)

# USD / 1M tokens: (input, output)。上线前由运维按实际价目表覆盖。
DEFAULT_PRICING: dict[str, tuple[float, float]] = {
    "deepseek-chat": (0.27, 1.10),
    "deepseek-reasoner": (0.55, 2.19),
    "text-embedding-small": (0.02, 0.0),
}


def estimate_cost(
    model: str,
    prompt_tokens: int,
    completion_tokens: int,
    pricing: Mapping[str, tuple[float, float]] | None = None,
) -> float:
    table = dict(DEFAULT_PRICING)
    if pricing:
        table.update(pricing)
    rates = table.get(model)
    if rates is None:
        return 0.0
    in_rate, out_rate = rates
    return (
        max(0, prompt_tokens) * in_rate + max(0, completion_tokens) * out_rate
    ) / 1_000_000


@dataclass
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens

    @classmethod
    def from_mapping(cls, data: Mapping[str, Any] | None) -> "Usage":
        data = data or {}
        prompt = int(data.get("prompt_tokens", 0) or 0)
        completion = int(data.get("completion_tokens", 0) or 0)
        if not prompt and not completion and data.get("total_tokens"):
            prompt = int(data.get("total_tokens", 0) or 0)
        return cls(prompt_tokens=prompt, completion_tokens=completion)


@dataclass
class LangfuseConfig:
    enabled: bool = False
    public_key: str | None = None
    secret_key: str | None = None
    host: str = "https://cloud.langfuse.com"
    sample_rate: float = 1.0
    release: str | None = None
    pricing: dict[str, tuple[float, float]] = field(default_factory=dict)
    metrics: Any | None = None


class Span:
    """trace / generation 的统一句柄，同时承担 token 成本入指标。"""

    def __init__(
        self,
        name: str,
        config: LangfuseConfig,
        *,
        sdk_span: Any | None = None,
        trace_id: str | None = None,
    ) -> None:
        self.name = name
        self.config = config
        self._sdk_span = sdk_span
        self.trace_id = trace_id or ""
        self.metadata: dict[str, Any] = {}
        self.usage: Usage | None = None
        self.model: str | None = None
        self.cost_usd: float = 0.0
        self._ended = False

    def set_input(self, value: Any) -> None:
        self._safe_call("update", input=value)

    def set_output(self, value: Any) -> None:
        self._safe_call("update", output=value)

    def set_metadata(self, metadata: Mapping[str, Any]) -> None:
        self.metadata.update(dict(metadata))
        self._safe_call("update", metadata=dict(self.metadata))

    def set_usage(
        self,
        model: str,
        usage: Usage | Mapping[str, Any] | None,
        cost_usd: float | None = None,
    ) -> float:
        if not isinstance(usage, Usage):
            usage = Usage.from_mapping(usage)
        self.usage = usage
        self.model = model
        self.cost_usd = (
            float(cost_usd)
            if cost_usd is not None
            else estimate_cost(
                model,
                usage.prompt_tokens,
                usage.completion_tokens,
                self.config.pricing,
            )
        )
        self._safe_call(
            "update",
            usage={
                "input": usage.prompt_tokens,
                "output": usage.completion_tokens,
                "total": usage.total_tokens,
                "unit": "TOKENS",
            },
            usage_details={
                "input": usage.prompt_tokens,
                "output": usage.completion_tokens,
                "total": usage.total_tokens,
            },
            cost_details={
                "input": self.cost_usd,
                "output": 0.0,
                "total": self.cost_usd,
            },
        )
        self._record_metrics()
        return self.cost_usd

    def add_score(self, name: str, value: float, comment: str | None = None) -> None:
        self._safe_call("score", name=name, value=value, comment=comment)

    def end(self) -> None:
        if self._ended:
            return
        self._ended = True
        self._safe_call("end")

    def _record_metrics(self) -> None:
        metrics = self.config.metrics
        if metrics is None or self.usage is None or not self.model:
            return
        try:
            metrics.observe_llm(
                model=self.model,
                prompt_tokens=self.usage.prompt_tokens,
                completion_tokens=self.usage.completion_tokens,
                cost_usd=self.cost_usd,
            )
        except Exception:  # noqa: BLE001 - 观测不得影响主链路
            logger.warning("record llm metrics failed", exc_info=True)

    def _safe_call(self, method: str, **kwargs: Any) -> None:
        sdk = self._sdk_span
        if sdk is None:
            return
        fn = getattr(sdk, method, None)
        if fn is None:
            return
        try:
            fn(**kwargs)
        except Exception:  # noqa: BLE001
            logger.warning("langfuse %s failed", method, exc_info=True)


class Tracer:
    """统一观测门面。所有方法不抛异常。"""

    degraded: bool = True

    def __init__(self, config: LangfuseConfig | None = None) -> None:
        self.config = config or LangfuseConfig()

    @contextmanager
    def trace(
        self,
        name: str,
        *,
        session_id: str | None = None,
        user_id: str | None = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> Iterator[Span]:
        span = Span(name, self.config)
        if metadata:
            span.set_metadata(metadata)
        try:
            yield span
        finally:
            span.end()

    @contextmanager
    def generation(
        self,
        parent: Span | None,
        name: str,
        *,
        model: str,
        usage: Usage | Mapping[str, Any] | None = None,
        prompt: Any = None,
        completion: Any = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> Iterator[Span]:
        span = Span(name, self.config)
        if prompt is not None:
            span.set_input(prompt)
        if completion is not None:
            span.set_output(completion)
        if metadata:
            span.set_metadata(metadata)
        if usage is not None:
            span.set_usage(model, usage)
        try:
            yield span
        finally:
            span.end()

    def flush(self) -> None:
        return


class NoOpTracer(Tracer):
    degraded = True


class LangfuseTracer(Tracer):
    """包装 langfuse SDK；SDK 调用全部吞异常，采样率可配。"""

    degraded = False

    def __init__(self, config: LangfuseConfig, client: Any | None = None) -> None:
        super().__init__(config)
        self._rng = random.Random()
        self._lock = threading.Lock()
        if client is not None:
            self._client = client
        else:
            self._client = self._build_client(config)
        if self._client is None:
            self.degraded = True

    @staticmethod
    def _build_client(config: LangfuseConfig) -> Any | None:
        if not config.enabled or not config.public_key or not config.secret_key:
            return None
        try:
            import langfuse  # type: ignore[import-not-found]
        except ImportError:
            logger.warning("langfuse SDK 未安装，trace 降级为 NoOp")
            return None
        try:
            return langfuse.Langfuse(
                public_key=config.public_key,
                secret_key=config.secret_key,
                host=config.host,
                release=config.release,
            )
        except Exception:  # noqa: BLE001
            logger.warning("langfuse 初始化失败，trace 降级为 NoOp", exc_info=True)
            return None

    def _sampled(self) -> bool:
        if self._client is None:
            return False
        rate = min(max(self.config.sample_rate, 0.0), 1.0)
        return rate >= 1.0 or self._rng.random() < rate

    @contextmanager
    def trace(
        self,
        name: str,
        *,
        session_id: str | None = None,
        user_id: str | None = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> Iterator[Span]:
        sdk_trace = None
        if self._sampled():
            try:
                sdk_trace = self._client.trace(
                    name=name,
                    session_id=session_id,
                    user_id=user_id,
                    metadata=dict(metadata or {}),
                )
            except Exception:  # noqa: BLE001
                logger.warning("langfuse trace 创建失败", exc_info=True)
        span = Span(name, self.config, sdk_span=sdk_trace)
        span.trace_id = getattr(sdk_trace, "id", "") or ""
        if metadata:
            span.set_metadata(metadata)
        try:
            yield span
        finally:
            span.end()

    @contextmanager
    def generation(
        self,
        parent: Span | None,
        name: str,
        *,
        model: str,
        usage: Usage | Mapping[str, Any] | None = None,
        prompt: Any = None,
        completion: Any = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> Iterator[Span]:
        sdk_gen = None
        if self._sampled() and self._client is not None:
            try:
                parent_span = parent._sdk_span if parent is not None else None
                factory = getattr(parent_span, "generation", None)
                if callable(factory):
                    sdk_gen = factory(name=name, model=model)
                else:
                    sdk_gen = self._client.generation(name=name, model=model)
            except Exception:  # noqa: BLE001
                logger.warning("langfuse generation 创建失败", exc_info=True)
        span = Span(name, self.config, sdk_span=sdk_gen)
        span.trace_id = parent.trace_id if parent is not None else ""
        if prompt is not None:
            span.set_input(prompt)
        if completion is not None:
            span.set_output(completion)
        if metadata:
            span.set_metadata(metadata)
        if usage is not None:
            span.set_usage(model, usage)
        try:
            yield span
        finally:
            span.end()

    def flush(self) -> None:
        try:
            flush = getattr(self._client, "flush", None)
            if callable(flush):
                flush()
        except Exception:  # noqa: BLE001
            logger.warning("langfuse flush 失败", exc_info=True)


_tracer: Tracer | None = None
_tracer_lock = threading.Lock()


def get_tracer(config: LangfuseConfig | None = None) -> Tracer:
    global _tracer
    with _tracer_lock:
        if _tracer is None:
            if config is None:
                from .metrics import get_registry

                config = LangfuseConfig(metrics=get_registry())
            if config.enabled:
                candidate = LangfuseTracer(config)
                _tracer = candidate if not candidate.degraded else NoOpTracer(config)
            else:
                _tracer = NoOpTracer(config)
        return _tracer


def reset_tracer() -> None:
    global _tracer
    with _tracer_lock:
        _tracer = None
