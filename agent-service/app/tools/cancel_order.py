"""取消订单工具：仅待支付订单可取消（自动恢复库存）。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext, ToolExecutionError


class CancelOrderArgs(BaseModel):
    """取消订单参数。"""

    order_id: str = Field(min_length=1, max_length=64, description="要取消的订单 ID")


class CancelOrderTool(BaseTool):
    name = "cancel_order"
    description = "取消一笔待支付订单（PENDING_PAYMENT），库存自动恢复；已支付/已发货订单不可取消"
    args_model = CancelOrderArgs

    def __init__(self, client: JavaApiClient | None = None, **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()

    async def _invoke(self, args: CancelOrderArgs, ctx: ToolContext) -> Any:
        if not ctx.user_token:
            raise ToolExecutionError("缺少用户登录态，无法取消订单")
        return await self._client.cancel_order(
            order_id=args.order_id,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
