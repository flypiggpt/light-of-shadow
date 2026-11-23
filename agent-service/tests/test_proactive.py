"""主动提议（proactive）测试：模板渲染 / interrupt 挂起恢复 / 幂等 / 丢弃 / HTTP 层。"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from langgraph.checkpoint.memory import InMemorySaver  # noqa: E402

from app.agent.graph import (  # noqa: E402
    AgentDeps,
    build_agent_graph,
    invoke_confirm,
    invoke_prepare,
)
from app.agent.proactive import build_actions, render_proposal  # noqa: E402
from app.agent.state import ToolResult  # noqa: E402


class _RecordingTools:
    """记录 invoke 参数，验证 confirm 恢复后工具收到预填参数。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any], Any]] = []
        self.ok = True

    def available(self):
        return ["add_cart", "cancel_order"]

    def invoke(self, name, arguments, *, timeout=None, user_token=None):
        self.calls.append((name, dict(arguments), user_token))
        return ToolResult(ok=self.ok, data={"echo": arguments})


class _NoLLM:
    """主动链路不走 LLM；若被调用即报错，保证模板路径纯净。"""

    def chat(self, messages, *, timeout=None):
        raise AssertionError("主动提议不应调用 LLM")


def _deps(tools: _RecordingTools | None = None) -> AgentDeps:
    return AgentDeps(llm=_NoLLM(), tools=tools or _RecordingTools())


def _facts_price_drop() -> dict[str, Any]:
    return {
        "productId": "p_milk",
        "productName": "500ml 装牛奶",
        "oldPriceCent": 2900,
        "newPriceCent": 2400,
        "days": 20,
    }


def _facts_unpaid() -> dict[str, Any]:
    return {
        "orderId": "o_abc123def456",
        "orderNo": "no_1",
        "productName": "500ml 装牛奶",
        "amountCent": 2900,
        "remainingMinutes": 25,
    }


def _facts_dup(trigger: str = "DUPLICATE") -> dict[str, Any]:
    return {
        "orderId": "o_new12345678",
        "productId": "p_milk",
        "productName": "500ml 装牛奶",
        "quantity": 8,
        "trigger": trigger,
    }


def _facts_cart(stock: int = 3) -> dict[str, Any]:
    return {
        "cartItemId": "12",
        "productId": "p_milk",
        "productName": "500ml 装牛奶",
        "days": 4,
        "stock": stock,
    }


# ---------------------------------------------------------------------------
# 模板渲染
# ---------------------------------------------------------------------------


class TestRenderProposal:
    def test_unpaid_槽位齐全(self):
        text = render_proposal("UNPAID_ORDER", _facts_unpaid())
        assert "500ml 装牛奶" in text
        assert "29 元" in text
        assert "25 分钟" in text

    def test_dup_duplicate_文案(self):
        text = render_proposal("DUP_ORDER", _facts_dup("DUPLICATE"))
        assert "两单" in text
        assert "500ml 装牛奶" in text

    def test_dup_quantity_文案(self):
        text = render_proposal("DUP_ORDER", _facts_dup("QUANTITY"))
        assert "× 8" in text
        assert "500ml 装牛奶" in text

    def test_cart_idle_文案(self):
        text = render_proposal("CART_IDLE", _facts_cart())
        assert "放了 4 天" in text
        assert "500ml 装牛奶" in text

    def test_cart_idle_没货文案(self):
        text = render_proposal("CART_IDLE", _facts_cart(stock=0))
        assert "没货" in text

    def test_price_drop_槽位齐全(self):
        text = render_proposal("PRICE_DROP", _facts_price_drop())
        assert "20 天前" in text
        assert "29 元" in text
        assert "24 元" in text
        assert "500ml 装牛奶" in text

    def test_未知类型_兜底(self):
        text = render_proposal("WHATEVER", {})
        assert text  # 不空、不抛

    def test_文案长度受控(self):
        for signal, facts in [
            ("UNPAID_ORDER", _facts_unpaid()),
            ("DUP_ORDER", _facts_dup()),
            ("CART_IDLE", _facts_cart()),
            ("PRICE_DROP", _facts_price_drop()),
        ]:
            assert len(render_proposal(signal, facts)) <= 80

    def test_恶意商品名_被消毒截断(self):
        facts = _facts_price_drop()
        facts["productName"] = "A" * 500
        text = render_proposal("PRICE_DROP", facts)
        assert len(text) <= 80


