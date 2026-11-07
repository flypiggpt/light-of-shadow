"""Golden 评测的 pytest 入口。

- 检索评测离线必跑（HashEmbedder，无外部依赖），直接对照基线；
- LLM 评测（真意图/路由/回答）需要 AGENT_LLM_API_KEY，无 key 自动跳过；
  本地 `python tests/golden/run_eval.py` 可出完整报告，CI 用 --require-llm 门禁。
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

GOLDEN_DIR = Path(__file__).resolve().parent
AGENT_SERVICE_ROOT = GOLDEN_DIR.parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from tests.golden.run_eval import DEFAULT_BASELINE, check_baseline  # noqa: E402
from tests.golden.test_golden_set import GoldenRunner, GoldenSet, build_default_retriever  # noqa: E402


def _baseline() -> dict[str, float]:
    path = GOLDEN_DIR / "baseline.json"
    baseline = dict(DEFAULT_BASELINE)
    if path.exists():
        baseline.update(json.loads(path.read_text(encoding="utf-8")))
    return baseline


def test_retrieval_eval_meets_baseline() -> None:
    """离线检索评测：MRR / 命中率不得低于基线。"""
    golden = GoldenSet()
    runner = GoldenRunner(golden, retriever=build_default_retriever(golden))
    report = runner.evaluate_retrieval()
    baseline = _baseline()
    data = report.to_dict()
    assert data["total"] > 0
    if data.get("mrr") is not None:
        assert data["mrr"] >= baseline["retrieval_mrr"], f"MRR {data['mrr']:.3f} 低于基线"
    assert data["rate"] is not None and data["rate"] >= baseline["retrieval_hit_rate"], (
        f"命中率 {data['rate']:.3f} 低于基线"
    )


def test_llm_eval_meets_baseline() -> None:
    """真路由评测（意图/工具/回答）：需要 LLM key，无 key 跳过。"""
    if not os.getenv("AGENT_LLM_API_KEY", "").strip():
        import pytest

        pytest.skip("未配置 AGENT_LLM_API_KEY，跳过 LLM 评测")
    from tests.golden.real_agent import make_real_agent

    golden = GoldenSet()
    retriever = build_default_retriever(golden)
    agent = make_real_agent(
        base_url=os.getenv("AGENT_LLM_BASE_URL", "https://api.deepseek.com/v1"),
        api_key=os.environ["AGENT_LLM_API_KEY"],
        model=os.getenv("AGENT_LLM_MODEL", "deepseek-chat"),
        retriever=retriever,
    )
    runner = GoldenRunner(golden, retriever=retriever, agent_fn=agent)
    results = runner.run_all()
    violations = check_baseline(results, _baseline())
    assert not violations, "低于基线: " + "; ".join(violations)
