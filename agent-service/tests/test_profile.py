"""S1 记忆偏好的单测：画像学习 / 渲染 / 存储 / 注入链路。

全部离线可跑（画像存储用内存后端，LLM/工具不触网）。
"""

from __future__ import annotations

import sys
from pathlib import Path

AGENT_SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_SERVICE_ROOT))

from app.agent.nodes import _build_tool_args, _slot_satisfied, make_nodes  # noqa: E402
from app.agent.prompts import build_llm_messages, INTENT_SYSTEM_PROMPT  # noqa: E402
from app.agent.state import AgentDeps, ToolResult  # noqa: E402
from app.memory.profile import (  # noqa: E402
    ProfileStore,
    UserProfile,
    extract_budget_cent,
    learn_from_turn,
    render_profile,
    wrap_profile_block,
)


# ---------------------------------------------------------------------------
# 预算抽取
# ---------------------------------------------------------------------------


class TestExtractBudget:
    def test_range(self):
        assert extract_budget_cent("预算 200-400 的耳机") == (20000, 40000)

    def test_range_with_unit_on_tail(self):
        assert extract_budget_cent("200到400块") == (20000, 40000)

    def test_ceiling(self):
        assert extract_budget_cent("不超过 500") == (None, 50000)

    def test_bare_budget_is_ceiling(self):
        assert extract_budget_cent("预算 200 耳机") == (None, 20000)

    def test_k_unit(self):
        assert extract_budget_cent("预算 2k 左右") == (160000, 240000)

    def test_no_keyword_no_signal(self):
        # 没有预算关键词的纯数字不误判
        assert extract_budget_cent("我想要 3 个") == (None, None)

    def test_empty(self):
        assert extract_budget_cent("") == (None, None)


# ---------------------------------------------------------------------------
# 偏好学习
# ---------------------------------------------------------------------------


class TestLearnFromTurn:
    def test_learn_from_entities(self):
        profile = UserProfile()
        learn_from_turn(
            profile,
            text="推荐个降噪耳机",
            entities={
                "maxPriceCent": 30000,
                "preference": "降噪",
                "brand": "索尼",
                "keyword": "耳机",
            },
        )
        assert profile.budget_max_cent == 30000
        assert "降噪" in profile.preferences
        assert "索尼" in profile.brands
        assert "耳机" in profile.categories

    def test_learn_from_text_budget(self):
        profile = UserProfile()
        learn_from_turn(profile, text="预算 200 买个鼠标")
        assert profile.budget_max_cent == 20000

    def test_new_signal_overrides_old(self):
        profile = UserProfile(budget_max_cent=20000)
        learn_from_turn(profile, text="", entities={"maxPriceCent": 50000})
        assert profile.budget_max_cent == 50000

    def test_list_merge_dedupe_and_order(self):
        profile = UserProfile(preferences=["音质"])
        learn_from_turn(profile, text="", entities={"preference": "降噪"})
        learn_from_turn(profile, text="", entities={"preference": "降噪"})
        assert profile.preferences[:2] == ["降噪", "音质"]

    def test_budget_like_preference_dropped(self):
        """LLM 常把“预算200元”塞进 preference 槽位——清洗掉，预算另有字段。"""
        profile = UserProfile()
        learn_from_turn(profile, text="预算 200", entities={"preference": "预算200元", "maxPriceCent": 20000})
        learn_from_turn(profile, text="", entities={"preference": "500元"})
        assert profile.preferences == []
        assert profile.budget_max_cent == 20000


# ---------------------------------------------------------------------------
# 渲染与注入
# ---------------------------------------------------------------------------


class TestRenderAndWrap:
    def test_render_full(self):
        profile = UserProfile(
            budget_min_cent=10000,
            budget_max_cent=30000,
            preferences=["降噪"],
            categories=["耳机"],
            brands=["索尼"],
            recent_orders=[{"createdAt": "2026-05-26T10:00:00Z", "items": ["蓝牙耳机×1"]}],
        )
        text = render_profile(profile)
        assert "预算：100-300 元" in text
        assert "偏好：降噪" in text
        assert "常看品类：耳机" in text
        assert "品牌倾向：索尼" in text
        assert "最近购买：蓝牙耳机×1（2026-05-26）" in text

    def test_render_empty(self):
        assert render_profile(UserProfile()) == ""

    def test_wrap_none_when_empty(self):
        assert wrap_profile_block(UserProfile()) is None

    def test_wrap_nonce_block(self):
        block = wrap_profile_block(UserProfile(budget_max_cent=20000))
        assert block is not None
        assert "[BEGIN_USER_PROFILE_" in block
        assert "[END_USER_PROFILE_" in block
        # nonce 随机，两端标记一致
        begin = block.split("[BEGIN_USER_PROFILE_")[1].split("]")[0]
        end = block.split("[END_USER_PROFILE_")[1].split("]")[0]
        assert begin == end

    def test_build_llm_messages_injects_profile(self):
        block = wrap_profile_block(UserProfile(budget_max_cent=20000))
        messages = build_llm_messages(
            [{"role": "user", "content": "推荐耳机"}],
            system_prompt=INTENT_SYSTEM_PROMPT,
            profile_block=block,
        )
        assert messages[0]["role"] == "system"
        assert messages[1]["role"] == "user"
        assert "USER_PROFILE" in messages[1]["content"]

    def test_build_llm_messages_without_profile(self):
        messages = build_llm_messages(
            [{"role": "user", "content": "推荐耳机"}],
            system_prompt=INTENT_SYSTEM_PROMPT,
        )
        assert len(messages) == 2
        assert "USER_PROFILE" not in messages[1]["content"]


# ---------------------------------------------------------------------------
# 存储
# ---------------------------------------------------------------------------


