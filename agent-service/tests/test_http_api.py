"""HTTP 路由层测试：统一信封、错误映射、请求头透传、已删端点。

路由只依赖 ``request.app.state.orchestrator``，故注入桩编排器避免真调 LLM/Java。
路由用例不触发 lifespan（直接装配 create_app + 桩），保证离线、快、可重复；
另有一例走完整 lifespan 验证外部服务缺失时内存兜底仍可启动。
"""

from __future__ import annotations

import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from fastapi.testclient import TestClient  # noqa: E402

from app.main import create_app  # noqa: E402


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class StubOrchestrator:
    """桩编排器：返回固定数据或按 ``send_mode`` 抛 KeyError/ValueError。"""

    def __init__(self) -> None:
        self.calls: dict[str, dict] = {}
        self.send_mode = "ok"  # ok | keyerror | valueerror

    def create_session(self, user_id: str, entry: str = "shop",
                       source: str = "app", locale: str = "zh-CN") -> dict:
        self.calls["create_session"] = {
            "user_id": user_id, "entry": entry, "source": source, "locale": locale,
        }
        return {
            "sessionId": "sg_stub", "userId": user_id, "status": "ACTIVE",
            "entry": entry, "source": source, "locale": locale,
            "welcomeMessage": "欢迎光临导购", "createdAt": _now_iso(), "updatedAt": _now_iso(),
        }

    def get_session(self, session_id: str) -> dict | None:
        self.calls["get_session"] = {"session_id": session_id}
        if session_id == "missing":
            return None
        return {
            "sessionId": session_id, "userId": "u1", "status": "ACTIVE",
            "createdAt": _now_iso(), "updatedAt": _now_iso(),
        }

    def send_message(self, session_id: str, user_id: str, text: str,
                     request_id: str = "", client_context=None, user_token: str = "") -> dict:
        self.calls["send_message"] = {
            "session_id": session_id, "user_id": user_id, "text": text,
            "request_id": request_id, "client_context": client_context, "user_token": user_token,
        }
        if self.send_mode == "keyerror":
            raise KeyError("no_such_session")
        if self.send_mode == "valueerror":
            raise ValueError("session_ended")
        return {
            "userMessage": {"messageId": "m_user", "role": "user", "contentType": "text",
                            "content": text, "createdAt": _now_iso()},
            "assistantMessage": {"messageId": "m_bot", "role": "assistant", "contentType": "text",
                                 "content": "为你推荐一款降噪耳机", "createdAt": _now_iso()},
            "intent": {"name": "buy", "confidence": 0.9, "entities": {"budget": 1000}},
            "recommendations": [{"productId": "p1", "name": "降噪耳机", "description": "音质出色",
                                 "priceCent": 19900, "stock": 5, "coverUrl": "http://img/p1.png",
                                 "score": 0.82, "reason": "性价比高"}],
            "actions": [{"type": "add_to_cart", "label": "加入购物车", "productId": "p1", "quantity": 1}],
        }

    def close_session(self, session_id: str) -> dict:
        self.calls["close_session"] = {"session_id": session_id}
        return {"sessionId": session_id, "status": "ENDED"}

    def get_profile(self, user_id: str) -> dict:
        self.calls["get_profile"] = {"user_id": user_id}
        return {"budgetMinCent": 10000, "budgetMaxCent": 50000,
                "categories": ["耳机"], "preferences": ["降噪"], "brands": ["Sony"],
                "recentOrders": []}


@pytest.fixture()
def stub() -> StubOrchestrator:
    return StubOrchestrator()


@pytest.fixture()
def client(stub: StubOrchestrator) -> TestClient:
    """装配 create_app 并注入桩编排器，不触发 lifespan（路由无需外部服务）。"""
    app = create_app()
    app.state.orchestrator = stub
    return TestClient(app)


def test_service_boots_with_in_memory_fallback() -> None:
    """外部服务缺失时 lifespan 走内存兜底，应用仍可启动（Redis 画像/checkpoint 回退）。"""
    app = create_app()
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        # lifespan 已装配真实编排器，证明依赖链能离线构建
        assert getattr(app.state, "orchestrator", None) is not None


def test_health_returns_200(client: TestClient) -> None:
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "healthy"}


# --- 创建会话：统一信封 + X-User-Id 透传 ---


def test_create_session_envelope_and_default_user(client: TestClient, stub: StubOrchestrator) -> None:
    r = client.post("/api/shoppingguide/sessions", json={})
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"code", "message", "data"}
    assert body["code"] == 0
    assert body["message"] == "ok"
    assert body["data"]["session"]["sessionId"] == "sg_stub"
    assert body["data"]["welcomeMessage"] == "欢迎光临导购"
    # 未指定 X-User-Id → 默认 anonymous
    assert stub.calls["create_session"]["user_id"] == "anonymous"


