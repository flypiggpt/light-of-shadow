"""状态机节点实现：guard → intent → (clarify | tool_route → tool_execute) → respond / degrade。

- 所有节点经 `safe_node` 包装：异常被捕获并标记 error，条件边路由到 degrade 节点（降级话术），
  绝不把异常抛给调用方；
- LLM/工具依赖通过 `AgentDeps` 注入，节点逻辑可脱离真实服务单测；
- 工具失败重试一次（SPEC 要求），多工具轮次由 max_tool_rounds 有界循环。
"""

from __future__ import annotations

import json
import logging
from typing import Any, Callable, Mapping

from app.memory.profile import UserProfile, render_profile, wrap_profile_block

from .prompts import (
    ANSWER_SYSTEM_PROMPT,
    CLARIFY_SYSTEM_PROMPT,
    CLARIFY_TEMPLATE,
    EMPTY_INPUT_REPLY,
    FALLBACK_REPLY,
    INTENT_SYSTEM_PROMPT,
    build_llm_messages,
    filter_output,
    looks_like_injection,
    parse_json_block,
    sanitize_user_text,
)
from .state import AgentDeps, ToolResult, last_user_text

__all__ = [
    "KNOWN_INTENTS",
    "INTENT_TOOL_MAP",
    "make_nodes",
    "safe_node",
    "route_after_guard",
    "route_after_intent",
    "route_after_tools",
]

logger = logging.getLogger("agent-service.agent.nodes")

# 意图归一化：LLM 输出别名 → 标准意图名
KNOWN_INTENTS = frozenset(
    {
        "recommend_product",
        "search_product",
        "compare_product",
        "add_cart",
        "product_detail",
        "faq",
        "chitchat",
        "other",
    }
)

_INTENT_ALIASES = {
    "recommend": "recommend_product",
    "推荐": "recommend_product",
    "推荐商品": "recommend_product",
    "search": "search_product",
    "搜索": "search_product",
    "搜索商品": "search_product",
    "compare": "compare_product",
    "对比": "compare_product",
    "对比商品": "compare_product",
    "add_to_cart": "add_cart",
    "加入购物车": "add_cart",
    "get_product": "product_detail",
    "detail": "product_detail",
    "商品详情": "product_detail",
    "qa": "faq",
    "question": "faq",
    "问答": "faq",
    "smalltalk": "chitchat",
    "闲聊": "chitchat",
}

# 意图 → 工具路由表
INTENT_TOOL_MAP = {
    "recommend_product": "search_products",
    "search_product": "search_products",
    "compare_product": "compare",
    "add_cart": "add_cart",
    "product_detail": "get_product",
    "faq": "faq",
}

_SLOT_LABELS = {
    "keyword": "想买的品类或用途",
    "minPriceCent": "预算下限",
    "maxPriceCent": "预算上限",
    "preference": "偏好的特性",
    "brand": "品牌",
    "productId": "具体商品",
    "productIds": "要对比的商品",
    "quantity": "数量",
}


def safe_node(
    fn: Callable[[Mapping[str, Any]], Mapping[str, Any]], *, name: str
) -> Callable[[Mapping[str, Any]], dict[str, Any]]:
    """节点安全包装：任何异常转成 error 标记，交给 degrade 节点降级。"""

    def wrapper(state: Mapping[str, Any]) -> dict[str, Any]:
        try:
            return dict(fn(state))
        except Exception as exc:  # noqa: BLE001 —— 节点级兜底是本层职责
            logger.warning("节点执行异常，转入降级 node=%s", name, exc_info=True)
            return {"error": f"{name}: {exc}", "degraded": True}

    wrapper.__name__ = f"safe_{name}"
    return wrapper


# ---- 条件边（纯函数，可单测） ----


def route_after_guard(state: Mapping[str, Any], **_: Any) -> str:
    if state.get("error"):
        return "degrade"
    return "intent"


def route_after_intent(state: Mapping[str, Any], *, max_clarify_rounds: int = 2, **_: Any) -> str:
    if state.get("error"):
        return "degrade"
    if state.get("needs_clarify"):
        if int(state.get("clarify_round") or 0) < max_clarify_rounds:
            return "clarify"
        # 追问轮次用尽：不再追问，尽力直接回答
        return "respond"
    if (state.get("intent") or {}).get("name") in INTENT_TOOL_MAP:
        return "tools"
    return "respond"


def route_after_tools(state: Mapping[str, Any], *, max_tool_rounds: int = 2, **_: Any) -> str:
    if state.get("error"):
        return "degrade"
    if state.get("more_tool_calls") and int(state.get("tool_rounds") or 0) < max_tool_rounds:
        return "tools"
    return "respond"


