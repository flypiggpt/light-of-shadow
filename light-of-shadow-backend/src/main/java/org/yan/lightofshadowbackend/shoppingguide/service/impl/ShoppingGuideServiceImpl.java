package org.yan.lightofshadowbackend.shoppingguide.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.entity.ProductEntity;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;
import org.yan.lightofshadowbackend.shoppingguide.client.AgentGatewayClient;
import org.yan.lightofshadowbackend.shoppingguide.entity.MessageEntity;
import org.yan.lightofshadowbackend.shoppingguide.entity.SessionEntity;
import org.yan.lightofshadowbackend.shoppingguide.entity.ShoppingGuideFeedbackEntity;
import org.yan.lightofshadowbackend.shoppingguide.mapper.ShoppingGuideMapper;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideCompareRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideFeedbackRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideMessageRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideSessionCreateRequest;
import org.yan.lightofshadowbackend.shoppingguide.service.ShoppingGuideService;

@Service
public class ShoppingGuideServiceImpl implements ShoppingGuideService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingGuideServiceImpl.class);
    private static final int DEFAULT_RECOMMENDATION_POOL_SIZE = 100;
    private static final int DEFAULT_RECOMMENDATION_LIMIT = 10;

    private final ProductMapper productMapper;
    private final ShoppingGuideMapper shoppingGuideMapper;
    private final ShopAuthSupport authSupport;
    private final AgentGatewayClient agentGatewayClient;
    private final ShoppingGuideCompareService compareService;
    private final ShoppingGuideSessionStore sessionStore;

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public ShoppingGuideServiceImpl(ProductMapper productMapper,
                                   ShoppingGuideMapper shoppingGuideMapper,
                                   ShopAuthSupport authSupport,
                                   AgentGatewayClient agentGatewayClient,
                                   ShoppingGuideCompareService compareService,
                                   ShoppingGuideSessionStore sessionStore) {
        this.productMapper = productMapper;
        this.shoppingGuideMapper = shoppingGuideMapper;
        this.authSupport = authSupport;
        this.agentGatewayClient = agentGatewayClient;
        this.compareService = compareService;
        this.sessionStore = sessionStore;
    }

    @Override
    public Map<String, Object> createSession(String userId, ShoppingGuideSessionCreateRequest request) {
        String sessionId = generateId("sg_");
        Instant now = Instant.now();
        SessionState session = new SessionState();
        session.sessionId = sessionId;
        session.userId = userId;
        session.entry = safeTrim(request == null ? null : request.entry(), "shop");
        session.source = safeTrim(request == null ? null : request.source(), "shop");
        session.locale = safeTrim(request == null ? null : request.locale(), "zh-CN");
        session.status = "ACTIVE";
        session.createdAt = now;
        session.updatedAt = now;
        session.welcomeMessage = "你好，我是商城导购助手。你可以直接告诉我预算、用途或偏好，我来帮你推荐。";
        
        sessions.put(sessionId, session);

        session.lastIntent = buildIntent("welcome", 1.0, Map.of("entry", session.entry, "source", session.source, "locale", session.locale));

        SessionEntity entity = new SessionEntity();
        entity.setId(session.sessionId);
        entity.setUserId(session.userId);
        entity.setEntry(session.entry);
        entity.setSource(session.source);
        entity.setLocale(session.locale);
        entity.setStatus(session.status);
        entity.setWelcomeMessage(session.welcomeMessage);
        entity.setSummary("");
        entity.setLastIntent("");
        entity.setRecommendationsJson("[]");
        entity.setCreatedAt(session.createdAt);
        entity.setUpdatedAt(session.updatedAt);
        shoppingGuideMapper.insertSession(entity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", sessionSummary(session));
        result.put("welcomeMessage", session.welcomeMessage);
        return result;
    }

    @Override
    public Map<String, Object> latestSession(String userId) {
        SessionEntity entity = shoppingGuideMapper.selectLatestSessionByUserId(userId);
        if (entity == null) {
            return new LinkedHashMap<>();
        }
        return getSession(userId, entity.getId());
    }

    @Override
    public Map<String, Object> getSession(String userId, String sessionId) {
        SessionState session = requireSession(userId, sessionId, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", sessionSummary(session));
        result.put("latestRecommendations", toRecommendationMaps(session.recommendations, 1, session.recommendations.size()));
        return result;
    }

    @Override
    public Map<String, Object> sendMessage(String userId, String authHeader, String sessionId, ShoppingGuideMessageRequest request) {
        SessionState session = requireSession(userId, sessionId, true);
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "message不能为空");
        }
        String text = request.message().trim();

        // 转发 Python Agent（LangGraph 状态机 + 工具 + 记忆偏好）；Agent 不可用直接报错，不做本地兜底
        try {
            return forwardMessage(userId, authHeader, sessionId, text, request, session);
        } catch (AgentGatewayClient.AgentUnavailableException e) {
            log.warn("Agent 网关不可用 session={}", sessionId, e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "导购服务暂不可用，请稍后再试");
        }
        // Agent 业务错误（ApiException）直接向上传播，不回退
    }

    /** 转发消息到 Python Agent；成功后照旧落库 sg_message、刷新内存会话状态（历史权威仍在 Java）。 */
    private Map<String, Object> forwardMessage(String userId, String authHeader, String sessionId,
            String text, ShoppingGuideMessageRequest request, SessionState session) {
        Map<String, Object> clientContext = null;
        if (request.clientContext() != null) {
            clientContext = new LinkedHashMap<>();
            if (request.clientContext().page() != null) {
                clientContext.put("page", request.clientContext().page());
            }
            if (request.clientContext().selectedTab() != null) {
                clientContext.put("selectedTab", request.clientContext().selectedTab());
            }
        }
        Map<String, Object> data = agentGatewayClient.sendMessage(userId, authHeader, sessionId,
                AgentGatewayClient.ShoppingGuideMessageBody.of(text, clientContext));

        Instant now = Instant.now();
        MessageRecord userMessage = new MessageRecord(generateId("msg_"), sessionId, "user", "text", text, now);
        session.messages.add(userMessage);
        sessionStore.persistMessage(userMessage);

        MessageRecord assistantMessage = new MessageRecord(generateId("msg_"), sessionId, "assistant", "text",
                extractAssistantContent(data.get("assistantMessage")), Instant.now());
        session.messages.add(assistantMessage);
        sessionStore.persistMessage(assistantMessage);

        session.lastUserMessage = text;
        session.summary = summarize(session.summary, text);
        session.updatedAt = now;
        refreshSessionFromAgent(session, data);
        sessionStore.persistExtra(session);
        return data;
    }

    /** Agent 响应 → 内存会话状态（intent/recommendations 供 listRecommendations 与后续回退链路用）。 */
    private void refreshSessionFromAgent(SessionState session, Map<String, Object> data) {
        Object intentObj = data.get("intent");
        if (intentObj instanceof Map<?, ?> intent) {
            String name = intent.get("name") != null ? String.valueOf(intent.get("name")) : "other";
            double confidence = intent.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
            Map<String, Object> entities = intent.get("entities") instanceof Map<?, ?> e
                    ? new LinkedHashMap<>((Map<String, Object>) e)
                    : new LinkedHashMap<>();
            session.lastIntent = new ParsedIntent(name, confidence, entities);
        }
        if (data.get("recommendations") instanceof List<?> list) {
            List<Recommendation> recommendations = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m) || m.get("productId") == null) {
                    continue;
                }
                ProductEntity product = productMapper.selectById(String.valueOf(m.get("productId")));
                if (product == null) {
                    continue;
                }
                double score = m.get("score") instanceof Number n ? n.doubleValue() : 0.95;
                String reason = m.get("reason") instanceof String r && !r.isBlank() ? r : "为你推荐";
                recommendations.add(toRecommendation(product, score, reason));
            }
            session.recommendations.clear();
            session.recommendations.addAll(recommendations);
        }
    }

    private static String extractAssistantContent(Object assistantMessage) {
        if (assistantMessage instanceof Map<?, ?> m && m.get("content") != null) {
            return String.valueOf(m.get("content"));
        }
        return "我暂时没有找到完全匹配的商品，你可以再补充预算或偏好。";
    }

    @Override
    public Map<String, Object> listMessages(String userId, String sessionId, int page, int size) {
        SessionState session = requireSession(userId, sessionId, false);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size, 100), 1);
        int offset = (safePage - 1) * safeSize;
        List<MessageRecord> all = session.messages;
        int total = all.size();
        int end = Math.min(offset + safeSize, total);
        List<Map<String, Object>> items = new ArrayList<>();
        if (offset < total) {
            for (int i = offset; i < end; i++) {
                items.add(toMessageMap(all.get(i)));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("items", items);
        return result;
    }

    @Override
    public Map<String, Object> closeSession(String userId, String authHeader, String sessionId) {
        SessionState session = requireSession(userId, sessionId, false);
        shoppingGuideMapper.updateSessionStatus(sessionId, "ENDED", Instant.now());
        session.status = "ENDED";
        session.updatedAt = Instant.now();
        // 尽力关闭 Agent 侧线程（不可用仅告警，DB 状态已是权威）
        agentGatewayClient.closeSession(authHeader, sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.sessionId);
        result.put("status", session.status);
        return result;
    }

    @Override
    public Map<String, Object> getProfile(String userId, String authHeader) {
        Map<String, Object> profile = agentGatewayClient.getProfile(userId, authHeader);
        return profile != null ? profile : new LinkedHashMap<>();
    }

    @Override
    public Map<String, Object> listRecommendations(String userId, String sessionId, int page, int size) {
        SessionState session = requireSession(userId, sessionId, false);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size, 100), 1);
        int offset = (safePage - 1) * safeSize;
        int total = session.recommendations.size();
        int end = Math.min(offset + safeSize, total);
        List<Map<String, Object>> items = new ArrayList<>();
        if (offset < total) {
            items.addAll(toRecommendationMaps(session.recommendations, offset, end));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("items", items);
        return result;
    }

    @Override
    public Map<String, Object> compareProducts(String userId, ShoppingGuideCompareRequest request) {
        if (request == null || request.productIds() == null || request.productIds().size() < 2) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "至少选择两个商品进行对比");
        }
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            requireSession(userId, request.sessionId(), false);
        }
        return compareService.compare(request.productIds());
    }

    @Override
    public Map<String, Object> submitFeedback(String userId, ShoppingGuideFeedbackRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "sessionId不能为空");
        }
        SessionState session = requireSession(userId, request.sessionId(), false);
        if (request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "rating必须在1到5之间");
        }
        if (request.messageId() != null && !request.messageId().isBlank()) {
            boolean exists = session.messages.stream().anyMatch(message -> message.messageId.equals(request.messageId()));
            if (!exists) {
                throw new ApiException(ErrorCode.NOT_FOUND, "messageId不存在");
            }
        }
        String feedbackId = generateId("fb_");
        Instant now = Instant.now();
        session.feedbacks.add(new FeedbackRecord(
                feedbackId,
                request.sessionId(),
                request.messageId(),
                request.rating(),
                safeTrim(request.feedback(), ""),
                now));
        session.updatedAt = now;
        // 落库：重启后反馈不丢（此前只存内存）
        ShoppingGuideFeedbackEntity feedbackEntity = new ShoppingGuideFeedbackEntity();
        feedbackEntity.setId(feedbackId);
        feedbackEntity.setSessionId(request.sessionId());
        feedbackEntity.setMessageId(request.messageId());
        feedbackEntity.setRating(request.rating());
        feedbackEntity.setFeedback(safeTrim(request.feedback(), ""));
        feedbackEntity.setCreatedAt(now);
        shoppingGuideMapper.insertFeedback(feedbackEntity);

        return Map.of("saved", true);
    }

    @Override
    public List<String> supportedIntents() {
        return List.of("recommend_product", "compare_product", "view_cart", "checkout", "manage_cart", "unknown");
    }

    private SessionState requireSession(String userId, String sessionId, boolean activeOnly) {
        SessionState session = sessions.get(sessionId);
        if (session == null) {
            // 内存无、DB 有：恢复会话元数据与消息日志（Agent 状态不在此恢复，留给 LangGraph）
            SessionEntity entity = shoppingGuideMapper.selectSessionById(sessionId);
            if (entity == null) {
                throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
            }
            session = new SessionState();
            session.sessionId = entity.getId();
            session.userId = entity.getUserId();
            session.entry = entity.getEntry();
            session.source = entity.getSource();
            session.locale = entity.getLocale();
            session.status = entity.getStatus();
            session.welcomeMessage = entity.getWelcomeMessage();
            session.createdAt = entity.getCreatedAt();
            session.updatedAt = entity.getUpdatedAt();
            for (MessageEntity m : shoppingGuideMapper.selectMessagesBySession(sessionId)) {
                session.messages.add(new MessageRecord(
                        m.getId(), m.getSessionId(), m.getRole(), m.getContentType(), m.getContent(), m.getCreatedAt()));
                if ("user".equals(m.getRole()) && m.getContent() != null) {
                    session.lastUserMessage = m.getContent();
                }
            }
            sessionStore.restoreExtra(session, entity);
            sessions.put(sessionId, session);
        }
        if (!session.userId.equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权访问该会话");
        }
        if (activeOnly && "ENDED".equals(session.status)) {
            throw new ApiException(ErrorCode.SESSION_ENDED, "会话已结束");
        }
        return session;
    }

    /** 会话扩展状态（summary/意图/推荐）落库，重启后由 restoreSessionExtra 恢复。 */

    /** 从 DB 恢复 summary/lastIntent/recommendations（消息已在前面恢复）。 */



    private Recommendation toRecommendation(ProductEntity product, double score, String reason) {
        return new Recommendation(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPriceCent() == null ? 0 : product.getPriceCent(),
                product.getStock() == null ? 0 : product.getStock(),
                product.getCoverUrl(),
                round(score),
                reason);
    }

    private Map<String, Object> toMessageMap(MessageRecord message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("messageId", message.messageId);
        map.put("role", message.role);
        map.put("contentType", message.contentType);
        map.put("content", message.content);
        map.put("createdAt", message.createdAt);
        return map;
    }

    private Map<String, Object> sessionSummary(SessionState session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", session.sessionId);
        map.put("userId", session.userId);
        map.put("status", session.status);
        map.put("createdAt", session.createdAt);
        map.put("updatedAt", session.updatedAt);
        map.put("lastUserMessage", session.lastUserMessage);
        map.put("summary", session.summary);
        return map;
    }

    private List<Map<String, Object>> toRecommendationMaps(List<Recommendation> recommendations, int fromInclusive, int toExclusive) {
        List<Map<String, Object>> items = new ArrayList<>();
        int start = Math.max(0, fromInclusive - 1);
        int end = Math.min(recommendations.size(), toExclusive);
        for (int i = start; i < end; i++) {
            Recommendation recommendation = recommendations.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("productId", recommendation.productId());
            map.put("name", recommendation.name());
            map.put("description", recommendation.description());
            map.put("priceCent", recommendation.priceCent());
            map.put("stock", recommendation.stock());
            map.put("coverUrl", recommendation.coverUrl());
            map.put("score", recommendation.score());
            map.put("reason", recommendation.reason());
            items.add(map);
        }
        return items;
    }




    private ParsedIntent buildIntent(String name, double confidence, Map<String, Object> entities) {
        return new ParsedIntent(name, confidence, entities == null ? Map.of() : entities);
    }

    private String summarize(String summary, String message) {
        if (summary == null || summary.isBlank()) {
            return message;
        }
        if (summary.contains(message)) {
            return summary;
        }
        return summary + "；" + message;
    }

    private String generateId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String safeTrim(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static class SessionState {
        String sessionId;
        String userId;
        String entry;
        String source;
        String locale;
        String status;
        Instant createdAt;
        Instant updatedAt;
        String welcomeMessage;
        List<MessageRecord> messages = new ArrayList<>();
        List<Recommendation> recommendations = new ArrayList<>();
        List<FeedbackRecord> feedbacks = new ArrayList<>();
        String summary = "";
        String lastUserMessage = "";
        ParsedIntent lastIntent = new ParsedIntent("welcome", 1.0, Map.of());

        String initialHint() {
            return String.format("%s entry at %s, user requirement: %s", entry, source, summary);
        }
    }

    record MessageRecord(String messageId, String sessionId, String role, String contentType, String content, Instant createdAt) {
    }

    record Recommendation(String productId, String name, String description, int priceCent, int stock, String coverUrl, double score, String reason) {
    }

    record FeedbackRecord(String feedbackId, String sessionId, String messageId, int rating, String feedback, Instant createdAt) {
    }

    record ParsedIntent(String name, double confidence, Map<String, Object> entities) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("confidence", confidence);
            map.put("entities", entities);
            return map;
        }
    }
}





