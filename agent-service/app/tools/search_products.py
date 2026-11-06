"""商品搜索工具：按关键词分页搜索商品。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext, ToolExecutionError


class SearchProductsArgs(BaseModel):
    """搜索参数，字段白名单，长度受限。"""

    keyword: str = Field(default="", max_length=100, description="搜索关键词，可为空")
    page: int = Field(default=1, ge=1, description="页码，从 1 开始")
    size: int = Field(default=10, ge=1, le=50, description="每页条数，最大 50")


class SearchProductsTool(BaseTool):
    name = "search_products"
    description = "按关键词搜索商品，返回分页商品列表（名称、价格、库存、封面）"
    args_model = SearchProductsArgs

    def __init__(self, client: JavaApiClient | None = None, **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()

    async def _invoke(self, args: SearchProductsArgs, ctx: ToolContext) -> Any:
        if not ctx.user_token:
            raise ToolExecutionError("缺少用户登录态，无法搜索商品")
        return await self._client.search_products(
            keyword=args.keyword,
            page=args.page,
            size=args.size,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
