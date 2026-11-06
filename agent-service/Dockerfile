FROM python:3.12-slim AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

WORKDIR /build
# 先只拷 pyproject.toml 装依赖（代码变更不会触发重新下载）
COPY pyproject.toml ./
RUN pip install --index-url https://pypi.tuna.tsinghua.edu.cn/simple .

# 再拷源码（轻量）
COPY app ./app
RUN pip install --index-url https://pypi.tuna.tsinghua.edu.cn/simple .

FROM python:3.12-slim AS runtime

ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    AGENT_HOST=0.0.0.0 \
    AGENT_PORT=8000

RUN useradd --create-home --uid 10001 appuser

WORKDIR /app
COPY --from=builder /opt/venv /opt/venv
COPY app ./app

USER appuser
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
