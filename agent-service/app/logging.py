"""structlog 结构化日志：JSON（prod）/ 彩色控制台（dev），日志携带 request_id。

request_id 通过 structlog contextvars 注入（见 app.main.RequestContextMiddleware），
所有经 get_logger() 取得的 logger 输出都会自动带上 request_id 字段。
"""

from __future__ import annotations

import logging
import sys
from typing import Any

import structlog

from app.config import Settings

_UVICORN_LOGGERS = ("uvicorn", "uvicorn.error", "uvicorn.access")


def _build_processors(use_json: bool) -> tuple[list[Any], Any]:
    shared: list[Any] = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True, key="timestamp"),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
    ]
    if use_json:
        renderer: Any = structlog.processors.JSONRenderer(ensure_ascii=False)
    else:
        renderer = structlog.dev.ConsoleRenderer(colors=sys.stderr.isatty())
    return shared, renderer


def configure_logging(settings: Settings) -> None:
    """按环境配置 structlog 与标准库 logging，二者共用同一渲染管线。"""
    level = getattr(logging, settings.log_level, logging.INFO)
    use_json = settings.resolved_log_format == "json"
    shared, renderer = _build_processors(use_json)

    formatter = structlog.stdlib.ProcessorFormatter(
        foreign_pre_chain=shared,
        processors=[
            structlog.stdlib.ProcessorFormatter.remove_processors_meta,
            renderer,
        ],
    )
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(formatter)

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level)

    # uvicorn 自带 handler，接入同一格式并关闭重复传播
    for name in _UVICORN_LOGGERS:
        uv_logger = logging.getLogger(name)
        uv_logger.handlers.clear()
        uv_logger.addHandler(handler)
        uv_logger.setLevel(level)
        uv_logger.propagate = False

    structlog.configure(
        processors=[*shared, structlog.stdlib.ProcessorFormatter.wrap_for_formatter],
        wrapper_class=structlog.stdlib.BoundLogger,
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str, **initial_values: Any) -> structlog.stdlib.BoundLogger:
    """获取结构化 logger。"""
    return structlog.get_logger(name, **initial_values)
