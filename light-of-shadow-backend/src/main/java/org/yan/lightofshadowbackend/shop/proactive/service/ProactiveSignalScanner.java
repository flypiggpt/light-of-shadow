package org.yan.lightofshadowbackend.shop.proactive.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yan.lightofshadowbackend.shop.entity.ProductEntity;
import org.yan.lightofshadowbackend.shop.entity.ShopOrderItemEntity;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;
import org.yan.lightofshadowbackend.shop.mapper.ShopOrderItemMapper;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactiveEventEntity;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactiveEventMapper;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactiveScanMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 信号检测器：四个信号（催付/误操作守护/购物车闲置/降价）的识别与事件落库。
 *
 * <p>检测全部基于 MySQL 现有表；命中 → 过策略层 → 写 proactive_event（PENDING）。
 * 发消息不在本类职责（后续 PR 接 Agent）。插入靠 dedupe_key 唯一约束幂等，重复命中直接吞掉。
 */
@Service
public class ProactiveSignalScanner {

    private static final Logger log = LoggerFactory.getLogger(ProactiveSignalScanner.class);

    static final String TYPE_UNPAID = "UNPAID_ORDER";
    static final String TYPE_DUP = "DUP_ORDER";
    static final String TYPE_CART_IDLE = "CART_IDLE";
    static final String TYPE_PRICE_DROP = "PRICE_DROP";

    /** S1：待支付满 5 分钟开始提醒。 */
    static final Duration UNPAID_REMIND_AFTER = Duration.ofMinutes(5);
    /** 超时关单阈值：待支付超 30 分钟。 */
    static final Duration UNPAID_AUTO_CANCEL_AFTER = Duration.ofMinutes(30);
    /** S3：购物车闲置 3 天。 */
    static final Duration CART_IDLE_AFTER = Duration.ofDays(3);
    /** S4：降幅 ≥ 10% 才提醒。 */
    static final double PRICE_DROP_RATIO = 0.9;
    /** S2 T1：重复单时间窗 10 分钟。 */
    static final Duration DUP_TIME_WINDOW = Duration.ofMinutes(10);
    /** S2 T2：数量 ≥ 5 且 ≥ 历史均量 3 倍。 */
    static final int DUP_QTY_MIN = 5;
    static final double DUP_QTY_MULTIPLIER = 3.0;

    private final ProactiveScanMapper scanMapper;
    private final ProactiveEventMapper eventMapper;
    private final ProactivePolicy policy;
    private final ProductMapper productMapper;
    private final ShopOrderItemMapper orderItemMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProactiveSignalScanner(ProactiveScanMapper scanMapper,
            ProactiveEventMapper eventMapper,
            ProactivePolicy policy,
            ProductMapper productMapper,
            ShopOrderItemMapper orderItemMapper) {
        this.scanMapper = scanMapper;
        this.eventMapper = eventMapper;
        this.policy = policy;
        this.productMapper = productMapper;
        this.orderItemMapper = orderItemMapper;
    }

    /** 定时扫描一轮：S1 催付 + S3 购物车闲置 + S4 降价。返回落库事件数。 */
    public int scan(Instant now) {
        int created = 0;
        created += scanUnpaidOrders(now);
        created += scanIdleCarts(now);
        created += scanPriceDrops(now);
        return created;
    }

    /** S1 催付。 */
    int scanUnpaidOrders(Instant now) {
        Instant older = now.minus(UNPAID_REMIND_AFTER);
        Instant newer = now.minus(UNPAID_AUTO_CANCEL_AFTER);
        List<ProactiveScanMapper.UnpaidRow> rows = scanMapper.selectUnpaidCandidates(older, newer);
        int created = 0;
        for (ProactiveScanMapper.UnpaidRow row : rows) {
            String productName = firstProductName(row.id);
            long remainingMinutes = Math.max(1,
                    Duration.between(now, row.createdAt.plus(UNPAID_AUTO_CANCEL_AFTER)).toMinutes());
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("orderId", row.id);
            facts.put("orderNo", row.orderNo);
            facts.put("productName", productName);
            facts.put("amountCent", row.totalCent);
            facts.put("remainingMinutes", remainingMinutes);
            created += record(TYPE_UNPAID, row.userId, row.id,
                    TYPE_UNPAID + ":" + row.id, facts, now) ? 1 : 0;
        }
        return created;
    }

