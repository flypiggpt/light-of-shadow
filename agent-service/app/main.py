"""FastAPI 入口：lifespan、路由注册、请求 ID、全局异常与优雅关闭。"""

from __future__ import annotations

import re
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import redis.asyncio as aioredis
import structlog
from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse
from starlette.datastructures import MutableHeaders
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.api import health, shoppingguide
from app.config import Settings, get_settings
from app.logging import configure_logging, get_logger

REQUEST_ID_HEADER = "X-Request-ID"
_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
_CREDENTIALS_RE = re.compile(r"//[^@/]+@")

logger = get_logger(__name__)


def _pick_request_id(scope: Scope) -> str:
    """取上游传入的 request_id（非法则重新生成），保证可贯穿全链路日志。"""
    for key, value in scope.get("headers", ()):
        if key.lower() == b"x-request-id":
            candidate = value.decode("latin-1")
            if _REQUEST_ID_RE.fullmatch(candidate):
                return candidate
            break
    return uuid.uuid4().hex


def _mask_credentials(url: str) -> str:
    """隐去 URL 中的用户名/密码，避免密钥进日志。"""
    return _CREDENTIALS_RE.sub("//***@", url)


def _envelope(code: int, message: str, data: Any = None) -> dict[str, Any]:
    return {"code": code, "message": message, "data": data}


def _request_id_of(request: Request) -> str | None:
    return getattr(request.state, "request_id", None)


