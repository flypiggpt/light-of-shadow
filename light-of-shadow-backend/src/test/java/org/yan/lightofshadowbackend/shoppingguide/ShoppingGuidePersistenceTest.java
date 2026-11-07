package org.yan.lightofshadowbackend.shoppingguide;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.yan.lightofshadowbackend.shoppingguide.client.AgentGatewayClient;
import org.yan.lightofshadowbackend.shoppingguide.service.ShoppingGuideService;
import org.yan.lightofshadowbackend.shoppingguide.service.impl.ShoppingGuideServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话/反馈持久化：feedback 落库、summary/意图/推荐跨重启恢复。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShoppingGuidePersistenceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShoppingGuideService shoppingGuideService;

    @MockBean
    private AgentGatewayClient gateway;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private String token;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update(
                "INSERT INTO sys_user (id, username, email, password_hash, nickname) VALUES (?,?,?,?,?)",
                "u_persist", "persisttest", "persist@example.com", encoder.encode("pw123456"), "用户");
        jdbc.update(
                "INSERT INTO sys_user (id, username, email, password_hash, nickname) VALUES (?,?,?,?,?)",
                "u_merchant_p", "pmtest", "pmtest@example.com", encoder.encode("pw123456"), "商家");
        jdbc.update(
                "INSERT INTO product (id, name, description, price_cent, stock, merchant_id) VALUES (?,?,?,?,?,?)",
                "p_persist_1", "测试耳机", "测试商品", 9900, 10, "u_merchant_p");

        String login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"persisttest\",\"password\":\"pw123456\"}"))
                .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(login).get("data").get("accessToken").asText();
    }

    private String createSession() throws Exception {
        String resp = mockMvc.perform(post("/api/shoppingguide/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entry\":\"shop\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("session").get("sessionId").asText();
    }

    @Test
    void feedbackPersistsToDatabase() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(post("/api/shoppingguide/feedback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"rating\":5,\"feedback\":\"推荐很准\"}"))
                .andExpect(status().isOk());

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sg_feedback WHERE session_id=? AND rating=5",
                Integer.class, sessionId);
        assertEquals(1, rows, "反馈应落库（此前只存内存）");
    }

    @Test
    void sessionExtraPersistsAndRestoresAfterRestart() throws Exception {
        String sessionId = createSession();

        when(gateway.sendMessage(anyString(), anyString(), anyString(), any())).thenReturn(Map.of(
                "assistantMessage", Map.of("content", "为你推荐测试耳机"),
                "intent", Map.of("name", "recommend_product", "confidence", 0.9, "entities", Map.of()),
                "recommendations", List.of(Map.of(
                        "productId", "p_persist_1", "name", "测试耳机", "priceCent", 9900,
                        "stock", 10, "coverUrl", "", "score", 0.9, "reason", "合适"))));

        mockMvc.perform(post("/api/shoppingguide/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "u_persist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"推荐个耳机\"}"))
                .andExpect(status().isOk());

        // 扩展状态已落库
        String summary = jdbc.queryForObject(
                "SELECT COALESCE(summary,'') FROM sg_session WHERE id=?", String.class, sessionId);
        String intentJson = jdbc.queryForObject(
                "SELECT COALESCE(last_intent,'') FROM sg_session WHERE id=?", String.class, sessionId);
        String recsJson = jdbc.queryForObject(
                "SELECT COALESCE(recommendations_json,'') FROM sg_session WHERE id=?", String.class, sessionId);
        assertTrue(intentJson.contains("recommend_product"), "意图应落库: " + intentJson);
        assertTrue(recsJson.contains("p_persist_1"), "推荐应落库: " + recsJson);

        // 模拟重启：清空内存会话，再读——应从 DB 恢复
        Field field = ShoppingGuideServiceImpl.class.getDeclaredField("sessions");
        field.setAccessible(true);
        ((Map<?, ?>) field.get(shoppingGuideService)).clear();

        String resp = mockMvc.perform(get("/api/shoppingguide/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(resp).get("data");
        JsonNode recs = data.get("latestRecommendations");
        assertTrue(recs != null && recs.size() > 0, "重启后推荐应恢复");
        assertEquals("p_persist_1", recs.get(0).get("productId").asText());
    }
}
