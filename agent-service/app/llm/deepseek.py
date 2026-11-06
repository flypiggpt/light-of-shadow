"""DeepSeek LLM 客户端（OpenAI 兼容协议）。

- 超时（默认 30s）+ 指数退避重试（≤2 次，仅 429/5xx/网络故障）
- 失败降级话术：重试耗尽后返回预置话术，不上抛到对话层
- token 统计：累计 prompt/completion tokens 与调用次数
- SSE 流式：逐块产出增量文本
"""

from __future__ import annotations

import asyncio
import json
import os
import random
import time
from dataclasses import dataclass, field
from typing import Any, AsyncIterator

import httpx
import structlog

logger = structlog.get_logger(__name__)

__all__ = [
    "LLMError",
    "LLMTimeoutError",
    "LLMConnectionError",
    "LLMAPIError",
    "TokenUsage",
    "ChatResult",
    "DeepSeekClient",
    "DEFAULT_FALLBACK_MESSAGE",
    "FALLBACK_MESSAGES",
]

DEFAULT_FALLBACK_MESSAGE = "抱歉，我现在开小差了，请稍后再试。"

FALLBACK_MESSAGES: tuple[str, ...] = (
    DEFAULT_FALLBACK_MESSAGE,
    "抱歉，刚才没听清，可以再说一遍吗？",
    "系统正在忙，请稍等片刻再试。",
)


class LLMError(Exception):
    """LLM 调用失败基类（重试耗尽）。"""


class LLMTimeoutError(LLMError):
    """请求超时。"""


class LLMConnectionError(LLMError):
    """网络连接失败。"""


class LLMAPIError(LLMError):
    """HTTP 4xx/5xx 或非法响应。"""

    def __init__(self, status_code: int, message: str) -> None:
        self.status_code = status_code
        self.message = message
        super().__init__(f"LLM API 错误 http={status_code} message={message}")


@dataclass
class TokenUsage:
    """token 用量累计。"""

    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    requests: int = 0

    def add(self, usage: dict[str, Any] | None) -> None:
        self.requests += 1
        if not usage:
            return
        self.prompt_tokens += int(usage.get("prompt_tokens") or 0)
        self.completion_tokens += int(usage.get("completion_tokens") or 0)
        self.total_tokens += int(usage.get("total_tokens") or 0)


@dataclass
class ChatResult:
    """非流式对话结果。"""

    content: str
    model: str = ""
    usage: dict[str, Any] = field(default_factory=dict)
    latency_ms: float = 0.0
    degraded: bool = False
    finish_reason: str = ""


