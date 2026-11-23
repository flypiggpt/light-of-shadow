package org.yan.lightofshadowbackend.shop.proactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.yan.lightofshadowbackend.shop.proactive.service.ProactiveScheduler;
import org.yan.lightofshadowbackend.shop.proactive.service.ProactiveSignalScanner;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 信号检测与超时关单：造表数据断言事件产出。
 */
@SpringBootTest
@Transactional
class ProactiveSignalScanTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProactiveSignalScanner scanner;

    @Autowired
    private ProactiveScheduler scheduler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void seedMerchant() {
        seedUser("u_merchant_1");
    }

    private void seedUser(String id) {
        jdbc.update("INSERT INTO sys_user (id, username, email, password_hash, nickname) VALUES (?,?,?,?,?)",
                id, id + "_name", id + "@example.com", "hash", id + "_nick");
    }

    private void seedProduct(String id, String name, int priceCent, int stock) {
        jdbc.update("INSERT INTO product (id, name, description, price_cent, stock, merchant_id) VALUES (?,?,?,?,?,?)",
                id, name, "desc", priceCent, stock, "u_merchant_1");
    }

    private void seedOrder(String id, String userId, String status, int totalCent, Instant createdAt) {
        jdbc.update("INSERT INTO shop_order (id, order_no, user_id, status, total_cent, created_at) VALUES (?,?,?,?,?,?)",
                id, "no_" + id, userId, status, totalCent, createdAt);
    }

    private void seedOrderItem(String orderId, String productId, String name, int priceCent, int qty) {
        jdbc.update("INSERT INTO shop_order_item (order_id, product_id, product_name, unit_price_cent, quantity) VALUES (?,?,?,?,?)",
                orderId, productId, name, priceCent, qty);
    }

    private void seedCartItem(String userId, String productId, int qty, Instant createdAt) {
        jdbc.update("INSERT INTO cart_item (user_id, product_id, quantity, created_at) VALUES (?,?,?,?)",
                userId, productId, qty, createdAt);
    }

    private List<Map<String, Object>> eventsOf(String type) {
        return jdbc.queryForList("SELECT * FROM proactive_event WHERE type = ?", type);
    }

    @Test
    void 催付信号_待支付满5分钟触发() throws Exception {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 10);
        Instant created = Instant.now().minus(6, ChronoUnit.MINUTES);
        seedOrder("o_1", "u_buyer_1", "PENDING_PAYMENT", 2900, created);
        seedOrderItem("o_1", "p_1", "牛奶", 2900, 1);

        int n = scanner.scan(Instant.now());

        assertEquals(1, n);
        List<Map<String, Object>> events = eventsOf("UNPAID_ORDER");
        assertEquals(1, events.size());
        String payload = (String) events.get(0).get("payload_json");
        assertTrue(payload.contains("牛奶"));
        assertTrue(payload.contains("2900"));
    }

    @Test
    void 催付信号_未满5分钟不触发() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 10);
        seedOrder("o_1", "u_buyer_1", "PENDING_PAYMENT", 2900,
                Instant.now().minus(2, ChronoUnit.MINUTES));
        seedOrderItem("o_1", "p_1", "牛奶", 2900, 1);

        int n = scanner.scan(Instant.now());

        assertEquals(0, n);
        assertTrue(eventsOf("UNPAID_ORDER").isEmpty());
    }

    @Test
    void 催付信号_重复扫描幂等() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 10);
        seedOrder("o_1", "u_buyer_1", "PENDING_PAYMENT", 2900,
                Instant.now().minus(6, ChronoUnit.MINUTES));
        seedOrderItem("o_1", "p_1", "牛奶", 2900, 1);

        scanner.scan(Instant.now());
        int second = scanner.scan(Instant.now());

        assertEquals(0, second);
        assertEquals(1, eventsOf("UNPAID_ORDER").size());
    }

    @Test
    void 重复下单_10分钟内同商品两单触发() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 10);
        Instant earlier = Instant.now().minus(3, ChronoUnit.MINUTES);
        seedOrder("o_old", "u_buyer_1", "PENDING_PAYMENT", 2900, earlier);
        seedOrderItem("o_old", "p_1", "牛奶", 2900, 1);
        seedOrder("o_new", "u_buyer_1", "PENDING_PAYMENT", 2900, Instant.now());
        seedOrderItem("o_new", "p_1", "牛奶", 2900, 1);

        int n = scanner.checkDuplicateOrder("u_buyer_1", "o_new", Instant.now());

        assertEquals(1, n);
        List<Map<String, Object>> events = eventsOf("DUP_ORDER");
        assertEquals(1, events.size());
        String payload = (String) events.get(0).get("payload_json");
        assertTrue(payload.contains("DUPLICATE"));
    }

    @Test
    void 数量异常_远超历史均量触发() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 100);
        // 两次历史购买，每次 1 件
        seedOrder("o_h1", "u_buyer_1", "PAID", 2900, Instant.now().minus(30, ChronoUnit.DAYS));
        seedOrderItem("o_h1", "p_1", "牛奶", 2900, 1);
        seedOrder("o_h2", "u_buyer_1", "PAID", 2900, Instant.now().minus(20, ChronoUnit.DAYS));
        seedOrderItem("o_h2", "p_1", "牛奶", 2900, 1);
        // 本次 8 件（≥5 且 ≥ 均量 3 倍）
        seedOrder("o_new", "u_buyer_1", "PENDING_PAYMENT", 23200, Instant.now());
        seedOrderItem("o_new", "p_1", "牛奶", 2900, 8);

        int n = scanner.checkDuplicateOrder("u_buyer_1", "o_new", Instant.now());

        assertEquals(1, n);
        String payload = (String) eventsOf("DUP_ORDER").get(0).get("payload_json");
        assertTrue(payload.contains("QUANTITY"));
    }

    @Test
    void 正常下单_不误报() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 100);
        seedOrder("o_new", "u_buyer_1", "PENDING_PAYMENT", 2900, Instant.now());
        seedOrderItem("o_new", "p_1", "牛奶", 2900, 1);

        int n = scanner.checkDuplicateOrder("u_buyer_1", "o_new", Instant.now());

        assertEquals(0, n);
    }

    @Test
    void 购物车闲置_满3天触发() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 10);
        seedCartItem("u_buyer_1", "p_1", 1, Instant.now().minus(4, ChronoUnit.DAYS));

        int n = scanner.scan(Instant.now());

        assertEquals(1, n);
        List<Map<String, Object>> events = eventsOf("CART_IDLE");
        assertEquals(1, events.size());
        String payload = (String) events.get(0).get("payload_json");
        assertTrue(payload.contains("牛奶"));
        assertTrue(payload.contains("4")); // 闲置天数
    }

    @Test
    void 购物车闲置_未满3天不触发() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 10);
        seedCartItem("u_buyer_1", "p_1", 1, Instant.now().minus(2, ChronoUnit.DAYS));

        assertEquals(0, scanner.scan(Instant.now()));
    }

    @Test
    void 降价_低于历史成交价一成触发() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2400, 10); // 现价 24 元
        seedOrder("o_1", "u_buyer_1", "PAID", 2900, Instant.now().minus(20, ChronoUnit.DAYS));
        seedOrderItem("o_1", "p_1", "牛奶", 2900, 1); // 当时 29 元

        int n = scanner.scan(Instant.now());

        assertEquals(1, n);
        List<Map<String, Object>> events = eventsOf("PRICE_DROP");
        assertEquals(1, events.size());
        String payload = (String) events.get(0).get("payload_json");
        assertTrue(payload.contains("2900"));
        assertTrue(payload.contains("2400"));
    }

    @Test
    void 降幅不足一成_不触发() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2800, 10); // 降 3.4%
        seedOrder("o_1", "u_buyer_1", "PAID", 2900, Instant.now().minus(20, ChronoUnit.DAYS));
        seedOrderItem("o_1", "p_1", "牛奶", 2900, 1);

        assertEquals(0, scanner.scan(Instant.now()));
    }

    @Test
    void 超时关单_待支付超30分钟取消并恢复库存() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 7);
        seedOrder("o_1", "u_buyer_1", "PENDING_PAYMENT", 2900,
                Instant.now().minus(31, ChronoUnit.MINUTES));
        seedOrderItem("o_1", "p_1", "牛奶", 2900, 3);

        int canceled = scheduler.cancelExpiredOrders(Instant.now());

        assertEquals(1, canceled);
        String status = jdbc.queryForObject("SELECT status FROM shop_order WHERE id = 'o_1'", String.class);
        assertEquals("CANCELED", status);
        Integer stock = jdbc.queryForObject("SELECT stock FROM product WHERE id = 'p_1'", Integer.class);
        assertEquals(10, stock); // 7 + 3
    }

    @Test
    void 超时关单_30分钟内不动() {
        seedUser("u_buyer_1");
        seedProduct("p_1", "牛奶", 2900, 7);
        seedOrder("o_1", "u_buyer_1", "PENDING_PAYMENT", 2900,
                Instant.now().minus(20, ChronoUnit.MINUTES));
        seedOrderItem("o_1", "p_1", "牛奶", 2900, 3);

        assertEquals(0, scheduler.cancelExpiredOrders(Instant.now()));
        String status = jdbc.queryForObject("SELECT status FROM shop_order WHERE id = 'o_1'", String.class);
        assertEquals("PENDING_PAYMENT", status);
    }

    @Test
    void 提议24小时未确认_过期() {
        seedUser("u_buyer_1");
        jdbc.update("INSERT INTO proactive_event (id, user_id, type, dedupe_key, ref_id, payload_json, status, proposed_at, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                "pe_old", "u_buyer_1", "UNPAID_ORDER", "UNPAID_ORDER:o_x", "o_x", "{}", "SENT",
                Instant.now().minus(25, ChronoUnit.HOURS), Instant.now().minus(25, ChronoUnit.HOURS));

        int expired = scheduler.expireProposals(Instant.now());

        assertEquals(1, expired);
        String status = jdbc.queryForObject("SELECT status FROM proactive_event WHERE id = 'pe_old'", String.class);
        assertEquals("EXPIRED", status);
    }
}