    /** S2 误操作守护：下单事务提交后异步立即调用（事件驱动，不等扫描轮次）。 */
    public int checkDuplicateOrder(String userId, String orderId, Instant now) {
        List<ShopOrderItemEntity> items = orderItemMapper.selectByOrderId(orderId);
        for (ShopOrderItemEntity item : items) {
            String trigger = null;
            // T1 重复单：10 分钟内同商品还有别的未取消单
            long dupCount = scanMapper.countRecentOrdersWithProduct(userId, item.getProductId(),
                    orderId, now.minus(DUP_TIME_WINDOW));
            if (dupCount >= 1) {
                trigger = "DUPLICATE";
            } else if (item.getQuantity() != null && item.getQuantity() >= DUP_QTY_MIN) {
                // T2 数量异常：有过 ≥2 次购买且本次 ≥ 5 件 ≥ 均量 3 倍
                ProactiveScanMapper.QuantityStats stats =
                        scanMapper.selectQuantityStats(userId, item.getProductId());
                if (stats != null && stats.purchaseCount != null && stats.purchaseCount >= 2
                        && stats.avgQuantity != null
                        && item.getQuantity() >= stats.avgQuantity * DUP_QTY_MULTIPLIER) {
                    trigger = "QUANTITY";
                }
            }
            if (trigger == null) {
                continue;
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("orderId", orderId);
            facts.put("productId", item.getProductId());
            facts.put("productName", item.getProductName());
            facts.put("quantity", item.getQuantity());
            facts.put("trigger", trigger);
            if (record(TYPE_DUP, userId, orderId, TYPE_DUP + ":" + orderId, facts, now)) {
                return 1;
            }
        }
        return 0;
    }

    /** S3 购物车闲置。 */
    int scanIdleCarts(Instant now) {
        List<ProactiveScanMapper.IdleCartRow> rows =
                scanMapper.selectIdleCartItems(now.minus(CART_IDLE_AFTER));
        int created = 0;
        for (ProactiveScanMapper.IdleCartRow row : rows) {
            ProductEntity product = productMapper.selectById(row.productId);
            if (product == null) {
                continue;
            }
            long days = Duration.between(row.createdAt, now).toDays();
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("cartItemId", String.valueOf(row.id));
            facts.put("productId", row.productId);
            facts.put("productName", product.getName());
            facts.put("days", days);
            facts.put("stock", product.getStock());
            created += record(TYPE_CART_IDLE, row.userId, row.productId,
                    TYPE_CART_IDLE + ":" + row.userId + ":" + row.productId, facts, now) ? 1 : 0;
        }
        return created;
    }

    /** S4 买过的商品降价（历史成交单价是天然历史价，不需要价格历史表）。 */
    int scanPriceDrops(Instant now) {
        int created = 0;
        for (String userId : scanMapper.selectDistinctBuyerIds()) {
            List<ProactiveScanMapper.PurchaseRow> history = scanMapper.selectPurchaseHistory(userId);
            Map<String, ProactiveScanMapper.PurchaseRow> latest = new HashMap<>();
            for (ProactiveScanMapper.PurchaseRow row : history) {
                latest.putIfAbsent(row.productId, row); // 已按新→旧排序，首见即最新成交
            }
            for (Map.Entry<String, ProactiveScanMapper.PurchaseRow> entry : latest.entrySet()) {
                ProductEntity product = productMapper.selectById(entry.getKey());
                if (product == null || product.getPriceCent() == null) {
                    continue;
                }
                int paidCent = entry.getValue().unitPriceCent == null ? 0 : entry.getValue().unitPriceCent;
                if (paidCent <= 0 || product.getPriceCent() >= paidCent * PRICE_DROP_RATIO) {
                    continue;
                }
                long days = Duration.between(entry.getValue().createdAt, now).toDays();
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("productId", product.getId());
                facts.put("productName", product.getName());
                facts.put("oldPriceCent", paidCent);
                facts.put("newPriceCent", product.getPriceCent());
                facts.put("days", days);
                String dedupe = TYPE_PRICE_DROP + ":" + userId + ":" + product.getId() + ":" + product.getPriceCent();
                created += record(TYPE_PRICE_DROP, userId, product.getId(), dedupe, facts, now) ? 1 : 0;
            }
        }
        return created;
    }

    /** 策略层放行后落库；dedupe_key 冲突视为已处理。返回是否新建。 */
    private boolean record(String type, String userId, String refId, String dedupeKey,
            Map<String, Object> facts, Instant now) {
        if (eventMapper.selectByDedupeKey(dedupeKey) != null) {
            return false;
        }
        ProactivePolicy.Decision decision = policy.evaluate(userId, type, refId, now);
        if (decision.verdict() != ProactivePolicy.Verdict.ALLOW) {
            log.info("信号被策略拦截 type={} user={} reason={}", type, userId, decision.reason());
            return false;
        }
        ProactiveEventEntity event = new ProactiveEventEntity();
        event.setId("pe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        event.setUserId(userId);
        event.setType(type);
        event.setDedupeKey(dedupeKey);
        event.setRefId(refId);
        event.setPayloadJson(toJson(facts));
        event.setStatus("PENDING");
        event.setCreatedAt(now);
        try {
            int inserted = eventMapper.insert(event);
            if (inserted > 0) {
                log.info("信号落库 type={} user={} dedupe={}", type, userId, dedupeKey);
                return true;
            }
        } catch (Exception e) {
            // dedupe_key 唯一约束冲突：并发重复命中，幂等吞掉
            log.debug("信号插入被去重 type={} dedupe={}", type, dedupeKey);
        }
        return false;
    }

    private String firstProductName(String orderId) {
        List<ShopOrderItemEntity> items = orderItemMapper.selectByOrderId(orderId);
        return items.isEmpty() ? "" : items.get(0).getProductName();
    }

    private String toJson(Map<String, Object> facts) {
        try {
            return objectMapper.writeValueAsString(facts);
        } catch (Exception e) {
            return "{}";
        }
    }
}