class DeepSeekClient:
    """OpenAI 兼容的 DeepSeek 异步客户端，建议全局单例。"""

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        *,
        model: str | None = None,
        timeout_s: float = 30.0,
        max_retries: int = 2,
        backoff_base_s: float = 0.5,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._api_key = api_key or os.getenv("DEEPSEEK_API_KEY", "")
        self._base_url = (
            base_url or os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
        ).rstrip("/")
        self._model = model or os.getenv("DEEPSEEK_MODEL", "deepseek-chat")
        self._timeout_s = timeout_s
        self._max_retries = max_retries
        self._backoff_base_s = backoff_base_s
        self.usage = TokenUsage()
        self._client = httpx.AsyncClient(
            base_url=self._base_url,
            timeout=httpx.Timeout(self._timeout_s, connect=min(10.0, self._timeout_s)),
            transport=transport,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> DeepSeekClient:
        return self

    async def __aexit__(self, *exc_info: object) -> None:
        await self.aclose()

    # ------------------------------------------------------------------
    # 非流式
    # ------------------------------------------------------------------

    async def chat(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float = 0.7,
        max_tokens: int | None = None,
        fallback_on_error: bool = False,
    ) -> ChatResult:
        """一次对话补全。

        fallback_on_error=True 时，重试耗尽后返回降级话术（degraded=True），
        而不是抛出 LLMError。
        """
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": messages,
            "temperature": temperature,
        }
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens

        start = time.perf_counter()
        try:
            data = await self._request_json(payload)
        except LLMError as exc:
            latency_ms = (time.perf_counter() - start) * 1000
            logger.warning(
                "llm.chat_failed",
                error=str(exc),
                latency_ms=round(latency_ms, 1),
                fallback=fallback_on_error,
            )
            if fallback_on_error:
                return self._fallback_result(exc, latency_ms)
            raise

        latency_ms = (time.perf_counter() - start) * 1000
        usage = data.get("usage") or {}
        self.usage.add(usage)
        choice = (data.get("choices") or [{}])[0]
        content = (choice.get("message") or {}).get("content") or ""
        logger.info(
            "llm.chat",
            model=data.get("model", self._model),
            latency_ms=round(latency_ms, 1),
            prompt_tokens=usage.get("prompt_tokens"),
            completion_tokens=usage.get("completion_tokens"),
            finish_reason=choice.get("finish_reason"),
        )
        return ChatResult(
            content=content,
            model=data.get("model", self._model),
            usage=usage,
            latency_ms=latency_ms,
            finish_reason=choice.get("finish_reason") or "",
        )

    # ------------------------------------------------------------------
    # SSE 流式
    # ------------------------------------------------------------------

    async def chat_stream(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float = 0.7,
        max_tokens: int | None = None,
    ) -> AsyncIterator[str]:
        """SSE 流式补全，逐块 yield 增量文本；失败向上抛 LLMError。"""
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": messages,
            "temperature": temperature,
            "stream": True,
        }
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens

        start = time.perf_counter()
        chunks = 0
        usage: dict[str, Any] | None = None
        headers = {"Authorization": f"Bearer {self._api_key}"}
        try:
            async with self._client.stream(
                "POST", "/chat/completions", json=payload, headers=headers
            ) as resp:
                if resp.status_code >= 400:
                    body = (await resp.aread()).decode("utf-8", "replace")[:200]
                    raise LLMAPIError(resp.status_code, body)
                async for line in resp.aiter_lines():
                    if not line or not line.startswith("data:"):
                        continue
                    data_str = line[len("data:"):].strip()
                    if data_str == "[DONE]":
                        break
                    try:
                        chunk = json.loads(data_str)
                    except json.JSONDecodeError:
                        logger.warning("llm.stream_bad_chunk", chunk=data_str[:100])
                        continue
                    if chunk.get("usage"):
                        usage = chunk["usage"]
                    for choice in chunk.get("choices") or []:
                        delta = (choice.get("delta") or {}).get("content")
                        if delta:
                            chunks += 1
                            yield delta
        except httpx.TimeoutException as exc:
            raise LLMTimeoutError("LLM 流式请求超时") from exc
        except httpx.TransportError as exc:
            raise LLMConnectionError("LLM 流式连接失败") from exc

        latency_ms = (time.perf_counter() - start) * 1000
        self.usage.add(usage)
        logger.info(
            "llm.chat_stream",
            model=self._model,
            latency_ms=round(latency_ms, 1),
            chunks=chunks,
            prompt_tokens=(usage or {}).get("prompt_tokens"),
            completion_tokens=(usage or {}).get("completion_tokens"),
        )

    # ------------------------------------------------------------------
    # 内部实现
    # ------------------------------------------------------------------

    async def _request_json(self, payload: dict[str, Any]) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {self._api_key}"}
        attempts = self._max_retries + 1
        last_error: LLMError | None = None

        for attempt in range(1, attempts + 1):
            start = time.perf_counter()
            try:
                resp = await self._client.post(
                    "/chat/completions", json=payload, headers=headers
                )
            except httpx.TimeoutException as exc:
                latency_ms = (time.perf_counter() - start) * 1000
                logger.warning(
                    "llm.timeout", attempt=attempt, latency_ms=round(latency_ms, 1)
                )
                last_error = LLMTimeoutError("LLM 请求超时")
                last_error.__cause__ = exc
            except httpx.TransportError as exc:
                latency_ms = (time.perf_counter() - start) * 1000
                logger.warning(
                    "llm.transport_error",
                    attempt=attempt,
                    latency_ms=round(latency_ms, 1),
                    error=str(exc),
                )
                last_error = LLMConnectionError("LLM 连接失败")
                last_error.__cause__ = exc
            else:
                if resp.status_code == 429 or resp.status_code >= 500:
                    logger.warning(
                        "llm.retryable_status",
                        status=resp.status_code,
                        attempt=attempt,
                    )
                    last_error = LLMAPIError(resp.status_code, resp.text[:200])
                elif resp.status_code >= 400:
                    raise LLMAPIError(resp.status_code, resp.text[:200])
                else:
                    try:
                        return resp.json()
                    except ValueError as exc:
                        raise LLMAPIError(resp.status_code, "非法 JSON 响应") from exc

            if attempt < attempts:
                delay = self._backoff_base_s * (2 ** (attempt - 1)) + random.uniform(0, 0.1)
                await asyncio.sleep(delay)

        assert last_error is not None
        raise last_error

    def _fallback_result(self, cause: Exception, latency_ms: float) -> ChatResult:
        logger.warning("llm.degraded", cause=str(cause), latency_ms=round(latency_ms, 1))
        self.usage.requests += 1
        return ChatResult(
            content=DEFAULT_FALLBACK_MESSAGE,
            model=self._model,
            latency_ms=latency_ms,
            degraded=True,
            finish_reason="fallback",
        )
