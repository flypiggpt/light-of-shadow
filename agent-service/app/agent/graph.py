"""LangGraph 状态机构建与单轮执行入口。

图结构（条件边 + 有界循环）：

    START ─┬─(用户对话)→ guard → intent ─┬─(需追问且轮次未尽)→ clarify → END
           │                             ├─(追问轮次用尽)────→ respond → END
           │                             ├─(带工具意图)──→ tool_route → tool_execute ─┬─(需下一轮工具)→ tool_execute
           │                             │                                          └─→ respond → END
           │                             └─(闲聊/其他)────────────────→ respond → END
           └─(主动提议 signal_id)→ propose ─┬─(工具动作)→ tool_execute → confirm_reply → END
                                           └─(客户端动作)────→ confirm_reply → END
    guard / intent / tool_* 异常 → degrade → END

追问循环跨轮完成：clarify 后本轮结束，用户补充后带 checkpoint 状态继续追问计数；
工具循环在轮内，受 max_tool_rounds 约束。

主动提议支路（proactive）：propose 节点 interrupt 挂起并 checkpoint（thread_id = signal_id），
confirm 用 Command(resume=…) 恢复执行；确认回执走模板，不进 LLM respond。
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
from .proactive import (
    make_confirm_reply_node,
    make_propose_node,
    route_after_propose,
    safe_propose_node,
)
from .prompts import FALLBACK_REPLY, sanitize_user_text
from .state import AgentDeps, AgentState, last_user_text

__all__ = ["build_agent_graph", "invoke_turn", "invoke_prepare", "invoke_confirm"]

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
    builder.add_node("propose", safe_propose_node(make_propose_node(deps)))
    builder.add_node("confirm_reply", safe_node(make_confirm_reply_node(), name="confirm_reply"))

    builder.add_conditional_edges(
        START,
        lambda state: "propose" if state.get("signal_id") else "guard",
        {"guard": "guard", "propose": "propose"},
    )
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
        lambda state: (
            ("tools" if state.get("more_tool_calls") else "confirm")
            if state.get("signal_id")
            else route_after_tools(state, max_tool_rounds=deps.max_tool_rounds)
        ),
        {
            "tools": "tool_execute",
            "respond": "respond",
            "degrade": "degrade",
            "confirm": "confirm_reply",
        },
    )
    builder.add_conditional_edges(
        "propose",
        lambda state: route_after_propose(state),
        {"tools": "tool_execute", "confirm": "confirm_reply"},
    )
    builder.add_edge("respond", END)
    builder.add_edge("confirm_reply", END)
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
    user_token: str = "",
    config: RunnableConfig | None = None,
) -> dict[str, Any]:
    """执行一轮对话。

    - 同一 session_id + checkpointer = 断点恢复（追问轮次、历史均续接）；
    - `user_profile` 为跨会话偏好快照（预算/偏好/购买历史），注入意图与回复节点；
    - `user_token` 请求级透传给工具调 Java（随状态流转，不共享）；
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
        "user_token": user_token or "",
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


def invoke_prepare(
    graph: Any,
    *,
    signal_id: str,
    signal_type: str,
    facts: Mapping[str, Any],
    user_id: str = "",
) -> dict[str, Any]:
    """主动提议入口：跑 propose 节点到 interrupt 挂起，返回提议内容。

    thread_id = signal_id，挂起态经 checkpoint 落盘；重复调用同一 signal_id
    返回同一提议（幂等）。
    """
    run_config: RunnableConfig = {
        "configurable": {"thread_id": signal_id, "checkpoint_ns": ""},
    }
    payload = {
        "signal_id": signal_id,
        "signal_type": signal_type,
        "facts": dict(facts or {}),
        "user_id": user_id,
        "session_id": signal_id,
        "messages": [],
    }
    try:
        result = graph.invoke(payload, run_config)
    except Exception:
        logger.exception("主动提议挂起失败 signal_id=%s", signal_id)
        raise

    interrupts = result.get("__interrupt__") or ()
    if interrupts:
        value = interrupts[0].value
        return {
            "signalId": signal_id,
            "message": value.get("message", ""),
            "actions": value.get("actions", []),
            "threadId": signal_id,
        }
    # 已经跑完（重复 prepare）：回显提议内容
    return {
        "signalId": signal_id,
        "message": (result.get("messages") or [{}])[-1].get("content", ""),
        "actions": result.get("proposed_actions") or [],
        "threadId": signal_id,
    }


def invoke_confirm(
    graph: Any,
    *,
    signal_id: str,
    action_id: str,
    user_id: str = "",
    user_token: str = "",
) -> dict[str, Any]:
    """确认入口：从 checkpoint 恢复挂起态并执行确认动作。

    幂等：同一 signal_id 重复确认返回上次结果，不重复执行工具。
    """
    from langgraph.types import Command

    run_config: RunnableConfig = {
        "configurable": {"thread_id": signal_id, "checkpoint_ns": ""},
    }
    # 幂等保护：已确认过的挂起直接回放结果，不重复执行工具；
    # 无挂起态（未 prepare / 已 discard）报业务错，交给调用方 4xx
    try:
        snapshot = graph.get_state(run_config)
    except Exception:  # noqa: BLE001 —— 状态读取失败按未确认处理，走正常恢复
        snapshot = None
    values = getattr(snapshot, "values", None) or {}
    if not values:
        raise KeyError(f"提议不存在或已处理: {signal_id}")
    if values.get("confirmed_action"):
        last_tool = (values.get("tool_results") or [{}])[-1] if values.get("tool_results") else {}
        return {
            "signalId": signal_id,
            "actionId": values.get("confirmed_action"),
            "reply": values.get("reply", ""),
            "executed": bool(values.get("executed")),
            "result": last_tool.get("data"),
        }

    try:
        result = graph.invoke(
            Command(resume={"actionId": action_id, "userToken": user_token or ""}),
            run_config,
        )
    except Exception:
        logger.exception("主动提议确认失败 signal_id=%s", signal_id)
        raise

    last_tool = (result.get("tool_results") or [{}])[-1] if result.get("tool_results") else {}
    return {
        "signalId": signal_id,
        "actionId": result.get("confirmed_action") or action_id,
        "reply": result.get("reply", ""),
        "executed": bool(result.get("executed")),
        "result": last_tool.get("data"),
    }