# ---- 节点工厂 ----


def make_nodes(deps: AgentDeps) -> dict[str, Callable[[Mapping[str, Any]], dict[str, Any]]]:
    """构造全部节点（闭包持有依赖，便于注入 mock）。"""

    def guard_node(state: Mapping[str, Any]) -> dict[str, Any]:
        raw_text = last_user_text(state)
        text = sanitize_user_text(raw_text)
        out: dict[str, Any] = {"injection_suspected": looks_like_injection(text)}
        if not text:
            out["error"] = "empty_input"
            out["degraded"] = True
        return out

    def intent_node(state: Mapping[str, Any]) -> dict[str, Any]:
        text = sanitize_user_text(last_user_text(state))
        profile = _profile_of(state)
        history = deps.window.render(list(state.get("messages") or []))
        messages = build_llm_messages(
            history,
            system_prompt=INTENT_SYSTEM_PROMPT,
            profile_block=wrap_profile_block(profile),
        )
        # LLM 异常向上传播（safe_node → degrade 节点降级话术）；
        # 仅当返回内容无法解析时才走规则启发式续流。
        raw = deps.llm.chat(messages, timeout=deps.llm_timeout)
        parsed = parse_json_block(raw)
        if parsed is None:
            logger.warning("意图 JSON 解析失败，规则启发式兜底")
            parsed = _heuristic_intent(text)

        name = _normalize_intent(str(parsed.get("name") or "other"))
        entities = parsed.get("entities") if isinstance(parsed.get("entities"), dict) else {}
        confidence = parsed.get("confidence")
        confidence = float(confidence) if isinstance(confidence, (int, float)) else 0.5
        missing_raw = [s for s in (parsed.get("missing_slots") or []) if isinstance(s, str)]
        # 画像已覆盖的槽位不算缺失（重复使用少追问）
        missing = [s for s in missing_raw if not _slot_satisfied(s, profile)]
        needs_clarify = bool(parsed.get("needs_clarify")) or (
            name in ("recommend_product", "search_product") and confidence < 0.5
        )
        if needs_clarify and not missing:
            # 仅当 LLM 没说缺什么、且连品类线索都没有时，才兜底追问品类/用途
            if not missing_raw and not entities.get("keyword"):
                missing = ["想买的品类或用途"]
        if not missing:
            needs_clarify = False

        return {
            "intent": {"name": name, "confidence": confidence, "entities": entities},
            "entities": entities,
            "needs_clarify": needs_clarify,
            "missing_slots": missing,
        }

    def clarify_node(state: Mapping[str, Any]) -> dict[str, Any]:
        slots = state.get("missing_slots") or ["想买的品类或用途"]
        labels = [_SLOT_LABELS.get(s, s) for s in slots][:2]
        question = CLARIFY_TEMPLATE.format(slots="、".join(labels))
        try:
            history = deps.window.render(list(state.get("messages") or []))
            messages = build_llm_messages(history, system_prompt=CLARIFY_SYSTEM_PROMPT)
            raw = filter_output(deps.llm.chat(messages, timeout=deps.llm_timeout))
            if raw and len(raw) <= 120:
                question = raw
        except Exception:
            # 追问生成失败不降级整轮：模板追问兜底
            logger.warning("追问生成失败，模板兜底", exc_info=True)

        return {
            "clarify_round": int(state.get("clarify_round") or 0) + 1,
            "clarify_question": question,
            "reply": question,
            "content_type": "text",
            "messages": [{"role": "assistant", "content": question}],
        }

    def tool_route_node(state: Mapping[str, Any]) -> dict[str, Any]:
        intent_name = (state.get("intent") or {}).get("name", "other")
        tool_name = INTENT_TOOL_MAP.get(intent_name)
        if tool_name is None:
            raise ValueError(f"意图没有对应工具: {intent_name}")
        arguments = _build_tool_args(intent_name, state)
        return {"tool_calls": [{"name": tool_name, "arguments": arguments}]}

    def tool_execute_node(state: Mapping[str, Any]) -> dict[str, Any]:
        calls = list(state.get("tool_calls") or [])
        executed: list[dict[str, Any]] = []
        any_ok = False
        next_calls: list[dict[str, Any]] = []
        for call in calls:
            name = str(call.get("name") or "")
            arguments = dict(call.get("arguments") or {})
            result = _invoke_with_retry(deps, name, arguments)
            executed.append(
                {
                    "name": name,
                    "arguments": arguments,
                    "ok": result.ok,
                    "data": result.data,
                    "error": result.error,
                }
            )
            if result.ok:
                any_ok = True
                follow_up = (result.data or {}).get("next_calls") if isinstance(result.data, dict) else None
                if isinstance(follow_up, list):
                    next_calls.extend(
                        {"name": str(c.get("name")), "arguments": dict(c.get("arguments") or {})}
                        for c in follow_up
                        if isinstance(c, dict) and c.get("name")
                    )

        rounds = int(state.get("tool_rounds") or 0) + 1
        out: dict[str, Any] = {
            "tool_rounds": rounds,
            "tool_results": list(state.get("tool_results") or []) + executed,
            "tool_calls": next_calls,
            "more_tool_calls": bool(next_calls) and rounds < deps.max_tool_rounds,
        }
        if not any_ok:
            out["error"] = "tool_failed"
            out["degraded"] = True
        else:
            recommendations, actions = _extract_api_payload(executed)
            if recommendations:
                out["recommendations"] = recommendations
            if actions:
                out["actions"] = actions
        return out

    def respond_node(state: Mapping[str, Any]) -> dict[str, Any]:
        reply = _template_reply(state)
        degraded = False
        try:
            history = deps.window.render(list(state.get("messages") or []))
            tool_summary = _format_tool_results(state.get("tool_results") or [])
            messages = build_llm_messages(
                history,
                system_prompt=ANSWER_SYSTEM_PROMPT,
                user_suffix=tool_summary or None,
                profile_block=wrap_profile_block(_profile_of(state)),
            )
            raw = filter_output(deps.llm.chat(messages, timeout=deps.llm_timeout))
            if raw:
                reply = raw
        except Exception:
            logger.warning("回复生成失败，模板兜底", exc_info=True)
            if reply == FALLBACK_REPLY:
                degraded = True

        content_type = "product_list" if state.get("recommendations") else "text"
        return {
            "reply": reply,
            "content_type": content_type,
            "degraded": degraded or bool(state.get("degraded")),
            "messages": [{"role": "assistant", "content": reply}],
        }

    def degrade_node(state: Mapping[str, Any]) -> dict[str, Any]:
        reply = EMPTY_INPUT_REPLY if state.get("error") == "empty_input" else FALLBACK_REPLY
        return {
            "reply": reply,
            "content_type": "error",
            "degraded": True,
            "messages": [{"role": "assistant", "content": reply}],
        }

    return {
        "guard": guard_node,
        "intent": intent_node,
        "clarify": clarify_node,
        "tool_route": tool_route_node,
        "tool_execute": tool_execute_node,
        "respond": respond_node,
        "degrade": degrade_node,
    }


