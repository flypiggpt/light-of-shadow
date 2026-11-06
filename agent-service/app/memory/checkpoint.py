"""LangGraph Redis checkpoint：断点恢复、并发安全、写失败不阻断。

设计要点：
- checkpoint 记录不可变（key 含 checkpoint_id），重复写幂等；
- 并发安全不依赖锁：checkpoint_id 全局单调，latest 由 Redis ZSET（同分数字典序）推导，
  写入均为单命令原子操作（SET / ZADD / HSETNX）；
- `ResilientCheckpointSaver` 吞掉读写异常并回退内存实现，checkpoint 失败绝不阻断主流程；
- 通道值随 checkpoint 整体存储（不再拆 blob），换取实现简单与单键原子读。
"""

from __future__ import annotations

import logging
from typing import Any, Iterator, Mapping, Sequence

from langgraph.checkpoint.base import (
    WRITES_IDX_MAP,
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
    JsonPlusSerializer,
    RunnableConfig,
    SerializerProtocol,
    get_checkpoint_id,
    get_checkpoint_metadata,
    writes_sort_key,
)

__all__ = [
    "RedisCheckpointSaver",
    "ResilientCheckpointSaver",
    "build_checkpointer",
    "new_run_config",
]

logger = logging.getLogger("agent-service.memory.checkpoint")

_NS_SEP = "\x00"


def _encode(part: str) -> str:
    """把 thread_id / checkpoint_ns 转成 Redis key 安全片段。"""
    return (part or "").replace("%", "%25").replace(":", "%3A").replace(" ", "%20")


