"""混合检索：向量 + BM25 关键词，RRF 融合，可选重排；任一环节失败自动降级。"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Callable, Protocol, Sequence

from .embedding import Embedder
from .store import KeywordIndex, ScoredChunk, VectorStore, tokenize


class Reranker(Protocol):
    def rerank(
        self, query: str, candidates: Sequence[ScoredChunk], top_k: int
    ) -> list[ScoredChunk]: ...


@dataclass
class RetrievalConfig:
    top_k: int = 5
    candidate_k: int = 20
    rrf_k: int = 60
    min_score: float = 0.0
    use_rerank: bool = True


@dataclass
class RetrievalResult:
    items: list[ScoredChunk] = field(default_factory=list)
    degraded: bool = False
    stages: dict[str, Any] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)
    latency_ms: float = 0.0

    @property
    def doc_ids(self) -> list[str]:
        seen: list[str] = []
        for item in self.items:
            if item.chunk.doc_id not in seen:
                seen.append(item.chunk.doc_id)
        return seen

    def to_dict(self) -> dict[str, Any]:
        return {
            "items": [i.to_dict() for i in self.items],
            "degraded": self.degraded,
            "stages": dict(self.stages),
            "errors": list(self.errors),
            "latency_ms": self.latency_ms,
        }


def rrf_fuse(
    rankings: Sequence[Sequence[ScoredChunk]], rrf_k: int = 60
) -> list[ScoredChunk]:
    """Reciprocal Rank Fusion：score(d) = Σ 1 / (rrf_k + rank_i(d))。"""
    if rrf_k <= 0:
        raise ValueError("rrf_k 必须为正数")
    scores: dict[str, float] = {}
    keep: dict[str, ScoredChunk] = {}
    for ranking in rankings:
        for rank, item in enumerate(ranking, start=1):
            cid = item.chunk.chunk_id
            scores[cid] = scores.get(cid, 0.0) + 1.0 / (rrf_k + rank)
            if cid not in keep:
                keep[cid] = item
    fused = [
        ScoredChunk(chunk=keep[cid].chunk, score=score, source="fused")
        for cid, score in scores.items()
    ]
    fused.sort(key=lambda s: (-s.score, s.chunk.chunk_id))
    return fused


class LexicalReranker:
    """轻量重排：查询词覆盖、短语命中、IDF 加权，无外部模型依赖。"""

    def __init__(self, phrase_bonus: float = 0.5, coverage_weight: float = 1.0) -> None:
        self.phrase_bonus = phrase_bonus
        self.coverage_weight = coverage_weight

    def rerank(
        self, query: str, candidates: Sequence[ScoredChunk], top_k: int
    ) -> list[ScoredChunk]:
        if top_k <= 0 or not candidates:
            return []
        q_tokens = tokenize(query)
        q_set = set(q_tokens)
        compact_query = "".join(query.split())
        rescored: list[ScoredChunk] = []
        for item in candidates:
            text_tokens = set(tokenize(item.chunk.text))
            coverage = len(q_set & text_tokens) / len(q_set) if q_set else 0.0
            bonus = self.phrase_bonus if compact_query and compact_query in "".join(
                item.chunk.text.split()
            ) else 0.0
            score = self.coverage_weight * coverage + bonus
            rescored.append(
                ScoredChunk(chunk=item.chunk, score=score, source="rerank")
            )
        rescored.sort(key=lambda s: (-s.score, s.chunk.chunk_id))
        return rescored[:top_k]


class LLMReranker:
    """LLM 打分重排：score_fn(query, text) -> float，异常由调用方降级兜底。"""

    def __init__(self, score_fn: Callable[[str, str], float]) -> None:
        self.score_fn = score_fn

    def rerank(
        self, query: str, candidates: Sequence[ScoredChunk], top_k: int
    ) -> list[ScoredChunk]:
        rescored: list[ScoredChunk] = []
        for item in candidates:
            score = float(self.score_fn(query, item.chunk.text))
            rescored.append(
                ScoredChunk(chunk=item.chunk, score=score, source="rerank")
            )
        rescored.sort(key=lambda s: (-s.score, s.chunk.chunk_id))
        return rescored[:top_k]


class HybridRetriever:
    """混合检索入口。

    降级顺序：embedding 失败→仅关键词；向量检索失败→仅关键词/仅向量；
    双路全失败→返回空结果（不抛异常）；重排失败→保留 RRF 融合序。
    """

    def __init__(
        self,
        vector_store: VectorStore,
        keyword_index: KeywordIndex,
        embedder: Embedder | None = None,
        reranker: Reranker | None = None,
        config: RetrievalConfig | None = None,
        metrics: Any | None = None,
    ) -> None:
        self.vector_store = vector_store
        self.keyword_index = keyword_index
        self.embedder = embedder
        self.reranker = reranker
        self.config = config or RetrievalConfig()
        self.metrics = metrics

    def retrieve(self, query: str, top_k: int | None = None) -> RetrievalResult:
        cfg = self.config
        k = top_k if top_k is not None else cfg.top_k
        if k <= 0:
            raise ValueError("top_k 必须为正数")
        started = time.perf_counter()
        errors: list[str] = []
        stages: dict[str, Any] = {"embed": False, "vector": False, "keyword": False, "rerank": None}
        vector_hits: list[ScoredChunk] = []
        keyword_hits: list[ScoredChunk] = []

        embedding: Sequence[float] | None = None
        if self.embedder is not None:
            try:
                embedding = self.embedder.embed_query(query)
                stages["embed"] = True
            except Exception as exc:  # noqa: BLE001 - 降级边界
                errors.append(f"embed: {type(exc).__name__}: {exc}")

        if embedding is not None:
            try:
                vector_hits = self.vector_store.search(embedding, k=cfg.candidate_k)
                stages["vector"] = True
            except Exception as exc:  # noqa: BLE001
                errors.append(f"vector: {type(exc).__name__}: {exc}")

        try:
            keyword_hits = self.keyword_index.search(query, k=cfg.candidate_k)
            stages["keyword"] = True
        except Exception as exc:  # noqa: BLE001
            errors.append(f"keyword: {type(exc).__name__}: {exc}")

        rankings = [r for r in (vector_hits, keyword_hits) if r]
        fused = rrf_fuse(rankings, rrf_k=cfg.rrf_k) if rankings else []
        if cfg.min_score > 0:
            fused = [item for item in fused if item.score >= cfg.min_score]

        items = fused[:k]
        if cfg.use_rerank and self.reranker is not None and fused:
            try:
                items = self.reranker.rerank(query, fused, k)
                stages["rerank"] = True
            except Exception as exc:  # noqa: BLE001
                stages["rerank"] = False
                errors.append(f"rerank: {type(exc).__name__}: {exc}")
                items = fused[:k]

        # 只要链路出过错（含重排失败）即视为降级；纯关键词模式（无 embedder）不算降级。
        degraded = bool(errors)
        latency_ms = (time.perf_counter() - started) * 1000.0
        result = RetrievalResult(
            items=items,
            degraded=degraded,
            stages=stages,
            errors=errors,
            latency_ms=latency_ms,
        )
        self._observe(result, len(items))
        return result

    def retrieve_docs(self, query: str, top_k: int | None = None) -> list[str]:
        return self.retrieve(query, top_k=top_k).doc_ids

    def _observe(self, result: RetrievalResult, result_count: int) -> None:
        if self.metrics is None:
            return
        try:
            self.metrics.observe_retrieval(
                mode="hybrid",
                duration_s=result.latency_ms / 1000.0,
                result_count=result_count,
                degraded=result.degraded,
            )
        except Exception:  # noqa: BLE001 - 观测失败不影响检索
            pass
