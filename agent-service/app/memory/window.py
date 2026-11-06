"""会话消息窗口：截断历史消息，控制 LLM 上下文长度。

checkpoint 中保留完整对话轨迹，送入 LLM 前用本模块裁剪成滑动窗口。
裁剪规则：
- system 消息永远保留在最前（可关）；
- 从最新消息往回保留，受条数与字符双预算约束；
- 不切断工具配对：若窗口首条是 tool 消息（其 assistant 调用被裁掉）则丢弃之；
- 消息顺序不变。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable, Sequence

__all__ = ["WindowPolicy", "MessageWindow", "trim_messages", "merge_and_trim"]


@dataclass(frozen=True)
class WindowPolicy:
    """消息窗口策略。"""

    max_messages: int = 20
    max_chars: int = 8000
    keep_system: bool = True
    # 硬上限：写入 checkpoint 的消息总数（防状态无限膨胀），窗口裁剪用它兜底。
    hard_max_messages: int = 200


def _message_len(message: Any) -> int:
    if isinstance(message, dict):
        content = message.get("content", "")
    else:
        content = getattr(message, "content", "")
    if isinstance(content, str):
        return len(content)
    return len(str(content))


def _role(message: Any) -> str:
    if isinstance(message, dict):
        return str(message.get("role", ""))
    return str(getattr(message, "role", ""))


def _is_system(message: Any) -> bool:
    return _role(message) == "system"


def _is_orphan_tool(message: Any) -> bool:
    """窗口开头的 tool 消息：对应的 assistant 工具调用已被裁掉，必须丢弃。"""
    return _role(message) == "tool"


def trim_messages(
    messages: Sequence[Any],
    policy: WindowPolicy = WindowPolicy(),
) -> list[Any]:
    """按窗口策略裁剪消息，返回新列表（不修改入参）。"""
    items = list(messages)
    if not items:
        return []

    system_msgs = [m for m in items if _is_system(m)] if policy.keep_system else []
    rest = [m for m in items if not _is_system(m)] if policy.keep_system else items

    budget_chars = policy.max_chars - sum(_message_len(m) for m in system_msgs)
    budget_msgs = policy.max_messages - len(system_msgs)
    if policy.keep_system is False:
        budget_chars = policy.max_chars
        budget_msgs = policy.max_messages

    kept_tail: list[Any] = []
    used_chars = 0
    for message in reversed(rest):
        if budget_msgs <= 0:
            break
        length = _message_len(message)
        if kept_tail and used_chars + length > budget_chars:
            break
        if not kept_tail and length > budget_chars:
            # 最新一条也超预算：保留它（截断历史但不丢当前上下文）。
            kept_tail.append(message)
            break
        kept_tail.append(message)
        used_chars += length
        budget_msgs -= 1

    kept_tail.reverse()
    while kept_tail and _is_orphan_tool(kept_tail[0]):
        kept_tail.pop(0)

    return system_msgs + kept_tail


class MessageWindow:
    """可复用的消息窗口（策略 + 便捷方法）。"""

    def __init__(self, policy: WindowPolicy = WindowPolicy()) -> None:
        self.policy = policy

    def render(self, messages: Sequence[Any]) -> list[Any]:
        """裁剪出送入 LLM 的上下文消息列表。"""
        return trim_messages(messages, self.policy)

    def hard_trim(self, messages: Sequence[Any]) -> list[Any]:
        """写 checkpoint 前的硬裁剪，防止状态无限膨胀。"""
        if len(messages) <= self.policy.hard_max_messages:
            return list(messages)
        keep = self.policy.hard_max_messages
        tail = list(messages[-keep:])
        while tail and _is_orphan_tool(tail[0]):
            tail.pop(0)
        head = [m for m in messages if _is_system(m)][:5]
        return head + tail


def _as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        return list(value)
    return [value]


def _to_dict(message: Any) -> dict[str, Any]:
    if isinstance(message, dict):
        return message
    out: dict[str, Any] = {
        "role": str(getattr(message, "role", "") or ""),
        "content": getattr(message, "content", ""),
    }
    msg_id = getattr(message, "id", None)
    if msg_id:
        out["id"] = str(msg_id)
    return out


def merge_and_trim(
    old: Iterable[Any] | None,
    new: Iterable[Any] | None,
    policy: WindowPolicy = WindowPolicy(),
) -> list[dict[str, Any]]:
    """消息 reducer：按 id 去重追加（add_messages 语义的纯 dict 实现）+ 硬裁剪。

    保持消息为纯 dict，便于 Redis checkpoint 序列化，也不让 pydantic 消息对象
    泄漏进状态与 API 层。
    """
    import uuid

    merged: list[dict[str, Any]] = [_to_dict(m) for m in _as_list(old)]
    index = {
        str(m.get("id")): i
        for i, m in enumerate(merged)
        if m.get("id") is not None
    }
    for raw in _as_list(new):
        item = dict(_to_dict(raw))
        item_id = item.get("id")
        if item_id is not None and str(item_id) in index:
            merged[index[str(item_id)]] = item
            continue
        if item_id is None:
            item["id"] = uuid.uuid4().hex
        index[str(item["id"])] = len(merged)
        merged.append(item)
    return MessageWindow(policy).hard_trim(merged)
