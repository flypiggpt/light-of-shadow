package org.yan.lightofshadowbackend.common.event;

/** 下单成功事件（事务提交后发布，供异步检测误操作守护信号用）。 */
public record OrderCreatedEvent(String userId, String orderId) {
}
