"""工具基类：pydantic 参数校验、单工具超时、失败重试一次、熔断。"""

from __future__ import annotations

import asyncio
import time
from abc import ABC, abstractmethod
from typing import Any, ClassVar

import structlog
from pydantic import BaseModel, ValidationError

logger = structlog.get_logger(__name__)

__all__ = [
    "ToolStatus",
    "ToolResult",
    "ToolContext",
    "ToolError",
    "ToolValidationError",
    "ToolTimeoutError",
    "ToolExecutionError",
    "BaseTool",
]


class ToolStatus:
    """工具执行状态常量。"""

    OK = "ok"
    ERROR = "error"
    TIMEOUT = "timeout"
    CIRCUIT_OPEN = "circuit_open"


class ToolError(Exception):
    """工具执行失败基类。"""


class ToolValidationError(ToolError):
    """参数校验失败。"""


class ToolTimeoutError(ToolError):
    """单次工具执行超时。"""


class ToolExecutionError(ToolError):
    """工具内部执行失败（下游 API 异常等）。"""


class ToolResult(BaseModel):
    """工具执行结果，总是返回而不上抛，供 Agent 层消解。"""

    status: str = ToolStatus.OK
    data: Any = None
    error: str | None = None
    latency_ms: float = 0.0

    @property
    def ok(self) -> bool:
        return self.status == ToolStatus.OK


class ToolContext(BaseModel):
    """调用上下文：请求链路与用户态信息。"""

    request_id: str = ""
    session_id: str = ""
    user_token: str | None = None


class BaseTool(ABC):
    """工具基类：子类声明 name/description/args_model 并实现 _invoke。"""

    name: ClassVar[str] = ""
    description: ClassVar[str] = ""
    args_model: ClassVar[type[BaseModel]]

    def __init__(
        self,
        *,
        timeout_s: float = 10.0,
        max_retries: int = 1,
        circuit_failure_threshold: int = 5,
        circuit_reset_s: float = 60.0,
    ) -> None:
        self.timeout_s = timeout_s
        self.max_retries = max_retries
        self.circuit_failure_threshold = circuit_failure_threshold
        self.circuit_reset_s = circuit_reset_s
        self._consecutive_failures = 0
        self._circuit_open_until = 0.0

    # ------------------------------------------------------------------
    # 对外入口
    # ------------------------------------------------------------------

    async def execute(self, ctx: ToolContext | None = None, **kwargs: Any) -> ToolResult:
        """校验参数后执行，统一超时/重试/熔断，永远返回 ToolResult。"""
        ctx = ctx or ToolContext()
        start = time.perf_counter()

        blocked = self._circuit_block_reason()
        if blocked is not None:
            logger.warning("tool.circuit_open", tool=self.name, error=blocked)
            return ToolResult(status=ToolStatus.CIRCUIT_OPEN, error=blocked)

        try:
            args = self.args_model.model_validate(kwargs)
        except ValidationError as exc:
            error = "; ".join(
                f"{'.'.join(str(p) for p in e['loc'])}: {e['msg']}" for e in exc.errors()
            )
            logger.warning("tool.invalid_args", tool=self.name, error=error)
            return ToolResult(status=ToolStatus.ERROR, error=f"参数校验失败: {error}")

        attempts = self.max_retries + 1
        last_error: str = ""
        status = ToolStatus.ERROR
        for attempt in range(1, attempts + 1):
            try:
                data = await asyncio.wait_for(
                    self._invoke(args, ctx), timeout=self.timeout_s
                )
            except asyncio.TimeoutError:
                status = ToolStatus.TIMEOUT
                last_error = f"工具执行超时（{self.timeout_s}s）"
                logger.warning("tool.timeout", tool=self.name, attempt=attempt)
            except Exception as exc:  # noqa: BLE001 - 工具边界统一兜底
                status = ToolStatus.ERROR
                last_error = f"{type(exc).__name__}: {exc}"
                logger.warning(
                    "tool.failed", tool=self.name, attempt=attempt, error=last_error
                )
            else:
                latency_ms = (time.perf_counter() - start) * 1000
                self._record_success()
                logger.info(
                    "tool.success",
                    tool=self.name,
                    attempt=attempt,
                    latency_ms=round(latency_ms, 1),
                    request_id=ctx.request_id,
                    session_id=ctx.session_id,
                )
                return ToolResult(status=ToolStatus.OK, data=data, latency_ms=latency_ms)

            if attempt < attempts:
                await asyncio.sleep(0.2 * attempt)

        latency_ms = (time.perf_counter() - start) * 1000
        self._record_failure()
        logger.error(
            "tool.exhausted",
            tool=self.name,
            status=status,
            error=last_error,
            latency_ms=round(latency_ms, 1),
            request_id=ctx.request_id,
            session_id=ctx.session_id,
        )
        return ToolResult(status=status, error=last_error, latency_ms=latency_ms)

    # ------------------------------------------------------------------
    # 子类实现
    # ------------------------------------------------------------------

    @abstractmethod
    async def _invoke(self, args: BaseModel, ctx: ToolContext) -> Any:
        """执行具体业务，异常会被基类捕获并重试。"""

    # ------------------------------------------------------------------
    # 熔断
    # ------------------------------------------------------------------

    def _circuit_block_reason(self) -> str | None:
        now = time.monotonic()
        if self._circuit_open_until > now:
            return (
                f"工具 {self.name} 熔断中，连续 {self._consecutive_failures} 次失败，"
                f"请稍后重试"
            )
        return None

    def _record_success(self) -> None:
        self._consecutive_failures = 0
        self._circuit_open_until = 0.0

    def _record_failure(self) -> None:
        self._consecutive_failures += 1
        if self._consecutive_failures >= self.circuit_failure_threshold:
            self._circuit_open_until = time.monotonic() + self.circuit_reset_s
            logger.warning(
                "tool.circuit_opened",
                tool=self.name,
                failures=self._consecutive_failures,
                reset_s=self.circuit_reset_s,
            )
