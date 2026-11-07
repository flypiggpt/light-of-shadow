"""审计修复的回归测试：token 请求级透传 / 画像新鲜注入 / checkpoint 跨轮 / RAG / 指标。"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from app.agent.graph import AgentDeps, build_agent_graph, invoke_turn  # noqa: E402
from app.agent.service import AgentOrchestrator, LLMAdapter, ToolExecutorAdapter  # noqa: E402
from app.agent.state import ToolResult  # noqa: E402
from app.memory.checkpoint import build_checkpointer  # noqa: E402
from app.memory.profile import ProfileStore  # noqa: E402
from app.obs.metrics import MetricsRegistry  # noqa: E402


class _RecordingTools:
    """记录 invoke 收到的 user_token，验证请求级透传。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, Any]] = []
        self.ctx_tokens: list[Any] = []

    def available(self):
        return ["search_products"]

    def invoke(self, name, arguments, *, timeout=None, user_token=None):
        self.calls.append((name, user_token))
        return ToolResult(ok=True, data={"products": []})

    async def execute(self, ctx, **kwargs):
        # 适配器路径：token 在 ToolContext 上
        self.ctx_tokens.append(ctx.user_token)
        return ToolResult(ok=True, data={"products": []})


class _ScriptedLLM:
    """意图/回复脚本化；记录每轮 prompt 里的历史消息。"""

    def __init__(self) -> None:
        self.seen: list[list[dict[str, str]]] = []

    def chat(self, messages, *, timeout=None):
        self.seen.append(list(messages))
        if "输出意图识别结果" in messages[0]["content"]:
            return '{"name": "chitchat", "confidence": 0.9, "entities": {}, "needs_clarify": false}'
        return "好的～"


# ---------------------------------------------------------------------------
# token 请求级透传
# ---------------------------------------------------------------------------


class TestTokenThreading:
    def test_node_passes_state_token_to_tools(self):
        tools = _RecordingTools()
        from app.agent.nodes import make_nodes

        nodes = make_nodes(AgentDeps(llm=_ScriptedLLM(), tools=tools))
        state = {
            "user_token": "tok_A",
            "tool_calls": [{"name": "search_products", "arguments": {"query": "x"}}],
            "messages": [{"role": "user", "content": "x"}],
        }
        nodes["tool_execute"](state)
        assert tools.calls == [("search_products", "tok_A")]

    def test_adapter_holds_no_shared_token(self):
        """调用后适配器不留存 token（不会串给下一次调用）。"""
        tools = _RecordingTools()
        adapter = ToolExecutorAdapter({"search_products": tools})
        adapter.invoke("search_products", {"query": "x"}, user_token="tok_A")
        adapter.invoke("search_products", {"query": "y"}, user_token="tok_B")
        assert tools.ctx_tokens == ["tok_A", "tok_B"]


# ---------------------------------------------------------------------------
# 画像同步后注入新鲜对象
# ---------------------------------------------------------------------------


class _FakeJava:
    async def list_orders(self, **kwargs):
        return {"items": [{"orderId": "o_1", "orderNo": "n1", "status": "PAID",
                           "totalCent": 804, "createdAt": "2026-06-01T00:00:00Z"}]}

    async def get_order(self, **kwargs):
        return {"orderId": "o_1", "items": [{"productName": "测试商品×1"}]}


class TestProfileFreshness:
    def test_sync_returns_profile_with_orders(self, monkeypatch):
        store = ProfileStore()
        orch = AgentOrchestrator(
            deps=AgentDeps(llm=_ScriptedLLM(), tools=_RecordingTools()),
            profile_store=store,
            java_client=_FakeJava(),
        )
        captured: dict[str, Any] = {}

        def fake_invoke_turn(graph, **kwargs):
            captured.update(kwargs)
            return {"reply": "ok", "intent": None, "recommendations": [], "actions": []}

        monkeypatch.setattr("app.agent.service.invoke_turn", fake_invoke_turn)
        orch.send_message(session_id="s1", user_id="u_fresh", text="你好", user_token="tok")
        profile = captured["user_profile"]
        # 本轮刚拉的购买历史必须已注入（同步前的旧对象不含 recent_orders）
        assert profile["recent_orders"], "同步后的画像应含最近购买"
        assert "测试商品" in profile["recent_orders"][0]["items"][0]


