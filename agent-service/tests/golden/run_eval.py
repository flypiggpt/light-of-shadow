"""Golden 评测入口：真路由打分 → 量化报告 → 基线门禁。

用法：
    python tests/golden/run_eval.py                  # 有 AGENT_LLM_API_KEY 跑全量，否则只跑检索
    python tests/golden/run_eval.py --require-llm    # 必须跑 LLM 评测（CI 门禁用）
    python tests/golden/run_eval.py --calibrate      # 用满分桩校准评测口径

环境变量（LLM 评测）：AGENT_LLM_API_KEY（必填）、AGENT_LLM_BASE_URL、AGENT_LLM_MODEL。

门禁：对比 tests/golden/baseline.json，低于阈值退出码 1。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

GOLDEN_DIR = Path(__file__).resolve().parent
AGENT_SERVICE_ROOT = GOLDEN_DIR.parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from tests.golden.test_golden_set import (  # noqa: E402
    GoldenRunner,
    GoldenSet,
    build_default_retriever,
    make_echo_agent,
)

DEFAULT_BASELINE = {
    "retrieval_mrr": 0.8,
    "retrieval_hit_rate": 0.9,
    "tool_call_accuracy": 0.7,
    "answer_correctness": 0.6,
}


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip() or default


def build_agent(golden: GoldenSet, retriever: Any, require_llm: bool, calibrate: bool):
    if calibrate:
        return make_echo_agent(golden), "echo(校准桩)"
    api_key = _env("AGENT_LLM_API_KEY")
    if not api_key:
        if require_llm:
            raise SystemExit("缺少 AGENT_LLM_API_KEY，无法运行 LLM 评测")
        return None, None
    from tests.golden.real_agent import make_real_agent

    agent = make_real_agent(
        base_url=_env("AGENT_LLM_BASE_URL", "https://api.deepseek.com/v1"),
        api_key=api_key,
        model=_env("AGENT_LLM_MODEL", "deepseek-chat"),
        retriever=retriever,
    )
    return agent, "real(意图+路由+检索回复, temp=0)"


def check_baseline(results: dict[str, Any], baseline: dict[str, float]) -> list[str]:
    """返回未达标的指标描述列表。只门禁本次真跑出的指标（skipped 不计）。"""
    violations: list[str] = []

    def gate(key: str, actual: float | None, skipped: int, total: int) -> None:
        threshold = baseline.get(key)
        if threshold is None or actual is None:
            return
        if skipped >= total and total > 0:
            return
        if actual < threshold:
            violations.append(f"{key}: {actual:.3f} < 基线 {threshold}")

    retrieval = results["retrieval"]
    gate("retrieval_mrr", retrieval.get("mrr"), retrieval.get("skipped", 0), retrieval["total"])
    gate(
        "retrieval_hit_rate",
        retrieval.get("rate"),
        retrieval.get("skipped", 0),
        retrieval["total"],
    )
    tool = results["tool"]
    gate("tool_call_accuracy", tool.get("rate"), tool.get("skipped", 0), tool["total"])
    answer = results["answer"]
    gate("answer_correctness", answer.get("rate"), answer.get("skipped", 0), answer["total"])
    return violations


def render_markdown(results: dict[str, Any], agent_label: str | None) -> str:
    lines = [
        "# Golden 评测报告",
        "",
        f"- agent: {agent_label or '无（仅检索）'}",
        "",
        "| 指标 | 通过/总数 | 通过率 | 补充 |",
        "|---|---|---|---|",
    ]
    for name, data in results.items():
        extra = ""
        if data.get("mrr") is not None:
            extra = f"MRR={data['mrr']:.3f}"
        if data.get("skipped"):
            extra = (extra + f" 跳过{data['skipped']}").strip()
        rate = data.get("rate")
        rate_s = f"{rate:.3f}" if rate is not None else "-"
        lines.append(f"| {name} | {data['passed']}/{data['total']} | {rate_s} | {extra} |")
    for name, data in results.items():
        if data.get("failures"):
            lines += ["", f"## {name} 失败样例（最多 5 条）"]
            for f in data["failures"][:5]:
                lines.append(f"- `{f.get('id')}` {f.get('query', '')[:40]} → 期望 {f.get('expected')} 实际 {f.get('got')}")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Golden 评测")
    parser.add_argument("--require-llm", action="store_true", help="缺少 LLM key 时直接失败")
    parser.add_argument("--calibrate", action="store_true", help="用满分桩校准评测口径")
    parser.add_argument("--baseline", type=Path, default=GOLDEN_DIR / "baseline.json")
    parser.add_argument("--report", type=Path, default=GOLDEN_DIR / "report.json")
    parser.add_argument("--markdown", type=Path, default=GOLDEN_DIR / "report.md")
    args = parser.parse_args()

    golden = GoldenSet()
    retriever = build_default_retriever(golden)
    agent, agent_label = build_agent(golden, retriever, args.require_llm, args.calibrate)
    runner = GoldenRunner(golden, retriever=retriever, agent_fn=agent)
    results = runner.run_all()

    baseline = dict(DEFAULT_BASELINE)
    if args.baseline.exists():
        baseline.update(json.loads(args.baseline.read_text(encoding="utf-8")))
    violations = [] if args.calibrate else check_baseline(results, baseline)

    report = {
        "agent": agent_label,
        "baseline": baseline,
        "results": results,
        "violations": violations,
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md = render_markdown(results, agent_label)
    args.markdown.write_text(md, encoding="utf-8")
    print(md)
    print(f"\n报告: {args.report} / {args.markdown}")
    if violations:
        print("\n未达基线:")
        for v in violations:
            print(f"  - {v}")
        return 1
    print("\n全部达标 ✅")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
