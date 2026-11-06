package org.yan.lightofshadowbackend.shoppingguide.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.config.AgentProperties;
import org.yan.lightofshadowbackend.exception.ApiException;

/**
 * Python Agent 网关客户端：把导购消息/关闭/画像请求转发给 Agent 服务。
 *
 * <p>失败语义：
 * <ul>
 *   <li>连接失败 / 超时 / 5xx / 响应不可解析 → {@link AgentUnavailableException}（调用方按配置回退本地链路）</li>
 *   <li>Agent 业务错误（4xx 或 code!=0）→ {@link ApiException} 直接透传，不降级</li>
 * </ul>
 */
@Component
public class AgentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(AgentGatewayClient.class);
    private static final String API_PREFIX = "/api/shoppingguide";

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AgentGatewayClient(AgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
    }

    /** Agent 不可用（连接/超时/5xx），调用方可回退。 */
    public static class AgentUnavailableException extends RuntimeException {
        public AgentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 转发发消息：返回 Agent 响应 data（userMessage/assistantMessage/intent/recommendations/actions）。 */
    public Map<String, Object> sendMessage(String userId, String authHeader, String sessionId,
            ShoppingGuideMessageBody body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new AgentUnavailableException("请求体序列化失败", e);
        }
        return post(API_PREFIX + "/sessions/" + encode(sessionId) + "/messages", userId, authHeader, json);
    }

    /** 尽力关闭 Agent 侧会话：不可用仅告警，返回是否成功。 */
    public boolean closeSession(String authHeader, String sessionId) {
        try {
            post(API_PREFIX + "/sessions/" + encode(sessionId) + "/close", null, authHeader, "{}");
            return true;
        } catch (AgentUnavailableException e) {
            log.warn("agent_gateway.close_unavailable session={}", sessionId, e);
            return false;
        } catch (ApiException e) {
            log.warn("agent_gateway.close_failed session={} code={} msg={}", sessionId, e.getErrorCode(), e.getMessage());
            return false;
        }
    }

    /** 读取用户画像；Agent 不可用返回 null（调用方降级为空画像）。 */
    public Map<String, Object> getProfile(String userId, String authHeader) {
        try {
            return get(API_PREFIX + "/profile", userId, authHeader);
        } catch (AgentUnavailableException e) {
            log.warn("agent_gateway.profile_unavailable user={}", userId, e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // HTTP 内部实现
    // ------------------------------------------------------------------

    private Map<String, Object> post(String path, String userId, String authHeader, String json) {
        HttpRequest request = baseRequest(userId, authHeader)
                .uri(URI.create(properties.baseUrl() + path))
                .timeout(Duration.ofMillis(properties.readTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return unwrap(send(request, path), path);
    }

    private Map<String, Object> get(String path, String userId, String authHeader) {
        HttpRequest request = baseRequest(userId, authHeader)
                .uri(URI.create(properties.baseUrl() + path))
                .timeout(Duration.ofMillis(properties.readTimeoutMs()))
                .GET()
                .build();
        return unwrap(send(request, path), path);
    }

    private HttpRequest.Builder baseRequest(String userId, String authHeader) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        if (userId != null && !userId.isBlank()) {
            builder.header("X-User-Id", userId);
        }
        if (authHeader != null && !authHeader.isBlank()) {
            builder.header("Authorization", authHeader.startsWith("Bearer ") ? authHeader : "Bearer " + authHeader);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request, String path) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AgentUnavailableException("Agent 不可达: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Agent 请求被中断: " + path, e);
        }
    }

    private Map<String, Object> unwrap(HttpResponse<String> response, String path) {
        int status = response.statusCode();
        if (status >= 500) {
            throw new AgentUnavailableException("Agent 5xx: " + status + " " + path, null);
        }
        Map<String, Object> envelope = parseEnvelope(response.body(), path);
        Object code = envelope.get("code");
        int codeVal = code instanceof Number ? ((Number) code).intValue() : status;
        if (status >= 400 || codeVal != 0) {
            throw new ApiException(mapError(codeVal), String.valueOf(envelope.getOrDefault("message", "Agent 请求失败")));
        }
        Object data = envelope.get("data");
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private Map<String, Object> parseEnvelope(String body, String path) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            if (parsed.containsKey("code")) {
                return parsed;
            }
            throw new AgentUnavailableException("Agent 响应缺少 code 字段: " + path, null);
        } catch (IOException e) {
            throw new AgentUnavailableException("Agent 响应不可解析: " + path, e);
        }
    }

    private static ErrorCode mapError(int code) {
        if (code == 4002 || code == 404) {
            return ErrorCode.SESSION_NOT_FOUND;
        }
        if (code == 4003) {
            return ErrorCode.SESSION_ENDED;
        }
        if (code == 401) {
            return ErrorCode.UNAUTHORIZED;
        }
        return ErrorCode.BAD_REQUEST;
    }

    private static String encode(String sessionId) {
        return sessionId == null ? "" : sessionId.replaceAll("[^A-Za-z0-9_-]", "");
    }

    /** 转发用消息体（与 Agent SendMessageRequest 对齐）。 */
    public record ShoppingGuideMessageBody(String message, Map<String, Object> clientContext) {

        public static ShoppingGuideMessageBody of(String message, Map<String, Object> clientContext) {
            return new ShoppingGuideMessageBody(
                    Objects.requireNonNullElse(message, ""),
                    clientContext == null ? Map.of() : clientContext);
        }
    }
}
