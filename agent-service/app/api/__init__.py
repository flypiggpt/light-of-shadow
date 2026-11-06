"""HTTP API 层：统一响应封装与路由模块。"""

from __future__ import annotations

from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

__all__ = ["ApiResponse", "CamelModel"]

T = TypeVar("T")


class CamelModel(BaseModel):
    """请求/响应模型统一 camelCase，与 docs/api/shoppingguide_api.md 契约一致。"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ApiResponse(CamelModel, Generic[T]):
    """统一响应格式：code == 0 成功，code != 0 业务失败（见接口契约 1.1）。"""

    code: int = 0
    message: str = "ok"
    data: T | None = None
