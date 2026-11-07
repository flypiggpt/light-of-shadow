"""真实链路 agent_fn：意图(LLM) → 工具路由 → 检索 + 回复(LLM)。

与线上同一条代码路径（make_nodes 的 intent / tool_route / respond 节点），
唯一差别是采样温度固定 0 保证评测可复现，且工具执行换成离线检索语料
（评测不能依赖真实 Java 服务）。
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, Mapping, Sequence

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[2]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from app.agent.nodes import INTENT_TOOL_MAP, make_nodes  # noqa: E402
from app.agent.service import LLMAdapter  # noqa: E402
from app.agent.state import AgentDeps, ToolResult  # noqa: E402

__all__ = ["make_real_agent", "EvalToolStub"]


class EvalToolStub:
    """评测工具桩：不触网；意图/路由节点只用它做依赖占位。"""

    def available(self) -> Sequence[str]:
        return list(INTENT_TOOL_MAP.values())

    def invoke(self, name: str, arguments: Mapping[str, Any], *, timeout: float | None = None) -> ToolResult:
        return ToolResult(ok=False, error="eval_stub")


def make_real_agent(
    *,
    base_url: str,
    api_key: str,
    model: str,
    retriever: Any,
    timeout_s: float = 30.0,
):
    """构造评测 agent_fn：返回 {"tool_calls": [...], "answer": str}。

    - tool_calls：真意图节点 + 真 tool_route 的决策（工具不执行）；
    - answer：真检索 top 文档作为工具数据，走真 respond 节点生成。
    """
    llm = LLMAdapter(base_url, api_key, model, temperature=0.0)
    deps = AgentDeps(llm=llm, tools=EvalToolStub(), llm_timeout=timeout_s)
    nodes = make_nodes(deps)

    def agent(query: str) -> Mapping[str, Any]:
        state: dict[str, Any] = {
            "messages": [{"role": "user", "content": query}],
            "user_profile": {},
        }
        intent_out = nodes["intent"](state)
        merged = {**state, **intent_out}

        tool_calls: list[dict[str, Any]] = []
        intent_name = (merged.get("intent") or {}).get("name", "")
        # 评测口径：意图命中工具即路由（needs_clarify 是“先追问再调”的行为轴，不影响选具）
        if intent_name in INTENT_TOOL_MAP:
            try:
                route_out = nodes["tool_route"](merged)
                tool_calls = list(route_out.get("tool_calls") or [])
            except Exception:  # noqa: BLE001 —— 路由失败按无工具评分
                tool_calls = []

        docs = retriever.retrieve(query, top_k=3)
        doc_payload = [
            {
                "doc_id": item.chunk.doc_id,
                "title": (item.chunk.metadata or {}).get("title", ""),
                "text": item.chunk.text,
            }
            for item in docs.items
        ]
        respond_out = nodes["respond"](
            {
                **merged,
                "tool_results": [{"name": "faq", "ok": True, "data": {"documents": doc_payload}}],
            }
        )
        return {"tool_calls": tool_calls, "answer": respond_out.get("reply") or ""}

    return agent
