package org.yan.lightofshadowbackend.shop.proactive.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactiveEventEntity;

@Mapper
public interface ProactiveEventMapper {

    /** 幂等插入：dedupe_key 唯一约束兜底，冲突返回 0。 */
    int insert(ProactiveEventEntity event);

    ProactiveEventEntity selectByDedupeKey(@Param("dedupeKey") String dedupeKey);

    ProactiveEventEntity selectById(@Param("id") String id);

    /** 同类信号近 N 条（负反馈计数、冷却判定用）。 */
    List<ProactiveEventEntity> selectRecentByUserAndType(@Param("userId") String userId,
            @Param("type") String type,
            @Param("limit") int limit);

    /** 用户当天已放行信号数（限额用，不含指定类型）。 */
    long countSentToday(@Param("userId") String userId,
            @Param("excludeType") String excludeType,
            @Param("dayStart") Instant dayStart);

    int updateStatus(@Param("id") String id,
            @Param("status") String status,
            @Param("actionId") String actionId,
            @Param("resolvedAt") Instant resolvedAt);

    /** 发送落库：提议文案/动作 + 会话 + 发送时刻。 */
    int markSent(@Param("id") String id,
            @Param("proposalJson") String proposalJson,
            @Param("sessionId") String sessionId,
            @Param("proposedAt") Instant proposedAt);

    /** 用户的未读提议（SENT 且未处理）。 */
    List<ProactiveEventEntity> selectSentByUser(@Param("userId") String userId,
            @Param("limit") int limit);

    /** 待派发的已放行事件（PENDING）。 */
    List<ProactiveEventEntity> selectPending(@Param("limit") int limit);

    /** 过期未处理的 SENT 事件（24 小时确认时效）。 */
    List<ProactiveEventEntity> selectExpired(@Param("before") Instant before);

    /** 冷却判定：同 ref 最近一次同类事件。 */
    ProactiveEventEntity selectLatestByUserTypeRef(@Param("userId") String userId,
            @Param("type") String type,
            @Param("refId") String refId);
}
