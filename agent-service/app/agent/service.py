"""Agent 编排服务：把 HTTP 层与 LangGraph 状态机接起来。

S1 记忆偏好：每轮注入用户画像（预算/偏好/购买历史）到意图与回复节点，
轮后从本轮 entities/文本学习偏好信号；跨会话记住，重复使用时减少追问。
"""
from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Mapping

from app.agent.graph import AgentDeps, build_agent_graph, invoke_turn
from app.memory.profile import ProfileStore, UserProfile, render_profile

logger = logging.getLogger("agent-service.agent.service")


def _run_async(coro, timeout: float = 30.0):
    """在独立线程的事件循环里跑协程，避免阻塞主事件循环导致死锁。"""
    import asyncio
    import concurrent.futures
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        return pool.submit(asyncio.run, coro).result(timeout=timeout)


@dataclass
class SessionRecord:
    session_id: str
    user_id: str
    status: str = "ACTIVE"
    entry: str = "shop"
    source: str = "app"
    locale: str = "zh-CN"
    welcome_message: str = ""
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    updated_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())


class ToolExecutorAdapter:
    """把工具 dict 适配成 ToolExecutor 协议。"""

    def __init__(self, tools: dict[str, Any], auth_token: str = "") -> None:
        self._tools = tools
        self._auth_token = auth_token

    def invoke(self, name: str, arguments: dict[str, Any], *, timeout: float | None = None) -> Any:
        tool = self._tools.get(name)
        if tool is None:
            raise ValueError(f"unknown tool: {name}")
        # 工具是 async execute，这里同步调用
        import asyncio
        from app.tools.base import ToolContext
        ctx = ToolContext(user_token=self._auth_token or None)
        kwargs = dict(arguments)
        coro = tool.execute(ctx, **kwargs) if callable(getattr(tool, "execute", None)) else tool(**kwargs)
        if asyncio.iscoroutine(coro):
            return _run_async(coro, timeout=timeout or 10)
        return coro


class LLMAdapter:
    """把 DeepSeekClient 适配成 LLMClient 协议（同步 chat 返回 str）。

    每次调用新建客户端，避免 httpx.AsyncClient 绑定旧事件循环。
    """

    def __init__(self, base_url: str, api_key: str, model: str) -> None:
        self._base_url = base_url
        self._api_key = api_key
        self._model = model

    def chat(self, messages: Any, *, timeout: float | None = None) -> str:
        async def _call():
            from app.llm.deepseek import DeepSeekClient
            client = DeepSeekClient(
                base_url=self._base_url, api_key=self._api_key, model=self._model
            )
            try:
                return await client.chat(list(messages))
            finally:
                await client.aclose()

        try:
            result = _run_async(_call(), timeout=timeout or 30)
        except Exception as exc:
            logger.warning("LLMAdapter.chat 失败", error=repr(exc))
            return "抱歉，我这边暂时出了点小状况，请稍后再试～"
        if hasattr(result, "content"):
            return str(result.content)
        return str(result)