# ---------------------------------------------------------------------------
# checkpoint 跨轮上下文
# ---------------------------------------------------------------------------


class TestCheckpointWiring:
    def test_cross_turn_history_reaches_llm(self):
        llm = _ScriptedLLM()
        graph = build_agent_graph(
            AgentDeps(llm=llm, tools=_RecordingTools()),
            checkpointer=build_checkpointer(),
        )
        invoke_turn(graph, session_id="s_ckpt", user_text="你好呀", user_id="u")
        invoke_turn(graph, session_id="s_ckpt", user_text="推荐个耳机", user_id="u")
        intent_calls = [m for m in llm.seen if "输出意图识别结果" in m[0]["content"]]
        assert len(intent_calls) >= 2
        second_turn_text = "\n".join(m["content"] for m in intent_calls[-1])
        assert "你好呀" in second_turn_text, "第二轮应携带第一轮消息（checkpoint 续接）"

    def test_build_checkpointer_without_redis_falls_back(self):
        saver = build_checkpointer()  # 不给 redis_url → 内存兜底
        assert saver is not None


# ---------------------------------------------------------------------------
# FAQ RAG 主路径
# ---------------------------------------------------------------------------


class _FakeChunk:
    def __init__(self) -> None:
        self.doc_id = "faq-shipping"
        self.text = "满 99 元包邮"
        self.metadata = {"title": "运费"}


class _FakeItem:
    def __init__(self) -> None:
        self.chunk = _FakeChunk()
        self.score = 0.9


class _FakeResult:
    items = [_FakeItem()]


class _FakeRetriever:
    def retrieve(self, query, top_k=3):
        return _FakeResult()


class _FakeAsyncClient:
    async def search_products(self, **kwargs):
        return {"items": []}


class TestFaqRagPath:
    async def test_retriever_hit_returns_documents(self):
        from app.tools.faq import FaqTool

        tool = FaqTool(_FakeAsyncClient(), retriever=_FakeRetriever())  # type: ignore[arg-type]
        from app.tools.base import ToolContext

        result = await tool.execute(ToolContext(), question="运费怎么算")
        assert result.ok
        assert result.data["type"] == "rag"
        assert result.data["documents"][0]["doc_id"] == "faq-shipping"

    async def test_without_retriever_falls_back_to_kb(self):
        from app.tools.base import ToolContext
        from app.tools.faq import FaqTool

        tool = FaqTool(_FakeAsyncClient(), retriever=None)  # type: ignore[arg-type]
        result = await tool.execute(ToolContext(), question="可以退换货吗 七天无理由")
        assert result.ok
        assert result.data["type"] == "faq"


# ---------------------------------------------------------------------------
# 指标记录
# ---------------------------------------------------------------------------


class TestMetricsWiring:
    def test_tool_adapter_records_metrics(self):
        registry = MetricsRegistry()
        adapter = ToolExecutorAdapter({"search_products": _RecordingTools()}, metrics=registry)
        adapter.invoke("search_products", {"query": "x"}, user_token="t")
        assert registry.tool_success_rate("search_products") == 1.0

    def test_llm_usage_recorded(self):
        registry = MetricsRegistry()
        adapter = LLMAdapter("http://x", "k", "deepseek-chat", metrics=registry)

        class _Result:
            content = "hi"
            usage = {"prompt_tokens": 100, "completion_tokens": 50}

        adapter._record_usage(_Result())
        assert registry.token_usage()["total_tokens"] == 150