# ---- 内部辅助 ----


def _profile_of(state: Mapping[str, Any]) -> UserProfile:
    """状态里的画像快照（dict）转成 UserProfile，缺失时返回空画像。"""
    return UserProfile.from_dict(state.get("user_profile") or {})


def _slot_satisfied(slot: str, profile: UserProfile) -> bool:
    """画像是否已覆盖该缺失槽位（只认粘性事实，不认本轮关键词）。"""
    if slot in ("maxPriceCent", "budget", "预算上限", "预算"):
        return profile.budget_max_cent is not None
    if slot in ("minPriceCent", "预算下限"):
        return profile.budget_min_cent is not None
    if slot in ("preference", "偏好的特性"):
        return bool(profile.preferences)
    if slot in ("brand", "品牌"):
        return bool(profile.brands)
    return False


def _normalize_intent(raw: str) -> str:
    key = raw.strip().lower().replace(" ", "_").replace("-", "_")
    key = _INTENT_ALIASES.get(raw.strip(), _INTENT_ALIASES.get(key, key))
    return key if key in KNOWN_INTENTS else "other"


def _heuristic_intent(text: str) -> dict[str, Any]:
    """LLM 输出不可用时的规则兜底（只用于续流，不追求高准确率）。"""
    lowered = text.lower()
    if any(k in text for k in ("加入购物车", "加购物车", "买它", "add to cart")) or "add_cart" in lowered:
        return {"name": "add_cart", "confidence": 0.5, "entities": {}, "needs_clarify": False}
    if any(k in text for k in ("对比", "比较", "compare")):
        return {"name": "compare_product", "confidence": 0.5, "entities": {}, "needs_clarify": False}
    if any(k in text for k in ("详情", "参数", "怎么样", "好不好用")):
        return {"name": "product_detail", "confidence": 0.5, "entities": {}, "needs_clarify": False}
    if any(k in text for k in ("退货", "退款", "发票", "物流", "发货", "售后", "客服")):
        return {"name": "faq", "confidence": 0.6, "entities": {}, "needs_clarify": False}
    if any(k in text for k in ("推荐", "买", "购", "找", "搜", "想要", "有什么")):
        entities: dict[str, Any] = {"keyword": text[:40]}
        needs = len(text.strip()) < 4
        return {
            "name": "recommend_product",
            "confidence": 0.5,
            "entities": entities,
            "needs_clarify": needs,
            "missing_slots": ["想买的品类或用途"] if needs else [],
        }
    return {
        "name": "other",
        "confidence": 0.3,
        "entities": {},
        "needs_clarify": True,
        "missing_slots": ["想买的品类或用途"],
    }