class TestProfileStore:
    def test_roundtrip(self):
        store = ProfileStore()
        profile = store.learn("u1", text="预算 200 买耳机", entities={"keyword": "耳机"})
        assert profile.budget_max_cent == 20000
        loaded = store.load("u1")
        assert loaded.budget_max_cent == 20000
        assert "耳机" in loaded.categories

    def test_learn_accumulates(self):
        store = ProfileStore()
        store.learn("u1", text="", entities={"maxPriceCent": 20000, "preference": "降噪"})
        profile = store.learn("u1", text="", entities={"brand": "索尼"})
        assert profile.budget_max_cent == 20000
        assert "降噪" in profile.preferences
        assert "索尼" in profile.brands

    def test_record_orders_and_stale(self):
        store = ProfileStore()
        profile = store.load("u1")
        assert store.orders_stale(profile)
        profile = store.record_orders(
            "u1", [{"orderNo": "202605260001", "createdAt": "2026-05-26T10:00:00Z", "items": ["耳机"]}]
        )
        assert not store.orders_stale(profile)
        assert store.load("u1").recent_orders[0]["items"] == ["耳机"]

    def test_isolated_users(self):
        store = ProfileStore()
        store.learn("u1", text="预算 100", entities={})
        assert store.load("u2").budget_max_cent is None


# ---------------------------------------------------------------------------
# 节点辅助：画像对追问槽位与工具参数的影响
# ---------------------------------------------------------------------------


class TestNodeHelpers:
    def test_slot_satisfied_by_profile(self):
        profile = UserProfile(budget_max_cent=20000, preferences=["降噪"], brands=["索尼"])
        assert _slot_satisfied("maxPriceCent", profile)
        assert _slot_satisfied("preference", profile)
        assert _slot_satisfied("brand", profile)
        assert not _slot_satisfied("keyword", profile)  # 品类不粘滞，避免误配
        assert not _slot_satisfied("maxPriceCent", UserProfile())

    def test_tool_args_fallback_to_profile_budget(self):
        state = {
            "entities": {"keyword": "耳机"},
            "user_profile": {"budget_min_cent": 10000, "budget_max_cent": 30000},
            "messages": [{"role": "user", "content": "推荐耳机"}],
        }
        args = _build_tool_args("recommend_product", state)
        assert args["min_price_cent"] == 10000
        assert args["max_price_cent"] == 30000

    def test_tool_args_entities_win_over_profile(self):
        state = {
            "entities": {"keyword": "耳机", "maxPriceCent": 50000},
            "user_profile": {"budget_max_cent": 30000},
            "messages": [{"role": "user", "content": "50000以内耳机"}],
        }
        args = _build_tool_args("recommend_product", state)
        assert args["max_price_cent"] == 50000


# ---------------------------------------------------------------------------
# 意图节点：画像覆盖缺失槽位 → 不再追问
# ---------------------------------------------------------------------------


class _FixedLLM:
    def __init__(self, payload: str) -> None:
        self.payload = payload
        self.seen: list[list[dict[str, str]]] = []

    def chat(self, messages, *, timeout=None):
        self.seen.append(list(messages))
        return self.payload


class _NoopTools:
    def available(self):
        return []

    def invoke(self, name, arguments, *, timeout=None):
        return ToolResult(ok=False, error="noop")


def _intent_node(llm_payload: str, state: dict) -> dict:
    llm = _FixedLLM(llm_payload)
    nodes = make_nodes(AgentDeps(llm=llm, tools=_NoopTools()))
    return nodes["intent"](state)


class TestIntentNodeWithProfile:
    def test_profile_covers_budget_no_clarify(self):
        """LLM 追问预算，但画像已有预算 → 不追问（S1 核心行为）。"""
        out = _intent_node(
            '{"name": "recommend_product", "confidence": 0.9,'
            ' "entities": {"keyword": "鼠标"},'
            ' "needs_clarify": true, "missing_slots": ["maxPriceCent"]}',
            {
                "user_profile": {"budget_max_cent": 20000},
                "messages": [{"role": "user", "content": "推荐个鼠标"}],
            },
        )
        assert out["needs_clarify"] is False
        assert out["missing_slots"] == []

    def test_vague_input_still_clarifies(self):
        """没有画像、没有品类线索的模糊输入仍要追问。"""
        out = _intent_node(
            '{"name": "other", "confidence": 0.3, "entities": {},'
            ' "needs_clarify": true, "missing_slots": []}',
            {"user_profile": {}, "messages": [{"role": "user", "content": "想买东西"}]},
        )
        assert out["needs_clarify"] is True
        assert out["missing_slots"]

    def test_missing_keyword_not_covered_by_profile(self):
        """品类不粘滞：画像品类不能顶替本轮缺失的品类。"""
        out = _intent_node(
            '{"name": "recommend_product", "confidence": 0.9, "entities": {},'
            ' "needs_clarify": true, "missing_slots": ["keyword"]}',
            {
                "user_profile": {"categories": ["耳机"]},
                "messages": [{"role": "user", "content": "随便推荐点"}],
            },
        )
        assert out["needs_clarify"] is True

    def test_profile_block_injected_into_intent_prompt(self):
        llm = _FixedLLM('{"name": "other", "confidence": 0.5, "entities": {}}')
        nodes = make_nodes(AgentDeps(llm=llm, tools=_NoopTools()))
        nodes["intent"](
            {
                "user_profile": {"budget_max_cent": 20000},
                "messages": [{"role": "user", "content": "推荐耳机"}],
            }
        )
        sent = "\n".join(m["content"] for m in llm.seen[0])
        assert "[BEGIN_USER_PROFILE_" in sent
        assert "预算：200 元以内" in sent
