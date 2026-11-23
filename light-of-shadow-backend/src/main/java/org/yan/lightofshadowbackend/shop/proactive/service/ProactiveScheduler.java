package org.yan.lightofshadowbackend.shop.proactive.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yan.lightofshadowbackend.shop.entity.ShopOrderItemEntity;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;
import org.yan.lightofshadowbackend.shop.mapper.ShopOrderItemMapper;
import org.yan.lightofshadowbackend.shop.mapper.ShopOrderMapper;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactiveEventEntity;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactiveEventMapper;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactiveScanMapper;

/**
 * 定时任务：信号扫描 + 超时关单 + 提议过期。
 * 扫描每 5 分钟一轮；S2 误操作守护不走定时（下单后事件驱动）。
 */
@Component
public class ProactiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProactiveScheduler.class);

    private final ProactiveSignalScanner scanner;
    private final ProactiveScanMapper scanMapper;
    private final ProactiveEventMapper eventMapper;
    private final ShopOrderMapper shopOrderMapper;
    private final ShopOrderItemMapper shopOrderItemMapper;
    private final ProductMapper productMapper;

    public ProactiveScheduler(ProactiveSignalScanner scanner,
            ProactiveScanMapper scanMapper,
            ProactiveEventMapper eventMapper,
            ShopOrderMapper shopOrderMapper,
            ShopOrderItemMapper shopOrderItemMapper,
            ProductMapper productMapper) {
        this.scanner = scanner;
        this.scanMapper = scanMapper;
        this.eventMapper = eventMapper;
        this.shopOrderMapper = shopOrderMapper;
        this.shopOrderItemMapper = shopOrderItemMapper;
        this.productMapper = productMapper;
    }

    @Scheduled(fixedDelayString = "${app.proactive.scan-interval-ms:300000}")
    @Transactional
    public void runOnce() {
        Instant now = Instant.now();
        int created = scanner.scan(now);
        int canceled = cancelExpiredOrders(now);
        int expired = expireProposals(now);
        log.info("主动提醒扫描完成：新增信号 {}，超时关单 {}，过期提议 {}", created, canceled, expired);
    }

    /** 超时关单：PENDING_PAYMENT 超 30 分钟 → 取消并恢复库存（复用取消语义）。 */
    public int cancelExpiredOrders(Instant now) {
        Instant cutoff = now.minus(ProactiveSignalScanner.UNPAID_AUTO_CANCEL_AFTER);
        List<ProactiveScanMapper.PendingRow> rows = scanMapper.selectPendingPaymentOlderThan(cutoff);
        int canceled = 0;
        for (ProactiveScanMapper.PendingRow row : rows) {
            List<ShopOrderItemEntity> items = shopOrderItemMapper.selectByOrderId(row.id);
            for (ShopOrderItemEntity item : items) {
                productMapper.increaseStock(item.getProductId(), item.getQuantity());
            }
            shopOrderMapper.updateStatusById(row.id, "CANCELED", now);
            canceled++;
            log.info("超时关单 orderId={} user={}", row.id, row.userId);
        }
        return canceled;
    }

    /** 提议 24 小时未确认 → EXPIRED，checkpoint 由 Agent 侧丢弃（后续 PR）。 */
    public int expireProposals(Instant now) {
        Instant before = now.minusSeconds(ProactivePolicy.PROPOSAL_TTL_HOURS * 3600L);
        List<ProactiveEventEntity> expired = eventMapper.selectExpired(before);
        for (ProactiveEventEntity event : expired) {
            eventMapper.updateStatus(event.getId(), "EXPIRED", null, now);
        }
        return expired.size();
    }
}