class TestBuildActions:
    def test_unpaid_两个动作(self):
        actions = build_actions("UNPAID_ORDER", _facts_unpaid())
        ids = {a["actionId"] for a in actions}
        assert ids == {"pay", "cancel_order"}

    def test_price_drop_add_cart(self):
        actions = build_actions("PRICE_DROP", _facts_price_drop())
        assert actions[0]["actionId"] == "add_cart"
        assert actions[0]["kind"] == "tool"

    def test_cart_idle_客户端动作(self):
        actions = build_actions("CART_IDLE", _facts_cart())
        kinds = {a["kind"] for a in actions}
        assert kinds == {"client"}


# ---------------------------------------------------------------------------
# interrupt 挂起 / 恢复
# ---------------------------------------------------------------------------


class TestInterruptFlow:
    def test_prepare_挂起返回提议(self):
        tools = _RecordingTools()
        graph = build_agent_graph(_deps(tools), checkpointer=InMemorySaver())
        result = invoke_prepare(graph, signal_id="pe_1", signal_type="PRICE_DROP",
                                facts=_facts_price_drop(), user_id="u1")
        assert "500ml 装牛奶" in result["message"]
        assert result["threadId"] == "pe_1"
        assert {a["actionId"] for a in result["actions"]} == {"add_cart", "no"}
        # 挂起态未执行工具
        assert tools.calls == []

    def test_confirm_工具收到预填参数(self):
        tools = _RecordingTools()
        graph = build_agent_graph(_deps(tools), checkpointer=InMemorySaver())
        invoke_prepare(graph, signal_id="pe_2", signal_type="PRICE_DROP",
                       facts=_facts_price_drop(), user_id="u1")
        result = invoke_confirm(graph, signal_id="pe_2", action_id="add_cart",
                                user_id="u1", user_token="tok")
        assert tools.calls == [("add_cart", {"product_id": "p_milk", "quantity": 1}, "tok")]
        assert result["executed"] is True
        assert "加入购物车" in result["reply"]

    def test_confirm_cancel_order_参数(self):
        tools = _RecordingTools()
        graph = build_agent_graph(_deps(tools), checkpointer=InMemorySaver())
        invoke_prepare(graph, signal_id="pe_3", signal_type="DUP_ORDER",
                       facts=_facts_dup(), user_id="u1")
        result = invoke_confirm(graph, signal_id="pe_3", action_id="cancel_order",
                                user_id="u1", user_token="tok")
        assert tools.calls == [("cancel_order", {"order_id": "o_new12345678"}, "tok")]
        assert result["executed"] is True

    def test_模拟重启_同一checkpointer恢复(self):
        tools = _RecordingTools()
        saver = InMemorySaver()
        graph1 = build_agent_graph(_deps(tools), checkpointer=saver)
        invoke_prepare(graph1, signal_id="pe_4", signal_type="PRICE_DROP",
                       facts=_facts_price_drop(), user_id="u1")
        # 重建图模拟服务重启，共用 checkpointer（挂起态还在）
        graph2 = build_agent_graph(_deps(tools), checkpointer=saver)
        result = invoke_confirm(graph2, signal_id="pe_4", action_id="add_cart",
                                user_id="u1", user_token="tok")
        assert result["executed"] is True
        assert len(tools.calls) == 1

    def test_客户端动作_只回执不执行(self):
        tools = _RecordingTools()
        graph = build_agent_graph(_deps(tools), checkpointer=InMemorySaver())
        invoke_prepare(graph, signal_id="pe_5", signal_type="UNPAID_ORDER",
                       facts=_facts_unpaid(), user_id="u1")
        result = invoke_confirm(graph, signal_id="pe_5", action_id="pay",
                                user_id="u1", user_token="tok")
        assert tools.calls == []
        assert result["executed"] is False
        assert "支付" in result["reply"]


# ---------------------------------------------------------------------------
# 幂等 / 丢弃 / 工具失败
# ---------------------------------------------------------------------------


