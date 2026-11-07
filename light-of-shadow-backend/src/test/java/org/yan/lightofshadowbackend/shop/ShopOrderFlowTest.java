package org.yan.lightofshadowbackend.shop;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商城下单核心链路：加购 → 下单（扣库存）→ 支付 / 取消 / 删除（恢复库存）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShopOrderFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private String token;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update(
                "INSERT INTO sys_user (id, username, email, password_hash, nickname) VALUES (?,?,?,?,?)",
                "u_merchant_1", "shoptest", "shoptest@example.com", encoder.encode("pw123456"), "商家");
        jdbc.update(
                "INSERT INTO sys_user (id, username, email, password_hash, nickname) VALUES (?,?,?,?,?)",
                "u_buyer_1", "buytest", "buytest@example.com", encoder.encode("pw123456"), "买家");
        jdbc.update(
                "INSERT INTO product (id, name, description, price_cent, stock, merchant_id) VALUES (?,?,?,?,?,?)",
                "p_test_1", "测试耳机", "测试商品", 9900, 10, "u_merchant_1");

        String login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"buytest\",\"password\":\"pw123456\"}"))
                .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(login).get("data").get("accessToken").asText();
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                          String body) throws Exception {
        String resp = mockMvc.perform(builder
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id='p_test_1'", Integer.class);
    }

    private String createOrder() throws Exception {
        call(post("/api/shop/cart/items"), "{\"productId\":\"p_test_1\",\"quantity\":2}");
        JsonNode order = call(post("/api/shop/orders"), "{}");
        assertEquals(0, order.get("code").asInt());
        return order.get("data").get("orderId").asText();
    }

    @Test
    void productListShowsSeededProduct() throws Exception {
        mockMvc.perform(get("/api/shop/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value("p_test_1"));
    }

    @Test
    void orderLifecycle_pay() throws Exception {
        String orderId = createOrder();
        assertEquals(8, stock(), "下单后库存应扣减");

        JsonNode pay = call(post("/api/shop/orders/" + orderId + "/pay"), "{}");
        assertEquals(0, pay.get("code").asInt());
        assertEquals("PAID", pay.get("data").get("status").asText());
    }

    @Test
    void cancelPendingOrderRestoresStock() throws Exception {
        String orderId = createOrder();
        assertEquals(8, stock());

        JsonNode cancel = call(post("/api/shop/orders/" + orderId + "/cancel"), "{}");
        assertEquals(0, cancel.get("code").asInt());
        assertEquals("CANCELED", cancel.get("data").get("status").asText());
        assertEquals(10, stock(), "取消后库存应恢复");
    }

    @Test
    void cancelPaidOrderRejected() throws Exception {
        String orderId = createOrder();
        call(post("/api/shop/orders/" + orderId + "/pay"), "{}");
        JsonNode cancel = call(post("/api/shop/orders/" + orderId + "/cancel"), "{}");
        assertEquals(400, cancel.get("code").asInt(), "已支付订单不可取消");
    }

    @Test
    void deletePendingOrderRemovesRowsAndRestoresStock() throws Exception {
        String orderId = createOrder();
        assertEquals(8, stock());

        JsonNode del = call(delete("/api/shop/orders/" + orderId), "{}");
        assertEquals(0, del.get("code").asInt());
        assertEquals(10, stock(), "删除待支付订单应恢复库存");
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE id=?", Integer.class, orderId);
        assertEquals(0, rows, "订单行应删除");
        Integer items = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_order_item WHERE order_id=?", Integer.class, orderId);
        assertEquals(0, items, "订单条目应删除");
    }

    @Test
    void deleteAlsoAcceptsOrderNo() throws Exception {
        String orderId = createOrder();
        String orderNo = jdbc.queryForObject(
                "SELECT order_no FROM shop_order WHERE id=?", String.class, orderId);
        JsonNode del = call(delete("/api/shop/orders/" + orderNo), "{}");
        assertEquals(0, del.get("code").asInt(), "订单号也应能删（口播单号场景）");
        assertEquals(10, stock());
    }
}
