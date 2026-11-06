"""网关模式回归：Java 生成的 sessionId 直达 Agent（自动建线程、close 幂等）。"""

from __future__ import annotations

import sys
from pathlib import Path

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from app.agent.graph import AgentDeps  # noqa: E402
from app.agent.service import AgentOrchestrator  # noqa: E402
from app.agent.state import ToolResult  # noqa: E402
from app.memory.profile import ProfileStore  # noqa: E402


class _FakeLLM:
    def __init__(self) -> None:
        self.seen: list[list[dict[str, str]]] = []

    def chat(self, messages, *, timeout=None):
        self.seen.append(list(messages))
        system = messages[0]["content"]
        if "输出意图识别结果" in system:
            return '{"name": "chitchat", "confidence": 0.9, "entities": {}, "needs_clarify": false}'
        return "你好呀～"


class _FakeTools:
    def available(self):
        return []

    def invoke(self, name, arguments, *, timeout=None):
        return ToolResult(ok=False, error="noop")


def _orchestrator() -> AgentOrchestrator:
    return AgentOrchestrator(
        deps=AgentDeps(llm=_FakeLLM(), tools=_FakeTools()),
        profile_store=ProfileStore(),
    )


class TestGatewaySession:
    def test_unknown_session_auto_created(self):
        """Java 生成的 sessionId 从未在 Agent 建过会话 → 直接可发消息。"""
        orch = _orchestrator()
        result = orch.send_message(
            session_id="sg_java_generated_abc123",
            user_id="u_test",
            text="你好",
        )
        assert result["assistantMessage"]["content"]
        assert orch.get_session("sg_java_generated_abc123") is not None

    def test_close_unknown_session_idempotent(self):
        """关闭 Agent 未见过的会话 → 返回 ENDED，不抛 404。"""
        orch = _orchestrator()
        data = orch.close_session("sg_never_seen")
        assert data["status"] == "ENDED"
        assert data["sessionId"] == "sg_never_seen"

    def test_closed_session_still_rejects(self):
        orch = _orchestrator()
        orch.send_message(session_id="sg_1", user_id="u_test", text="hi")
        orch.close_session("sg_1")
        try:
            orch.send_message(session_id="sg_1", user_id="u_test", text="again")
        except ValueError as exc:
            assert str(exc) == "session_ended"
        else:
            raise AssertionError("ENDED 会话应拒绝消息")
