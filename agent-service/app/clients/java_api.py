"""Java 内部 API 客户端。

httpx 异步封装，负责调用 Java 单体的商城/导购接口：
- 服务间调用带内部 token（X-Internal-Token），用户态调用透传 Authorization
- 超时 + 指数退避重试（仅网络/5xx/429 等瞬时故障）
- 解包统一响应 {code, message, data}，业务失败抛 JavaApiBusinessError
- structlog 日志含每次调用耗时
"""

from __future__ import annotations

import asyncio
import os
import random
import time
from typing import Any

import httpx
import structlog

logger = structlog.get_logger(__name__)

__all__ = [
    "JavaApiError",
    "JavaApiTimeoutError",
    "JavaApiConnectionError",
    "JavaApiResponseError",
    "JavaApiBusinessError",
    "JavaApiClient",
]


class JavaApiError(Exception):
    """Java 内部 API 调用失败基类。"""


class JavaApiTimeoutError(JavaApiError):
    """请求超时（重试耗尽后仍失败）。"""


class JavaApiConnectionError(JavaApiError):
    """网络连接失败（重试耗尽后仍失败）。"""


class JavaApiResponseError(JavaApiError):
    """响应不是预期的 JSON 结构。"""


class JavaApiBusinessError(JavaApiError):
    """Java 侧业务失败（code != 0 或 HTTP 4xx/5xx 落到统一响应）。"""

    def __init__(self, code: int, message: str, status_code: int) -> None:
        self.code = code
        self.message = message
        self.status_code = status_code
        super().__init__(f"Java API 业务失败 code={code} http={status_code} message={message}")