class RedisCheckpointSaver(BaseCheckpointSaver):
    """基于 Redis 的 checkpoint 存储（客户端需实现 redis-py 同步接口）。

    Key 布局（prefix 默认 `agent:ckpt:`）：
    - `c:{thread}:{ns}:{cid}`：checkpoint 记录（checkpoint + metadata + parent）
    - `w:{thread}:{ns}:{cid}`：该 checkpoint 的 pending writes（hash）
    - `i:{thread}:{ns}`：checkpoint_id 有序集合（字典序 = 时间序）
    - `n:{thread}`：该线程用到的 namespace 集合
    - `k:{thread}`：该线程全部 key 清单（delete_thread 用）
    - `threads`：全部线程集合
    """

    def __init__(
        self,
        redis_client: Any,
        *,
        prefix: str = "agent:ckpt:",
        ttl_seconds: int | None = None,
        serde: SerializerProtocol | None = None,
    ) -> None:
        super().__init__(serde=serde or JsonPlusSerializer())
        self.redis = redis_client
        self.prefix = prefix
        self.ttl_seconds = ttl_seconds

    # ---- key helpers -------------------------------------------------

    def _record_key(self, thread_id: str, ns: str, checkpoint_id: str) -> str:
        return f"{self.prefix}c:{_encode(thread_id)}:{_encode(ns)}:{checkpoint_id}"

    def _writes_key(self, thread_id: str, ns: str, checkpoint_id: str) -> str:
        return f"{self.prefix}w:{_encode(thread_id)}:{_encode(ns)}:{checkpoint_id}"

    def _ids_key(self, thread_id: str, ns: str) -> str:
        return f"{self.prefix}i:{_encode(thread_id)}:{_encode(ns)}"

    def _ns_key(self, thread_id: str) -> str:
        return f"{self.prefix}n:{_encode(thread_id)}"

    def _keys_key(self, thread_id: str) -> str:
        return f"{self.prefix}k:{_encode(thread_id)}"

    def _threads_key(self) -> str:
        return f"{self.prefix}threads"

    def _register_keys(self, thread_id: str, *keys: str) -> None:
        pipe = self.redis.pipeline(transaction=True)
        pipe.sadd(self._keys_key(thread_id), *keys)
        pipe.sadd(self._threads_key(), thread_id)
        if self.ttl_seconds:
            for k in keys:
                pipe.expire(k, self.ttl_seconds)
            pipe.expire(self._keys_key(thread_id), self.ttl_seconds)
            pipe.expire(self._threads_key(), self.ttl_seconds)
        pipe.execute()

    def _write_field(self, task_path: str, task_id: str, idx: int) -> str:
        return f"{task_path}{_NS_SEP}{task_id}{_NS_SEP}{idx:06d}"

    def _read_write_field(self, field: str) -> tuple[str, str, int]:
        task_path, task_id, raw_idx = field.split(_NS_SEP)
        return task_path, task_id, int(raw_idx)

    def _pending_writes(
        self, thread_id: str, ns: str, checkpoint_id: str
    ) -> list[tuple[str, str, Any]]:
        raw = self.redis.hgetall(self._writes_key(thread_id, ns, checkpoint_id))
        entries: list[tuple[tuple[str, str, int], tuple[str, str, Any, str]]] = []
        for field, payload in (raw or {}).items():
            if isinstance(field, bytes):
                field = field.decode()
            record = self.serde.loads_typed(_to_typed(payload))
            task_path, task_id, idx = self._read_write_field(field)
            entries.append(
                (
                    (task_path, task_id, idx),
                    (record["task_id"], record["channel"], record["value"], record["task_path"]),
                )
            )
        entries.sort(key=lambda item: writes_sort_key(item[1][3], item[0][1], item[0][2]))
        return [(tid, ch, val) for _, (tid, ch, val, _path) in entries]

    def _tuple_from_record(
        self,
        thread_id: str,
        ns: str,
        checkpoint_id: str,
        payload: bytes,
    ) -> CheckpointTuple:
        record = self.serde.loads_typed(_to_typed(payload))
        parent_id = record.get("parent")
        return CheckpointTuple(
            config={
                "configurable": {
                    "thread_id": thread_id,
                    "checkpoint_ns": ns,
                    "checkpoint_id": checkpoint_id,
                }
            },
            checkpoint=record["checkpoint"],
            metadata=record["metadata"],
            parent_config=(
                {
                    "configurable": {
                        "thread_id": thread_id,
                        "checkpoint_ns": ns,
                        "checkpoint_id": parent_id,
                    }
                }
                if parent_id
                else None
            ),
            pending_writes=self._pending_writes(thread_id, ns, checkpoint_id),
        )

    # ---- BaseCheckpointSaver API -------------------------------------

    def get_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        thread_id: str = config["configurable"]["thread_id"]
        ns: str = config["configurable"].get("checkpoint_ns", "")
        checkpoint_id = get_checkpoint_id(config)
        if checkpoint_id is None:
            ids = self.redis.zrange(self._ids_key(thread_id, ns), -1, -1)
            if not ids:
                return None
            checkpoint_id = _decode(ids[0])
        payload = self.redis.get(self._record_key(thread_id, ns, checkpoint_id))
        if payload is None:
            return None
        return self._tuple_from_record(thread_id, ns, checkpoint_id, payload)

    def list(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> Iterator[CheckpointTuple]:
        if config is not None:
            thread_ids = [config["configurable"]["thread_id"]]
            ns_filter: str | None = config["configurable"].get("checkpoint_ns")
            config_checkpoint_id = get_checkpoint_id(config)
        else:
            thread_ids = [
                _decode(t) for t in (self.redis.zrange(self._threads_key(), 0, -1) or [])
            ]
            ns_filter = None
            config_checkpoint_id = None

        before_id = get_checkpoint_id(before) if before else None

        for thread_id in thread_ids:
            raw_ns = self.redis.smembers(self._ns_key(thread_id)) or {b""}
            namespaces = sorted({_decode(n) for n in raw_ns})
            for ns in namespaces:
                if ns_filter is not None and ns != ns_filter:
                    continue
                ids = [
                    _decode(i)
                    for i in (self.redis.zrange(self._ids_key(thread_id, ns), 0, -1) or [])
                ]
                for checkpoint_id in reversed(ids):
                    if config_checkpoint_id and checkpoint_id != config_checkpoint_id:
                        continue
                    if before_id and checkpoint_id >= before_id:
                        continue
                    payload = self.redis.get(
                        self._record_key(thread_id, ns, checkpoint_id)
                    )
                    if payload is None:
                        continue
                    item = self._tuple_from_record(thread_id, ns, checkpoint_id, payload)
                    if filter and not all(
                        expected == item.metadata.get(key)
                        for key, expected in filter.items()
                    ):
                        continue
                    if limit is not None:
                        if limit <= 0:
                            return
                        limit -= 1
                    yield item

    def put(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        thread_id: str = config["configurable"]["thread_id"]
        ns: str = config["configurable"].get("checkpoint_ns", "")
        checkpoint_id: str = checkpoint["id"]
        record = {
            "checkpoint": checkpoint,
            "metadata": get_checkpoint_metadata(config, metadata),
            "parent": config["configurable"].get("checkpoint_id"),
        }
        record_key = self._record_key(thread_id, ns, checkpoint_id)
        ids_key = self._ids_key(thread_id, ns)
        self.redis.set(record_key, _from_typed(self.serde.dumps_typed(record)))
        self.redis.zadd(ids_key, {checkpoint_id: 0})
        self.redis.sadd(self._ns_key(thread_id), ns)
        self._register_keys(thread_id, record_key, ids_key, self._ns_key(thread_id))
        return {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_ns": ns,
                "checkpoint_id": checkpoint_id,
            }
        }

    def put_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        thread_id: str = config["configurable"]["thread_id"]
        ns: str = config["configurable"].get("checkpoint_ns", "")
        checkpoint_id: str = config["configurable"]["checkpoint_id"]
        writes_key = self._writes_key(thread_id, ns, checkpoint_id)
        for idx, (channel, value) in enumerate(writes):
            field = self._write_field(task_path, task_id, WRITES_IDX_MAP.get(channel, idx))
            payload = _from_typed(
                self.serde.dumps_typed(
                    {
                        "task_id": task_id,
                        "channel": channel,
                        "value": value,
                        "task_path": task_path,
                    }
                )
            )
            if WRITES_IDX_MAP.get(channel, idx) >= 0:
                # 与 InMemorySaver 一致：普通写首次生效，后续同键写忽略（幂等）。
                self.redis.hsetnx(writes_key, field, payload)
            else:
                self.redis.hset(writes_key, field, payload)
        self._register_keys(thread_id, writes_key)

    def delete_thread(self, thread_id: str) -> None:
        keys = [
            _decode(k) for k in (self.redis.smembers(self._keys_key(thread_id)) or [])
        ]
        if keys:
            self.redis.delete(*keys)
        self.redis.delete(self._keys_key(thread_id), self._ns_key(thread_id))
        self.redis.zrem(self._threads_key(), thread_id)

    # ---- async variants（委托同步实现） --------------------------------

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        return self.get_tuple(config)

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ):
        for item in self.list(config, filter=filter, before=before, limit=limit):
            yield item

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        return self.put(config, checkpoint, metadata, new_versions)

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        self.put_writes(config, writes, task_id, task_path)

    async def adelete_thread(self, thread_id: str) -> None:
        self.delete_thread(thread_id)


