"""商品对比工具：多商品导购式对比。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext


class CompareProductsArgs(BaseModel):
    """对比参数。"""

    product_ids: list[str] = Field(
        min_length=2, max_length=5, description="要对比的商品 ID 列表，2-5 个"
    )
    session_id: str = Field(default="", max_length=64, description="导购会话 ID，可为空")


class CompareProductsTool(BaseTool):
    name = "compare_products"
    description = "对比 2-5 个商品的卖点与差异，返回结构化对比结果"
    args_model = CompareProductsArgs

    def __init__(self, client: JavaApiClient | None = None, **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()

    async def _invoke(self, args: CompareProductsArgs, ctx: ToolContext) -> Any:
        # 去重并保持顺序，防止重复 ID 干扰对比
        product_ids = list(dict.fromkeys(args.product_ids))
        return await self._client.compare_products(
            product_ids=product_ids,
            session_id=args.session_id or None,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
