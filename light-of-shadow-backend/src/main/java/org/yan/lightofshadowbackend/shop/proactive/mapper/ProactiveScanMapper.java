package org.yan.lightofshadowbackend.shop.proactive.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 信号检测专用查询：四个信号的数据切片 + 超时关单候选。
 * 只读，不写库（写库走 ProactiveEventMapper）。
 */
@Mapper
public interface ProactiveScanMapper {

    /** S1 催付候选：待支付 5 分钟以上、30 分钟以内（超 30 分钟由关单任务处理）。 */
    List<UnpaidRow> selectUnpaidCandidates(@Param("olderThan") Instant olderThan,
            @Param("newerThan") Instant newerThan);

    /** S3 购物车闲置候选：加入购物车 3 天以上。 */
    List<IdleCartRow> selectIdleCartItems(@Param("olderThan") Instant olderThan);

    /** S4 历史购买：用户所有成交订单项（新→旧），用于找最新成交价。 */
    List<PurchaseRow> selectPurchaseHistory(@Param("userId") String userId);

    /** S4 买家清单：有成交记录的用户。 */
    List<String> selectDistinctBuyerIds();

    /** S2 T1 重复单：同用户同商品、排除指定单、时间窗内的未取消订单数。 */
    long countRecentOrdersWithProduct(@Param("userId") String userId,
            @Param("productId") String productId,
            @Param("excludeOrderId") String excludeOrderId,
            @Param("since") Instant since);

    /** S2 T2 数量异常：该用户该商品历史购买次数与平均数量（成交单）。 */
    QuantityStats selectQuantityStats(@Param("userId") String userId, @Param("productId") String productId);

    /** 超时关单候选：待支付超过截止时刻。 */
    List<PendingRow> selectPendingPaymentOlderThan(@Param("cutoff") Instant cutoff);

    class UnpaidRow {
        public String id;
        public String userId;
        public String orderNo;
        public Integer totalCent;
        public Instant createdAt;
    }

    class IdleCartRow {
        public Long id;
        public String userId;
        public String productId;
        public Integer quantity;
        public Instant createdAt;
    }

    class PurchaseRow {
        public String productId;
        public Integer unitPriceCent;
        public Instant createdAt;
    }

    class PendingRow {
        public String id;
        public String userId;
    }

    class QuantityStats {
        public Long purchaseCount;
        public Double avgQuantity;
    }
}
