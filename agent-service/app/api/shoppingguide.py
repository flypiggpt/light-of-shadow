"""导购接口（契约见 docs/api/shoppingguide_api.md）。

网关模式下本服务只承载 Agent 侧端点：会话创建/详情、发消息、关闭、用户画像。
消息历史 / 推荐翻页 / 商品对比 / 反馈由 Java 网关实现（历史落库在 Java），
本服务不再保留对应 501 占位。
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from fastapi import Request, APIRouter, HTTPException
from pydantic import Field

from app.api import ApiResponse, CamelModel

router = APIRouter(prefix="/api/shoppingguide", tags=["shoppingguide"])

SessionStatus = Literal["ACTIVE", "ENDED"]
MessageRole = Literal["user", "assistant", "system"]
MessageContentType = Literal["text", "product_list", "action", "error"]
ActionType = Literal[
    "add_to_cart",
    "update_cart",
    "remove_from_cart",
    "checkout",
    "refine_query",
    "show_more",
    "compare",
    "view_cart",
]


# ---------------------------------------------------------------------------
# 数据模型（字段命名 camelCase，与契约一致）
# ---------------------------------------------------------------------------


class SessionInfo(CamelModel):
    session_id: str
    status: SessionStatus
    created_at: datetime
    updated_at: datetime
    user_id: str | None = None
    summary: str | None = None
    last_user_message: str | None = None


class Recommendation(CamelModel):
    product_id: str
    name: str
    description: str
    price_cent: int
    stock: int
    cover_url: str
    score: float = Field(ge=0.0, le=1.0)
    reason: str


class Action(CamelModel):
    type: ActionType
    label: str
    product_id: str | None = None
    quantity: int | None = Field(default=None, ge=1)


class Message(CamelModel):
    message_id: str
    role: MessageRole
    content_type: MessageContentType
    content: str
    created_at: datetime
    session_id: str | None = None


class Intent(CamelModel):
    name: str
    confidence: float = Field(ge=0.0, le=1.0)
    entities: dict[str, object] = Field(default_factory=dict)


# --- 请求体 ---


class CreateSessionRequest(CamelModel):
    entry: str = Field(default="shop", max_length=64)
    source: str | None = Field(default=None, max_length=64)
    locale: str = Field(default="zh-CN", max_length=32)


class SendMessageRequest(CamelModel):
    message: str = Field(min_length=1, max_length=2000)
    client_context: dict[str, object] | None = None






# --- 响应 data ---


class CreateSessionData(CamelModel):
    session: SessionInfo
    welcome_message: str


class SessionDetailData(CamelModel):
    session: SessionInfo
    latest_recommendations: list[Recommendation] = Field(default_factory=list)


class SendMessageData(CamelModel):
    user_message: Message
    assistant_message: Message
    intent: Intent | None = None
    recommendations: list[Recommendation] = Field(default_factory=list)
    actions: list[Action] = Field(default_factory=list)




class CloseSessionData(CamelModel):
    session_id: str
    status: SessionStatus










class RecentOrder(CamelModel):
    order_no: str | None = None
    status: str | None = None
    total_cent: int | None = None
    created_at: str | None = None
    items: list[str] = Field(default_factory=list)


class UserProfileData(CamelModel):
    """用户画像（S1 记忆偏好）：预算 / 偏好 / 品类 / 品牌 / 最近购买。"""

    budget_min_cent: int | None = None
    budget_max_cent: int | None = None
    categories: list[str] = Field(default_factory=list)
    preferences: list[str] = Field(default_factory=list)
    brands: list[str] = Field(default_factory=list)
    recent_orders: list[RecentOrder] = Field(default_factory=list)
    orders_synced_at: str | None = None
    updated_at: str | None = None


# ---------------------------------------------------------------------------
# 路由
# ---------------------------------------------------------------------------


@router.post(
    "/sessions",
    response_model=ApiResponse[CreateSessionData],
    summary="创建导购会话",
)
async def create_session(body: CreateSessionRequest, request: Request) -> ApiResponse[CreateSessionData]:
    orch = request.app.state.orchestrator
    user_id = request.headers.get("X-User-Id", "anonymous")
    data = orch.create_session(user_id=user_id, entry=body.entry or "shop",
                               source=body.source or "app", locale=body.locale or "zh-CN")
    return ApiResponse(data={"session": data, "welcomeMessage": data.get("welcomeMessage", "")})


@router.get(
    "/sessions/{session_id}",
    response_model=ApiResponse[SessionDetailData],
    summary="获取会话详情",
)
async def get_session(session_id: str, request: Request) -> ApiResponse[SessionDetailData]:
    orch = request.app.state.orchestrator
    data = orch.get_session(session_id)
    if data is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    return ApiResponse(data={"session": data, "latestRecommendations": []})


@router.post(
    "/sessions/{session_id}/messages",
    response_model=ApiResponse[SendMessageData],
    summary="发送用户消息并获取导购回复",
)
async def send_message(
    session_id: str, body: SendMessageRequest, request: Request
) -> ApiResponse[SendMessageData]:
    orch = request.app.state.orchestrator
    user_id = request.headers.get("X-User-Id", "anonymous")
    auth = request.headers.get("Authorization", "")
    token = auth[7:] if auth.startswith("Bearer ") else ""
    try:
        result = orch.send_message(session_id=session_id, user_id=user_id,
                                   text=body.message,
                                   request_id=request.headers.get("X-Request-ID", ""),
                                   client_context=body.client_context,
                                   user_token=token)
    except KeyError:
        raise HTTPException(status_code=404, detail="会话不存在")
    except ValueError:
        raise HTTPException(status_code=400, detail="会话已结束")
    return ApiResponse(data={
        "userMessage": result.get("userMessage"),
        "assistantMessage": result.get("assistantMessage"),
        "intent": result.get("intent"),
        "recommendations": result.get("recommendations", []),
        "actions": result.get("actions", []),
    })




@router.post(
    "/sessions/{session_id}/close",
    response_model=ApiResponse[CloseSessionData],
    summary="结束会话",
)
async def close_session(session_id: str, request: Request) -> ApiResponse[CloseSessionData]:
    orch = request.app.state.orchestrator
    # 幂等：未知会话也返回 ENDED（网关可能关闭 Agent 未见过的会话）
    return ApiResponse(data=orch.close_session(session_id))








@router.get(
    "/profile",
    response_model=ApiResponse[UserProfileData],
    summary="获取用户画像（记忆偏好）",
)
async def get_profile(request: Request) -> ApiResponse[UserProfileData]:
    orch = request.app.state.orchestrator
    user_id = request.headers.get("X-User-Id", "anonymous")
    return ApiResponse(data=UserProfileData.model_validate(orch.get_profile(user_id)))
