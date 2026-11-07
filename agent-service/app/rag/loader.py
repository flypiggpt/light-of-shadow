"""FAQ 语料装载与检索器构建（生产链路用）。

与 golden 评测同一套混合检索口径：HashEmbedder + BM25 + RRF + 词法重排，
构建失败抛出（启动期暴露，好过线上静默降级成关键词）。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Sequence

from app.rag.chunker import TextChunker
from app.rag.embedding import HashEmbedder
from app.rag.indexer import Indexer
from app.rag.retriever import HybridRetriever, LexicalReranker, RetrievalConfig
from app.rag.store import KeywordIndex, VectorStore

__all__ = ["DEFAULT_CORPUS_PATH", "load_corpus", "build_faq_retriever"]

DEFAULT_CORPUS_PATH = Path(__file__).resolve().parents[1] / "data" / "faq_corpus.json"


def load_corpus(path: Path | str = DEFAULT_CORPUS_PATH) -> list[dict[str, Any]]:
    """读取 FAQ 语料（[{doc_id, title, text}]）。"""
    docs = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(docs, list):
        raise ValueError(f"FAQ 语料格式错误: {path}")
    return docs


def build_faq_retriever(
    docs: Sequence[dict[str, Any]] | None = None,
    *,
    top_k: int = 3,
    candidate_k: int = 12,
) -> HybridRetriever:
    """构建 FAQ 混合检索器。"""
    docs = list(docs if docs is not None else load_corpus())
    chunker = TextChunker(chunk_size=256, overlap=32)
    embedder = HashEmbedder(dim=128)
    vector_store = VectorStore()
    keyword_index = KeywordIndex()
    indexer = Indexer(chunker, embedder, vector_store, keyword_index, batch_size=16)
    report = indexer.index(
        [
            {"doc_id": d["doc_id"], "text": d["text"], "metadata": {"title": d.get("title", "")}}
            for d in docs
        ]
    )
    if report.failed:
        raise RuntimeError(f"FAQ 语料索引失败: {report.errors}")
    return HybridRetriever(
        vector_store,
        keyword_index,
        embedder=embedder,
        reranker=LexicalReranker(),
        config=RetrievalConfig(top_k=top_k, candidate_k=candidate_k),
    )
