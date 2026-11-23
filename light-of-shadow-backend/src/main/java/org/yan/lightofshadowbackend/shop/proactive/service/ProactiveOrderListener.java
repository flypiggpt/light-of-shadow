package org.yan.lightofshadowbackend.shop.proactive.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.yan.lightofshadowbackend.common.event.OrderCreatedEvent;

/**
 * S2 误操作守护：下单事务提交后异步立即检测，不等定时扫描——
 * 要抢在用户支付前开口，晚几分钟就失去意义。
 */
@Component
public class ProactiveOrderListener {

    private static final Logger log = LoggerFactory.getLogger(ProactiveOrderListener.class);

    private final ProactiveSignalScanner scanner;

    public ProactiveOrderListener(ProactiveSignalScanner scanner) {
        this.scanner = scanner;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            int created = scanner.checkDuplicateOrder(event.userId(), event.orderId(), Instant.now());
            if (created > 0) {
                log.info("误操作守护信号已落库 orderId={}", event.orderId());
            }
        } catch (Exception e) {
            // 守护检测失败不影响下单主流程
            log.warn("误操作守护检测失败 orderId={}", event.orderId(), e);
        }
    }
}
