"""Embedding：OpenAI 兼容接口 + 确定性哈希兜底，网络失败自动降级。"""

from __future__ import annotations

import hashlib
import json
import math
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Protocol, Sequence

JsonDict = dict[str, Any]
PostFn = Callable[[str, JsonDict, dict[str, str], float], JsonDict]


class Embedder(Protocol):
    @property
    def dim(self) -> int: ...

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]: ...

    def embed_query(self, text: str) -> list[float]: ...


def _l2_normalize(vec: list[float]) -> list[float]:
    norm = math.sqrt(sum(v * v for v in vec))
    if norm <= 0.0:
        out = [0.0] * len(vec)
        out[0] = 1e-12
        return out
    return [v / norm for v in vec]


class HashEmbedder:
    """特征哈希向量化：无网络、完全确定，用于降级与离线测试。"""

    def __init__(self, dim: int = 256, seed: str = "los-rag") -> None:
        if dim < 8:
            raise ValueError("dim 必须 >= 8")
        self._dim = dim
        self._seed = seed

    @property
    def dim(self) -> int:
        return self._dim

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        return [self._embed_one(t) for t in texts]

    def embed_query(self, text: str) -> list[float]:
        return self._embed_one(text)

    def _embed_one(self, text: str) -> list[float]:
        vec = [0.0] * self._dim
        for token in self._tokenize(text):
            digest = hashlib.blake2b(
                f"{self._seed}:{token}".encode("utf-8"), digest_size=8
            ).digest()
            h = int.from_bytes(digest, "big")
            idx = h % self._dim
            sign = 1.0 if (h >> 63) & 1 == 0 else -1.0
            vec[idx] += sign
        return _l2_normalize(vec)

    @staticmethod
    def _tokenize(text: str) -> list[str]:
        tokens: list[str] = []
        word = []
        chars = list(text.lower())
        for ch in chars:
            if ch.isalnum():
                word.append(ch)
                continue
            if word:
                tokens.append("".join(word))
                word = []
        if word:
            tokens.append("".join(word))
        # CJK 按单字 + 二字词切分，提升中文短查询的召回。
        cjk = [c for c in chars if "\u4e00" <= c <= "\u9fff"]
        tokens.extend(cjk)
        tokens.extend("".join(pair) for pair in zip(cjk, cjk[1:]))
        return tokens


