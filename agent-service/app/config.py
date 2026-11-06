"""分环境配置（pydantic-settings）。

环境变量统一使用 AGENT_ 前缀，优先级：环境变量 > .env 文件 > 默认值。
prod 环境强制校验服务间内部 token 与 LLM API Key，避免带默认空密钥上线。
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal, Self

from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["dev", "prod", "test"]
LogFormat = Literal["json", "console"]

_VALID_LOG_LEVELS = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}


class Settings(BaseSettings):
    """应用配置。字段名即环境变量名（去掉 AGENT_ 前缀，不区分大小写）。"""

    model_config = SettingsConfigDict(
        env_prefix="AGENT_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # --- 基础 ---
    env: Environment = "dev"
    app_name: str = "agent-service"
    host: str = "0.0.0.0"
    port: int = 8000

    # --- 日志 ---
    log_level: str = "INFO"
    # 留空则 dev/test 用 console，prod 强制 json
    log_format: LogFormat | None = None

    # --- Redis（会话 / checkpoint）---
    redis_url: str = "redis://localhost:6379/0"
    redis_socket_timeout_s: float = 1.0

    # --- Java 后端内部 API ---
    java_api_base_url: str = "http://localhost:8080"
    internal_token: SecretStr = SecretStr("")

    # --- LLM（DeepSeek，OpenAI 兼容）---
    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_api_key: SecretStr = SecretStr("")
    llm_model: str = "deepseek-chat"
    llm_timeout_s: float = 30.0
    llm_max_retries: int = 2

    # --- 观测（Langfuse）---
    langfuse_enabled: bool = False
    langfuse_public_key: SecretStr = SecretStr("")
    langfuse_secret_key: SecretStr = SecretStr("")
    langfuse_host: str = "https://cloud.langfuse.com"

    # --- 输入限制 ---
    max_message_chars: int = 2000

    @field_validator("log_level")
    @classmethod
    def _normalize_log_level(cls, value: str) -> str:
        level = value.upper()
        if level not in _VALID_LOG_LEVELS:
            raise ValueError(f"非法 log_level: {value}")
        return level

    @field_validator(
        "redis_url", "java_api_base_url", "llm_base_url", "langfuse_host"
    )
    @classmethod
    def _strip_trailing_slash(cls, value: str) -> str:
        return value.rstrip("/")

    @field_validator("port", "llm_max_retries", "max_message_chars")
    @classmethod
    def _positive_int(cls, value: int) -> int:
        if value <= 0:
            raise ValueError("必须为正整数")
        return value

    @model_validator(mode="after")
    def _validate_prod(self) -> Self:
        if self.env != "prod":
            return self
        missing: list[str] = []
        if not self.internal_token.get_secret_value():
            missing.append("AGENT_INTERNAL_TOKEN")
        if not self.llm_api_key.get_secret_value():
            missing.append("AGENT_LLM_API_KEY")
        if missing:
            raise ValueError("prod 环境缺少必填配置: " + ", ".join(missing))
        return self

    @property
    def is_prod(self) -> bool:
        return self.env == "prod"

    @property
    def resolved_log_format(self) -> LogFormat:
        if self.log_format is not None:
            return self.log_format
        return "json" if self.is_prod else "console"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """进程内单例配置。"""
    return Settings()