class ResilientCheckpointSaver(BaseCheckpointSaver):
    """包装任意 saver：读写失败只记日志，可选回退内存实现，绝不阻断图执行。"""

    def __init__(
        self,
        inner: BaseCheckpointSaver,
        *,
        fallback: BaseCheckpointSaver | None = None,
        serde: SerializerProtocol | None = None,
    ) -> None:
        super().__init__(serde=serde or inner.serde)
        self.inner = inner
        self.fallback = fallback

    def _read(
        self, method: str, fn, *args, **kwargs
    ) -> Any:
        try:
            return fn(*args, **kwargs)
        except Exception:
            logger.warning("checkpoint 读失败（已忽略） method=%s", method, exc_info=True)
            if self.fallback is not None:
                try:
                    return getattr(self.fallback, method)(*args, **kwargs)
                except Exception:
                    logger.warning(
                        "checkpoint 回退读失败（已忽略） method=%s", method, exc_info=True
                    )
            return None

    def _write(self, method: str, fn, default, *args, **kwargs) -> Any:
        try:
            return fn(*args, **kwargs)
        except Exception:
            logger.warning("checkpoint 写失败（已忽略） method=%s", method, exc_info=True)
            if self.fallback is not None:
                try:
                    return getattr(self.fallback, method)(*args, **kwargs)
                except Exception:
                    logger.warning(
                        "checkpoint 回退写失败（已忽略） method=%s", method, exc_info=True
                    )
            return default() if callable(default) else default

    def get_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        result = self._read("get_tuple", self.inner.get_tuple, config)
        return result

    def list(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> Iterator[CheckpointTuple]:
        try:
            yield from self.inner.list(config, filter=filter, before=before, limit=limit)
        except Exception:
            logger.warning("checkpoint 列表读失败（已忽略）", exc_info=True)
            if self.fallback is not None:
                try:
                    yield from self.fallback.list(
                        config, filter=filter, before=before, limit=limit
                    )
                except Exception:
                    logger.warning("checkpoint 回退列表读失败（已忽略）", exc_info=True)

    def put(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        def unchanged() -> RunnableConfig:
            return {
                "configurable": {
                    **config.get("configurable", {}),
                    "checkpoint_id": checkpoint["id"],
                }
            }

        return self._write(
            "put", self.inner.put, unchanged, config, checkpoint, metadata, new_versions
        )

    def put_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        self._write("put_writes", self.inner.put_writes, None, config, writes, task_id, task_path)

    def delete_thread(self, thread_id: str) -> None:
        self._write("delete_thread", self.inner.delete_thread, None, thread_id)

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        try:
            return await self.inner.aget_tuple(config)
        except Exception:
            logger.warning("checkpoint 异步读失败（已忽略）", exc_info=True)
            return None

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ):
        try:
            async for item in self.inner.alist(
                config, filter=filter, before=before, limit=limit
            ):
                yield item
        except Exception:
            logger.warning("checkpoint 异步列表读失败（已忽略）", exc_info=True)

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        try:
            return await self.inner.aput(config, checkpoint, metadata, new_versions)
        except Exception:
            logger.warning("checkpoint 异步写失败（已忽略）", exc_info=True)
            return {
                "configurable": {
                    **config.get("configurable", {}),
                    "checkpoint_id": checkpoint["id"],
                }
            }

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        try:
            await self.inner.aput_writes(config, writes, task_id, task_path)
        except Exception:
            logger.warning("checkpoint 异步写失败（已忽略）", exc_info=True)

    async def adelete_thread(self, thread_id: str) -> None:
        try:
            await self.inner.adelete_thread(thread_id)
        except Exception:
            logger.warning("checkpoint 异步删线程失败（已忽略）", exc_info=True)


def build_checkpointer(
    *,
    redis_url: str | None = None,
    redis_client: Any | None = None,
    prefix: str = "agent:ckpt:",
    ttl_seconds: int | None = None,
    fallback_to_memory: bool = True,
) -> BaseCheckpointSaver:
    """构造生产 checkpointer：Redis 优先，异常时回退内存，外层永远不阻断。"""
    inner: BaseCheckpointSaver | None = None

    if redis_client is not None:
        inner = RedisCheckpointSaver(redis_client, prefix=prefix, ttl_seconds=ttl_seconds)
    elif redis_url:
        try:
            import redis as redis_lib

            client = redis_lib.Redis.from_url(redis_url, socket_timeout=3.0)
            client.ping()
            inner = RedisCheckpointSaver(client, prefix=prefix, ttl_seconds=ttl_seconds)
        except Exception:
            logger.warning("Redis checkpoint 不可用，回退内存实现", exc_info=True)

    if inner is None:
        if not fallback_to_memory:
            raise RuntimeError("checkpoint 存储不可用（Redis 不可达且禁止回退）")
        from langgraph.checkpoint.memory import InMemorySaver

        inner = InMemorySaver()

    fallback: BaseCheckpointSaver | None = None
    if fallback_to_memory:
        from langgraph.checkpoint.memory import InMemorySaver

        fallback = InMemorySaver()

    return ResilientCheckpointSaver(inner, fallback=fallback)


def new_run_config(session_id: str, *, checkpoint_id: str | None = None) -> RunnableConfig:
    """会话 ID 即 thread_id；带上同一 config 即可断点恢复。"""
    configurable: dict[str, Any] = {"thread_id": session_id, "checkpoint_ns": ""}
    if checkpoint_id:
        configurable["checkpoint_id"] = checkpoint_id
    return {"configurable": configurable}


def _from_typed(typed: tuple[str, bytes]) -> bytes:
    # Redis 值统一存 bytes：`类型标签 + 0x1F + 载荷`。
    kind, body = typed
    return kind.encode() + b"\x1f" + body


def _to_typed(payload: Any) -> tuple[str, bytes]:
    if isinstance(payload, tuple) and len(payload) == 2:
        return str(payload[0]), payload[1]
    raw = payload if isinstance(payload, bytes) else str(payload).encode()
    kind, sep, body = raw.partition(b"\x1f")
    if not sep:
        return "json", raw
    return kind.decode(), body


def _decode(value: Any) -> str:
    if isinstance(value, bytes):
        return value.decode()
    return str(value)