class OpenAICompatibleEmbedder:
    """调用 OpenAI 兼容 /embeddings 接口；带超时与指数退避重试。"""

    def __init__(
        self,
        base_url: str,
        model: str,
        api_key: str | None = None,
        timeout: float = 10.0,
        max_retries: int = 2,
        batch_size: int = 64,
        dimensions: int | None = None,
        post_fn: PostFn | None = None,
    ) -> None:
        if not base_url:
            raise ValueError("base_url 不能为空")
        if not model:
            raise ValueError("model 不能为空")
        if max_retries < 0:
            raise ValueError("max_retries 不能为负数")
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.api_key = api_key
        self.timeout = timeout
        self.max_retries = max_retries
        self.batch_size = max(1, batch_size)
        self._dim = dimensions or 0
        self._post_fn = post_fn

    @property
    def dim(self) -> int:
        return self._dim

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        vectors: list[list[float]] = []
        for i in range(0, len(texts), self.batch_size):
            batch = list(texts[i : i + self.batch_size])
            vectors.extend(self._embed_batch(batch))
        return vectors

    def embed_query(self, text: str) -> list[float]:
        return self._embed_batch([text])[0]

    def _embed_batch(self, batch: list[str]) -> list[list[float]]:
        payload: JsonDict = {"model": self.model, "input": batch}
        if self._dim:
            payload["dimensions"] = self._dim
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        body = self._post_with_retry(f"{self.base_url}/embeddings", payload, headers)
        data = body.get("data")
        if not isinstance(data, list) or len(data) != len(batch):
            raise RuntimeError(f"embedding 响应异常: {body!r:.200}")
        items = sorted(data, key=lambda d: int(d.get("item", d.get("index", 0))))
        vectors: list[list[float]] = []
        for item in items:
            vec = item.get("embedding")
            if not isinstance(vec, list) or not vec or not all(
                isinstance(v, (int, float)) for v in vec
            ):
                raise RuntimeError("embedding 响应缺少合法向量")
            vectors.append([float(v) for v in vec])
        if self._dim == 0:
            self._dim = len(vectors[0])
        elif len(vectors[0]) != self._dim:
            raise RuntimeError(
                f"embedding 维度不一致: 期望 {self._dim} 实际 {len(vectors[0])}"
            )
        return vectors

    def _post_with_retry(self, url: str, payload: JsonDict, headers: dict[str, str]) -> JsonDict:
        last_error: Exception | None = None
        attempts = self.max_retries + 1
        for attempt in range(attempts):
            try:
                return self._post(url, payload, headers, self.timeout)
            except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, RuntimeError) as exc:
                last_error = exc
                if attempt < attempts - 1:
                    time.sleep(min(0.5 * (2**attempt), 4.0))
        raise RuntimeError(f"embedding 调用失败（重试 {attempts} 次）: {last_error}")

    def _post(self, url: str, payload: JsonDict, headers: dict[str, str], timeout: float) -> JsonDict:
        if self._post_fn is not None:
            return self._post_fn(url, payload, headers, timeout)
        data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
        parsed = json.loads(raw)
        if not isinstance(parsed, dict):
            raise RuntimeError("embedding 响应不是 JSON 对象")
        return parsed


class FallbackEmbedder:
    """主 embedding 失败时切换到兜底实现，检索链路不因向量化故障而中断。"""

    def __init__(self, primary: Embedder | None, fallback: Embedder) -> None:
        if fallback is None:
            raise ValueError("fallback 不能为空")
        self.primary = primary
        self.fallback = fallback
        self.degraded = False
        self.last_error: str | None = None

    @property
    def dim(self) -> int:
        active = self.primary or self.fallback
        return active.dim

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        if self.primary is not None:
            try:
                return self.primary.embed_documents(texts)
            except Exception as exc:  # noqa: BLE001 - 降级边界，任何异常都不外溢
                self._degrade(exc)
        return self.fallback.embed_documents(texts)

    def embed_query(self, text: str) -> list[float]:
        if self.primary is not None:
            try:
                return self.primary.embed_query(text)
            except Exception as exc:  # noqa: BLE001
                self._degrade(exc)
        return self.fallback.embed_query(text)

    def _degrade(self, exc: Exception) -> None:
        self.degraded = True
        self.last_error = f"{type(exc).__name__}: {exc}"


@dataclass
class EmbedderConfig:
    """由 config 层填充；此处不依赖具体配置实现。"""

    provider: str = "hash"  # hash | openai | fallback
    base_url: str | None = None
    api_key: str | None = None
    model: str = "text-embedding-small"
    dim: int = 256
    timeout: float = 10.0
    max_retries: int = 2
    batch_size: int = 64


def create_embedder(config: EmbedderConfig, post_fn: PostFn | None = None) -> Embedder:
    fallback = HashEmbedder(dim=config.dim)
    if config.provider == "hash":
        return fallback
    if not config.base_url:
        raise ValueError("provider=openai/fallback 时必须提供 base_url")
    primary = OpenAICompatibleEmbedder(
        base_url=config.base_url,
        model=config.model,
        api_key=config.api_key,
        timeout=config.timeout,
        max_retries=config.max_retries,
        batch_size=config.batch_size,
        dimensions=config.dim if config.provider == "fallback" else None,
        post_fn=post_fn,
    )
    if config.provider == "openai":
        return primary
    if config.provider == "fallback":
        return FallbackEmbedder(primary, fallback)
    raise ValueError(f"未知 embedding provider: {config.provider}")
