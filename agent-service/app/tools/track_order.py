"""查订单详情工具：单号/ID 查订单状态与商品条目（物流跟踪入口）。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext, ToolExecutionError


class TrackOrderArgs(BaseModel):
    """订单详情参数。"""

    order_id: str = Field(min_length=1, max_length=64, description="订单 ID 或单号")


class TrackOrderTool(BaseTool):
    name = "track_order"
    description = "按订单 ID/单号查询订单详情：状态、金额、商品条目（查物流/看进度用这个）"
    args_model = TrackOrderArgs

    def __init__(self, client: JavaApiClient | None = None, **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()

    async def _invoke(self, args: TrackOrderArgs, ctx: ToolContext) -> Any:
        if not ctx.user_token:
            raise ToolExecutionError("缺少用户登录态，无法查询订单")
        return await self._client.get_order(
            order_id=args.order_id,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
