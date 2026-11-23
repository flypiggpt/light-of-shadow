"""主动提议节点：模板文案 + 动作组装 + interrupt 挂起/恢复。

设计见 docs/proactive-agent-plan.md §8：
- propose 节点接收预填的信号类型与事实字段，用模板生成一句话提议（LLM 不参与）；
- interrupt({signalId, message, actions}) 挂起并 checkpoint 落盘（thread_id = signalId）；
- resume 时 interrupt() 返回 {actionId}：工具动作走 tool_execute 复用现有工具链，
  客户端动作（pay/go_cart 等）只回执不执行；
- 事实字段进模板前过消毒，输出长度受控。
"""

from __future__ import annotations

import logging
from typing import Any, Mapping

from langgraph.errors import GraphInterrupt
from langgraph.types import interrupt

from .prompts import sanitize_user_text
from .state import AgentDeps, AgentState

logger = logging.getLogger("agent-service.agent.proactive")

__all__ = [
    "make_propose_node",
    "make_confirm_reply_node",
    "safe_propose_node",
    "route_after_propose",
    "route_after_confirm_tools",
    "render_proposal",
    "build_actions",
    "TOOL_ACTIONS",
    "CLIENT_ACTIONS",
]

# ---- 动作注册表 ----
# 工具动作：confirm 恢复后经 tool_execute 真正执行
TOOL_ACTIONS = {
    "cancel_order": ("cancel_order", lambda facts: {"order_id": str(facts.get("orderId") or "")}),
    "add_cart": ("add_cart", lambda facts: {
        "product_id": str(facts.get("productId") or ""),
        "quantity": 1,
    }),
}
# 客户端动作：只回执，由客户端调商城 API 或导航
CLIENT_ACTIONS = {"pay", "go_cart", "remove_cart_item", "keep", "no", "dismiss"}


# ---- 文案模板（事实槽位全部来自 payload，不走 LLM）----

def _yuan(cent: Any) -> str:
    """分 → 元字符串：2900 → 29，2450 → 24.5。"""
    try:
        value = int(cent) / 100
    except (TypeError, ValueError):
        return "0"
    return str(int(value)) if value == int(value) else str(round(value, 2))


def render_proposal(signal_type: str, facts: Mapping[str, Any]) -> str:
    """按信号类型渲染提议文案（≤80 字）。"""
    product = sanitize_user_text(str(facts.get("productName") or "商品"))[:20]
    if signal_type == "UNPAID_ORDER":
        return (f"你有一笔 {product} 订单还没支付（{_yuan(facts.get('amountCent'))} 元），"
                f"{int(facts.get('remainingMinutes') or 0)} 分钟内不支付会自动关闭，要继续支付吗？")
    if signal_type == "DUP_ORDER":
        if facts.get("trigger") == "QUANTITY":
            return (f"这单 {product} × {int(facts.get('quantity') or 0)}，"
                    f"比你平时多不少，确定吗？")
        return f"你刚下了两单 {product}，是重复下单吗？要帮你取消一单吗？"
    if signal_type == "CART_IDLE":
        if facts.get("stock") == 0:
            return f"购物车里的 {product} 没货了，要清掉吗？"
        return f"购物车里的 {product} 放了 {int(facts.get('days') or 0)} 天，现在要下单吗？"
    if signal_type == "PRICE_DROP":
        return (f"你 {int(facts.get('days') or 0)} 天前 {_yuan(facts.get('oldPriceCent'))} 元买的 "
                f"{product}，现在只要 {_yuan(facts.get('newPriceCent'))} 元，要再买一件吗？")
    return "有一条提醒需要你确认。"


def build_actions(signal_type: str, facts: Mapping[str, Any]) -> list[dict[str, Any]]:
    """按信号类型组装动作按钮（工具动作预填参数，客户端动作只出按钮）。"""
    if signal_type == "UNPAID_ORDER":
        return [
            {"actionId": "pay", "label": "继续支付", "kind": "client"},
            {"actionId": "cancel_order", "label": "取消订单", "kind": "tool"},
        ]
    if signal_type == "DUP_ORDER":
        return [
            {"actionId": "cancel_order", "label": "取消这单", "kind": "tool"},
            {"actionId": "keep", "label": "保留", "kind": "client"},
        ]
    if signal_type == "CART_IDLE":
        return [
            {"actionId": "go_cart", "label": "去购物车", "kind": "client"},
            {"actionId": "remove_cart_item", "label": "清掉这件", "kind": "client"},
        ]
    if signal_type == "PRICE_DROP":
        return [
            {"actionId": "add_cart", "label": "再买一件", "kind": "tool"},
            {"actionId": "no", "label": "不用了", "kind": "client"},
        ]
    return []


