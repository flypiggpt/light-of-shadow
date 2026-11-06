"""健康探针：/health（存活）与 /ready（就绪）。

探针响应为平台约定的扁平 JSON，不套用业务统一响应封装。
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.logging import get_logger

router = APIRouter(tags=["health"])
logger = get_logger(__name__)


@router.get("/health", summary="存活探针")
async def health() -> dict[str, str]:
    """进程存活即返回 200，不依赖外部组件。"""
    return {"status": "healthy"}


@router.get("/ready", summary="就绪探针")
async def ready(request: Request) -> JSONResponse:
    """就绪检查：Redis 可连通（会话 checkpoint 依赖）。失败返回 503。"""
    checks: dict[str, str] = {}
    is_ready = True

    redis: Any = getattr(request.app.state, "redis", None)
    if redis is None:
        checks["redis"] = "unset"
        is_ready = False
    else:
        try:
            await redis.ping()
            checks["redis"] = "ok"
        except Exception as exc:  # noqa: BLE001 - 探针需吞掉依赖错误并上报 503
            checks["redis"] = f"error:{type(exc).__name__}"
            is_ready = False
            logger.warning("ready_check_failed", check="redis", error=repr(exc))

    return JSONResponse(
        status_code=200 if is_ready else 503,
        content={"status": "ready" if is_ready else "not_ready", "checks": checks},
    )
