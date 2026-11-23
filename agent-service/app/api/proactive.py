"""主动提议内部接口（契约见 docs/proactive-agent-plan.md §6.2）。

Java 网关调用，不直接暴露给客户端：
- POST /internal/proactive/prepare：生成提议并 interrupt 挂起
- POST /internal/proactive/confirm：从 checkpoint 恢复并执行确认动作
- POST /internal/proactive/discard：丢弃挂起态
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import Field

from app.api import ApiResponse, CamelModel

router = APIRouter(prefix="/internal/proactive", tags=["proactive"])


class PrepareRequest(CamelModel):
    signal_id: str
    type: str
    facts: dict[str, Any] = Field(default_factory=dict)
    user_id: str = ""


class ConfirmRequest(CamelModel):
    signal_id: str
    action_id: str
    user_id: str = ""


class DiscardRequest(CamelModel):
    signal_id: str


class ProposalData(CamelModel):
    signal_id: str
    message: str
    actions: list[dict[str, Any]] = Field(default_factory=list)
    thread_id: str = ""


class ConfirmData(CamelModel):
    signal_id: str
    action_id: str
    reply: str = ""
    executed: bool = False
    result: Any = None


class DiscardData(CamelModel):
    signal_id: str
    status: str = "DISCARDED"


def _auth_token(request: Request) -> str:
    auth = request.headers.get("Authorization", "")
    return auth[7:] if auth.startswith("Bearer ") else ""


@router.post(
    "/prepare",
    response_model=ApiResponse[ProposalData],
    summary="生成主动提议并挂起",
)
async def prepare(body: PrepareRequest, request: Request) -> ApiResponse[ProposalData]:
    orch = request.app.state.orchestrator
    user_id = body.user_id or request.headers.get("X-User-Id", "anonymous")
    try:
        data = orch.prepare_signal(signal_id=body.signal_id, signal_type=body.type,
                                   facts=body.facts, user_id=user_id)
    except Exception as exc:  # noqa: BLE001 —— 挂起失败让调用方暂缓发送
        raise HTTPException(status_code=500, detail=f"提议生成失败: {exc}")
    return ApiResponse(data=data)


@router.post(
    "/confirm",
    response_model=ApiResponse[ConfirmData],
    summary="确认主动提议并执行动作",
)
async def confirm(body: ConfirmRequest, request: Request) -> ApiResponse[ConfirmData]:
    orch = request.app.state.orchestrator
    user_id = body.user_id or request.headers.get("X-User-Id", "anonymous")
    try:
        data = orch.confirm_signal(signal_id=body.signal_id, action_id=body.action_id,
                                   user_id=user_id, user_token=_auth_token(request))
    except KeyError:
        raise HTTPException(status_code=404, detail="提议不存在或已处理")
    except ValueError:
        raise HTTPException(status_code=400, detail="提议状态不可确认")
    except Exception as exc:  # noqa: BLE001 —— 工具执行失败带回降级话术而不是 500
        raise HTTPException(status_code=500, detail=f"确认执行失败: {exc}")
    return ApiResponse(data=data)


@router.post(
    "/discard",
    response_model=ApiResponse[DiscardData],
    summary="丢弃主动提议",
)
async def discard(body: DiscardRequest, request: Request) -> ApiResponse[DiscardData]:
    orch = request.app.state.orchestrator
    return ApiResponse(data=orch.discard_signal(signal_id=body.signal_id))
