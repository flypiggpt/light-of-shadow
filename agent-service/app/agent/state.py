"""LangGraph 状态机状态定义与协作协议。

状态字段全部 JSON 友好，便于 Redis checkpoint 序列化。
`messages` 通道使用自定义 reducer：追加 + 硬裁剪，防止状态无限膨胀。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Annotated, Any, Mapping, Protocol, Sequence, TypedDict

from ..memory.window import MessageWindow, WindowPolicy, merge_and_trim

__all__ = [
    "AgentState",
    "IntentInfo",
    "ToolResult",
    "LLMClient",
    "ToolExecutor",
    "AgentDeps",
    "last_user_text",
]

# 写入 checkpoint 时的消息硬上限（追加后自动裁剪）。
_HARD_WINDOW = WindowPolicy(hard_max_messages=200)


def _merge_messages(
    old: Sequence[Mapping[str, Any]] | None,
    new: Sequence[Mapping[str, Any]] | None,
) -> list[Mapping[str, Any]]:
    return merge_and_trim(old, new, _HARD_WINDOW)


class IntentInfo(TypedDict, total=False):
    """意图识别结果（对齐 docs/api/shoppingguide_api.md 的 intent 对象）。"""

    name: str
    confidence: float
    entities: dict[str, Any]


class AgentState(TypedDict, total=False):
    """单轮状态机的完整状态。"""

    # ---- 输入 ----
    session_id: str
    user_id: str
    request_id: str
    client_context: dict[str, Any]
    # 用户画像（跨会话偏好：预算/品类/偏好/最近购买），每轮由编排层注入
    user_profile: dict[str, Any]
    # 请求级用户 token（工具调 Java 用），随状态流转，不共享可变字段
    user_token: str

    # ---- 对话历史（reducer：追加后硬裁剪）----
    messages: Annotated[Sequence[Mapping[str, Any]], _merge_messages]

    # ---- 意图与追问 ----
    intent: IntentInfo
    entities: dict[str, Any]
    needs_clarify: bool
    missing_slots: list[str]
    clarify_round: int
    clarify_question: str

    # ---- 工具 ----
    tool_rounds: int
    tool_calls: list[dict[str, Any]]
    tool_results: list[dict[str, Any]]
    more_tool_calls: bool

    # ---- 输出（对齐 API 响应）----
    reply: str
    content_type: str  # text | product_list | action | error
    recommendations: list[dict[str, Any]]
    actions: list[dict[str, Any]]

    # ---- 运行标记 ----
    degraded: bool
    error: str | None
    injection_suspected: bool


@dataclass
class ToolResult:
    """工具执行结果。"""

    ok: bool
    data: Any = None
    error: str | None = None


class LLMClient(Protocol):
    """LLM 客户端协议（app.llm 实现，节点只依赖此窄接口）。"""

    def chat(
        self,
        messages: Sequence[Mapping[str, str]],
        *,
        timeout: float | None = None,
    ) -> str: ...


class ToolExecutor(Protocol):
    """工具执行器协议（app.tools 实现）。"""

    def available(self) -> Sequence[str]: ...

    def invoke(
        self,
        name: str,
        arguments: Mapping[str, Any],
        *,
        timeout: float | None = None,
        user_token: str | None = None,
    ) -> ToolResult: ...


@dataclass
class AgentDeps:
    """节点依赖（依赖注入，便于单测替换）。"""

    llm: LLMClient
    tools: ToolExecutor
    window: MessageWindow = field(default_factory=MessageWindow)
    max_clarify_rounds: int = 2
    max_tool_rounds: int = 2
    llm_timeout: float = 30.0
    tool_timeout: float = 10.0


def last_user_text(state: Mapping[str, Any]) -> str:
    """取最近一条用户消息文本（兼容 dict 与消息对象）。"""
    for message in reversed(list(state.get("messages") or [])):
        role = message.get("role") if isinstance(message, Mapping) else getattr(message, "role", None)
        if role == "user":
            content = message.get("content") if isinstance(message, Mapping) else getattr(message, "content", "")
            return str(content or "")
    return ""