# ---- 确认结果话术（确定性模板）----

_CLIENT_REPLY = {
    "pay": "好的，请继续完成支付。",
    "go_cart": "好的，已为你打开购物车。",
    "remove_cart_item": "好的，已清掉这件。",
    "keep": "好的，已为你保留。",
    "no": "好的，不再提了。",
    "dismiss": "好的，不再提了。",
}


def _tool_reply(action_id: str, facts: Mapping[str, Any], ok: bool) -> str:
    if not ok:
        return "操作没有成功，请稍后再试。"
    product = sanitize_user_text(str(facts.get("productName") or "商品"))
    if action_id == "add_cart":
        return f"已把 {product} × 1 加入购物车。"
    if action_id == "cancel_order":
        return f"已取消订单（{_short_id(facts.get('orderId'))}），库存已恢复。"
    return "已完成。"


def _short_id(value: Any) -> str:
    text = str(value or "")
    return text if len(text) <= 12 else text[:12]


# ---- 节点工厂 ----

def safe_propose_node(fn):
    """propose 的安全包装：普通异常转降级，但 GraphInterrupt（挂起）必须放行。

    GraphInterrupt 继承 Exception，用通用 safe_node 会被吞掉，
    interrupt 挂起就退化成降级——必须单独包装。
    """

    def wrapper(state):
        try:
            return dict(fn(state))
        except GraphInterrupt:
            raise
        except Exception as exc:  # noqa: BLE001 —— 节点级兜底是本层职责
            logger.warning("propose 节点执行异常，转入降级", exc_info=True)
            return {"error": f"propose: {exc}", "degraded": True}

    wrapper.__name__ = "safe_propose"
    return wrapper


def make_propose_node(deps: AgentDeps):
    """propose 节点：生成提议并 interrupt 挂起；resume 后映射确认动作。"""

    def propose(state: AgentState) -> dict[str, Any]:
        signal_type = str(state.get("signal_type") or "")
        facts = dict(state.get("facts") or {})
        signal_id = str(state.get("signal_id") or "")
        message = render_proposal(signal_type, facts)
        actions = build_actions(signal_type, facts)

        # 首次执行在此挂起；resume 时 interrupt() 返回 {"actionId", "userToken"}
        decision = interrupt({
            "signalId": signal_id,
            "message": message,
            "actions": actions,
        }) or {}
        action_id = str(decision.get("actionId") or "")

        out: dict[str, Any] = {
            "confirmed_action": action_id,
            "proposed_actions": actions,
        }
        # confirm 请求带的是新鲜登录态（挂起可能隔数小时，prepare 时的 token 会过期）
        if decision.get("userToken"):
            out["user_token"] = str(decision.get("userToken") or "")
        tool_mapping = TOOL_ACTIONS.get(action_id)
        if tool_mapping is not None:
            tool_name, args_builder = tool_mapping
            out["tool_calls"] = [{"name": tool_name, "arguments": args_builder(facts)}]
            logger.info("主动提议确认走工具 signal=%s action=%s", signal_id, action_id)
        else:
            # 客端动作或未知动作：只回执不执行
            reply = _CLIENT_REPLY.get(action_id, "好的。")
            out.update({
                "reply": reply,
                "content_type": "text",
                "executed": False,
                "messages": [{"role": "assistant", "content": reply}],
            })
            logger.info("主动提议确认走客户端 signal=%s action=%s", signal_id, action_id)
        return out

    return propose


def make_confirm_reply_node():
    """确认回复节点：把工具执行结果渲染成确定性话术（不走 LLM）。"""

    def confirm_reply(state: AgentState) -> dict[str, Any]:
        if state.get("reply"):
            # propose 已给出客户端动作回执
            return {}
        action_id = str(state.get("confirmed_action") or "")
        facts = dict(state.get("facts") or {})
        results = list(state.get("tool_results") or [])
        last = results[-1] if results else {}
        ok = bool(last.get("ok")) and not state.get("degraded")
        reply = _tool_reply(action_id, facts, ok)
        return {
            "reply": reply,
            "content_type": "text",
            "executed": ok,
            "messages": [{"role": "assistant", "content": reply}],
        }

    return confirm_reply


# ---- 路由 ----

def route_after_propose(state: Mapping[str, Any], **_: Any) -> str:
    """propose 之后：有工具调用走工具链，否则直接确认回执。"""
    return "tools" if state.get("tool_calls") else "confirm"


def route_after_confirm_tools(state: Mapping[str, Any], **_: Any) -> str:
    """主动链路的 tool_execute 之后统一到确认回执（不进 LLM respond）。"""
    return "confirm"
