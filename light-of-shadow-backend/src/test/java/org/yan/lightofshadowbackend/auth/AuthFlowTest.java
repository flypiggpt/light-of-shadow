package org.yan.lightofshadowbackend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权核心链路：登录 → 受保护接口 → refresh 轮换 → 登出作废。
 * 数据走 H2（内存），每个用例事务回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void seedUser() {
        jdbc.update(
                "INSERT INTO sys_user (id, username, email, password_hash, nickname) VALUES (?,?,?,?,?)",
                "u_test_auth", "authtest", "authtest@example.com", encoder.encode("pw123456"), "测试用户");
    }

    private JsonNode postJson(String path, String body) throws Exception {
        String resp = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    @Test
    void loginWithWrongPasswordFails() throws Exception {
        JsonNode resp = postJson("/api/auth/login",
                "{\"account\":\"authtest\",\"password\":\"wrong\"}");
        assertEquals(401, resp.get("code").asInt());
    }

    @Test
    void loginSuccessReturnsTokens() throws Exception {
        JsonNode resp = postJson("/api/auth/login",
                "{\"account\":\"authtest\",\"password\":\"pw123456\"}");
        assertEquals(0, resp.get("code").asInt());
        JsonNode data = resp.get("data");
        assertNotNull(data.get("accessToken").asText());
        assertNotNull(data.get("refreshToken").asText());
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/shop/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenRotates() throws Exception {
        JsonNode login = postJson("/api/auth/login",
                "{\"account\":\"authtest\",\"password\":\"pw123456\"}");
        String refresh1 = login.get("data").get("refreshToken").asText();

        JsonNode refreshed = postJson("/api/auth/refresh", "{\"refreshToken\":\"" + refresh1 + "\"}");
        assertEquals(0, refreshed.get("code").asInt());
        String refresh2 = refreshed.get("data").get("refreshToken").asText();
        assertNotEquals(refresh1, refresh2, "refresh token 应轮换");

        // 旧 refresh 已作废，复用应失败
        JsonNode reuse = postJson("/api/auth/refresh", "{\"refreshToken\":\"" + refresh1 + "\"}");
        assertNotEquals(0, reuse.get("code").asInt());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        JsonNode login = postJson("/api/auth/login",
                "{\"account\":\"authtest\",\"password\":\"pw123456\"}");
        String access = login.get("data").get("accessToken").asText();
        String refresh = login.get("data").get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk());

        JsonNode reuse = postJson("/api/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
        assertNotEquals(0, reuse.get("code").asInt(), "登出后 refresh 应作废");
    }

    // ---- 商家邀请码注册 ----

    @Autowired
    private org.yan.lightofshadowbackend.auth.code.VerificationCodeService codeService;

    private String registerBody(String username, String email, String code, String invite) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"pw123456\","
                + "\"nickname\":\"昵称\",\"verificationCode\":\"" + code + "\",\"inviteCode\":\"" + invite + "\"}";
    }

    @Test
    void registerWithMerchantInviteCreatesMerchant() throws Exception {
        String code = codeService.send("m@example.com", "register").code();
        JsonNode resp = postJson("/api/auth/register",
                registerBody("newmerchant", "m@example.com", code, "test-merchant-invite"));
        assertEquals(0, resp.get("code").asInt());
        String role = jdbc.queryForObject(
                "SELECT role FROM sys_user WHERE username='newmerchant'", String.class);
        assertEquals("MERCHANT", role, "带有效邀请码应注册为商家");
    }

    @Test
    void registerWithWrongInviteRejected() throws Exception {
        String code = codeService.send("w@example.com", "register").code();
        JsonNode resp = postJson("/api/auth/register",
                registerBody("wronginvite", "w@example.com", code, "not-the-code"));
        assertNotEquals(0, resp.get("code").asInt(), "错误邀请码应拒绝");
    }

    @Test
    void registerWithoutInviteIsNormalUser() throws Exception {
        String code = codeService.send("n@example.com", "register").code();
        JsonNode resp = postJson("/api/auth/register",
                registerBody("normaluser", "n@example.com", code, ""));
        assertEquals(0, resp.get("code").asInt());
        String role = jdbc.queryForObject(
                "SELECT role FROM sys_user WHERE username='normaluser'", String.class);
        assertEquals("USER", role);
    }
}
