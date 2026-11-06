"""查订单工具：分页查询当前用户的订单列表。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext, ToolExecutionError


class QueryOrdersArgs(BaseModel):
    """查订单参数。"""

    page: int = Field(default=1, ge=1, description="页码，从 1 开始")
    size: int = Field(default=10, ge=1, le=50, description="每页条数，最大 50")
    status: str | None = Field(
        default=None,
        max_length=32,
        description="按状态过滤：PENDING_PAYMENT/PAID/SHIPPED/COMPLETED/CANCELED",
    )


class QueryOrdersTool(BaseTool):
    name = "query_orders"
    description = "查询当前用户的订单列表（单号、状态、金额、时间），可按状态过滤"
    args_model = QueryOrdersArgs

    def __init__(self, client: JavaApiClient | None = None, **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()

    async def _invoke(self, args: QueryOrdersArgs, ctx: ToolContext) -> Any:
        if not ctx.user_token:
            raise ToolExecutionError("缺少用户登录态，无法查询订单")
        return await self._client.list_orders(
            page=args.page,
            size=args.size,
            status=args.status,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
