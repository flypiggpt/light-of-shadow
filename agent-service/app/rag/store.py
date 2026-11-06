"""存储层：内存余弦向量库 + Okapi BM25 关键词索引，支持 JSON 持久化。"""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

from .chunker import Chunk

_TOKEN_RE = re.compile(r"[a-z0-9]+")
_CJK_RE = re.compile(r"[\u4e00-\u9fff]")


def tokenize(text: str) -> list[str]:
    """英文/数字按词，中文按字 + 二字词，兼顾短查询召回。"""
    lowered = text.lower()
    tokens = _TOKEN_RE.findall(lowered)
    cjk = _CJK_RE.findall(lowered)
    tokens.extend(cjk)
    tokens.extend("".join(pair) for pair in zip(cjk, cjk[1:]))
    return tokens


@dataclass
class ScoredChunk:
    chunk: Chunk
    score: float
    source: str = "unknown"

    def to_dict(self) -> dict[str, Any]:
        return {"chunk": self.chunk.to_dict(), "score": self.score, "source": self.source}


class VectorStore:
    """内存向量库：精确余弦相似度检索。规模上限由内存决定，适合单机部署。"""

    def __init__(self) -> None:
        self._chunks: dict[str, Chunk] = {}
        self._vectors: dict[str, list[float]] = {}
        self._norms: dict[str, float] = {}
        self._dim: int | None = None

    def __len__(self) -> int:
        return len(self._chunks)

    @property
    def dim(self) -> int | None:
        return self._dim

    def add(self, chunks: Sequence[Chunk], vectors: Sequence[Sequence[float]]) -> int:
        if len(chunks) != len(vectors):
            raise ValueError("chunks 与 vectors 数量不一致")
        for chunk, vec in zip(chunks, vectors):
            if not vec:
                raise ValueError(f"chunk {chunk.chunk_id} 的向量为空")
            if self._dim is None:
                self._dim = len(vec)
            elif len(vec) != self._dim:
                raise ValueError(
                    f"chunk {chunk.chunk_id} 维度不一致: 期望 {self._dim} 实际 {len(vec)}"
                )
            values = [float(v) for v in vec]
            self._chunks[chunk.chunk_id] = chunk
            self._vectors[chunk.chunk_id] = values
            self._norms[chunk.chunk_id] = math.sqrt(sum(v * v for v in values)) or 1.0
        return len(chunks)

    def search(self, vector: Sequence[float], k: int = 10) -> list[ScoredChunk]:
        if k <= 0:
            return []
        if not self._chunks:
            return []
        if self._dim is not None and len(vector) != self._dim:
            raise ValueError(f"查询向量维度不一致: 期望 {self._dim} 实际 {len(vector)}")
        query = [float(v) for v in vector]
        q_norm = math.sqrt(sum(v * v for v in query)) or 1.0
        scored: list[ScoredChunk] = []
        for chunk_id, vec in self._vectors.items():
            dot = sum(a * b for a, b in zip(query, vec))
            score = dot / (q_norm * self._norms[chunk_id])
            scored.append(ScoredChunk(chunk=self._chunks[chunk_id], score=score, source="vector"))
        scored.sort(key=lambda s: (-s.score, s.chunk.chunk_id))
        return scored[:k]

    def delete_doc(self, doc_id: str) -> int:
        targets = [cid for cid, c in self._chunks.items() if c.doc_id == doc_id]
        for cid in targets:
            self._chunks.pop(cid, None)
            self._vectors.pop(cid, None)
            self._norms.pop(cid, None)
        return len(targets)

    def get_chunk(self, chunk_id: str) -> Chunk | None:
        return self._chunks.get(chunk_id)

    def save(self, path: str | Path) -> None:
        payload = {
            "dim": self._dim,
            "chunks": [c.to_dict() for c in self._chunks.values()],
            "vectors": {cid: self._vectors[cid] for cid in self._chunks},
        }
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    @classmethod
    def load(cls, path: str | Path) -> "VectorStore":
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        store = cls()
        chunks = [Chunk.from_dict(c) for c in payload.get("chunks", [])]
        vectors = [payload["vectors"][c.chunk_id] for c in chunks]
        if chunks:
            store.add(chunks, vectors)
        return store