class AgentOrchestrator:
    """会话生命周期 + 状态机调用的门面。"""

    def __init__(
        self,
        deps: AgentDeps,
        checkpointer: Any = None,
        *,
        profile_store: ProfileStore | None = None,
        java_client: Any = None,
    ) -> None:
        self._deps = deps
        self._graph = build_agent_graph(deps, checkpointer=checkpointer)
        self._sessions: dict[str, SessionRecord] = {}
        self._messages: dict[str, list[dict[str, Any]]] = {}
        self._profile = profile_store or ProfileStore()
        self._java = java_client
        self._auth_token = ""

    def create_session(self, user_id: str, entry: str = "shop",
                       source: str = "app", locale: str = "zh-CN") -> dict[str, Any]:
        sid = "sg_" + uuid.uuid4().hex[:16]
        rec = SessionRecord(session_id=sid, user_id=user_id, entry=entry,
                            source=source, locale=locale)
        rec.welcome_message = self._welcome_for(user_id)
        self._sessions[sid] = rec
        self._messages[sid] = [{"role": "assistant", "content": rec.welcome_message}]
        return self._session_dict(rec)

    def _welcome_for(self, user_id: str) -> str:
        """按画像个性化欢迎语（老用户少走引导）。"""
        base = "你好，我是商城导购助手。"
        profile = self._profile.load(user_id)
        if not profile.has_signal():
            return base + "告诉我预算、用途或偏好，我来帮你推荐。"
        hint = render_profile(profile)
        return base + f"我记得你之前的情况（{hint}）。今天想看点什么？"

    def get_session(self, session_id: str) -> dict[str, Any] | None:
        rec = self._sessions.get(session_id)
        return self._session_dict(rec) if rec else None

    def set_auth_token(self, token: str) -> None:
        """设置/更新调用 Java 工具的用户 token（请求级透传）。"""
        self._auth_token = token or ""
        if hasattr(self._deps.tools, "_auth_token"):
            self._deps.tools._auth_token = token

    def send_message(self, session_id: str, user_id: str, text: str,
                     request_id: str = "", client_context: Mapping[str, Any] | None = None) -> dict[str, Any]:
        rec = self._sessions.get(session_id)
        if rec is None:
            # 网关模式：sessionId 由 Java 生成落库，Agent 按需建线程（LangGraph thread_id）
            rec = SessionRecord(session_id=session_id, user_id=user_id, source="gateway")
            self._sessions[session_id] = rec
            self._messages.setdefault(session_id, [])
        if rec.status == "ENDED":
            raise ValueError("session_ended")

        profile = self._profile.load(user_id)
        self._sync_purchase_history(user_id, profile)

        result = invoke_turn(
            self._graph,
            session_id=session_id,
            user_text=text,
            user_id=user_id,
            request_id=request_id,
            client_context=client_context,
            user_profile=profile.to_dict(),
        )

        # 轮后学习：预算/偏好/品类记入画像（跨会话生效）
        turn_intent = result.get("intent") or {}
        learn_entities = result.get("entities") or turn_intent.get("entities") or {}
        self._profile.learn(user_id, text=text, entities=learn_entities)

        reply = str(result.get("reply") or "")
        recommendations = list(result.get("recommendations") or [])
        actions = list(result.get("actions") or [])
        intent = result.get("intent")

        msgs = self._messages.setdefault(session_id, [])
        msgs.append({"role": "user", "content": text})
        msgs.append({"role": "assistant", "content": reply})
        rec.updated_at = datetime.now(timezone.utc).isoformat()

        now_iso = datetime.now(timezone.utc).isoformat()
        return {
            "userMessage": {"messageId": "msg_" + uuid.uuid4().hex[:12], "role": "user",
                            "contentType": "text", "content": text, "createdAt": now_iso},
            "assistantMessage": {"messageId": "msg_" + uuid.uuid4().hex[:12], "role": "assistant",
                                 "contentType": "text", "content": reply, "createdAt": now_iso},
            "intent": intent,
            "recommendations": recommendations,
            "actions": actions,
            "degraded": bool(result.get("degraded")),
        }

    def list_messages(self, session_id: str, page: int = 1, size: int = 50) -> dict[str, Any]:
        msgs = self._messages.get(session_id, [])
        start = (page - 1) * size
        return {
            "page": page,
            "size": size,
            "total": len(msgs),
            "items": msgs[start:start + size],
        }

    def close_session(self, session_id: str) -> dict[str, Any]:
        rec = self._sessions.get(session_id)
        if rec is None:
            # 幂等：网关可能关闭 Agent 未见过的会话（未发过消息/已重启）
            return {
                "sessionId": session_id,
                "status": "ENDED",
            }
        rec.status = "ENDED"
        rec.updated_at = datetime.now(timezone.utc).isoformat()
        return self._session_dict(rec)

    def get_profile(self, user_id: str) -> dict[str, Any]:
        """读取用户画像（调试/演示用）。"""
        return self._profile.load(user_id).to_dict()

    def _sync_purchase_history(self, user_id: str, profile: UserProfile) -> None:
        """拉取最近订单入画像（尽力而为，24h 缓存；失败不影响对话）。"""
        if self._java is None or not self._profile.orders_stale(profile):
            return
        try:
            orders = _run_async(self._fetch_recent_orders(), timeout=5.0)
        except Exception as exc:  # noqa: BLE001 —— 购买历史同步失败不阻断对话
            logger.warning("purchase_sync_failed", user_id=user_id, error=repr(exc))
            return
        if orders:
            self._profile.record_orders(user_id, orders)

    async def _fetch_recent_orders(self) -> list[dict[str, Any]]:
        """取最近 2 笔订单 + 条目名（先列表后详情，条目名截断）。"""
        token = self._auth_token or None
        page = await self._java.list_orders(page=1, size=2, user_token=token)
        summaries: list[dict[str, Any]] = []
        for order in (page or {}).get("items") or []:
            if not isinstance(order, dict):
                continue
            item_names: list[str] = []
            order_id = order.get("orderId")
            if order_id:
                try:
                    detail = await self._java.get_order(order_id=str(order_id), user_token=token)
                except Exception:  # noqa: BLE001 —— 详情失败只记订单摘要
                    detail = None
                for item in (detail or {}).get("items") or []:
                    name = str(item.get("productName") or item.get("name") or "").strip()
                    if name:
                        item_names.append(name[:20])
            summaries.append(
                {
                    "orderNo": order.get("orderNo"),
                    "status": order.get("status"),
                    "totalCent": order.get("totalCent"),
                    "createdAt": order.get("createdAt"),
                    "items": item_names,
                }
            )
        return summaries

    def _session_dict(self, rec: SessionRecord) -> dict[str, Any]:
        return {
            "sessionId": rec.session_id,
            "userId": rec.user_id,
            "status": rec.status,
            "entry": rec.entry,
            "source": rec.source,
            "locale": rec.locale,
            "welcomeMessage": rec.welcome_message,
            "createdAt": rec.created_at,
            "updatedAt": rec.updated_at,
        }
