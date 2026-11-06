"""商品详情工具：按商品 ID 查询详情。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext


class GetProductArgs(BaseModel):
    """商品详情参数。"""

    product_id: str = Field(min_length=1, max_length=64, description="商品 ID")


class GetProductTool(BaseTool):
    name = "get_product"
    description = "按商品 ID 查询商品详情（名称、描述、价格、库存、封面）"
    args_model = GetProductArgs

    def __init__(self, client: JavaApiClient | None = None, **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()

    async def _invoke(self, args: GetProductArgs, ctx: ToolContext) -> Any:
        # 商品详情无需登录（shop_api.md），token 有则透传
        return await self._client.get_product(
            product_id=args.product_id,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