class KeywordIndex:
    """Okapi BM25 倒排索引。"""

    def __init__(self, k1: float = 1.5, b: float = 0.75) -> None:
        self.k1 = k1
        self.b = b
        self._chunks: dict[str, Chunk] = {}
        self._tokens: dict[str, list[str]] = {}
        self._postings: dict[str, dict[str, int]] = {}
        self._doc_len: dict[str, int] = {}
        self._doc_ids: dict[str, set[str]] = {}

    def __len__(self) -> int:
        return len(self._chunks)

    def add(self, chunks: Sequence[Chunk]) -> int:
        for chunk in chunks:
            self._remove_chunk(chunk.chunk_id)
            tokens = tokenize(chunk.text)
            self._chunks[chunk.chunk_id] = chunk
            self._tokens[chunk.chunk_id] = tokens
            self._doc_len[chunk.chunk_id] = len(tokens)
            self._doc_ids.setdefault(chunk.doc_id, set()).add(chunk.chunk_id)
            counts: dict[str, int] = {}
            for token in tokens:
                counts[token] = counts.get(token, 0) + 1
            for token, tf in counts.items():
                self._postings.setdefault(token, {})[chunk.chunk_id] = tf
        return len(chunks)

    def search(self, query: str, k: int = 10) -> list[ScoredChunk]:
        if k <= 0 or not self._chunks:
            return []
        query_tokens = tokenize(query)
        if not query_tokens:
            return []
        n_docs = len(self._chunks)
        avg_len = (sum(self._doc_len.values()) / n_docs) if n_docs else 0.0
        scores: dict[str, float] = {}
        for token in query_tokens:
            postings = self._postings.get(token)
            if not postings:
                continue
            df = len(postings)
            idf = math.log(1.0 + (n_docs - df + 0.5) / (df + 0.5))
            for chunk_id, tf in postings.items():
                dl = self._doc_len[chunk_id]
                denom = tf + self.k1 * (1.0 - self.b + self.b * dl / (avg_len or 1.0))
                scores[chunk_id] = scores.get(chunk_id, 0.0) + idf * (
                    tf * (self.k1 + 1.0) / denom
                )
        ranked = sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))
        return [
            ScoredChunk(chunk=self._chunks[cid], score=score, source="keyword")
            for cid, score in ranked[:k]
        ]

    def delete_doc(self, doc_id: str) -> int:
        chunk_ids = list(self._doc_ids.get(doc_id, ()))
        for cid in chunk_ids:
            self._remove_chunk(cid)
        self._doc_ids.pop(doc_id, None)
        return len(chunk_ids)

    def _remove_chunk(self, chunk_id: str) -> None:
        tokens = self._tokens.pop(chunk_id, None)
        if tokens is None:
            return
        chunk = self._chunks.pop(chunk_id, None)
        self._doc_len.pop(chunk_id, None)
        if chunk is not None:
            ids = self._doc_ids.get(chunk.doc_id)
            if ids is not None:
                ids.discard(chunk_id)
                if not ids:
                    self._doc_ids.pop(chunk.doc_id, None)
        for token in set(tokens):
            postings = self._postings.get(token)
            if postings is None:
                continue
            postings.pop(chunk_id, None)
            if not postings:
                self._postings.pop(token, None)

    def save(self, path: str | Path) -> None:
        payload = {
            "k1": self.k1,
            "b": self.b,
            "chunks": [c.to_dict() for c in self._chunks.values()],
        }
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    @classmethod
    def load(cls, path: str | Path) -> "KeywordIndex":
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        index = cls(k1=float(payload.get("k1", 1.5)), b=float(payload.get("b", 0.75)))
        index.add([Chunk.from_dict(c) for c in payload.get("chunks", [])])
        return index
