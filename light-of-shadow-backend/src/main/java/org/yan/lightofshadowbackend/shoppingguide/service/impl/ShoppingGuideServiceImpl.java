package org.yan.lightofshadowbackend.shoppingguide.service.impl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.config.DeepSeekProperties;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.entity.ProductEntity;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideCompareRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideFeedbackRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideMessageRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideSessionCreateRequest;
import org.yan.lightofshadowbackend.shoppingguide.service.ShoppingGuideService;

@Service
public class ShoppingGuideServiceImpl implements ShoppingGuideService {

    private static final int DEFAULT_RECOMMENDATION_POOL_SIZE = 100;
    private static final int DEFAULT_RECOMMENDATION_LIMIT = 10;

    private final ProductMapper productMapper;
    private final ShopAuthSupport authSupport;
    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public ShoppingGuideServiceImpl(ProductMapper productMapper, 
                                   ShopAuthSupport authSupport, 
                                   DeepSeekProperties deepSeekProperties,
                                   ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.authSupport = authSupport;
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().build();
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", sessionSummary(session));
        result.put("welcomeMessage", session.welcomeMessage);
        return result;
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
    public Map<String, Object> sendMessage(String userId, String sessionId, ShoppingGuideMessageRequest request) {
        SessionState session = requireSession(userId, sessionId, true);
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "message不能为空");
        }

        Instant now = Instant.now();
        MessageRecord userMessage = new MessageRecord(generateId("msg_"), sessionId, "user", "text", request.message().trim(), now);
        session.messages.add(userMessage);
        session.lastUserMessage = request.message().trim();
        session.updatedAt = now;

        // Strictly use LLM to analyze the message and products
        LLMResult llmResult = callDeepSeek(session, userMessage.content());
        
        session.summary = summarize(session.summary, llmResult.summary() != null ? llmResult.summary() : userMessage.content());
        session.lastIntent = new ParsedIntent(llmResult.intent(), llmResult.confidence(), llmResult.entities());

        // Recommendations are now strictly driven by LLM selected IDs
        List<Recommendation> recommendations = new ArrayList<>();
        if (llmResult.recommendedProductIds() != null && !llmResult.recommendedProductIds().isEmpty()) {
            recommendations = llmResult.recommendedProductIds().stream()
                    .map(productMapper::selectById)
                    .filter(java.util.Objects::nonNull)
                    .map(p -> toRecommendation(p, 0.95, llmResult.reasoning() != null ? llmResult.reasoning() : "基于大模型分析深度推荐"))
                    .collect(Collectors.toList());
        }
        
        session.recommendations.clear();
        session.recommendations.addAll(recommendations);

        MessageRecord assistantMessage = new MessageRecord(generateId("msg_"), sessionId, "assistant", "text", llmResult.content(), Instant.now());
        session.messages.add(assistantMessage);

        // Actions are also strictly driven by LLM or a minimal set if LLM doesn't provide them
        List<Map<String, Object>> actions = llmResult.actions();
        if (actions == null) {
            actions = buildDefaultActions(session.lastIntent, recommendations);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userMessage", toMessageMap(userMessage));
        result.put("assistantMessage", toMessageMap(assistantMessage));
        result.put("intent", session.lastIntent.toMap());
        result.put("recommendations", toRecommendationMaps(recommendations, 1, recommendations.size()));
        result.put("actions", actions);
        return result;
    }