class JavaApiClient:
    """调 Java 内部 API 的异步客户端，连接池复用，建议全局单例。"""

    def __init__(
        self,
        base_url: str | None = None,
        internal_token: str | None = None,
        *,
        timeout_s: float = 10.0,
        max_retries: int = 2,
        backoff_base_s: float = 0.5,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = (
            base_url or os.getenv("JAVA_API_BASE_URL", "http://localhost:8080")
        ).rstrip("/")
        self._internal_token = (
            internal_token if internal_token is not None else os.getenv("JAVA_INTERNAL_TOKEN", "")
        )
        self._timeout_s = timeout_s
        self._max_retries = max_retries
        self._backoff_base_s = backoff_base_s
        self._client = httpx.AsyncClient(
            base_url=self._base_url,
            timeout=httpx.Timeout(self._timeout_s, connect=min(5.0, self._timeout_s)),
            transport=transport,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> JavaApiClient:
        return self

    async def __aexit__(self, *exc_info: object) -> None:
        await self.aclose()

    # ------------------------------------------------------------------
    # 业务方法（与 docs/api/shop_api.md、shoppingguide_api.md 对齐）
    # ------------------------------------------------------------------

    async def search_products(
        self,
        *,
        keyword: str = "",
        page: int = 1,
        size: int = 20,
        user_token: str | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        """GET /api/shop/products 商品列表/关键词搜索，返回分页数据。"""
        params: dict[str, Any] = {"page": page, "size": size}
        if keyword:
            params["keyword"] = keyword
        return await self._request_json(
            "GET",
            "/api/shop/products",
            params=params,
            user_token=user_token,
            request_id=request_id,
        )

    async def get_product(
        self,
        *,
        product_id: str,
        user_token: str | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        """GET /api/shop/products/{id} 商品详情。"""
        return await self._request_json(
            "GET",
            f"/api/shop/products/{product_id}",
            user_token=user_token,
            request_id=request_id,
        )

    async def add_cart_item(
        self,
        *,
        product_id: str,
        quantity: int = 1,
        user_token: str | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        """POST /api/shop/cart/items 加购物车，返回 {itemId, ...}。"""
        return await self._request_json(
            "POST",
            "/api/shop/cart/items",
            json_body={"productId": product_id, "quantity": quantity},
            user_token=user_token,
            request_id=request_id,
        )

    async def compare_products(
        self,
        *,
        product_ids: list[str],
        session_id: str | None = None,
        user_token: str | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        """POST /api/shoppingguide/compare 多商品导购对比。"""
        json_body: dict[str, Any] = {"productIds": product_ids}
        if session_id:
            json_body["sessionId"] = session_id
        return await self._request_json(
            "POST",
            "/api/shoppingguide/compare",
            json_body=json_body,
            user_token=user_token,
            request_id=request_id,
        )

    async def list_orders(
        self,
        *,
        page: int = 1,
        size: int = 20,
        status: str | None = None,
        user_token: str | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        """GET /api/shop/orders 我的订单列表（购买历史来源）。"""
        params: dict[str, Any] = {"page": page, "size": size}
        if status:
            params["status"] = status
        return await self._request_json(
            "GET",
            "/api/shop/orders",
            params=params,
            user_token=user_token,
            request_id=request_id,
        )

    async def get_order(
        self,
        *,
        order_id: str,
        user_token: str | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        """GET /api/shop/orders/{id} 订单详情（含商品条目）。"""
        return await self._request_json(
            "GET",
            f"/api/shop/orders/{order_id}",
            user_token=user_token,
            request_id=request_id,
        )

    async def cancel_order(
        self,
        *,
        order_id: str,
        user_token: str | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        """POST /api/shop/orders/{id}/cancel 取消待支付订单（自动恢复库存）。"""
        return await self._request_json(
            "POST",
            f"/api/shop/orders/{order_id}/cancel",
            user_token=user_token,
            request_id=request_id,
        )

    # ------------------------------------------------------------------
    # 内部实现
    # ------------------------------------------------------------------

    def _headers(self, user_token: str | None, request_id: str | None) -> dict[str, str]:
        headers: dict[str, str] = {}
        if self._internal_token:
            headers["X-Internal-Token"] = self._internal_token
        if user_token:
            headers["Authorization"] = f"Bearer {user_token}"
        if request_id:
            headers["X-Request-Id"] = request_id
        return headers

    async def _request_json(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
        user_token: str | None = None,
        request_id: str | None = None,
    ) -> Any:
        headers = self._headers(user_token, request_id)
        attempts = self._max_retries + 1
        last_error: JavaApiError | None = None

        for attempt in range(1, attempts + 1):
            start = time.perf_counter()
            try:
                resp = await self._client.request(
                    method, path, params=params, json=json_body, headers=headers
                )
            except httpx.TimeoutException as exc:
                latency_ms = (time.perf_counter() - start) * 1000
                logger.warning(
                    "java_api.timeout",
                    method=method,
                    path=path,
                    attempt=attempt,
                    latency_ms=round(latency_ms, 1),
                )
                last_error = JavaApiTimeoutError(f"Java API 超时: {method} {path}")
                last_error.__cause__ = exc
            except httpx.TransportError as exc:
                latency_ms = (time.perf_counter() - start) * 1000
                logger.warning(
                    "java_api.transport_error",
                    method=method,
                    path=path,
                    attempt=attempt,
                    latency_ms=round(latency_ms, 1),
                    error=str(exc),
                )
                last_error = JavaApiConnectionError(f"Java API 连接失败: {method} {path}")
                last_error.__cause__ = exc
            else:
                latency_ms = (time.perf_counter() - start) * 1000
                if resp.status_code == 429 or resp.status_code >= 500:
                    logger.warning(
                        "java_api.retryable_status",
                        method=method,
                        path=path,
                        status=resp.status_code,
                        attempt=attempt,
                        latency_ms=round(latency_ms, 1),
                    )
                    last_error = JavaApiBusinessError(
                        code=resp.status_code,
                        message=resp.text[:200],
                        status_code=resp.status_code,
                    )
                else:
                    logger.info(
                        "java_api.request",
                        method=method,
                        path=path,
                        status=resp.status_code,
                        attempt=attempt,
                        latency_ms=round(latency_ms, 1),
                    )
                    return self._unwrap(resp, path)

            if attempt < attempts:
                delay = self._backoff_base_s * (2 ** (attempt - 1)) + random.uniform(0, 0.1)
                await asyncio.sleep(delay)

        assert last_error is not None
        raise last_error

    @staticmethod
    def _unwrap(resp: httpx.Response, path: str) -> Any:
        try:
            payload = resp.json()
        except ValueError as exc:
            raise JavaApiResponseError(f"Java API 返回非法 JSON: {path}") from exc
        if not isinstance(payload, dict) or "code" not in payload:
            raise JavaApiResponseError(f"Java API 响应缺少 code 字段: {path}")
        code = payload.get("code")
        message = payload.get("message") or ""
        if resp.status_code >= 400 or code != 0:
            raise JavaApiBusinessError(
                code=int(code) if isinstance(code, int) else resp.status_code,
                message=message,
                status_code=resp.status_code,
            )
        return payload.get("data")