def _build_tool_args(intent_name: str, state: Mapping[str, Any]) -> dict[str, Any]:
    entities = dict(state.get("entities") or {})
    text = sanitize_user_text(last_user_text(state))
    tool_name = INTENT_TOOL_MAP[intent_name]
    profile = _profile_of(state)

    def pick(*keys: str) -> Any:
        for key in keys:
            if entities.get(key) not in (None, "", [], {}):
                return entities[key]
        return None

    if tool_name == "search_products":
        args: dict[str, Any] = {"query": pick("keyword", "query") or text[:80]}
        for src, dst in (
            ("minPriceCent", "min_price_cent"),
            ("maxPriceCent", "max_price_cent"),
            ("brand", "brand"),
            ("preference", "preference"),
            ("quantity", "quantity"),
        ):
            value = pick(src)
            if value is not None:
                args[dst] = value
        # 画像预算兜底：本轮没说预算时沿用历史预算，搜索更贴合用户
        if "min_price_cent" not in args and profile.budget_min_cent is not None:
            args["min_price_cent"] = profile.budget_min_cent
        if "max_price_cent" not in args and profile.budget_max_cent is not None:
            args["max_price_cent"] = profile.budget_max_cent
        return args
    if tool_name == "compare":
        ids = pick("productIds", "product_id") or pick("productId")
        if isinstance(ids, str):
            ids = [ids]
        return {"product_ids": ids or []}
    if tool_name == "add_cart":
        return {
            "product_id": pick("productId", "product_id"),
            "quantity": pick("quantity") or 1,
        }
    if tool_name == "get_product":
        return {"product_id": pick("productId", "product_id")}
    return {"question": text[:200]}


def _invoke_with_retry(deps: AgentDeps, name: str, arguments: Mapping[str, Any]) -> ToolResult:
    """工具调用：失败重试一次（SPEC）。"""
    try:
        return deps.tools.invoke(name, arguments, timeout=deps.tool_timeout)
    except Exception as exc:  # noqa: BLE001 —— 第一次失败转成结果对象再重试
        logger.warning("工具调用异常（第 1 次）tool=%s", name, exc_info=True)
        first_error = str(exc)
    try:
        return deps.tools.invoke(name, arguments, timeout=deps.tool_timeout)
    except Exception as exc:  # noqa: BLE001
        logger.warning("工具调用异常（重试后仍失败）tool=%s", name, exc_info=True)
        return ToolResult(ok=False, error=f"{first_error}; retry: {exc}")


def _extract_api_payload(
    executed: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """从工具结果提取 API 响应所需的 recommendations / actions。"""
    recommendations: list[dict[str, Any]] = []
    actions: list[dict[str, Any]] = []
    for item in executed:
        if not item.get("ok"):
            continue
        data = item.get("data")
        if isinstance(data, dict):
            recs = data.get("recommendations") or data.get("products") or []
            if isinstance(recs, list):
                recommendations.extend(r for r in recs if isinstance(r, dict))
            acts = data.get("actions") or []
            if isinstance(acts, list):
                actions.extend(a for a in acts if isinstance(a, dict))
        elif isinstance(data, list):
            recommendations.extend(r for r in data if isinstance(r, dict))
    return recommendations, actions


def _format_tool_results(results: list[dict[str, Any]]) -> str:
    if not results:
        return ""
    compact = [
        {
            "name": r.get("name"),
            "ok": r.get("ok"),
            "data": r.get("data"),
            "error": r.get("error"),
        }
        for r in results[-3:]
    ]
    return "工具返回数据（JSON）：" + json.dumps(compact, ensure_ascii=False, default=str)[:4000]


def _template_reply(state: Mapping[str, Any]) -> str:
    recs = list(state.get("recommendations") or [])
    if recs:
        names = "、".join(str(r.get("name") or r.get("productId") or "商品") for r in recs[:3])
        return f"为你找到 {len(recs)} 款相关商品：{names}。需要看详情或加入购物车吗？"
    if any(r.get("ok") for r in state.get("tool_results") or []):
        return "已为你处理好，还有其他需要吗？"
    return FALLBACK_REPLY