    private LLMResult callDeepSeek(SessionState session, String currentMessage) {
        try {
            // Provide a broader context of products to the LLM
            List<ProductEntity> products = productMapper.selectPage(0, 50, null); 
            String productsJson = objectMapper.writeValueAsString(products.stream().map(p -> Map.of(
                    "id", p.getId(),
                    "name", p.getName(),
                    "description", p.getDescription() != null ? p.getDescription() : "",
                    "priceCent", p.getPriceCent(),
                    "stock", p.getStock()
            )).collect(Collectors.toList()));

            String systemPrompt = """
                You are the core intelligence of 'Light of Shadow' AI Shopping Assistant (Java-based backend).
                Store niche: Specialized products for visually impaired individuals.
                
                Available Catalog Data: %s
                
                Your responsibilities:
                1. Identify detailed user intent.
                2. Extract entities (budget, preferences).
                3. Select precise product IDs from the catalog that match user needs.
                4. Generate a helpful, concise response in the user's language.
                5. Suggest subsequent actions.
                
                Output MUST be a valid JSON object:
                {
                  "intent": "recommend_product" | "compare_product" | "view_cart" | "checkout" | "manage_cart" | "unknown",
                  "confidence": 0.0-1.0,
                  "entities": { "minPriceCent": number, "maxPriceCent": number, "preference": string[] },
                  "content": "natural language response",
                  "summary": "one sentence requirement summary",
                  "recommendedProductIds": ["id1", "id2"],
                  "reasoning": "why these specific products match",
                  "actions": [ { "type": "add_to_cart" | "compare" | "show_more" | "view_cart" | "checkout", "label": "button text", "productId": "id", "quantity": 1 } ]
                }
                Locale: %s
                """.formatted(productsJson, session.locale);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            
            // Context window management
            int startIdx = Math.max(0, session.messages.size() - 6);
            for (int i = startIdx; i < session.messages.size(); i++) {
                MessageRecord msg = session.messages.get(i);
                messages.add(Map.of("role", msg.role().equals("assistant") ? "assistant" : "user", "content", msg.content()));
            }

            Map<String, Object> body = new HashMap<>();
            body.put("model", deepSeekProperties.model());
            body.put("messages", messages);
            body.put("response_format", Map.of("type", "json_object"));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(deepSeekProperties.baseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + deepSeekProperties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "AI服务响应异常: " + response.statusCode());
            }

            Map<String, Object> respMap = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

            return objectMapper.readValue(content, LLMResult.class);
        } catch (Exception e) {
            if (e instanceof ApiException) throw (ApiException) e;
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "AI引擎连接失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> buildDefaultActions(ParsedIntent intent, List<Recommendation> recommendations) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (!recommendations.isEmpty()) {
            Recommendation top = recommendations.getFirst();
            actions.add(action("add_to_cart", "加入购物车", top.productId(), 1));
        }
        actions.add(action("view_cart", "查看购物车", null, null));
        if ("checkout".equals(intent.name)) {
            actions.add(action("checkout", "结账", null, null));
        }
        return actions;
    }

    private record LLMResult(
            String intent,
            double confidence,
            Map<String, Object> entities,
            String content,
            String summary,
            List<String> recommendedProductIds,
            String reasoning,
            List<Map<String, Object>> actions) {}

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
    public Map<String, Object> closeSession(String userId, String sessionId) {
        SessionState session = requireSession(userId, sessionId, false);
        session.status = "ENDED";
        session.updatedAt = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.sessionId);
        result.put("status", session.status);
        return result;
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
        List<Map<String, Object>> items = new ArrayList<>();
        List<ProductEntity> products = new ArrayList<>();
        for (String productId : request.productIds()) {
            ProductEntity product = requireProduct(productId);
            products.add(product);
            items.add(compareItem(product));
        }
        String summary = buildCompareSummary(products);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("items", items);
        return result;
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
        session.feedbacks.add(new FeedbackRecord(
                generateId("fb_"),
                request.sessionId(),
                request.messageId(),
                request.rating(),
                safeTrim(request.feedback(), ""),
                Instant.now()));
        session.updatedAt = Instant.now();

        return Map.of("saved", true);
    }

    @Override
    public List<String> supportedIntents() {
        return List.of("recommend_product", "compare_product", "view_cart", "checkout", "manage_cart", "unknown");
    }

    private SessionState requireSession(String userId, String sessionId, boolean activeOnly) {
        SessionState session = sessions.get(sessionId);
        if (session == null) {
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
        }
        if (!session.userId.equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权访问该会话");
        }
        if (activeOnly && "ENDED".equals(session.status)) {
            throw new ApiException(ErrorCode.SESSION_ENDED, "会话已结束");
        }
        return session;
    }

    private ProductEntity requireProduct(String productId) {
        ProductEntity product = productMapper.selectById(productId);
        if (product == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return product;
    }

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

    private Map<String, Object> action(String type, String label, String productId, Integer quantity) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", type);
        action.put("label", label);
        if (productId != null) {
            action.put("productId", productId);
        }
        if (quantity != null) {
            action.put("quantity", quantity);
        }
        return action;
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

    private Map<String, Object> compareItem(ProductEntity product) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("productId", product.getId());
        map.put("name", product.getName());
        map.put("highlights", buildHighlights(product));
        map.put("priceCent", product.getPriceCent() == null ? 0 : product.getPriceCent());
        map.put("stock", product.getStock() == null ? 0 : product.getStock());
        map.put("coverUrl", product.getCoverUrl());
        return map;
    }

    private List<String> buildHighlights(ProductEntity product) {
        List<String> highlights = new ArrayList<>();
        String text = ((product.getName() == null ? "" : product.getName()) + " " + (product.getDescription() == null ? "" : product.getDescription())).toLowerCase(Locale.ROOT);
        if (text.contains("语音")) highlights.add("语音播报");
        if (text.contains("轻") || text.contains("量")) highlights.add("超轻便设计");
        if (highlights.isEmpty()) highlights.add("专业辅具");
        return highlights;
    }

    private String buildCompareSummary(List<ProductEntity> products) {
        if (products.size() < 2) {
            return "商品数量不足，无法对比。";
        }
        ProductEntity first = products.get(0);
        ProductEntity second = products.get(1);
        return "“" + first.getName() + "”和“" + second.getName() + "”已完成对比，前者更适合优先关注，后者可作为备选。";
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

    private static class SessionState {
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

    private record MessageRecord(String messageId, String sessionId, String role, String contentType, String content, Instant createdAt) {
    }

    private record Recommendation(String productId, String name, String description, int priceCent, int stock, String coverUrl, double score, String reason) {
    }

    private record FeedbackRecord(String feedbackId, String sessionId, String messageId, int rating, String feedback, Instant createdAt) {
    }

    private record ParsedIntent(String name, double confidence, Map<String, Object> entities) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("confidence", confidence);
            map.put("entities", entities);
            return map;
        }
    }
}





