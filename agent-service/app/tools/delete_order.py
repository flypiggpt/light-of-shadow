"""删除订单工具：删除订单记录（待支付先恢复库存）。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext, ToolExecutionError


class DeleteOrderArgs(BaseModel):
    """删除订单参数。"""

    order_id: str = Field(min_length=1, max_length=64, description="要删除的订单 ID")


class DeleteOrderTool(BaseTool):
    name = "delete_order"
    description = "删除一笔订单记录（任意状态；待支付会先恢复库存）；删除后不可恢复"
    args_model = DeleteOrderArgs

    def __init__(self, client: JavaApiClient | None = None, **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()

    async def _invoke(self, args: DeleteOrderArgs, ctx: ToolContext) -> Any:
        if not ctx.user_token:
            raise ToolExecutionError("缺少用户登录态，无法删除订单")
        return await self._client.delete_order(
            order_id=args.order_id,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