def test_create_session_custom_user_id(client: TestClient, stub: StubOrchestrator) -> None:
    r = client.post("/api/shoppingguide/sessions", json={}, headers={"X-User-Id": "alice"})
    assert r.status_code == 200
    assert r.json()["data"]["session"]["userId"] == "alice"
    assert stub.calls["create_session"]["user_id"] == "alice"


# --- 发消息：成功信封 + Bearer token 剥离 ---


def test_send_message_success_and_token_stripped(client: TestClient, stub: StubOrchestrator) -> None:
    r = client.post(
        "/api/shoppingguide/sessions/sg_stub/messages",
        json={"message": "我要买耳机"},
        headers={"X-User-Id": "alice", "Authorization": "Bearer tok123", "X-Request-ID": "req-1"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["code"] == 0
    data = body["data"]
    for key in ("userMessage", "assistantMessage", "intent", "recommendations", "actions"):
        assert key in data
    assert data["userMessage"]["content"] == "我要买耳机"
    assert data["assistantMessage"]["role"] == "assistant"
    assert data["intent"]["name"] == "buy"
    assert data["recommendations"][0]["productId"] == "p1"
    assert data["actions"][0]["type"] == "add_to_cart"
    # Authorization 剥掉 Bearer 前缀后透传给编排器
    assert stub.calls["send_message"]["user_token"] == "tok123"
    assert stub.calls["send_message"]["user_id"] == "alice"


# --- 发消息：编排器异常 → 错误映射 ---


def test_send_message_keyerror_maps_to_404(client: TestClient, stub: StubOrchestrator) -> None:
    stub.send_mode = "keyerror"
    r = client.post("/api/shoppingguide/sessions/sg_x/messages", json={"message": "hi"})
    assert r.status_code == 404
    body = r.json()
    assert body["code"] == 404
    assert body["message"] == "会话不存在"
    assert body["data"] is None


def test_send_message_valueerror_maps_to_400(client: TestClient, stub: StubOrchestrator) -> None:
    stub.send_mode = "valueerror"
    r = client.post("/api/shoppingguide/sessions/sg_x/messages", json={"message": "hi"})
    assert r.status_code == 400
    body = r.json()
    assert body["code"] == 400
    assert body["message"] == "会话已结束"
    assert body["data"] is None


# --- 发消息：参数校验失败 → 422 信封 ---


def test_send_message_validation_error_422_envelope(client: TestClient) -> None:
    r = client.post("/api/shoppingguide/sessions/sg_stub/messages", json={"message": ""})
    assert r.status_code == 422
    body = r.json()
    assert body["code"] == 422
    assert body["message"] == "请求参数校验失败"
    errors = body["data"]
    assert isinstance(errors, list) and errors
    assert "message" in errors[0]["loc"]


# --- 关闭会话：幂等信封 ---


def test_close_session_idempotent_envelope(client: TestClient, stub: StubOrchestrator) -> None:
    r = client.post("/api/shoppingguide/sessions/sg_stub/close")
    assert r.status_code == 200
    body = r.json()
    assert body["code"] == 0
    assert body["data"] == {"sessionId": "sg_stub", "status": "ENDED"}
    assert stub.calls["close_session"]["session_id"] == "sg_stub"


# --- 会话详情 ---


def test_get_session_returns_envelope(client: TestClient) -> None:
    r = client.get("/api/shoppingguide/sessions/sg_stub")
    assert r.status_code == 200
    body = r.json()
    assert body["code"] == 0
    assert body["data"]["session"]["sessionId"] == "sg_stub"
    assert body["data"]["latestRecommendations"] == []


def test_get_session_missing_returns_404(client: TestClient) -> None:
    r = client.get("/api/shoppingguide/sessions/missing")
    assert r.status_code == 404
    body = r.json()
    assert body["code"] == 404
    assert body["message"] == "会话不存在"
    assert body["data"] is None


# --- 用户画像 ---


def test_profile_envelope_and_data(client: TestClient, stub: StubOrchestrator) -> None:
    r = client.get("/api/shoppingguide/profile", headers={"X-User-Id": "alice"})
    assert r.status_code == 200
    body = r.json()
    assert body["code"] == 0
    data = body["data"]
    assert data["budgetMinCent"] == 10000
    assert data["categories"] == ["耳机"]
    assert stub.calls["get_profile"]["user_id"] == "alice"


# --- 已删除的 501 端点：不再存在 → 404 ---


def test_deleted_compare_endpoint_returns_404(client: TestClient) -> None:
    r = client.post("/api/shoppingguide/compare", json={})
    assert r.status_code == 404
    assert r.json()["code"] == 404


def test_deleted_feedback_endpoint_returns_404(client: TestClient) -> None:
    r = client.post("/api/shoppingguide/feedback", json={})
    assert r.status_code == 404
    assert r.json()["code"] == 404
