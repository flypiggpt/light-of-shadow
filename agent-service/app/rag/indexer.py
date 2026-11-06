"""索引管线：分块 → 批量向量化 → 向量库 + BM25 双写；失败可局部降级。"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass, field
from typing import Any, Iterable, Mapping

from .chunker import Chunk, TextChunker
from .embedding import Embedder
from .store import KeywordIndex, VectorStore


@dataclass
class IndexDocument:
    doc_id: str
    text: str
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_obj(cls, obj: "IndexDocument | Mapping[str, Any]") -> "IndexDocument":
        if isinstance(obj, IndexDocument):
            return obj
        doc_id = str(obj.get("doc_id") or obj.get("id") or "").strip()
        text = str(obj.get("text") or "")
        if not doc_id:
            raise ValueError("文档缺少 doc_id")
        return cls(doc_id=doc_id, text=text, metadata=dict(obj.get("metadata") or {}))


@dataclass
class IndexReport:
    documents: int = 0
    chunks_built: int = 0
    embedded: int = 0
    vectors_added: int = 0
    keyword_added: int = 0
    skipped: int = 0
    failed: int = 0
    degraded: bool = False
    errors: list[str] = field(default_factory=list)
    duration_s: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "documents": self.documents,
            "chunks_built": self.chunks_built,
            "embedded": self.embedded,
            "vectors_added": self.vectors_added,
            "keyword_added": self.keyword_added,
            "skipped": self.skipped,
            "failed": self.failed,
            "degraded": self.degraded,
            "errors": list(self.errors),
            "duration_s": self.duration_s,
        }


def content_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class Indexer:
    """文档入库器。

    - 幂等：同一 doc_id 重复索引会替换旧分块；内容未变化则跳过。
    - 降级：向量化失败时仍写入关键词索引，保证关键词检索可用。
    """

    def __init__(
        self,
        chunker: TextChunker,
        embedder: Embedder,
        vector_store: VectorStore,
        keyword_index: KeywordIndex,
        batch_size: int = 32,
        metrics: Any | None = None,
    ) -> None:
        self.chunker = chunker
        self.embedder = embedder
        self.vector_store = vector_store
        self.keyword_index = keyword_index
        self.batch_size = max(1, batch_size)
        self.metrics = metrics
        self._hashes: dict[str, str] = {}

    def index(
        self, docs: Iterable[IndexDocument | Mapping[str, Any]]
    ) -> IndexReport:
        started = time.perf_counter()
        report = IndexReport()
        prepared: list[IndexDocument] = []
        for obj in docs:
            doc = IndexDocument.from_obj(obj)
            report.documents += 1
            digest = content_hash(doc.text)
            if self._hashes.get(doc.doc_id) == digest:
                report.skipped += 1
                continue
            prepared.append(doc)
            self._hashes[doc.doc_id] = digest

        pending: list[Chunk] = []
        for doc in prepared:
            try:
                self.remove(doc.doc_id, drop_hash=False)
                chunks = self.chunker.chunk(doc.text, doc.doc_id, doc.metadata)
            except Exception as exc:  # noqa: BLE001 - 单文档失败不阻断批次
                report.failed += 1
                report.errors.append(f"{doc.doc_id}: {type(exc).__name__}: {exc}")
                self._hashes.pop(doc.doc_id, None)
                continue
            report.chunks_built += len(chunks)
            pending.extend(chunks)

        for start in range(0, len(pending), self.batch_size):
            batch = pending[start : start + self.batch_size]
            self._index_batch(batch, report)

        report.duration_s = time.perf_counter() - started
        self._observe(report)
        return report

    def _index_batch(self, batch: list[Chunk], report: IndexReport) -> None:
        vectors: list[list[float]] | None = None
        try:
            vectors = self.embedder.embed_documents([c.text for c in batch])
            if len(vectors) != len(batch):
                raise RuntimeError("embedding 返回数量与分块数量不一致")
            report.embedded += len(batch)
        except Exception as exc:  # noqa: BLE001 - 降级到仅关键词索引
            report.degraded = True
            report.errors.append(f"embed: {type(exc).__name__}: {exc}")
            vectors = None

        if vectors is not None:
            try:
                report.vectors_added += self.vector_store.add(batch, vectors)
            except Exception as exc:  # noqa: BLE001
                report.degraded = True
                report.errors.append(f"vector_store: {type(exc).__name__}: {exc}")

        try:
            report.keyword_added += self.keyword_index.add(batch)
        except Exception as exc:  # noqa: BLE001
            report.degraded = True
            report.errors.append(f"keyword_index: {type(exc).__name__}: {exc}")

    def remove(self, doc_id: str, drop_hash: bool = False) -> None:
        self.vector_store.delete_doc(doc_id)
        self.keyword_index.delete_doc(doc_id)
        if drop_hash:
            self._hashes.pop(doc_id, None)

    def stats(self) -> dict[str, Any]:
        return {
            "vectors": len(self.vector_store),
            "keyword_chunks": len(self.keyword_index),
            "tracked_docs": len(self._hashes),
        }

    def _observe(self, report: IndexReport) -> None:
        if self.metrics is None:
            return
        try:
            outcome = "degraded" if report.degraded else "ok"
            if report.failed and not report.chunks_built:
                outcome = "failed"
            self.metrics.observe_index(
                outcome=outcome,
                documents=report.documents,
                chunks=report.chunks_built,
                duration_s=report.duration_s,
            )
        except Exception:  # noqa: BLE001
            pass
