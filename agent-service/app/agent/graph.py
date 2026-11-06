"""LangGraph 状态机构建与单轮执行入口。

图结构（条件边 + 有界循环）：

    START → guard → intent ─┬─(需追问且轮次未尽)→ clarify → END
                           ├─(追问轮次用尽)────→ respond → END
                           ├─(带工具意图)──→ tool_route → tool_execute ─┬─(需下一轮工具)→ tool_execute
                           │                                          └─→ respond → END
                           └─(闲聊/其他)────────────────→ respond → END
    guard / intent / tool_* 异常 → degrade → END

追问循环跨轮完成：clarify 后本轮结束，用户补充后带 checkpoint 状态继续追问计数；
工具循环在轮内，受 max_tool_rounds 约束。
"""

from __future__ import annotations

import logging
from typing import Any, Mapping

from langgraph.graph import END, START, StateGraph
from langgraph.types import RunnableConfig

from .nodes import (
    make_nodes,
    route_after_guard,
    route_after_intent,
    route_after_tools,
    safe_node,
)
from .prompts import FALLBACK_REPLY, sanitize_user_text
from .state import AgentDeps, AgentState, last_user_text

__all__ = ["build_agent_graph", "invoke_turn"]

logger = logging.getLogger("agent-service.agent.graph")


def build_agent_graph(deps: AgentDeps, *, checkpointer: Any = None) -> Any:
    """构建并编译状态机。checkpointer 传入 app.memory 的 saver 即可断点恢复。"""
    nodes = make_nodes(deps)

    builder = StateGraph(AgentState)
    builder.add_node("guard", safe_node(nodes["guard"], name="guard"))
    builder.add_node("intent", safe_node(nodes["intent"], name="intent"))
    builder.add_node("clarify", safe_node(nodes["clarify"], name="clarify"))
    builder.add_node("tool_route", safe_node(nodes["tool_route"], name="tool_route"))
    builder.add_node("tool_execute", safe_node(nodes["tool_execute"], name="tool_execute"))
    builder.add_node("respond", safe_node(nodes["respond"], name="respond"))
    builder.add_node("degrade", nodes["degrade"])

    builder.add_edge(START, "guard")
    builder.add_conditional_edges(
        "guard",
        lambda state: route_after_guard(state),
        {"intent": "intent", "degrade": "degrade"},
    )
    builder.add_conditional_edges(
        "intent",
        lambda state: route_after_intent(
            state, max_clarify_rounds=deps.max_clarify_rounds
        ),
        {
            "clarify": "clarify",
            "tools": "tool_route",
            "respond": "respond",
            "degrade": "degrade",
        },
    )
    builder.add_edge("clarify", END)
    builder.add_edge("tool_route", "tool_execute")
    builder.add_conditional_edges(
        "tool_execute",
        lambda state: route_after_tools(state, max_tool_rounds=deps.max_tool_rounds),
        {"tools": "tool_execute", "respond": "respond", "degrade": "degrade"},
    )
    builder.add_edge("respond", END)
    builder.add_edge("degrade", END)

    return builder.compile(checkpointer=checkpointer)


def invoke_turn(
    graph: Any,
    *,
    session_id: str,
    user_text: str,
    user_id: str = "",
    request_id: str = "",
    client_context: Mapping[str, Any] | None = None,
    user_profile: Mapping[str, Any] | None = None,
    config: RunnableConfig | None = None,
) -> dict[str, Any]:
    """执行一轮对话。

    - 同一 session_id + checkpointer = 断点恢复（追问轮次、历史均续接）；
    - `user_profile` 为跨会话偏好快照（预算/偏好/购买历史），注入意图与回复节点；
    - 任何节点异常已在图内降级，checkpoint 写失败由 saver 兜底，本函数不抛业务异常。
    """
    run_config: RunnableConfig = config or {
        "configurable": {"thread_id": session_id, "checkpoint_ns": ""},
    }
    payload = {
        "session_id": session_id,
        "user_id": user_id,
        "request_id": request_id,
        "client_context": dict(client_context or {}),
        "user_profile": dict(user_profile or {}),
        "messages": [{"role": "user", "content": user_text}],
    }
    try:
        result = graph.invoke(payload, run_config)
    except Exception:
        logger.exception("状态机执行失败，返回全局降级 session_id=%s", session_id)
        return {
            "reply": FALLBACK_REPLY,
            "content_type": "error",
            "degraded": True,
            "error": "graph_failed",
            "intent": None,
            "recommendations": [],
            "actions": [],
            "messages": [
                {"role": "user", "content": sanitize_user_text(user_text)},
                {"role": "assistant", "content": FALLBACK_REPLY},
            ],
        }

    if isinstance(result, Mapping):
        return dict(result)
    return {"reply": FALLBACK_REPLY, "content_type": "error", "degraded": True, "error": "bad_result"}


def current_user_text(state: Mapping[str, Any]) -> str:
    """便捷读取当前轮用户输入（供 API 层回显）。"""
    return last_user_text(state)
