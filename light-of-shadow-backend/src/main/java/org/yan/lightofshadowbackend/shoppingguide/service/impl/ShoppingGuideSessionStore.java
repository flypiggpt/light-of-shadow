package org.yan.lightofshadowbackend.shoppingguide.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yan.lightofshadowbackend.shoppingguide.entity.MessageEntity;
import org.yan.lightofshadowbackend.shoppingguide.entity.SessionEntity;
import org.yan.lightofshadowbackend.shoppingguide.mapper.ShoppingGuideMapper;

/**
 * 会话持久化：消息日志落库 + 扩展状态（summary/意图/推荐）落库与恢复。
 * 写失败只告警不阻断对话；重启后由 restoreExtra 恢复。
 */
@Component
public class ShoppingGuideSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ShoppingGuideSessionStore.class);

    private final ShoppingGuideMapper shoppingGuideMapper;
    private final ObjectMapper objectMapper;

    public ShoppingGuideSessionStore(ShoppingGuideMapper shoppingGuideMapper, ObjectMapper objectMapper) {
        this.shoppingGuideMapper = shoppingGuideMapper;
        this.objectMapper = objectMapper;
    }

    public void persistMessage(ShoppingGuideServiceImpl.MessageRecord record) {
        MessageEntity entity = new MessageEntity();
        entity.setId(record.messageId());
        entity.setSessionId(record.sessionId());
        entity.setRole(record.role());
        entity.setContentType(record.contentType());
        entity.setContent(record.content());
        entity.setCreatedAt(record.createdAt());
        shoppingGuideMapper.insertMessage(entity);
    }

    /** 会话扩展状态（summary/意图/推荐）落库。 */
    public void persistExtra(ShoppingGuideServiceImpl.SessionState session) {
        try {
            String intentJson = objectMapper.writeValueAsString(session.lastIntent.toMap());
            String recommendationsJson = objectMapper.writeValueAsString(
                    session.recommendations.stream().map(r -> {
                        Map<String, Object> map = new java.util.LinkedHashMap<>();
                        map.put("productId", r.productId());
                        map.put("name", r.name());
                        map.put("description", r.description());
                        map.put("priceCent", r.priceCent());
                        map.put("stock", r.stock());
                        map.put("coverUrl", r.coverUrl());
                        map.put("score", r.score());
                        map.put("reason", r.reason());
                        return map;
                    }).toList());
            shoppingGuideMapper.updateSessionExtra(session.sessionId,
                    session.summary == null ? "" : session.summary,
                    intentJson, recommendationsJson, Instant.now());
        } catch (Exception e) {
            log.warn("session_extra_persist_failed session={}", session.sessionId, e);
        }
    }

    /** 从 DB 恢复 summary/lastIntent/recommendations（消息日志由调用方先行恢复）。 */
    @SuppressWarnings("unchecked")
    public void restoreExtra(ShoppingGuideServiceImpl.SessionState session, SessionEntity entity) {
        session.summary = entity.getSummary() == null ? "" : entity.getSummary();
        try {
            if (entity.getLastIntent() != null && !entity.getLastIntent().isBlank()) {
                Map<String, Object> intent = objectMapper.readValue(entity.getLastIntent(), Map.class);
                session.lastIntent = new ShoppingGuideServiceImpl.ParsedIntent(
                        String.valueOf(intent.getOrDefault("name", "other")),
                        intent.get("confidence") instanceof Number n ? n.doubleValue() : 0.5,
                        intent.get("entities") instanceof Map<?, ?> e ? (Map<String, Object>) e : Map.of());
            }
        } catch (Exception e) {
            log.warn("session_intent_restore_failed session={}", session.sessionId, e);
        }
        try {
            if (entity.getRecommendationsJson() != null && !entity.getRecommendationsJson().isBlank()) {
                List<Map<String, Object>> recs = objectMapper.readValue(entity.getRecommendationsJson(), List.class);
                session.recommendations.clear();
                for (Map<String, Object> rec : recs) {
                    session.recommendations.add(new ShoppingGuideServiceImpl.Recommendation(
                            String.valueOf(rec.get("productId")),
                            String.valueOf(rec.getOrDefault("name", "")),
                            String.valueOf(rec.getOrDefault("description", "")),
                            rec.get("priceCent") instanceof Number n ? n.intValue() : 0,
                            rec.get("stock") instanceof Number n ? n.intValue() : 0,
                            String.valueOf(rec.getOrDefault("coverUrl", "")),
                            rec.get("score") instanceof Number n ? n.doubleValue() : 0.9,
                            String.valueOf(rec.getOrDefault("reason", ""))));
                }
            }
        } catch (Exception e) {
            log.warn("session_recommendations_restore_failed session={}", session.sessionId, e);
        }
    }
}
