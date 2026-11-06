"""Golden set 评测框架：检索命中率 / 工具调用成功率 / 回答正确率。

- 检索评测走真实 RAG 管线（hash embedding + BM25 + RRF + 轻量重排），离线可跑。
- 工具/回答评测通过 agent_fn 注入（返回 tool_calls + answer），Agent 编排接入后即测。
- 附带 RAG 降级链路与观测指标的自检，保证评测基座本身可信。
"""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[2]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from app.obs.langfuse import LangfuseConfig, NoOpTracer, Usage, estimate_cost  # noqa: E402
from app.obs.metrics import MetricsRegistry  # noqa: E402
from app.rag import (  # noqa: E402
    Chunk,
    HashEmbedder,
    HybridRetriever,
    Indexer,
    KeywordIndex,
    LexicalReranker,
    RetrievalConfig,
    ScoredChunk,
    TextChunker,
    VectorStore,
    rrf_fuse,
)

CASES_PATH = Path(__file__).resolve().parent / "cases.json"

VALID_TYPES = {"retrieval", "tool", "answer", "mixed"}
AgentFn = Callable[[str], Mapping[str, Any]]


@dataclass
class EvalReport:
    name: str
    total: int = 0
    passed: int = 0
    skipped: int = 0
    rate: float | None = None
    mrr: float | None = None
    failures: list[dict[str, Any]] = field(default_factory=list)

    def finalize(self) -> "EvalReport":
        evaluated = self.total - self.skipped
        self.rate = (self.passed / evaluated) if evaluated else None
        return self

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "total": self.total,
            "passed": self.passed,
            "skipped": self.skipped,
            "rate": self.rate,
            "mrr": self.mrr,
            "failures": list(self.failures),
        }


