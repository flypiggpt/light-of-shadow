package org.yan.lightofshadowbackend.shoppingguide.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yan.lightofshadowbackend.config.AgentProperties;
import org.yan.lightofshadowbackend.exception.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AgentGatewayClient：转发成功 / 头注入 / 业务错误透传 / 不可用判定。 */
class AgentGatewayClientTest {

    private HttpServer server;
    private AgentGatewayClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastUserId = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new AgentGatewayClient(
                new AgentProperties(baseUrl, 500, 2000, true), mapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void stub(int status, String body) {
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @Test
    void sendMessage_success_injectsHeadersAndReturnsData() {
        stub(200, "{\"code\":0,\"message\":\"ok\",\"data\":{\"assistantMessage\":{\"content\":\"你好\"}}}");

        Map<String, Object> data = client.sendMessage("u_123", "Bearer tok", "sg_abc",
                AgentGatewayClient.ShoppingGuideMessageBody.of("hi", Map.of("page", "Shop")));

        assertEquals("/api/shoppingguide/sessions/sg_abc/messages", lastPath.get());
        assertEquals("u_123", lastUserId.get());
        assertEquals("Bearer tok", lastAuth.get());
        assertTrue(lastBody.get().contains("\"hi\""));
        assertEquals("你好", ((Map<?, ?>) data.get("assistantMessage")).get("content"));
    }

    @Test
    void sendMessage_agent5xx_throwsUnavailable() {
        stub(503, "upstream broken");
        assertThrows(AgentGatewayClient.AgentUnavailableException.class,
                () -> client.sendMessage("u", "Bearer t", "sg", AgentGatewayClient.ShoppingGuideMessageBody.of("x", null)));
    }

    @Test
    void sendMessage_agent400_throwsBusinessApiException_notFallback() {
        stub(400, "{\"code\":400,\"message\":\"会话已结束\",\"data\":null}");

        ApiException e = assertThrows(ApiException.class,
                () -> client.sendMessage("u", "Bearer t", "sg", AgentGatewayClient.ShoppingGuideMessageBody.of("x", null)));
        assertEquals("会话已结束", e.getMessage());
    }

    @Test
    void connectRefused_throwsUnavailable() {
        AgentGatewayClient dead = new AgentGatewayClient(
                new AgentProperties("http://127.0.0.1:1", 200, 500, true), mapper);
        assertThrows(AgentGatewayClient.AgentUnavailableException.class,
                () -> dead.sendMessage("u", "Bearer t", "sg", AgentGatewayClient.ShoppingGuideMessageBody.of("x", null)));
    }

    @Test
    void closeSession_unavailable_returnsFalseWithoutThrow() {
        AgentGatewayClient dead = new AgentGatewayClient(
                new AgentProperties("http://127.0.0.1:1", 200, 500, true), mapper);
        assertEquals(false, dead.closeSession("Bearer t", "sg"));
    }

    @Test
    void getProfile_unavailable_returnsNull() {
        AgentGatewayClient dead = new AgentGatewayClient(
                new AgentProperties("http://127.0.0.1:1", 200, 500, true), mapper);
        assertNull(dead.getProfile("u", "Bearer t"));
    }

    @Test
    void getProfile_success_returnsData() {
        stub(200, "{\"code\":0,\"message\":\"ok\",\"data\":{\"budgetMaxCent\":20000}}");
        Map<String, Object> profile = client.getProfile("u_9", "Bearer t");
        assertEquals(20000, profile.get("budgetMaxCent"));
        assertEquals("u_9", lastUserId.get());
        assertInstanceOf(Map.class, profile);
    }
}
