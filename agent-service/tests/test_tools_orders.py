"""S2 订单工具：三工具调用、意图路由、缺单号追问、启发式兜底。"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from app.agent.nodes import (  # noqa: E402
    INTENT_TOOL_MAP,
    KNOWN_INTENTS,
    _build_tool_args,
    _heuristic_intent,
    make_nodes,
)
from app.agent.state import AgentDeps, ToolResult  # noqa: E402
from app.tools.base import ToolContext
from app.tools.cancel_order import CancelOrderTool  # noqa: E402
from app.tools.query_orders import QueryOrdersTool  # noqa: E402
from app.tools.track_order import TrackOrderTool  # noqa: E402


class _FakeClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def list_orders(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(("list_orders", kwargs))
        return {"items": [{"orderId": "o_1", "status": "PAID", "totalCent": 804}]}

    async def get_order(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(("get_order", kwargs))
        return {"orderId": kwargs.get("order_id"), "status": "PAID", "items": []}

    async def cancel_order(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(("cancel_order", kwargs))
        return {"orderId": kwargs.get("order_id"), "status": "CANCELED"}


class _FakeLLM:
    def __init__(self, payload: str) -> None:
        self.payload = payload

    def chat(self, messages, *, timeout=None):
        return self.payload


# ---------------------------------------------------------------------------
# 工具层
# ---------------------------------------------------------------------------


class TestOrderTools:
    async def test_query_orders_passes_token_and_status(self):
        client = _FakeClient()
        tool = QueryOrdersTool(client)  # type: ignore[arg-type]
        result = await tool.execute(ToolContext(user_token="tok"), status="PAID")
        assert result.ok
        assert client.calls[0][0] == "list_orders"
        assert client.calls[0][1]["user_token"] == "tok"
        assert client.calls[0][1]["status"] == "PAID"

    async def test_track_order_passes_order_id(self):
        client = _FakeClient()
        tool = TrackOrderTool(client)  # type: ignore[arg-type]
        result = await tool.execute(ToolContext(user_token="tok"), order_id="20260526001")
        assert result.ok
        assert client.calls[0] == (
            "get_order",
            {"order_id": "20260526001", "user_token": "tok", "request_id": ""},
        )

    async def test_cancel_order_requires_token(self):
        tool = CancelOrderTool(_FakeClient())  # type: ignore[arg-type]
        result = await tool.execute(ToolContext(), order_id="o_1")
        assert not result.ok
        assert "登录态" in (result.error or "")

    async def test_cancel_order_calls_cancel(self):
        client = _FakeClient()
        tool = CancelOrderTool(client)  # type: ignore[arg-type]
        result = await tool.execute(ToolContext(user_token="tok"), order_id="o_9")
        assert result.ok
        assert client.calls[0][0] == "cancel_order"
        assert client.calls[0][1]["order_id"] == "o_9"


# ---------------------------------------------------------------------------
# 路由与意图
# ---------------------------------------------------------------------------


class TestOrderRouting:
    def test_intents_registered(self):
        assert {"query_orders", "track_order", "cancel_order"} <= KNOWN_INTENTS
        assert INTENT_TOOL_MAP["query_orders"] == "query_orders"
        assert INTENT_TOOL_MAP["track_order"] == "track_order"
        assert INTENT_TOOL_MAP["cancel_order"] == "cancel_order"

    def test_compare_intent_maps_to_real_tool_name(self):
        """存量 bug 回归：compare 工具名是 compare_products。"""
        assert INTENT_TOOL_MAP["compare_product"] == "compare_products"

    def test_tool_args_order_branches(self):
        assert _build_tool_args("query_orders", {"entities": {"orderStatus": "paid"}}) == {
            "status": "PAID"
        }
        assert _build_tool_args("track_order", {"entities": {"orderId": "o_1"}}) == {
            "order_id": "o_1"
        }
        assert _build_tool_args("cancel_order", {"entities": {"orderId": "o_2"}}) == {
            "order_id": "o_2"
        }

    def test_heuristic_cancel_with_id(self):
        parsed = _heuristic_intent("帮我取消订单 20260526001")
        assert parsed["name"] == "cancel_order"
        assert parsed["entities"]["orderId"] == "20260526001"
        assert parsed["needs_clarify"] is False

    def test_heuristic_cancel_without_id_clarifies(self):
        parsed = _heuristic_intent("取消订单")
        assert parsed["name"] == "cancel_order"
        assert parsed["needs_clarify"] is True
        assert parsed["missing_slots"] == ["orderId"]

    def test_heuristic_logistics_goes_track_not_faq(self):
        assert _heuristic_intent("我的物流到哪了")["name"] == "track_order"
        assert _heuristic_intent("订单详情")["name"] == "track_order"

    def test_heuristic_query_orders(self):
        assert _heuristic_intent("看看我有哪些订单")["name"] == "query_orders"


# ---------------------------------------------------------------------------
# 意图节点硬规则
# ---------------------------------------------------------------------------


class _NoopTools:
    def available(self):
        return []

    def invoke(self, name, arguments, *, timeout=None):
        return ToolResult(ok=False, error="noop")


def _intent(payload: str, state: dict) -> dict:
    nodes = make_nodes(AgentDeps(llm=_FakeLLM(payload), tools=_NoopTools()))  # type: ignore[arg-type]
    return nodes["intent"](state)


class TestIntentNodeOrderRules:
    def test_cancel_without_order_id_forces_clarify(self):
        """即使 LLM 忘了标 clarify，缺单号也必须追问（写操作安全）。"""
        out = _intent(
            '{"name": "cancel_order", "confidence": 0.9, "entities": {},'
            ' "needs_clarify": false, "missing_slots": []}',
            {"user_profile": {}, "messages": [{"role": "user", "content": "取消订单"}]},
        )
        assert out["needs_clarify"] is True
        assert "orderId" in out["missing_slots"]

    def test_cancel_with_order_id_no_clarify(self):
        out = _intent(
            '{"name": "cancel_order", "confidence": 0.9, "entities": {"orderId": "20260526001"},'
            ' "needs_clarify": false, "missing_slots": []}',
            {"user_profile": {}, "messages": [{"role": "user", "content": "取消 20260526001"}]},
        )
        assert out["needs_clarify"] is False

    def test_query_orders_never_clarifies(self):
        out = _intent(
            '{"name": "query_orders", "confidence": 0.9, "entities": {},'
            ' "needs_clarify": false, "missing_slots": []}',
            {"user_profile": {}, "messages": [{"role": "user", "content": "我的订单"}]},
        )
        assert out["needs_clarify"] is False