class TestIdempotencyAndDiscard:
    def test_重复confirm_不重复执行工具(self):
        tools = _RecordingTools()
        graph = build_agent_graph(_deps(tools), checkpointer=InMemorySaver())
        invoke_prepare(graph, signal_id="pe_6", signal_type="PRICE_DROP",
                       facts=_facts_price_drop(), user_id="u1")
        first = invoke_confirm(graph, signal_id="pe_6", action_id="add_cart",
                               user_id="u1", user_token="tok")
        second = invoke_confirm(graph, signal_id="pe_6", action_id="add_cart",
                                user_id="u1", user_token="tok")
        assert len(tools.calls) == 1  # 工具只跑一次
        assert first["reply"] == second["reply"]
        assert first["executed"] == second["executed"]

    def test_重复prepare_幂等(self):
        tools = _RecordingTools()
        graph = build_agent_graph(_deps(tools), checkpointer=InMemorySaver())
        first = invoke_prepare(graph, signal_id="pe_7", signal_type="CART_IDLE",
                               facts=_facts_cart(), user_id="u1")
        second = invoke_prepare(graph, signal_id="pe_7", signal_type="CART_IDLE",
                                facts=_facts_cart(), user_id="u1")
        assert first["message"] == second["message"]

    def test_未prepare直接confirm_业务错(self):
        graph = build_agent_graph(_deps(), checkpointer=InMemorySaver())
        try:
            invoke_confirm(graph, signal_id="pe_missing", action_id="add_cart")
        except KeyError:
            pass
        else:
            raise AssertionError("应抛 KeyError")

    def test_工具失败_回执失败话术(self):
        tools = _RecordingTools()
        tools.ok = False
        graph = build_agent_graph(_deps(tools), checkpointer=InMemorySaver())
        invoke_prepare(graph, signal_id="pe_8", signal_type="PRICE_DROP",
                       facts=_facts_price_drop(), user_id="u1")
        result = invoke_confirm(graph, signal_id="pe_8", action_id="add_cart",
                                user_id="u1", user_token="tok")
        assert result["executed"] is False
        assert "没有成功" in result["reply"]


# ---------------------------------------------------------------------------
# HTTP 层
# ---------------------------------------------------------------------------


class _StubOrchestrator:
    def __init__(self) -> None:
        self.discarded: list[str] = []

    def prepare_signal(self, signal_id, signal_type, facts, user_id=""):
        return {"signalId": signal_id, "message": "要再买一件吗？",
                "actions": [{"actionId": "add_cart", "label": "再买一件"}],
                "threadId": signal_id}

    def confirm_signal(self, signal_id, action_id, user_id="", user_token=""):
        if signal_id.startswith("missing"):
            raise KeyError(signal_id)
        return {"signalId": signal_id, "actionId": action_id,
                "reply": "已把 500ml 装牛奶 × 1 加入购物车。", "executed": True, "result": {}}

    def discard_signal(self, signal_id):
        self.discarded.append(signal_id)
        return {"signalId": signal_id, "status": "DISCARDED"}


class TestHttpApi:
    def _client(self):
        from fastapi.testclient import TestClient

        from app.main import create_app

        app = create_app()
        app.state.orchestrator = _StubOrchestrator()
        return TestClient(app)

    def test_prepare_信封(self):
        resp = self._client().post("/internal/proactive/prepare", json={
            "signalId": "pe_h1", "type": "PRICE_DROP", "facts": {}, "userId": "u1",
        })
        assert resp.status_code == 200
        body = resp.json()
        assert body["code"] == 0
        assert body["data"]["message"]
        assert body["data"]["threadId"] == "pe_h1"

    def test_confirm_信封(self):
        resp = self._client().post("/internal/proactive/confirm", json={
            "signalId": "pe_h2", "actionId": "add_cart", "userId": "u1",
        })
        assert resp.status_code == 200
        body = resp.json()
        assert body["data"]["executed"] is True
        assert "加入购物车" in body["data"]["reply"]

    def test_confirm_不存在_404(self):
        resp = self._client().post("/internal/proactive/confirm", json={
            "signalId": "missing_x", "actionId": "add_cart",
        })
        assert resp.status_code == 404

    def test_discard_信封(self):
        client = self._client()
        resp = client.post("/internal/proactive/discard", json={"signalId": "pe_h3"})
        assert resp.status_code == 200
        assert resp.json()["data"]["status"] == "DISCARDED"

    def test_confirm_透传token(self):
        """confirm 必须把 Authorization 带给工具执行链。"""
        orch = _StubOrchestrator()
        seen: dict[str, Any] = {}

        def spy(signal_id, action_id, user_id="", user_token=""):
            seen["user_token"] = user_token
            return {"signalId": signal_id, "actionId": action_id,
                    "reply": "ok", "executed": True, "result": None}

        orch.confirm_signal = spy  # type: ignore[method-assign]
        from fastapi.testclient import TestClient

        from app.main import create_app

        app = create_app()
        app.state.orchestrator = orch
        client = TestClient(app)
        client.post("/internal/proactive/confirm",
                    json={"signalId": "pe_h4", "actionId": "add_cart"},
                    headers={"Authorization": "Bearer secret-token"})
        assert seen["user_token"] == "secret-token"
