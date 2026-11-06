"""加购物车工具。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext, ToolExecutionError


class AddCartArgs(BaseModel):
    """加购参数。"""

    product_id: str = Field(min_length=1, max_length=64, description="商品 ID")
    quantity: int = Field(default=1, ge=1, le=99, description="加购数量，1-99")


class AddCartTool(BaseTool):
    name = "add_cart"
    description = "把指定商品加入购物车，返回购物车项 ID"
    args_model = AddCartArgs

    def __init__(self, client: JavaApiClient | None = None, **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()

    async def _invoke(self, args: AddCartArgs, ctx: ToolContext) -> Any:
        if not ctx.user_token:
            raise ToolExecutionError("缺少用户登录态，无法加入购物车")
        return await self._client.add_cart_item(
            product_id=args.product_id,
            quantity=args.quantity,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