class RequestContextMiddleware:
    """请求 ID 中间件：生成/透传 request_id，注入日志上下文并回写响应头；顺带记录 HTTP 指标。"""

    def __init__(self, app: ASGIApp, metrics: Any = None) -> None:
        self.app = app
        self._metrics = metrics

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_id = _pick_request_id(scope)
        scope.setdefault("state", {})["request_id"] = request_id
        structlog.contextvars.bind_contextvars(request_id=request_id)
        started = time.perf_counter()
        status_code = 500

        async def send_wrapper(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
                MutableHeaders(scope=message)[REQUEST_ID_HEADER] = request_id
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            latency_s = time.perf_counter() - started
            logger.info(
                "http_request",
                method=scope.get("method", ""),
                path=scope.get("path", ""),
                status_code=status_code,
                latency_ms=round(latency_s * 1000, 2),
            )
            if self._metrics is not None:
                try:
                    self._metrics.observe_http(
                        scope.get("method", ""), scope.get("path", ""), status_code, latency_s
                    )
                except Exception:  # noqa: BLE001 —— 指标失败不影响请求
                    pass
            structlog.contextvars.clear_contextvars()


# ---------------------------------------------------------------------------
# 全局异常处理（统一响应格式，不向客户端泄漏堆栈）
# ---------------------------------------------------------------------------


async def _http_exception_handler(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    detail = exc.detail if isinstance(exc.detail, str) else "请求失败"
    data = None if isinstance(exc.detail, str) else jsonable_encoder(exc.detail)
    headers = dict(exc.headers or {})
    if (rid := _request_id_of(request)) is not None:
        headers[REQUEST_ID_HEADER] = rid
    return JSONResponse(
        status_code=exc.status_code,
        content=_envelope(exc.status_code, detail, data),
        headers=headers,
    )


async def _validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    errors = [
        {"loc": list(err.get("loc", ())), "msg": err.get("msg", ""), "type": err.get("type", "")}
        for err in exc.errors()
    ]
    logger.warning("request_validation_failed", errors=errors)
    headers: dict[str, str] = {}
    if (rid := _request_id_of(request)) is not None:
        headers[REQUEST_ID_HEADER] = rid
    return JSONResponse(
        status_code=422,
        content=_envelope(422, "请求参数校验失败", errors),
        headers=headers,
    )


async def _unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    rid = _request_id_of(request)
    logger.error(
        "unhandled_exception",
        request_id=rid,
        path=request.url.path,
        error=repr(exc),
        exc_info=True,
    )
    return JSONResponse(
        status_code=500,
        content=_envelope(500, "服务内部错误"),
        headers={REQUEST_ID_HEADER: rid} if rid else None,
    )


# ---------------------------------------------------------------------------
# lifespan：启动初始化 / 优雅关闭
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings: Settings = get_settings()
    redis: aioredis.Redis[str] = aioredis.from_url(
        settings.redis_url,
        encoding="utf-8",
        decode_responses=True,
        socket_connect_timeout=settings.redis_socket_timeout_s,
        socket_timeout=settings.redis_socket_timeout_s,
    )
    app.state.settings = settings
    app.state.redis = redis

    # 初始化 Agent 编排（LLM + 工具 + 状态机）
    from app.agent.service import AgentOrchestrator
    from app.agent.graph import AgentDeps
    from app.llm.deepseek import DeepSeekClient
    from app.tools.search_products import SearchProductsTool
    from app.tools.get_product import GetProductTool
    from app.tools.add_cart import AddCartTool
    from app.tools.compare_products import CompareProductsTool
    from app.tools.faq import FaqTool
    from app.tools.query_orders import QueryOrdersTool
    from app.tools.track_order import TrackOrderTool
    from app.tools.cancel_order import CancelOrderTool
    from app.tools.delete_order import DeleteOrderTool
    from app.clients.java_api import JavaApiClient

    internal_token = settings.internal_token.get_secret_value() if settings.internal_token else ""
    java_client = JavaApiClient(
        base_url=settings.java_api_base_url,
        internal_token=internal_token,
    )
    llm_api_key = settings.llm_api_key.get_secret_value() if settings.llm_api_key else ""

    # 观测：Prometheus 指标 + Langfuse trace（关闭时 NoOp 无损降级）
    from app.obs.langfuse import LangfuseConfig, Tracer

    metrics = app.state.metrics
    tracer = Tracer(
        LangfuseConfig(
            enabled=settings.langfuse_enabled,
            public_key=settings.langfuse_public_key.get_secret_value() or None,
            secret_key=settings.langfuse_secret_key.get_secret_value() or None,
            host=settings.langfuse_host,
            metrics=metrics,
        )
    )

    # FAQ RAG 检索器（生产主路径；构建失败回退词表）
    from app.rag.loader import build_faq_retriever

    try:
        faq_retriever = build_faq_retriever()
    except Exception as exc:  # noqa: BLE001 —— 语料问题不阻断启动，FAQ 走词表兜底
        logger.warning("faq_retriever_build_failed", error=repr(exc))
        faq_retriever = None

    tools = {
        "search_products": SearchProductsTool(java_client),
        "get_product": GetProductTool(java_client),
        "add_cart": AddCartTool(java_client),
        "compare_products": CompareProductsTool(java_client),
        "faq": FaqTool(java_client, retriever=faq_retriever),
        "query_orders": QueryOrdersTool(java_client),
        "track_order": TrackOrderTool(java_client),
        "cancel_order": CancelOrderTool(java_client),
        "delete_order": DeleteOrderTool(java_client),
    }
    from app.agent.service import LLMAdapter, ToolExecutorAdapter
    deps = AgentDeps(
        llm=LLMAdapter(settings.llm_base_url, llm_api_key, settings.llm_model, metrics=metrics),
        tools=ToolExecutorAdapter(tools, metrics=metrics),
    )

    # 用户画像存储（S1 记忆偏好）：Redis 优先，异常回退进程内内存
    from app.memory.profile import ProfileStore

    profile_store = ProfileStore()
    try:
        import redis as redis_lib

        sync_redis = redis_lib.Redis.from_url(
            settings.redis_url, socket_timeout=settings.redis_socket_timeout_s
        )
        sync_redis.ping()
        profile_store = ProfileStore(sync_redis)
    except Exception as exc:  # noqa: BLE001 —— 画像持久化不可用时内存兜底，不阻断启动
        logger.warning("profile_redis_unavailable", error=repr(exc))

    # LangGraph checkpoint：跨轮对话上下文/断点恢复（Redis 优先，失败回退内存）
    from app.memory.checkpoint import build_checkpointer

    checkpointer = build_checkpointer(redis_url=settings.redis_url)

    app.state.orchestrator = AgentOrchestrator(
        deps=deps, checkpointer=checkpointer, profile_store=profile_store,
        java_client=java_client, tracer=tracer,
    )
    app.state.java_client = java_client
    logger.info("agent_orchestrator_ready", tools=len(tools))
    logger.info(
        "service_start",
        env=settings.env,
        redis_url=_mask_credentials(settings.redis_url),
        java_api_base_url=settings.java_api_base_url,
    )
    try:
        yield
    finally:
        logger.info("service_stop_begin")
        try:
            await redis.aclose()
        except Exception as exc:  # noqa: BLE001 - 关闭阶段不让清理异常掩盖主流程
            logger.warning("redis_close_failed", error=repr(exc))
        logger.info("service_stop_done")


# ---------------------------------------------------------------------------
# 应用装配
# ---------------------------------------------------------------------------


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging(settings)

    from app.obs.metrics import MetricsRegistry

    metrics = MetricsRegistry()

    application = FastAPI(
        title="音影寻光 · 导购智能服务",
        description="音影寻光导购智能：会话编排、商品推荐与导购问答（Agent 服务）",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None if settings.is_prod else "/docs",
        redoc_url=None if settings.is_prod else "/redoc",
        openapi_url=None if settings.is_prod else "/openapi.json",
    )
    application.state.metrics = metrics
    application.add_middleware(RequestContextMiddleware, metrics=metrics)
    application.add_exception_handler(StarletteHTTPException, _http_exception_handler)
    application.add_exception_handler(RequestValidationError, _validation_exception_handler)
    application.add_exception_handler(Exception, _unhandled_exception_handler)
    application.include_router(health.router)
    application.include_router(shoppingguide.router)

    @application.get("/metrics", include_in_schema=False)
    def metrics_endpoint() -> PlainTextResponse:
        return PlainTextResponse(metrics.render_prometheus())

    return application


app = create_app()
