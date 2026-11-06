"""文本分块：边界感知的滑动窗口切分，支持重叠与元数据透传。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Sequence

DEFAULT_SEPARATORS: tuple[str, ...] = (
    "\n\n",
    "\n",
    "。",
    "！",
    "？",
    ". ",
    "! ",
    "? ",
    "；",
    "; ",
    "，",
    ", ",
    " ",
)


@dataclass
class Chunk:
    """分块结果。chunk_id 在同一 doc_id 内按序号稳定生成。"""

    chunk_id: str
    doc_id: str
    text: str
    ordinal: int
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "chunk_id": self.chunk_id,
            "doc_id": self.doc_id,
            "text": self.text,
            "ordinal": self.ordinal,
            "metadata": dict(self.metadata),
        }

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> "Chunk":
        return cls(
            chunk_id=str(data["chunk_id"]),
            doc_id=str(data["doc_id"]),
            text=str(data["text"]),
            ordinal=int(data["ordinal"]),
            metadata=dict(data.get("metadata") or {}),
        )


class TextChunker:
    """把长文本切成不超过 chunk_size 的块，块间保留 overlap 个字符的重叠。

    切分位置优先落在分隔符（段落/句子/标点/空格）边界上，避免截断语义单元。
    """

    def __init__(
        self,
        chunk_size: int = 512,
        overlap: int = 64,
        separators: Sequence[str] | None = None,
        min_chunk_ratio: float = 0.5,
    ) -> None:
        if chunk_size < 32:
            raise ValueError("chunk_size 必须 >= 32")
        if not 0 <= overlap < chunk_size:
            raise ValueError("overlap 必须满足 0 <= overlap < chunk_size")
        if not 0 < min_chunk_ratio <= 1:
            raise ValueError("min_chunk_ratio 必须在 (0, 1] 内")
        self.chunk_size = chunk_size
        self.overlap = overlap
        self.separators = tuple(separators) if separators is not None else DEFAULT_SEPARATORS
        self.min_chunk_ratio = min_chunk_ratio

    def chunk(
        self,
        text: str,
        doc_id: str,
        metadata: Mapping[str, Any] | None = None,
    ) -> list[Chunk]:
        if not isinstance(text, str):
            raise TypeError("text 必须是 str")
        if not doc_id:
            raise ValueError("doc_id 不能为空")
        normalized = self._normalize(text)
        if not normalized:
            return []
        meta = dict(metadata or {})
        chunks: list[Chunk] = []
        start = 0
        n = len(normalized)
        while start < n:
            end = min(start + self.chunk_size, n)
            if end < n:
                end = self._find_boundary(normalized, start, end)
            piece = normalized[start:end].strip()
            if piece:
                chunks.append(
                    Chunk(
                        chunk_id=f"{doc_id}#{len(chunks)}",
                        doc_id=doc_id,
                        text=piece,
                        ordinal=len(chunks),
                        metadata=dict(meta),
                    )
                )
            if end >= n:
                break
            start = max(end - self.overlap, start + 1)
        return chunks

    @staticmethod
    def _normalize(text: str) -> str:
        lines = [line.rstrip() for line in text.strip().splitlines()]
        collapsed: list[str] = []
        blank = False
        for line in lines:
            if line:
                collapsed.append(line)
                blank = False
            elif not blank:
                collapsed.append("")
                blank = True
        return "\n".join(collapsed).strip()

    def _find_boundary(self, text: str, start: int, end: int) -> int:
        min_end = start + max(int(self.chunk_size * self.min_chunk_ratio), 1)
        for sep in self.separators:
            if not sep:
                continue
            idx = text.rfind(sep, min_end, end)
            if idx != -1:
                cut = idx + len(sep)
                if start < cut <= end:
                    return cut
        idx = text.rfind(" ", min_end, end)
        if idx != -1 and start < idx <= end:
            return idx
        return end