class GoldenSet:
    """golden cases.json 的加载与结构校验。"""

    def __init__(self, path: str | Path = CASES_PATH) -> None:
        self.path = Path(path)
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        self.version = str(raw.get("version", ""))
        self.corpus: list[dict[str, Any]] = list(raw.get("corpus") or [])
        self.cases: list[dict[str, Any]] = list(raw.get("cases") or [])
        self._validate()

    def _validate(self) -> None:
        if not self.corpus:
            raise ValueError("corpus 不能为空")
        corpus_ids = [str(d.get("doc_id", "")) for d in self.corpus]
        if any(not cid for cid in corpus_ids) or len(set(corpus_ids)) != len(corpus_ids):
            raise ValueError("corpus doc_id 必须非空且唯一")
        if not 30 <= len(self.cases) <= 50:
            raise ValueError(f"cases 数量必须在 30–50 之间，实际 {len(self.cases)}")
        seen: set[str] = set()
        for case in self.cases:
            cid = str(case.get("id", ""))
            if not cid or cid in seen:
                raise ValueError(f"case id 为空或重复: {cid!r}")
            seen.add(cid)
            if case.get("type") not in VALID_TYPES:
                raise ValueError(f"{cid}: type 非法 {case.get('type')!r}")
            query = case.get("query")
            if not isinstance(query, str) or not query.strip():
                raise ValueError(f"{cid}: query 必须为非空字符串")
            expected = case.get("expected")
            if not isinstance(expected, dict) or not expected:
                raise ValueError(f"{cid}: expected 必须为非空对象")
            kinds = {
                key
                for key in ("doc_ids", "tool", "answer_contains")
                if expected.get(key)
            }
            if not kinds:
                raise ValueError(f"{cid}: expected 至少包含一种期望")
            if case["type"] == "retrieval" and "doc_ids" not in kinds:
                raise ValueError(f"{cid}: retrieval 类型必须有 doc_ids")
            if case["type"] == "tool" and "tool" not in kinds:
                raise ValueError(f"{cid}: tool 类型必须有 tool")
            if case["type"] == "answer" and "answer_contains" not in kinds:
                raise ValueError(f"{cid}: answer 类型必须有 answer_contains")
            if case["type"] == "mixed" and len(kinds) < 2:
                raise ValueError(f"{cid}: mixed 类型至少两种期望")
            doc_ids = expected.get("doc_ids")
            if doc_ids is not None and (
                not isinstance(doc_ids, list)
                or not doc_ids
                or not all(isinstance(d, str) and d for d in doc_ids)
            ):
                raise ValueError(f"{cid}: doc_ids 必须为非空字符串数组")
            unknown = [d for d in (doc_ids or []) if d not in corpus_ids]
            if unknown:
                raise ValueError(f"{cid}: doc_ids 引用不存在的文档 {unknown}")
            answer_contains = expected.get("answer_contains")
            if answer_contains is not None and (
                not isinstance(answer_contains, list)
                or not answer_contains
                or not all(isinstance(k, str) and k for k in answer_contains)
            ):
                raise ValueError(f"{cid}: answer_contains 必须为非空字符串数组")
            if expected.get("match") not in (None, "all", "any"):
                raise ValueError(f"{cid}: match 只能是 all/any")
            tool = expected.get("tool")
            if tool is not None:
                if not isinstance(tool, dict) or not tool.get("name"):
                    raise ValueError(f"{cid}: tool 必须包含 name")
                args_contains = tool.get("args_contains")
                if args_contains is not None and not isinstance(args_contains, dict):
                    raise ValueError(f"{cid}: args_contains 必须为对象")

    def cases_with(self, *keys: str) -> list[dict[str, Any]]:
        out = []
        for case in self.cases:
            expected = case["expected"]
            if all(expected.get(key) for key in keys):
                out.append(case)
        return out

    @property
    def type_counts(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for case in self.cases:
            counts[case["type"]] = counts.get(case["type"], 0) + 1
        return counts


def _match_tool(prediction: Mapping[str, Any], expected_tool: Mapping[str, Any]) -> bool:
    calls = prediction.get("tool_calls") or []
    if isinstance(calls, Mapping):
        calls = [calls]
    for call in calls:
        if not isinstance(call, Mapping) or call.get("name") != expected_tool.get("name"):
            continue
        args = call.get("args") or {}
        if not isinstance(args, Mapping):
            continue
        ok = True
        for key, want in (expected_tool.get("args_contains") or {}).items():
            got = args.get(key)
            if got is None or str(want) not in str(got):
                ok = False
                break
        if ok:
            return True
    return False


def _match_answer(answer: str, expected: Mapping[str, Any]) -> bool:
    keywords: Sequence[str] = expected.get("answer_contains") or []
    mode = expected.get("match") or "all"
    hits = [kw for kw in keywords if kw in answer]
    if not hits:
        return False
    return len(hits) == len(keywords) if mode == "all" else True


def build_default_retriever(golden: GoldenSet) -> HybridRetriever:
    """离线默认管线：HashEmbedder + BM25 + RRF + 词法重排。"""
    chunker = TextChunker(chunk_size=256, overlap=32)
    embedder = HashEmbedder(dim=128)
    vector_store = VectorStore()
    keyword_index = KeywordIndex()
    indexer = Indexer(chunker, embedder, vector_store, keyword_index, batch_size=16)
    report = indexer.index(
        [
            {
                "doc_id": doc["doc_id"],
                "text": doc["text"],
                "metadata": {"title": doc.get("title", "")},
            }
            for doc in golden.corpus
        ]
    )
    if report.failed:
        raise RuntimeError(f"golden 语料索引失败: {report.errors}")
    return HybridRetriever(
        vector_store,
        keyword_index,
        embedder=embedder,
        reranker=LexicalReranker(),
        config=RetrievalConfig(top_k=5, candidate_k=20),
    )


class GoldenRunner:
    """评测执行器：同一套指标口径，检索/工具/回答三类分别出报告。"""

    def __init__(
        self,
        golden: GoldenSet | None = None,
        retriever: HybridRetriever | None = None,
        agent_fn: AgentFn | None = None,
    ) -> None:
        self.golden = golden or GoldenSet()
        self.retriever = retriever or build_default_retriever(self.golden)
        self.agent_fn = agent_fn

    def evaluate_retrieval(self, top_k: int = 5) -> EvalReport:
        report = EvalReport(name="retrieval_hit_rate")
        rank_sum = 0.0
        rank_count = 0
        for case in self.golden.cases_with("doc_ids"):
            report.total += 1
            expected = set(case["expected"]["doc_ids"])
            result = self.retriever.retrieve(case["query"], top_k=top_k)
            ranked = result.doc_ids
            hit_rank = next(
                (i + 1 for i, doc_id in enumerate(ranked) if doc_id in expected), None
            )
            if hit_rank is None:
                report.failures.append(
                    {"id": case["id"], "query": case["query"], "ranked": ranked}
                )
                continue
            report.passed += 1
            rank_sum += 1.0 / hit_rank
            rank_count += 1
        report.mrr = (rank_sum / rank_count) if rank_count else None
        return report.finalize()

    def evaluate_tools(self) -> EvalReport:
        report = EvalReport(name="tool_call_accuracy")
        if self.agent_fn is None:
            report.skipped = len(self.golden.cases_with("tool"))
            report.total = report.skipped
            return report.finalize()
        for case in self.golden.cases_with("tool"):
            report.total += 1
            prediction = self.agent_fn(case["query"])
            expected_tool = case["expected"]["tool"]
            if _match_tool(prediction, expected_tool):
                report.passed += 1
            else:
                report.failures.append(
                    {
                        "id": case["id"],
                        "query": case["query"],
                        "expected": expected_tool,
                        "got": prediction.get("tool_calls"),
                    }
                )
        return report.finalize()

    def evaluate_answers(self) -> EvalReport:
        report = EvalReport(name="answer_correctness")
        if self.agent_fn is None:
            report.skipped = len(self.golden.cases_with("answer_contains"))
            report.total = report.skipped
            return report.finalize()
        for case in self.golden.cases_with("answer_contains"):
            report.total += 1
            prediction = self.agent_fn(case["query"])
            answer = str(prediction.get("answer") or "")
            if _match_answer(answer, case["expected"]):
                report.passed += 1
            else:
                report.failures.append(
                    {
                        "id": case["id"],
                        "query": case["query"],
                        "expected": case["expected"].get("answer_contains"),
                        "got": answer,
                    }
                )
        return report.finalize()

    def run_all(self) -> dict[str, Any]:
        return {
            "retrieval": self.evaluate_retrieval().to_dict(),
            "tool": self.evaluate_tools().to_dict(),
            "answer": self.evaluate_answers().to_dict(),
        }


def make_echo_agent(golden: GoldenSet) -> AgentFn:
    """校准用桩：输出等于期望，用于验证评测口径（满分=口径正确）。"""
    by_query = {case["query"]: case["expected"] for case in golden.cases}

    def agent(query: str) -> Mapping[str, Any]:
        expected = by_query[query]
        tool = expected.get("tool")
        calls = []
        if tool:
            calls.append(
                {"name": tool["name"], "args": dict(tool.get("args_contains") or {})}
            )
        return {
            "tool_calls": calls,
            "answer": " ".join(expected.get("answer_contains") or []),
        }

    return agent


def make_null_agent() -> AgentFn:
    """反向桩：全错，用于验证评测能拉开差距。"""

    def agent(query: str) -> Mapping[str, Any]:
        return {"tool_calls": [{"name": "noop", "args": {}}], "answer": "抱歉，我不知道。"}

    return agent


# ---------------------------------------------------------------------------
# 用例结构
# ---------------------------------------------------------------------------


def test_cases_schema() -> None:
    golden = GoldenSet()
    assert 30 <= len(golden.cases) <= 50
    counts = golden.type_counts
    assert {"retrieval", "tool", "answer"} <= set(counts)
    assert sum(counts.values()) == len(golden.cases)
    assert len({case["id"] for case in golden.cases}) == len(golden.cases)
    assert GoldenSet(CASES_PATH).version


# ---------------------------------------------------------------------------
# 检索命中率（真实 RAG 管线）
# ---------------------------------------------------------------------------


def test_retrieval_hit_rate() -> None:
    report = GoldenRunner().evaluate_retrieval()
    assert report.total >= 15
    assert report.rate is not None and report.rate >= 0.85, report.to_dict()
    assert report.mrr is not None and report.mrr >= 0.7, report.to_dict()


def test_retrieval_ranking_quality() -> None:
    golden = GoldenSet()
    runner = GoldenRunner(golden)
    top1 = 0
    for case in golden.cases_with("doc_ids"):
        ranked = runner.retriever.retrieve_docs(case["query"], top_k=5)
        if ranked and ranked[0] in set(case["expected"]["doc_ids"]):
            top1 += 1
    assert top1 / len(golden.cases_with("doc_ids")) >= 0.7


# ---------------------------------------------------------------------------
# 工具 / 回答评测框架校准
# ---------------------------------------------------------------------------


def test_tool_eval_framework() -> None:
    golden = GoldenSet()
    perfect = GoldenRunner(golden, agent_fn=make_echo_agent(golden)).evaluate_tools()
    assert perfect.rate == 1.0 and perfect.total >= 8, perfect.to_dict()
    wrong = GoldenRunner(golden, agent_fn=make_null_agent()).evaluate_tools()
    assert wrong.rate == 0.0 and len(wrong.failures) == wrong.total, wrong.to_dict()


def test_answer_eval_framework() -> None:
    golden = GoldenSet()
    perfect = GoldenRunner(golden, agent_fn=make_echo_agent(golden)).evaluate_answers()
    assert perfect.rate == 1.0 and perfect.total >= 12, perfect.to_dict()
    wrong = GoldenRunner(golden, agent_fn=make_null_agent()).evaluate_answers()
    assert wrong.rate == 0.0 and len(wrong.failures) == wrong.total, wrong.to_dict()


def test_run_all_with_agent() -> None:
    golden = GoldenSet()
    runner = GoldenRunner(golden, agent_fn=make_echo_agent(golden))
    results = runner.run_all()
    assert results["retrieval"]["rate"] is not None
    assert results["retrieval"]["rate"] >= 0.85
    assert results["tool"]["rate"] == 1.0
    assert results["answer"]["rate"] == 1.0


def test_run_all_without_agent_skips_tools() -> None:
    results = GoldenRunner().run_all()
    assert results["tool"]["rate"] is None
    assert results["tool"]["skipped"] >= 8
    assert results["answer"]["rate"] is None


# ---------------------------------------------------------------------------
# RAG 降级链路
# ---------------------------------------------------------------------------


class _BrokenEmbedder:
    dim = 8

    def embed_documents(self, texts):  # noqa: ANN001, ANN202
        raise RuntimeError("embedding service down")

    def embed_query(self, text):  # noqa: ANN001, ANN202
        raise RuntimeError("embedding service down")


class _BrokenSearch:
    def search(self, *args, **kwargs):  # noqa: ANN002, ANN003, ANN202
        raise RuntimeError("store down")

    def add(self, *args, **kwargs):  # noqa: ANN002, ANN003, ANN202
        raise RuntimeError("store down")

    def delete_doc(self, *args, **kwargs):  # noqa: ANN002, ANN003, ANN202
        return 0


class _BrokenReranker:
    def rerank(self, query, candidates, top_k):  # noqa: ANN001, ANN202
        raise RuntimeError("rerank service down")


def test_degrade_to_keyword_when_embed_fails() -> None:
    golden = GoldenSet()
    base = build_default_retriever(golden)
    retriever = HybridRetriever(
        base.vector_store,
        base.keyword_index,
        embedder=_BrokenEmbedder(),
        reranker=LexicalReranker(),
    )
    result = retriever.retrieve("退货怎么退，运费谁出", top_k=3)
    assert result.degraded is True
    assert result.stages["keyword"] is True
    assert result.stages["vector"] is False
    assert result.items, "关键词降级路径必须仍能返回结果"
    assert "faq-return" in result.doc_ids
    assert any("embed" in err for err in result.errors)


def test_empty_result_when_all_paths_fail() -> None:
    golden = GoldenSet()
    base = build_default_retriever(golden)
    retriever = HybridRetriever(
        _BrokenSearch(),
        _BrokenSearch(),
        embedder=HashEmbedder(dim=8),
    )
    result = retriever.retrieve("任意查询", top_k=3)
    assert result.items == []
    assert result.degraded is True
    assert len(result.errors) >= 2
    assert result.stages["vector"] is False
    assert result.stages["keyword"] is False


def test_rerank_failure_keeps_fused_order() -> None:
    golden = GoldenSet()
    base = build_default_retriever(golden)
    query = "会员积分怎么抵现"
    baseline = HybridRetriever(
        base.vector_store,
        base.keyword_index,
        embedder=base.embedder,
        config=RetrievalConfig(top_k=5, use_rerank=False),
    ).retrieve(query, top_k=5)
    degraded = HybridRetriever(
        base.vector_store,
        base.keyword_index,
        embedder=base.embedder,
        reranker=_BrokenReranker(),
        config=RetrievalConfig(top_k=5),
    ).retrieve(query, top_k=5)
    assert degraded.degraded is True
    assert degraded.stages["rerank"] is False
    assert [i.chunk.chunk_id for i in degraded.items] == [
        i.chunk.chunk_id for i in baseline.items
    ]


def test_indexer_degrades_when_embedding_fails() -> None:
    keyword_index = KeywordIndex()
    indexer = Indexer(
        TextChunker(chunk_size=128, overlap=16),
        _BrokenEmbedder(),
        VectorStore(),
        keyword_index,
    )
    report = indexer.index([{"doc_id": "d1", "text": "支持 7 天无理由退货，退货运费说明。"}])
    assert report.degraded is True
    assert report.vectors_added == 0
    assert report.keyword_added > 0
    assert any("embed" in err for err in report.errors)
    hits = keyword_index.search("无理由退货", k=3)
    assert hits and hits[0].chunk.doc_id == "d1"


def test_indexer_skips_unchanged_docs() -> None:
    keyword_index = KeywordIndex()
    indexer = Indexer(
        TextChunker(chunk_size=128, overlap=16),
        HashEmbedder(dim=32),
        VectorStore(),
        keyword_index,
    )
    doc = {"doc_id": "d1", "text": "订单满 99 元包邮。"}
    first = indexer.index([doc])
    second = indexer.index([doc])
    assert first.skipped == 0 and first.keyword_added > 0
    assert second.skipped == 1 and second.keyword_added == 0


def test_rrf_fusion_promotes_consensus() -> None:
    golden = GoldenSet()
    base = build_default_retriever(golden)
    query = "降噪耳机的续航是多久"
    fused = rrf_fuse(
        [
            base.vector_store.search(base.embedder.embed_query(query), k=10),
            base.keyword_index.search(query, k=10),
        ]
    )
    assert "product-earbuds" in {i.chunk.doc_id for i in fused[:3]}

    # 合成排名：双路共识文档必须压过仅单路命中的文档
    chunks = [
        Chunk(chunk_id=f"c{i}", doc_id=f"d{i}", text=f"t{i}", ordinal=i) for i in range(3)
    ]
    rank_vector = [ScoredChunk(chunks[0], 0.9, "vector"), ScoredChunk(chunks[1], 0.8, "vector")]
    rank_keyword = [ScoredChunk(chunks[1], 0.9, "keyword"), ScoredChunk(chunks[2], 0.8, "keyword")]
    consensus = rrf_fuse([rank_vector, rank_keyword])
    assert consensus[0].chunk.chunk_id == "c1"
    assert all(isinstance(item, ScoredChunk) for item in consensus)


# ---------------------------------------------------------------------------
# 观测指标自检
# ---------------------------------------------------------------------------


def test_metrics_qps_percentiles_tool_success() -> None:
    registry = MetricsRegistry()
    for i in range(100):
        registry.observe_http("GET", "/api/shoppingguide/faq", 200, 0.01 + i * 0.001)
    registry.observe_http("POST", "/api/shoppingguide/sessions", 500, 1.2)
    registry.observe_tool("search_products", True, 0.05)
    registry.observe_tool("search_products", True, 0.08)
    registry.observe_tool("add_cart", False, 0.2)
    registry.observe_llm("deepseek-chat", 1200, 300, cost_usd=0.001)

    assert registry.qps(60.0) > 0
    percentiles = registry.percentiles()
    assert None not in percentiles.values()
    p50, p95, p99 = percentiles["p50"], percentiles["p95"], percentiles["p99"]
    assert p50 is not None and p95 is not None and p99 is not None
    assert p50 <= p95 <= p99
    assert registry.tool_success_rate() == 2 / 3
    assert registry.tool_success_rate("search_products") == 1.0
    assert registry.token_usage()["total_tokens"] == 1500

    text = registry.render_prometheus()
    for metric_name in (
        "http_requests_total",
        "http_requests_per_second",
        "http_request_duration_seconds_bucket",
        "tool_calls_total",
        "tool_success_rate",
        "llm_tokens_total",
        "llm_cost_usd_total",
    ):
        assert metric_name in text, metric_name
    snapshot = registry.snapshot()
    assert snapshot["http_requests_total"] == 101
    assert snapshot["latency"]["p50"] is not None


def test_langfuse_token_cost_recording() -> None:
    registry = MetricsRegistry()
    config = LangfuseConfig(enabled=False, metrics=registry)
    tracer = NoOpTracer(config)
    assert tracer.degraded is True
    with tracer.trace("shopping_guide", session_id="s1") as span:
        with tracer.generation(
            span,
            "deepseek-chat",
            model="deepseek-chat",
            usage=Usage(prompt_tokens=1000, completion_tokens=1000),
        ) as gen:
            assert gen.cost_usd > 0
    usage = registry.token_usage()
    assert usage["prompt_tokens"] == 1000
    assert usage["completion_tokens"] == 1000
    assert usage["cost_usd"] > 0
    expected = estimate_cost("deepseek-chat", 1000, 1000)
    assert abs(usage["cost_usd"] - expected) < 1e-12


def test_estimate_cost_unknown_model_is_zero() -> None:
    assert estimate_cost("no-such-model", 10, 10) == 0.0
    assert estimate_cost("deepseek-chat", 0, 0) == 0.0


# ---------------------------------------------------------------------------
# 无 pytest 环境下的自跑入口
# ---------------------------------------------------------------------------


def main() -> int:
    failures = 0
    fns = [
        (name, fn)
        for name, fn in sorted(globals().items())
        if name.startswith("test_") and callable(fn)
    ]
    for name, fn in fns:
        try:
            fn()
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL {name}: {type(exc).__name__}: {exc}")
        else:
            print(f"PASS {name}")
    golden = GoldenSet()
    runner = GoldenRunner(golden, agent_fn=make_echo_agent(golden))
    print(json.dumps(runner.run_all(), ensure_ascii=False, indent=2))
    print(f"{len(fns) - failures}/{len(fns)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
