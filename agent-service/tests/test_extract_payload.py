"""recommendations 提取：商品工具结果 → 契约形状（订单工具的 items 不得混入）。"""

from __future__ import annotations

import sys
from pathlib import Path

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from app.agent.nodes import _as_recommendations, _extract_api_payload  # noqa: E402


class TestExtractPayload:
    def test_search_items_become_recommendations(self):
        data = {
            "page": 1,
            "items": [
                {"id": "p_1", "name": "耳机", "description": "d", "priceCent": 9900, "stock": 3,
                 "coverUrl": "u"}
            ],
        }
        recs, actions = _extract_api_payload(
            [{"name": "search_products", "ok": True, "data": data}]
        )
        assert len(recs) == 1
        assert recs[0]["productId"] == "p_1"
        assert recs[0]["priceCent"] == 9900
        assert recs[0]["score"] == 0.9
        assert actions == []

    def test_get_product_becomes_single_recommendation(self):
        data = {"id": "p_9", "name": "详情商品", "priceCent": 100, "stock": 1}
        recs, _ = _extract_api_payload([{"name": "get_product", "ok": True, "data": data}])
        assert len(recs) == 1
        assert recs[0]["productId"] == "p_9"

    def test_order_items_do_not_leak(self):
        """订单工具的 items 是订单不是商品，绝不能进 recommendations。"""
        data = {"items": [{"orderId": "o_1", "totalCent": 804}]}
        recs, _ = _extract_api_payload([{"name": "query_orders", "ok": True, "data": data}])
        assert recs == []
        assert _as_recommendations("query_orders", data) == []

    def test_failed_tool_skipped(self):
        recs, _ = _extract_api_payload(
            [{"name": "search_products", "ok": False, "data": None, "error": "x"}]
        )
        assert recs == []

    def test_explicit_recommendations_key_wins(self):
        data = {"recommendations": [{"productId": "p_x", "name": "n"}]}
        recs, _ = _extract_api_payload([{"name": "search_products", "ok": True, "data": data}])
        assert recs[0]["productId"] == "p_x"
